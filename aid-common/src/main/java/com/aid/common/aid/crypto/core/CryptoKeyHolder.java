package com.aid.common.aid.crypto.core;

/**
 * 单次请求内的 AES 密钥持有者（ThreadLocal）。
 *
 * @author 视觉AID
 */
public final class CryptoKeyHolder {

    private static final ThreadLocal<byte[]> AES_KEY = new ThreadLocal<>();

    private CryptoKeyHolder() {
    }

    /**
     * 写入本次请求的 AES 密钥。
     *
     * @param aesKey 32 字节 AES 密钥
     */
    public static void setAesKey(byte[] aesKey) {
        AES_KEY.set(aesKey);
    }

    /**
     * 获取本次请求的 AES 密钥。
     *
     * @return AES 密钥；不存在返回 null
     */
    public static byte[] getAesKey() {
        return AES_KEY.get();
    }

    /**
     * 是否已持有 AES 密钥。
     *
     * @return true 已持有
     */
    public static boolean hasAesKey() {
        return AES_KEY.get() != null;
    }

    /**
     * 清理 ThreadLocal，必须在请求结束时调用。
     */
    public static void clear() {
        AES_KEY.remove();
    }
}
