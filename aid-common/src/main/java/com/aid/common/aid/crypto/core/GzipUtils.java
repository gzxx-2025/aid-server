package com.aid.common.aid.crypto.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.aid.common.aid.crypto.exception.ApiCryptoException;

/**
 * GZIP 压缩 / 解压工具（接口信封加密专用）。
 *
 * 加密流程顺序：明文 →（可选 GZIP）→ AES-GCM 加密 → Base64；
 * 解密为逆序。压缩对 JSON 文本收益极高（通常 70%~90%），用于缓解大响应体压力。
 *
 * @author 视觉AID
 */
public final class GzipUtils {

    private GzipUtils() {
    }

    /**
     * GZIP 压缩。
     *
     * @param data 原始字节
     * @return 压缩后字节
     */
    public static byte[] compress(byte[] data) {
        // 输入为空直接返回，避免无意义流操作
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, data.length / 2));
             GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(data);
            // 必须 finish 后才能拿到完整压缩数据
            gzip.finish();
            return bos.toByteArray();
        } catch (Exception e) {
            // 先记录原始异常，再抛出供上层统一处理（此处不直接吐给前端）
            throw new ApiCryptoException("GZIP压缩失败", e);
        }
    }

    /**
     * GZIP 解压。
     *
     * @param data 压缩字节
     * @return 解压后字节
     */
    public static byte[] decompress(byte[] data) {
        // 输入为空直接返回
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             GZIPInputStream gzip = new GZIPInputStream(bis);
             ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 2)) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = gzip.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new ApiCryptoException("GZIP解压失败", e);
        }
    }
}
