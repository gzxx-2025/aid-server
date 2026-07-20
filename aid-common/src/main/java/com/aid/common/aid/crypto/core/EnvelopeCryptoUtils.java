package com.aid.common.aid.crypto.core;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import com.aid.common.aid.crypto.exception.ApiCryptoException;

/**
 * 信封加密核心工具：AES-GCM-256（对称，加密业务数据）+ RSA-OAEP-SHA256（非对称，加密 AES 密钥）。
 *
 * @author 视觉AID
 */
public final class EnvelopeCryptoUtils {

    /** AES-GCM 变换名 */
    private static final String AES_TRANSFORM = "AES/GCM/NoPadding";

    /** RSA-OAEP 变换名（SHA-256） */
    private static final String RSA_TRANSFORM = "RSA/ECB/OAEPPadding";

    /** GCM IV 长度（字节）。GCM 推荐 12 字节 */
    public static final int GCM_IV_LENGTH = 12;

    /** GCM 认证 Tag 长度（位）。固定 128 位 */
    public static final int GCM_TAG_BIT_LENGTH = 128;

    /** AES 密钥长度（字节）。32 字节 = 256 位 */
    public static final int AES_KEY_LENGTH = 32;

    /** 安全随机源 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Base64 编解码器（标准字母表） */
    private static final Base64.Encoder B64_ENCODER = Base64.getEncoder();
    private static final Base64.Decoder B64_DECODER = Base64.getDecoder();

    private EnvelopeCryptoUtils() {
    }
    /**
     * 生成 256 位随机 AES 密钥。
     *
     * @return 32 字节密钥
     */
    public static byte[] generateAesKey() {
        byte[] key = new byte[AES_KEY_LENGTH];
        SECURE_RANDOM.nextBytes(key);
        return key;
    }

    /**
     * 生成 12 字节随机 GCM IV。
     *
     * 调用方必须保证“同一把 AES 密钥 + 同一个 IV”绝不重复使用，故每次加密都应新生成 IV。
     *
     * @return 12 字节 IV
     */
    public static byte[] generateIv() {
        byte[] iv = new byte[GCM_IV_LENGTH];
        SECURE_RANDOM.nextBytes(iv);
        return iv;
    }

    /**
     * AES-GCM 加密。
     *
     * @param plain  明文字节
     * @param aesKey 32 字节 AES 密钥
     * @param iv     12 字节 IV
     * @return 密文（含末尾 16 字节认证 Tag，与 WebCrypto 输出一致）
     */
    public static byte[] aesGcmEncrypt(byte[] plain, byte[] aesKey, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance(AES_TRANSFORM);
            // GCM 参数：Tag 长度 + IV
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BIT_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), spec);
            return cipher.doFinal(plain);
        } catch (Exception e) {
            // 内部细节仅落日志，对外短文案由上层处理
            throw new ApiCryptoException("AES加密失败", e);
        }
    }

    /**
     * AES-GCM 解密。
     *
     * @param cipherText 密文（含末尾认证 Tag）
     * @param aesKey     32 字节 AES 密钥
     * @param iv         12 字节 IV
     * @return 明文字节
     */
    public static byte[] aesGcmDecrypt(byte[] cipherText, byte[] aesKey, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance(AES_TRANSFORM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BIT_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), spec);
            // Tag 校验失败会抛 AEADBadTagException，说明数据被篡改或密钥/IV 不匹配
            return cipher.doFinal(cipherText);
        } catch (Exception e) {
            throw new ApiCryptoException("AES解密失败", e);
        }
    }
    /**
     * RSA-OAEP 公钥加密（一般由前端执行，此处提供给联调/自测用）。
     *
     * @param data         待加密数据（如 AES 密钥）
     * @param publicKeyB64 X509 公钥 Base64
     * @return 密文字节
     */
    public static byte[] rsaEncrypt(byte[] data, String publicKeyB64) {
        try {
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(B64_DECODER.decode(publicKeyB64));
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec);
            Cipher cipher = Cipher.getInstance(RSA_TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, buildOaepSpec());
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new ApiCryptoException("RSA加密失败", e);
        }
    }

    /**
     * RSA-OAEP 私钥解密（服务端执行，解出前端发来的一次性 AES 密钥）。
     *
     * @param cipherText    密文字节
     * @param privateKeyB64 PKCS8 私钥 Base64
     * @return 明文字节（AES 密钥）
     */
    public static byte[] rsaDecrypt(byte[] cipherText, String privateKeyB64) {
        try {
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(B64_DECODER.decode(privateKeyB64));
            PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec);
            Cipher cipher = Cipher.getInstance(RSA_TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, privateKey, buildOaepSpec());
            return cipher.doFinal(cipherText);
        } catch (Exception e) {
            throw new ApiCryptoException("RSA解密失败", e);
        }
    }

    /**
     * 构造 OAEP 参数：SHA-256 + MGF1(SHA-256)。
     *
     * 必须显式指定，否则部分 JDK 默认 MGF1 使用 SHA-1，会与前端 WebCrypto（统一 SHA-256）不匹配，
     * 导致解密失败。
     */
    private static OAEPParameterSpec buildOaepSpec() {
        return new OAEPParameterSpec("SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    }
    public static String base64Encode(byte[] data) {
        return B64_ENCODER.encodeToString(data);
    }

    public static byte[] base64Decode(String data) {
        return B64_DECODER.decode(data);
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
