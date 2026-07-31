package com.aid.media.util;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import cn.hutool.core.util.StrUtil;
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
        MediaBytesFetcher.Content content = MediaBytesFetcher.fetch(fullUrl);
        if (content.isEmpty()) {
            log.warn("音频时长探测下载为空, url={}", fullUrl);
            return null;
        }
        if (content.truncated()) {
            log.warn("音频超出探测上限仅解析头部, url={}, limit={}", fullUrl, MediaBytesFetcher.DEFAULT_MAX_BYTES);
        }
        return probeDurationMs(content.bytes(), content.truncated());
    }

    /**
     * 从音频字节流探测时长（毫秒）：自动识别 wav / mp3。
     *
     * @param bytes 音频完整字节
     * @return 时长毫秒；无法识别返回 null
     */
    public static Integer probeDurationMs(byte[] bytes) {
        return probeDurationMs(bytes, false);
    }

    /**
     * 从音频字节流探测时长（毫秒）。
     *
     * @param bytes     音频字节
     * @param truncated 字节是否被截断；截断时按文件大小估算的 CBR 分支不可信，返回 null
     * @return 时长毫秒；无法识别返回 null
     */
    public static Integer probeDurationMs(byte[] bytes, boolean truncated) {
        if (Objects.isNull(bytes) || bytes.length < 128) {
            return null;
        }
        try {
            // wav 时长由容器头声明，截断不影响结果，统一交给 wav 容器工具解析
            if (WavAudioSupport.isWav(bytes)) {
                return WavAudioSupport.durationMs(bytes);
            }
            return parseMp3DurationMs(bytes, truncated);
        } catch (Exception ex) {
            log.warn("音频时长解析异常, err={}", ex.getMessage());
            return null;
        }
    }

    /**
     * mp3：跳过 ID3v2 → 找首个合法帧头 → 读版本/比特率/采样率；
     * 有 Xing/Info VBR 头按帧数精确计算，否则按 CBR 用文件大小/比特率估算。
     *
     * @param bytes     mp3 字节
     * @param truncated 字节是否被截断
     * @return 时长毫秒
     */
    private static Integer parseMp3DurationMs(byte[] bytes, boolean truncated) {
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
            String tag = new String(bytes, xingPos, 4, StandardCharsets.US_ASCII);
            if ("Xing".equals(tag) || "Info".equals(tag)) {
                int flags = (int) readBeUint32(bytes, xingPos + 4);
                if ((flags & 0x01) != 0) {
                    long frameCount = readBeUint32(bytes, xingPos + 8);
                    return (int) (frameCount * samplesPerFrame * 1000L / sampleRate);
                }
            }
        }
        // CBR 估算依赖完整文件大小，字节被截断时结果必然偏小，宁可返回空也不返回错值
        if (truncated) {
            log.warn("mp3 无 Xing 头且字节被截断,放弃时长估算");
            return null;
        }
        // CBR 估算：音频数据字节数 × 8 / 比特率
        long audioBytes = (long) bytes.length - frameStart;
        return (int) (audioBytes * 8L / bitrateKbps);
    }

    /** 大端读 uint32。 */
    private static long readBeUint32(byte[] bytes, int pos) {
        return ((bytes[pos] & 0xFFL) << 24) | ((bytes[pos + 1] & 0xFFL) << 16)
                | ((bytes[pos + 2] & 0xFFL) << 8) | (bytes[pos + 3] & 0xFFL);
    }
}
