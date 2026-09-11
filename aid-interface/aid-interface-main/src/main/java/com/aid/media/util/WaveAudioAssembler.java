package com.aid.media.util;

import com.aid.common.exception.ServiceException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 合并音频事件，重复 WAV 头只保留一份并重新计算容器长度。 */
public final class WaveAudioAssembler {
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private final ByteArrayOutputStream data = new ByteArrayOutputStream();
    private byte[] format;

    public void append(byte[] chunk) {
        if (chunk == null || chunk.length == 0) return;
        if (chunk.length >= 12 && tag(chunk, 0, "RIFF") && tag(chunk, 8, "WAVE")) {
            if (format == null && data.size() > 0) throw new ServiceException("音频格式不一致");
            int offset = 12;
            boolean hasData = false;
            while (offset + 8 <= chunk.length) {
                long length = Integer.toUnsignedLong(ByteBuffer.wrap(chunk, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
                int available = chunk.length - offset - 8;
                if (tag(chunk, offset, "data")) {
                    // 流式 WAV 可用未知长度标记；此时当前事件剩余字节即为音频内容。
                    int count = length == 0xffffffffL || length == 0 ? available : Math.toIntExact(length);
                    if (format == null || count > available) throw new ServiceException("音频容器不完整");
                    write(chunk, offset + 8, count);
                    hasData = true;
                    offset += 8 + count + (count & 1);
                } else {
                    if (length > available) throw new ServiceException("音频容器不完整");
                    int count = (int) length;
                    if (tag(chunk, offset, "fmt ")) {
                        byte[] next = Arrays.copyOfRange(chunk, offset + 8, offset + 8 + count);
                        if (next.length < 16 || format != null && !Arrays.equals(format, next)) throw new ServiceException("音频格式不一致");
                        format = next;
                    }
                    offset += 8 + count + (count & 1);
                }
            }
            if (!hasData) throw new ServiceException("音频容器不完整");
        } else write(chunk, 0, chunk.length);
    }

    public byte[] finish() {
        byte[] bytes = data.toByteArray();
        if (format == null) return bytes;
        int formatPadding = format.length & 1;
        int dataPadding = bytes.length & 1;
        ByteBuffer result = ByteBuffer.allocate(28 + format.length + formatPadding + bytes.length + dataPadding).order(ByteOrder.LITTLE_ENDIAN);
        result.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(result.capacity() - 8).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        result.putInt(format.length).put(format);
        if (formatPadding != 0) result.put((byte) 0);
        result.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes.length).put(bytes);
        if (dataPadding != 0) result.put((byte) 0);
        return result.array();
    }

    public int size() { return data.size(); }

    private void write(byte[] chunk, int offset, int count) {
        if (data.size() + (long) count > MAX_BYTES) throw new ServiceException("音频响应过大");
        data.write(chunk, offset, count);
    }

    private boolean tag(byte[] value, int offset, String tag) {
        return offset + 4 <= value.length && value[offset] == tag.charAt(0) && value[offset + 1] == tag.charAt(1)
                && value[offset + 2] == tag.charAt(2) && value[offset + 3] == tag.charAt(3);
    }
}
