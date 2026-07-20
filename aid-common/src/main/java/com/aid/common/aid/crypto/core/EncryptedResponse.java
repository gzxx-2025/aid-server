package com.aid.common.aid.crypto.core;

import lombok.Data;

/**
 * 加密响应包体。
 *
 * @author 视觉AID
 */
@Data
public class EncryptedResponse {

    /** 加密标志，固定 true */
    private final boolean encrypted = true;

    /** 本次响应使用的随机 IV（Base64） */
    private String iv;

    /** 密文解密后是否需要 GZIP 解压 */
    private boolean gzip;

    /** AES-GCM 加密后的业务数据（Base64，含认证 Tag） */
    private String data;

    public EncryptedResponse(String iv, boolean gzip, String data) {
        this.iv = iv;
        this.gzip = gzip;
        this.data = data;
    }
}
