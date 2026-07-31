package com.aid.media.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static java.time.Duration.ofSeconds;

class AudioDurationProberTest {

    @Test
    void shouldParseWavDurationFromRiffHeader() {
        // 16000 字节数据 / 16000 字节每秒 = 1 秒
        byte[] wav = wav(16000, 16000);

        assertEquals(1000, AudioDurationProber.probeDurationMs(wav));
    }

    @Test
    void shouldReturnNullWhenWavHasNoFmtChunk() {
        byte[] wav = wavWithoutFmt(16000);

        assertNull(AudioDurationProber.probeDurationMs(wav));
    }

    @Test
    void shouldNotLoopForeverOnZeroSizedChunk() {
        // 块长为 0 会让游标停在原地，历史实现在此死循环
        byte[] corrupted = wavWithZeroSizedChunk();

        assertTimeoutPreemptively(ofSeconds(2),
                () -> assertNull(AudioDurationProber.probeDurationMs(corrupted)));
    }

    @Test
    void shouldParseMp3CbrDurationWhenBytesComplete() {
        // 128kbps CBR，4000 字节 ≈ 250ms
        byte[] mp3 = mp3Cbr(4000);

        Integer duration = AudioDurationProber.probeDurationMs(mp3, false);

        assertNotNull(duration);
        assertEquals(250, duration);
    }

    @Test
    void shouldRefuseCbrEstimateWhenBytesTruncated() {
        // 截断后按文件大小估算必然偏小，宁可返回空也不能返回错值
        byte[] mp3 = mp3Cbr(4000);

        assertNull(AudioDurationProber.probeDurationMs(mp3, true));
    }

    @Test
    void shouldReturnNullForBlankUrlAndTinyPayload() {
        assertNull(AudioDurationProber.probeDurationMs("  "));
        assertNull(AudioDurationProber.probeDurationMs(new byte[64]));
        assertNull(AudioDurationProber.probeDurationMs((byte[]) null));
    }

    /** 构造最小可解析 wav：RIFF + fmt(byteRate) + data(dataSize)。 */
    private static byte[] wav(int byteRate, int dataSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "RIFF");
        writeLe32(out, 36 + dataSize);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeLe32(out, 16);
        writeLe16(out, 1);
        writeLe16(out, 1);
        writeLe32(out, byteRate);
        writeLe32(out, byteRate);
        writeLe16(out, 2);
        writeLe16(out, 16);
        writeAscii(out, "data");
        writeLe32(out, dataSize);
        out.writeBytes(new byte[Math.min(dataSize, 256)]);
        return pad(out.toByteArray());
    }

    private static byte[] wavWithoutFmt(int dataSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "RIFF");
        writeLe32(out, 36 + dataSize);
        writeAscii(out, "WAVE");
        writeAscii(out, "data");
        writeLe32(out, dataSize);
        out.writeBytes(new byte[256]);
        return pad(out.toByteArray());
    }

    private static byte[] wavWithZeroSizedChunk() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "RIFF");
        writeLe32(out, 512);
        writeAscii(out, "WAVE");
        writeAscii(out, "junk");
        writeLe32(out, 0);
        out.writeBytes(new byte[256]);
        return pad(out.toByteArray());
    }

    /** 构造最小 mp3：MPEG1 Layer3 128kbps 44100Hz 帧头 + 填充数据（无 Xing 头）。 */
    private static byte[] mp3Cbr(int totalBytes) {
        byte[] bytes = new byte[Math.max(totalBytes, 256)];
        bytes[0] = (byte) 0xFF;
        // MPEG1(11) Layer3(01) 无 CRC(1)
        bytes[1] = (byte) 0xFB;
        // bitrateIndex=9(128kbps) sampleRateIndex=0(44100)
        bytes[2] = (byte) 0x90;
        bytes[3] = (byte) 0x00;
        return bytes;
    }

    private static byte[] pad(byte[] bytes) {
        if (bytes.length >= 256) {
            return bytes;
        }
        byte[] padded = new byte[256];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        return padded;
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeLe32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeLe16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
