package com.aid.auth.domain.vo;

import lombok.Data;

/**
 * 接口加密公钥下发 VO。
 *
 * 前端进入应用时拉取一次，缓存于内存；用该公钥（RSA-OAEP-SHA256）加密一次性 AES 密钥。
 *
 * @author 视觉AID
 */
@Data
public class CryptoPublicKeyVO {

    /** RSA 公钥（X509 / SubjectPublicKeyInfo，Base64）。可公开 */
    private String publicKey;

    /** 算法标识，固定 RSA-OAEP（SHA-256），供前端对齐 WebCrypto 参数 */
    private String algorithm = "RSA-OAEP-SHA256";

    /** 服务端当前时间戳（毫秒），供前端校准时差，构造 X-Encrypt-Ts */
    private Long serverTime;

    public CryptoPublicKeyVO(String publicKey, Long serverTime) {
        this.publicKey = publicKey;
        this.serverTime = serverTime;
    }
}
