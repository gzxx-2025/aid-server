package com.aid.media.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static java.time.Duration.ofSeconds;

class VideoDurationProberTest {

    @Test
    void shouldParseDurationFromVersion0Mvhd() {
        // timescale=600, duration=1800 → 3 秒
        byte[] mp4 = mp4(0, 600, 1800L);

        assertEquals(3000, VideoDurationProber.probeDurationMs(mp4));
    }

    @Test
    void shouldParseDurationFromVersion1Mvhd() {
        // 64 位时间字段：timescale=1000, duration=14520 → 14.52 秒
        byte[] mp4 = mp4(1, 1000, 14520L);

        assertEquals(14520, VideoDurationProber.probeDurationMs(mp4));
    }

    @Test
    void shouldReturnNullWhenDurationUnknown() {
        // 32 位时长全 1 表示时长未知，不能当成真实时长返回
        byte[] mp4 = mp4(0, 600, 0xFFFFFFFFL);

        assertNull(VideoDurationProber.probeDurationMs(mp4));
    }

    @Test
    void shouldReturnNullWhenMoovMissing() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBox(out, "ftyp", new byte[32]);

        assertNull(VideoDurationProber.probeDurationMs(pad(out.toByteArray())));
    }

    @Test
    void shouldNotLoopForeverOnZeroSizedBox() {
        // 盒子长度为 0 表示延伸至末尾，历史实现易在此死循环
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBe32(out, 0);
        writeAscii(out, "free");
        out.writeBytes(new byte[128]);

        assertTimeoutPreemptively(ofSeconds(2),
                () -> assertNull(VideoDurationProber.probeDurationMs(pad(out.toByteArray()))));
    }

    @Test
    void shouldReturnNullForTinyOrNullPayload() {
        assertNull(VideoDurationProber.probeDurationMs(new byte[32]));
        assertNull(VideoDurationProber.probeDurationMs(null));
    }

    /** 构造最小可解析 mp4：ftyp + moov(mvhd)。 */
    private static byte[] mp4(int version, int timescale, long duration) {
        ByteArrayOutputStream mvhd = new ByteArrayOutputStream();
        mvhd.write(version);
        mvhd.writeBytes(new byte[3]);
        if (version == 1) {
            mvhd.writeBytes(new byte[16]);
            writeBe32(mvhd, timescale);
            writeBe64(mvhd, duration);
        } else {
            mvhd.writeBytes(new byte[8]);
            writeBe32(mvhd, timescale);
            writeBe32(mvhd, duration);
        }
        mvhd.writeBytes(new byte[16]);

        ByteArrayOutputStream moov = new ByteArrayOutputStream();
        writeBox(moov, "mvhd", mvhd.toByteArray());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBox(out, "ftyp", new byte[16]);
        writeBox(out, "moov", moov.toByteArray());
        return pad(out.toByteArray());
    }

    private static void writeBox(ByteArrayOutputStream out, String type, byte[] content) {
        writeBe32(out, content.length + 8);
        writeAscii(out, type);
        out.writeBytes(content);
    }

    private static byte[] pad(byte[] bytes) {
        if (bytes.length >= 64) {
            return bytes;
        }
        byte[] padded = new byte[64];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        return padded;
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeBe32(ByteArrayOutputStream out, long value) {
        out.write((int) ((value >> 24) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) (value & 0xFF));
    }

    private static void writeBe64(ByteArrayOutputStream out, long value) {
        for (int i = 7; i >= 0; i--) {
            out.write((int) ((value >> (i * 8)) & 0xFF));
        }
    }
}
