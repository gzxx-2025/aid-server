package com.aid.media.util;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;

/**
 * WAV 容器处理工具（无第三方依赖）：裸 PCM 加装 RIFF 头、WAV 尾部补静音到指定时长。
 * 仅支持 16 位及以上整数 PCM（静音即全零字节），其余编码一律拒绝，所有方法失败返回 null。
 *
 * @author 视觉AID
 */
@Slf4j
public final class WavAudioSupport {

    /** 音频格式标识：wav */
    public static final String FORMAT_WAV = "wav";

    /** 标准 RIFF 头长度：RIFF(12) + fmt(24) + data 头(8) */
    private static final int CANONICAL_HEADER_BYTES = 44;

    /** RIFF 剩余长度基数：标准头长度减去 "RIFF" 与长度字段自身的 8 字节 */
    private static final int RIFF_TRAILING_BASE = CANONICAL_HEADER_BYTES - 8;

    /** 块正文起始偏移：RIFF(4) + 长度(4) + WAVE(4) */
    private static final int RIFF_BODY_OFFSET = 12;

    /** 块头长度：标识(4) + 长度(4) */
    private static final int CHUNK_HEADER_BYTES = 8;

    /** 块标识长度 */
    private static final int CHUNK_ID_BYTES = 4;

    /** WAVE 标识偏移：RIFF(4) + 长度(4) */
    private static final int WAVE_ID_OFFSET = 8;

    /** uint16 字段字节数 */
    private static final int UINT16_BYTES = 2;

    /** uint32 字段字节数 */
    private static final int UINT32_BYTES = 4;

    /** 标准 fmt 块正文长度 */
    private static final int FMT_CHUNK_BYTES = 16;

    /** PCM 编码标识 */
    private static final int FORMAT_PCM = 1;

    /** 最低支持位深：8 位无符号 PCM 的静音值是 0x80 而非 0，补零会变成爆音，直接排除 */
    private static final int MIN_BITS_PER_SAMPLE = 16;

    /** 每字节位数 */
    private static final int BITS_PER_BYTE = 8;

    /** 秒→毫秒换算 */
    private static final int MS_PER_SECOND = 1000;

    /** RIFF 标识 */
    private static final String RIFF_ID = "RIFF";

    /** WAVE 标识 */
    private static final String WAVE_ID = "WAVE";

    /** 块标识：格式块 */
    private static final String CHUNK_FMT = "fmt ";

    /** 块标识：数据块 */
    private static final String CHUNK_DATA = "data";

    /** fmt 块正文内字段偏移：编码 */
    private static final int FMT_OFFSET_ENCODING = 0;

    /** fmt 块正文内字段偏移：声道数 */
    private static final int FMT_OFFSET_CHANNELS = 2;

    /** fmt 块正文内字段偏移：采样率 */
    private static final int FMT_OFFSET_SAMPLE_RATE = 4;

    /** fmt 块正文内字段偏移：字节率 */
    private static final int FMT_OFFSET_BYTE_RATE = 8;

    /** fmt 块正文内字段偏移：位深 */
    private static final int FMT_OFFSET_BITS_PER_SAMPLE = 14;

    private WavAudioSupport() {
    }

    /**
     * 判断字节流是否为 WAV 容器。
     *
     * @param bytes 音频字节
     * @return true=RIFF/WAVE 头合法
     */
    public static boolean isWav(byte[] bytes) {
        if (Objects.isNull(bytes) || bytes.length < CANONICAL_HEADER_BYTES) {
            return false;
        }
        return RIFF_ID.equals(readAscii(bytes, 0)) && WAVE_ID.equals(readAscii(bytes, WAVE_ID_OFFSET));
    }

    /**
     * 裸 PCM 加装标准 RIFF 头。
     *
     * @param pcm           裸 PCM 数据（小端整数样本）
     * @param sampleRate    采样率
     * @param channels      声道数
     * @param bitsPerSample 位深（16 的整数倍字节对齐）
     * @return wav 字节；参数非法返回 null
     */
    public static byte[] fromPcm(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        if (Objects.isNull(pcm) || pcm.length == 0 || !isSupportedFormat(sampleRate, channels, bitsPerSample)) {
            log.warn("PCM 封装 wav 参数非法, sampleRate={}, channels={}, bits={}", sampleRate, channels, bitsPerSample);
            return null;
        }
        int blockAlign = blockAlign(channels, bitsPerSample);
        byte[] wav = new byte[CANONICAL_HEADER_BYTES + pcm.length];
        int pos = writeAscii(wav, 0, RIFF_ID);
        pos = writeLeUint32(wav, pos, RIFF_TRAILING_BASE + (long) pcm.length);
        pos = writeAscii(wav, pos, WAVE_ID);
        pos = writeAscii(wav, pos, CHUNK_FMT);
        pos = writeLeUint32(wav, pos, FMT_CHUNK_BYTES);
        pos = writeLeUint16(wav, pos, FORMAT_PCM);
        pos = writeLeUint16(wav, pos, channels);
        pos = writeLeUint32(wav, pos, sampleRate);
        pos = writeLeUint32(wav, pos, (long) sampleRate * blockAlign);
        pos = writeLeUint16(wav, pos, blockAlign);
        pos = writeLeUint16(wav, pos, bitsPerSample);
        pos = writeAscii(wav, pos, CHUNK_DATA);
        pos = writeLeUint32(wav, pos, pcm.length);
        System.arraycopy(pcm, 0, wav, pos, pcm.length);
        return wav;
    }

    /**
     * 解析 wav 时长（毫秒）：按 data 块声明长度与 fmt 块声明的字节率换算，不限编码，
     * 因而字节被截断也能得到完整时长。
     *
     * @param wav wav 字节
     * @return 时长毫秒；容器不合法或字节率缺失返回 null
     */
    public static Integer durationMs(byte[] wav) {
        WavLayout layout = parseLayout(wav);
        if (Objects.isNull(layout) || layout.byteRate() <= 0) {
            return null;
        }
        return (int) (layout.declaredDataSize() * MS_PER_SECOND / layout.byteRate());
    }

    /**
     * WAV 尾部补静音到目标时长。原时长已达目标、编码不支持、超出体积上限均返回 null 交由调用方降级。
     *
     * @param wav              原始 wav 字节
     * @param targetDurationMs 目标时长（毫秒）
     * @param maxResultBytes   结果体积上限（字节）
     * @return 补齐后的 wav 字节；无需补齐或无法补齐返回 null
     */
    public static byte[] padWithSilence(byte[] wav, int targetDurationMs, int maxResultBytes) {
        if (targetDurationMs <= 0 || maxResultBytes <= 0) {
            return null;
        }
        WavLayout layout = parseLayout(wav);
        // 补静音要按样本重写数据块，只接受静音即全零字节的 16 位及以上整数 PCM
        if (Objects.isNull(layout) || layout.encoding() != FORMAT_PCM
                || !isSupportedFormat(layout.sampleRate(), layout.channels(), layout.bitsPerSample())) {
            log.warn("wav 补静音跳过: 容器解析失败或编码不支持");
            return null;
        }
        // 声明长度可能大于实际字节（写入中断的文件），按实际可读长度截断
        int dataSize = (int) Math.min(layout.declaredDataSize(), (long) wav.length - layout.dataOffset());
        if (dataSize <= 0) {
            return null;
        }
        int blockAlign = blockAlign(layout.channels(), layout.bitsPerSample());
        int byteRate = layout.sampleRate() * blockAlign;
        long currentMs = (long) dataSize * MS_PER_SECOND / byteRate;
        if (currentMs >= targetDurationMs) {
            return null;
        }
        // 补齐字节数按采样块向下对齐，避免半个样本导致声道错位
        long padBytes = (targetDurationMs - currentMs) * byteRate / MS_PER_SECOND;
        padBytes -= padBytes % blockAlign;
        if (padBytes <= 0) {
            return null;
        }
        long total = (long) CANONICAL_HEADER_BYTES + dataSize + padBytes;
        if (total > maxResultBytes) {
            log.warn("wav 补静音跳过: 结果体积超限, total={}, limit={}", total, maxResultBytes);
            return null;
        }
        // 16 位及以上 PCM 的静音就是全零字节，新数组尾部保持默认值即为静音段
        byte[] data = new byte[(int) (dataSize + padBytes)];
        System.arraycopy(wav, layout.dataOffset(), data, 0, dataSize);
        return fromPcm(data, layout.sampleRate(), layout.channels(), layout.bitsPerSample());
    }

    /**
     * 按 RIFF 块结构解析 wav 头部，原样返回 fmt 块声明的参数与 data 块位置，不做编码取舍。
     *
     * @param wav wav 字节
     * @return 容器布局；容器不合法或缺少 data 块返回 null
     */
    private static WavLayout parseLayout(byte[] wav) {
        if (!isWav(wav)) {
            return null;
        }
        long pos = RIFF_BODY_OFFSET;
        int encoding = 0;
        int channels = 0;
        int sampleRate = 0;
        int byteRate = 0;
        int bitsPerSample = 0;
        while (pos + CHUNK_HEADER_BYTES <= wav.length) {
            int offset = (int) pos;
            String chunkId = readAscii(wav, offset);
            long chunkSize = readLeUint32(wav, offset + CHUNK_ID_BYTES);
            int body = offset + CHUNK_HEADER_BYTES;
            if (CHUNK_FMT.equals(chunkId) && body + FMT_CHUNK_BYTES <= wav.length) {
                encoding = readLeUint16(wav, body + FMT_OFFSET_ENCODING);
                channels = readLeUint16(wav, body + FMT_OFFSET_CHANNELS);
                sampleRate = (int) readLeUint32(wav, body + FMT_OFFSET_SAMPLE_RATE);
                byteRate = (int) readLeUint32(wav, body + FMT_OFFSET_BYTE_RATE);
                bitsPerSample = readLeUint16(wav, body + FMT_OFFSET_BITS_PER_SAMPLE);
            } else if (CHUNK_DATA.equals(chunkId)) {
                return new WavLayout(body, chunkSize, encoding, channels, sampleRate, byteRate, bitsPerSample);
            }
            // 块大小为奇数时按偶数对齐；long 累加避免 uint32 强转回绕
            long next = pos + CHUNK_HEADER_BYTES + chunkSize + (chunkSize % 2);
            if (next <= pos || next > wav.length) {
                return null;
            }
            pos = next;
        }
        return null;
    }

    /**
     * 采样参数是否受支持。
     *
     * @param sampleRate    采样率
     * @param channels      声道数
     * @param bitsPerSample 位深
     * @return true=受支持
     */
    private static boolean isSupportedFormat(int sampleRate, int channels, int bitsPerSample) {
        return sampleRate > 0 && channels > 0
                && bitsPerSample >= MIN_BITS_PER_SAMPLE && bitsPerSample % BITS_PER_BYTE == 0;
    }

    /**
     * 单个采样块字节数。
     *
     * @param channels      声道数
     * @param bitsPerSample 位深
     * @return 块对齐字节数
     */
    private static int blockAlign(int channels, int bitsPerSample) {
        return channels * bitsPerSample / BITS_PER_BYTE;
    }

    /** wav 容器布局：data 块位置与 fmt 块声明的原始参数（declaredDataSize 为声明长度，可能大于实际字节）。 */
    private record WavLayout(int dataOffset, long declaredDataSize, int encoding,
                             int channels, int sampleRate, int byteRate, int bitsPerSample) {
    }

    /** 读 4 字节 ASCII 标识。 */
    private static String readAscii(byte[] bytes, int pos) {
        return new String(bytes, pos, CHUNK_ID_BYTES, StandardCharsets.US_ASCII);
    }

    /** 小端读 uint16。 */
    private static int readLeUint16(byte[] bytes, int pos) {
        return (bytes[pos] & 0xFF) | ((bytes[pos + 1] & 0xFF) << 8);
    }

    /** 小端读 uint32。 */
    private static long readLeUint32(byte[] bytes, int pos) {
        return (bytes[pos] & 0xFFL) | ((bytes[pos + 1] & 0xFFL) << 8)
                | ((bytes[pos + 2] & 0xFFL) << 16) | ((bytes[pos + 3] & 0xFFL) << 24);
    }

    /** 写 ASCII 标识，返回下一写入位。 */
    private static int writeAscii(byte[] bytes, int pos, String value) {
        byte[] raw = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(raw, 0, bytes, pos, raw.length);
        return pos + raw.length;
    }

    /** 小端写 uint16，返回下一写入位。 */
    private static int writeLeUint16(byte[] bytes, int pos, int value) {
        bytes[pos] = (byte) (value & 0xFF);
        bytes[pos + 1] = (byte) ((value >> 8) & 0xFF);
        return pos + UINT16_BYTES;
    }

    /** 小端写 uint32，返回下一写入位。 */
    private static int writeLeUint32(byte[] bytes, int pos, long value) {
        bytes[pos] = (byte) (value & 0xFFL);
        bytes[pos + 1] = (byte) ((value >> 8) & 0xFFL);
        bytes[pos + 2] = (byte) ((value >> 16) & 0xFFL);
        bytes[pos + 3] = (byte) ((value >> 24) & 0xFFL);
        return pos + UINT32_BYTES;
    }
}
