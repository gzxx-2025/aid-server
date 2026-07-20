package com.aid.common.aid.encrypt.core;

import lombok.Data;
import com.aid.common.aid.encrypt.enumd.AlgorithmType;
import com.aid.common.aid.encrypt.enumd.EncodeType;

/**
 * 加密上下文 用于encryptor传递必要的参数。
 *
 * @author 视觉AID
 *
 */
@Data
public class EncryptContext {

    /**
     * 默认算法
     */
    private AlgorithmType algorithm;

    /**
     * 安全秘钥
     */
    private String password;

    /**
     * 公钥
     */
    private String publicKey;

    /**
     * 私钥
     */
    private String privateKey;

    /**
     * 编码方式，base64/hex
     */
    private EncodeType encode;

}
