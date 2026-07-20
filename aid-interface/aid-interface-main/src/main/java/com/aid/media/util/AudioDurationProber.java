package com.aid.media.util;

import java.util.Objects;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 音频真实时长探测工具（无第三方依赖）。
 * 背景：部分 TTS 厂商（豆包等）不回传音频时长，aid_audio_record.duration_ms 落库为 null；
 * 合成对齐、扣费估算都依赖真实时长，缺失会导致成片音画错位。本工具直接解析音频文件
 * 字节流计算时长：mp3 按帧头（含 Xing/VBRI VBR 头）解析，wav 按 RIFF 头解析。
 * 所有方法失败返回 null，绝不抛异常阻断主流程。
 *
 * @author 视觉AID
 */
@Slf4j
public final class AudioDurationProber {

    /** 下载上限：TTS 音频通常几百 KB，超过视为异常文件拒绝解析 */
    private static final int MAX_DOWNLOAD_BYTES = 30 * 1024 * 1024;

    /** MPEG1 Layer3 比特率表（kbps，index 1-14） */
    private static final int[] BITRATE_V1_L3 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};

    /** MPEG2/2.5 Layer3 比特率表（kbps） */
    private static final int[] BITRATE_V2_L3 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};

    /** 采样率表：MPEG1 / MPEG2 / MPEG2.5 */
    private static final int[] SAMPLE_RATE_V1 = {44100, 48000, 32000};
    private static final int[] SAMPLE_RATE_V2 = {22050, 24000, 16000};
    private static final int[] SAMPLE_RATE_V25 = {11025, 12000, 8000};

    private AudioDurationProber() {
    }

    /**
     * 下载音频并探测真实时长（毫秒）。
     *
     * @param fullUrl 完整可访问 URL
     * @return 时长毫秒；解析失败/下载失败返回 null
     */
    public static Integer probeDurationMs(String fullUrl) {
        if (StrUtil.isBlank(fullUrl)) {
            return null;
        }
        try {
            byte[] bytes = HttpUtil.downloadBytes(fullUrl);
            if (Objects.isNull(bytes) || bytes.length == 0 || bytes.length > MAX_DOWNLOAD_BYTES) {
                log.warn("音频时长探测下载异常, url={}, size={}", fullUrl, Objects.isNull(bytes) ? -1 : bytes.length);
                return null;
            }
            return probeDurationMs(bytes);
        } catch (Exception ex) {
            log.warn("音频时长探测失败, url={}, err={}", fullUrl, ex.getMessage());
            return null;
        }
    }

    /**
     * 从音频字节流探测时长（毫秒）：自动识别 wav / mp3。
     *
     * @param bytes 音频完整字节
     * @return 时长毫秒；无法识别返回 null
     */
    public static Integer probeDurationMs(byte[] bytes) {
        if (Objects.isNull(bytes) || bytes.length < 128) {
            return null;
        }
        try {
            // RIFF....WAVE → wav
            if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E') {
                return parseWavDurationMs(bytes);
            }
            return parseMp3DurationMs(bytes);
        } catch (Exception ex) {
            log.warn("音频时长解析异常, err={}", ex.getMessage());
            return null;
        }
    }

    /**
     * wav：定位 fmt 块取 byteRate，定位 data 块取数据长度，时长 = dataSize / byteRate。
     *
     * @param bytes wav 字节
     * @return 时长毫秒
     */
    private static Integer parseWavDurationMs(byte[] bytes) {
        int pos = 12;
        int byteRate = 0;
        while (pos + 8 <= bytes.length) {
            String chunkId = new String(bytes, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long chunkSize = readLeUint32(bytes, pos + 4);
            if ("fmt ".equals(chunkId) && pos + 16 + 4 <= bytes.length) {
                byteRate = (int) readLeUint32(bytes, pos + 16);
            } else if ("data".equals(chunkId)) {
                if (byteRate <= 0) {
                    return null;
                }
                return (int) (chunkSize * 1000L / byteRate);
            }
            // 块大小奇数按偶数对齐
            pos += 8 + (int) chunkSize + ((chunkSize % 2 == 1) ? 1 : 0);
        }
        return null;
    }

    /**
     * mp3：跳过 ID3v2 → 找首个合法帧头 → 读版本/比特率/采样率；
     * 有 Xing/Info VBR 头按帧数精确计算，否则按 CBR 用文件大小/比特率估算。
     *
     * @param bytes mp3 字节
     * @return 时长毫秒
     */
    private static Integer parseMp3DurationMs(byte[] bytes) {
        int offset = 0;
        // ID3v2 头：ID3 + ver(2) + flag(1) + size(4, synchsafe)
        if (bytes.length > 10 && bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            int tagSize = ((bytes[6] & 0x7F) << 21) | ((bytes[7] & 0x7F) << 14)
                    | ((bytes[8] & 0x7F) << 7) | (bytes[9] & 0x7F);
            offset = 10 + tagSize;
        }
        // 找首个帧同步字 0xFFEx
        int frameStart = -1;
        for (int i = offset; i < bytes.length - 4; i++) {
            if ((bytes[i] & 0xFF) == 0xFF && (bytes[i + 1] & 0xE0) == 0xE0) {
                frameStart = i;
                break;
            }
        }
        if (frameStart < 0) {
            return null;
        }
        int b1 = bytes[frameStart + 1] & 0xFF;
        int b2 = bytes[frameStart + 2] & 0xFF;
        int versionBits = (b1 >> 3) & 0x03;
        int layerBits = (b1 >> 1) & 0x03;
        int bitrateIndex = (b2 >> 4) & 0x0F;
        int sampleRateIndex = (b2 >> 2) & 0x03;
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3 || layerBits != 1) {
            // 仅支持 Layer3（TTS 输出均为 mp3 Layer3），异常索引直接放弃
            return null;
        }
        boolean isV1 = versionBits == 3;
        int sampleRate;
        if (isV1) {
            sampleRate = SAMPLE_RATE_V1[sampleRateIndex];
        } else if (versionBits == 2) {
            sampleRate = SAMPLE_RATE_V2[sampleRateIndex];
        } else {
            sampleRate = SAMPLE_RATE_V25[sampleRateIndex];
        }
        int bitrateKbps = isV1 ? BITRATE_V1_L3[bitrateIndex] : BITRATE_V2_L3[bitrateIndex];
        if (bitrateKbps <= 0 || sampleRate <= 0) {
            return null;
        }
        int samplesPerFrame = isV1 ? 1152 : 576;

        // VBR：帧头后偏移处找 Xing/Info 标记（含总帧数）
        int sideInfoLen = isV1 ? 32 : 17;
        int xingPos = frameStart + 4 + sideInfoLen;
        if (xingPos + 16 <= bytes.length) {
            String tag = new String(bytes, xingPos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if ("Xing".equals(tag) || "Info".equals(tag)) {
                int flags = (int) readBeUint32(bytes, xingPos + 4);
                if ((flags & 0x01) != 0) {
                    long frameCount = readBeUint32(bytes, xingPos + 8);
                    return (int) (frameCount * samplesPerFrame * 1000L / sampleRate);
                }
            }
        }
        // CBR 估算：音频数据字节数 × 8 / 比特率
        long audioBytes = (long) bytes.length - frameStart;
        return (int) (audioBytes * 8L / bitrateKbps);
    }

    /** 小端读 uint32。 */
    private static long readLeUint32(byte[] bytes, int pos) {
        return (bytes[pos] & 0xFFL) | ((bytes[pos + 1] & 0xFFL) << 8)
                | ((bytes[pos + 2] & 0xFFL) << 16) | ((bytes[pos + 3] & 0xFFL) << 24);
    }

    /** 大端读 uint32。 */
    private static long readBeUint32(byte[] bytes, int pos) {
        return ((bytes[pos] & 0xFFL) << 24) | ((bytes[pos + 1] & 0xFFL) << 16)
                | ((bytes[pos + 2] & 0xFFL) << 8) | (bytes[pos + 3] & 0xFFL);
    }
}
