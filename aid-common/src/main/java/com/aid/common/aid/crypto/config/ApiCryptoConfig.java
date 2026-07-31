package com.aid.common.aid.crypto.config;

import lombok.Data;

/**
 * 接口加密“生效配置”快照。
 *
 * @author 视觉AID
 */
@Data
public class ApiCryptoConfig {

    /** 全局开关：true 开启接口加解密 */
    private boolean enabled = false;

    /** 是否对加密前明文做 GZIP 压缩 */
    private boolean gzipEnabled = true;

    /** GZIP 压缩阈值（字节），小于该值不压缩 */
    private int gzipThreshold = 1024;

    /** 单次可加密明文最大字节数（兜底），默认 20MB */
    private long maxPlainSize = 20L * 1024 * 1024;

    /** 时间戳防重放窗口（毫秒），默认 5 分钟；<=0 关闭校验 */
    private long timestampWindowMs = 5L * 60 * 1000;

    /** RSA 公钥（X509，Base64），可公开 */
    private String publicKey;

    /** RSA 私钥（PKCS8，Base64），仅服务端持有 */
    private String privateKey;
}
