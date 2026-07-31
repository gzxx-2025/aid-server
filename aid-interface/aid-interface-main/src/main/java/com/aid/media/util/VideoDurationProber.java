package com.aid.media.util;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;

/**
 * 视频真实时长探测工具（无第三方依赖，解析 ISO BMFF/MP4 容器头）。
 * 背景：部分视频厂商（Vidu 对口型等）不回传成片时长，导致 aid_media_task.output_duration_seconds
 * 与业务表 video_duration 落库为空，时间轴按 0 秒排布、成片导出错位。本工具直接从视频字节的
 * {@code moov → mvhd} 头读取 duration 与 timescale 计算时长。
 * 所有方法失败返回 null，绝不抛异常阻断主流程。
 *
 * @author 视觉AID
 */
@Slf4j
public final class VideoDurationProber {

    /** 最小可解析字节数：容器头都放不下的字节直接判不可解析 */
    private static final int MIN_PARSE_BYTES = 64;

    /** 容器盒子类型：影片头容器 */
    private static final String BOX_MOOV = "moov";

    /** 容器盒子类型：影片头（时长与时间刻度） */
    private static final String BOX_MVHD = "mvhd";

    /** 盒子头长度：size(4) + type(4) */
    private static final int BOX_HEADER_BYTES = 8;

    /** 扩展盒子头长度：size(4) + type(4) + largesize(8) */
    private static final int LARGE_BOX_HEADER_BYTES = 16;

    /** size==1 表示长度由随后的 64 位 largesize 给出 */
    private static final long BOX_SIZE_LARGE_FLAG = 1L;

    /** size==0 表示盒子延伸至文件末尾 */
    private static final long BOX_SIZE_TO_EOF = 0L;

    /** mvhd version==1 时时间字段为 64 位 */
    private static final int MVHD_VERSION_64BIT = 1;

    /** 32 位时长全 1 表示时长未知 */
    private static final long DURATION_UNKNOWN_32 = 0xFFFFFFFFL;

    /** 秒→毫秒换算 */
    private static final int MS_PER_SECOND = 1000;

    private VideoDurationProber() {
    }

    /**
     * 从视频字节流探测时长（毫秒）：支持 ISO BMFF 系列容器（mp4 / m4v / mov）。
     *
     * @param bytes 视频完整字节
     * @return 时长毫秒；容器不支持或头信息缺失返回 null
     */
    public static Integer probeDurationMs(byte[] bytes) {
        if (Objects.isNull(bytes) || bytes.length < MIN_PARSE_BYTES) {
            return null;
        }
        try {
            int[] moov = locateBox(bytes, 0, bytes.length, BOX_MOOV);
            if (Objects.isNull(moov)) {
                return null;
            }
            int[] mvhd = locateBox(bytes, moov[0], moov[1], BOX_MVHD);
            if (Objects.isNull(mvhd)) {
                return null;
            }
            return parseMvhdDurationMs(bytes, mvhd[0], mvhd[1]);
        } catch (Exception ex) {
            log.warn("视频时长解析异常, err={}", ex.getMessage());
            return null;
        }
    }

    /**
     * 在给定区间内按盒子链表顺序查找指定类型盒子。
     *
     * @param bytes 视频字节
     * @param start 区间起点
     * @param end   区间终点（不含）
     * @param type  盒子类型（四字符）
     * @return {内容起点, 内容终点}；未找到返回 null
     */
    private static int[] locateBox(byte[] bytes, int start, int end, String type) {
        int cursor = start;
        while (cursor + BOX_HEADER_BYTES <= end) {
            long size = readUInt32(bytes, cursor);
            String boxType = new String(bytes, cursor + 4, 4, StandardCharsets.US_ASCII);
            int headerBytes = BOX_HEADER_BYTES;
            if (size == BOX_SIZE_LARGE_FLAG) {
                if (cursor + LARGE_BOX_HEADER_BYTES > end) {
                    return null;
                }
                size = readUInt64(bytes, cursor + BOX_HEADER_BYTES);
                headerBytes = LARGE_BOX_HEADER_BYTES;
            } else if (size == BOX_SIZE_TO_EOF) {
                size = (long) end - cursor;
            }
            // 盒子长度非法或越界：容器已损坏，停止解析
            if (size < headerBytes || cursor + size > end) {
                return null;
            }
            if (type.equals(boxType)) {
                return new int[]{cursor + headerBytes, (int) (cursor + size)};
            }
            cursor += (int) size;
        }
        return null;
    }

    /**
     * 解析 mvhd 内容区的时长（毫秒）。
     *
     * @param bytes        视频字节
     * @param contentStart mvhd 内容起点
     * @param contentEnd   mvhd 内容终点（不含）
     * @return 时长毫秒；字段缺失或时长未知返回 null
     */
    private static Integer parseMvhdDurationMs(byte[] bytes, int contentStart, int contentEnd) {
        int version = bytes[contentStart] & 0xFF;
        // version + flags 共 4 字节，其后依次为创建时间、修改时间、时间刻度、时长
        int timescaleOffset = version == MVHD_VERSION_64BIT ? contentStart + 20 : contentStart + 12;
        int durationOffset = version == MVHD_VERSION_64BIT ? contentStart + 24 : contentStart + 16;
        int durationBytes = version == MVHD_VERSION_64BIT ? 8 : 4;
        if (durationOffset + durationBytes > contentEnd) {
            return null;
        }
        long timescale = readUInt32(bytes, timescaleOffset);
        long duration = version == MVHD_VERSION_64BIT
                ? readUInt64(bytes, durationOffset)
                : readUInt32(bytes, durationOffset);
        if (timescale <= 0 || duration <= 0 || duration == DURATION_UNKNOWN_32
                || duration > Long.MAX_VALUE / MS_PER_SECOND) {
            return null;
        }
        long durationMs = duration * MS_PER_SECOND / timescale;
        if (durationMs <= 0 || durationMs > Integer.MAX_VALUE) {
            return null;
        }
        return (int) durationMs;
    }

    /** 读取无符号 32 位大端整数 */
    private static long readUInt32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xFF) << 24)
                | ((long) (bytes[offset + 1] & 0xFF) << 16)
                | ((long) (bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    /** 读取无符号 64 位大端整数（超过 long 正数范围的极端值按非法处理，返回负数由调用方拦截） */
    private static long readUInt64(byte[] bytes, int offset) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFF);
        }
        return value;
    }
}
