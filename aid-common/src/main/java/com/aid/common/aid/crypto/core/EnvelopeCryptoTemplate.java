package com.aid.common.aid.crypto.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aid.common.aid.crypto.config.ApiCryptoConfig;
import com.aid.common.aid.crypto.exception.ApiCryptoException;

import cn.hutool.core.util.StrUtil;

/**
 * 信封加密编排模板。
 *
 * @author 视觉AID
 */
public class EnvelopeCryptoTemplate {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeCryptoTemplate.class);

    private final ApiCryptoConfigProvider configProvider;

    public EnvelopeCryptoTemplate(ApiCryptoConfigProvider configProvider) {
        this.configProvider = configProvider;
    }

    /**
     * 用 RSA 私钥解出前端发来的一次性 AES 密钥。
     *
     * @param encryptedKeyBase64 Base64(RSA-OAEP(AES_KEY))，即请求头 X-Encrypt-Key 的值
     * @return 32 字节 AES 密钥
     */
    public byte[] decryptAesKey(String encryptedKeyBase64) {
        ApiCryptoConfig config = configProvider.getConfig();
        // 缺密钥头：先记录再抛短文案
        if (StrUtil.isBlank(encryptedKeyBase64)) {
            log.error("接口解密失败: 缺少加密密钥头 X-Encrypt-Key");
            throw new ApiCryptoException("缺少密钥");
        }
        // 服务端未配置私钥：配置缺失，明确报错便于排查
        if (StrUtil.isBlank(config.getPrivateKey())) {
            log.error("接口解密失败: 未配置 RSA 私钥 (aid_config:api_crypto.private_key)");
            throw new ApiCryptoException("密钥未配置");
        }
        byte[] cipher = EnvelopeCryptoUtils.base64Decode(encryptedKeyBase64.trim());
        byte[] aesKey = EnvelopeCryptoUtils.rsaDecrypt(cipher, config.getPrivateKey());
        // 解出的 AES 密钥必须是 32 字节（256 位），否则视为非法请求
        if (aesKey.length != EnvelopeCryptoUtils.AES_KEY_LENGTH) {
            log.error("接口解密失败: AES 密钥长度非法, 实际={}字节", aesKey.length);
            throw new ApiCryptoException("密钥非法");
        }
        return aesKey;
    }

    /**
     * 解密请求体（请求不做 GZIP）。
     *
     * @param bodyBase64 Base64(AES-GCM(明文JSON))，即加密后的请求体
     * @param aesKey     32 字节 AES 密钥
     * @param ivBase64   Base64(IV)，即请求头 X-Encrypt-Iv 的值
     * @return 解密后的明文 JSON 字节
     */
    public byte[] decryptRequestBody(String bodyBase64, byte[] aesKey, String ivBase64) {
        // 缺 IV 头无法解密
        if (StrUtil.isBlank(ivBase64)) {
            log.error("接口解密失败: 缺少 IV 头 X-Encrypt-Iv");
            throw new ApiCryptoException("缺少向量");
        }
        byte[] iv = EnvelopeCryptoUtils.base64Decode(ivBase64.trim());
        // GCM IV 必须为 12 字节
        if (iv.length != EnvelopeCryptoUtils.GCM_IV_LENGTH) {
            log.error("接口解密失败: IV 长度非法, 实际={}字节", iv.length);
            throw new ApiCryptoException("向量非法");
        }
        byte[] cipher = EnvelopeCryptoUtils.base64Decode(bodyBase64);
        // GCM 解密自带完整性校验，篡改会抛异常
        return EnvelopeCryptoUtils.aesGcmDecrypt(cipher, aesKey, iv);
    }

    /**
     * 加密响应体。
     *
     * @param plain  明文字节（通常是响应 JSON 序列化结果）
     * @param aesKey 32 字节 AES 密钥（复用本次请求解出的密钥）
     * @return 加密结果（含新随机 IV、是否压缩标志、Base64 密文）
     */
    public EncryptedResult encryptResponse(byte[] plain, byte[] aesKey) {
        ApiCryptoConfig config = configProvider.getConfig();
        // 兜底防护：明文过大直接拒绝，避免大对象拖垮服务
        if (plain.length > config.getMaxPlainSize()) {
            log.error("接口加密失败: 明文超过上限, size={}字节, max={}字节",
                    plain.length, config.getMaxPlainSize());
            throw new ApiCryptoException("数据过大");
        }
        boolean gzip = false;
        byte[] payload = plain;
        // 超过阈值且开启 GZIP 时压缩（对大 JSON 收益显著）
        if (config.isGzipEnabled() && plain.length >= config.getGzipThreshold()) {
            payload = GzipUtils.compress(plain);
            gzip = true;
        }
        // 每次响应生成全新随机 IV，绝不复用请求 IV
        byte[] iv = EnvelopeCryptoUtils.generateIv();
        byte[] cipher = EnvelopeCryptoUtils.aesGcmEncrypt(payload, aesKey, iv);
        return new EncryptedResult(
                EnvelopeCryptoUtils.base64Encode(iv),
                gzip,
                EnvelopeCryptoUtils.base64Encode(cipher));
    }

    /**
     * 时间戳防重放窗口（毫秒），<=0 表示关闭校验。供拦截器读取。
     */
    public long getTimestampWindowMs() {
        return configProvider.getConfig().getTimestampWindowMs();
    }

    /**
     * 加密结果载体。
     *
     * @param ivBase64   本次响应使用的随机 IV（Base64）
     * @param gzip       data 在加密前是否经过 GZIP 压缩
     * @param bodyBase64 加密后的响应体（Base64）
     */
    public record EncryptedResult(String ivBase64, boolean gzip, String bodyBase64) {
    }
}
