package com.aid.common.moderation.tencent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import cn.hutool.core.util.HexUtil;

/**
 * 腾讯云 TC3-HMAC-SHA256 通用签名工具
 * - 严格遵循腾讯云官方 Java 签名算法（POST + Content-Type application/json）
 * - 复用价值：后续接腾讯云 COS API、短信 V3 等均可共用同一签名器
 *
 * @author 视觉AID
 */
public final class TencentCloudTc3Signer
{
    /**
     * 签名算法标识
     */
    private static final String ALGORITHM = "TC3-HMAC-SHA256";

    /**
     * 固定的 Content-Type（参与签名，必须与请求头完全一致）
     */
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";

    /**
     * 终止字符串
     */
    private static final String TC3_REQUEST = "tc3_request";

    /**
     * 工具类禁止实例化
     */
    private TencentCloudTc3Signer()
    {
    }

    /**
     * 构建腾讯云 API 请求所需的全部 HTTP 头（含 Authorization 与 X-TC-* 系列）。
     *
     * @param service      产品名（如 ims）
     * @param host         请求域名（如 ims.tencentcloudapi.com）
     * @param action       接口名（如 ImageModeration）
     * @param version      接口版本（如 2020-12-29）
     * @param region       地域（如 ap-shanghai）
     * @param payloadJson  请求体 JSON 字符串
     * @param secretId     腾讯云 SecretId
     * @param secretKey    腾讯云 SecretKey
     * @param timestampSec 请求时间戳（秒）
     * @return 需要附加到请求上的 HTTP 头 Map
     */
    public static Map<String, String> buildHeaders(String service, String host, String action, String version,
            String region, String payloadJson, String secretId, String secretKey, long timestampSec)
    {
        try
        {
            // 第一步：拼接规范请求串
            String httpRequestMethod = "POST";
            String canonicalUri = "/";
            String canonicalQueryString = "";
            // 规范头部：content-type 与 host，注意行尾换行
            String canonicalHeaders = "content-type:" + CONTENT_TYPE + "\n" + "host:" + host + "\n";
            String signedHeaders = "content-type;host";
            // 请求体哈希
            String hashedRequestPayload = sha256Hex(payloadJson);
            String canonicalRequest = httpRequestMethod + "\n"
                    + canonicalUri + "\n"
                    + canonicalQueryString + "\n"
                    + canonicalHeaders + "\n"
                    + signedHeaders + "\n"
                    + hashedRequestPayload;

            // 第二步：拼接待签名字符串
            // 日期使用 UTC，从时间戳换算，格式 yyyy-MM-dd
            String date = formatUtcDate(timestampSec);
            String credentialScope = date + "/" + service + "/" + TC3_REQUEST;
            String hashedCanonicalRequest = sha256Hex(canonicalRequest);
            String stringToSign = ALGORITHM + "\n"
                    + timestampSec + "\n"
                    + credentialScope + "\n"
                    + hashedCanonicalRequest;

            // 第三步：计算派生密钥
            byte[] secretDate = hmac256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
            byte[] secretService = hmac256(secretDate, service);
            byte[] secretSigning = hmac256(secretService, TC3_REQUEST);
            // 第四步：计算签名
            String signature = HexUtil.encodeHexStr(hmac256(secretSigning, stringToSign));

            // 第五步：拼接 Authorization
            String authorization = ALGORITHM + " "
                    + "Credential=" + secretId + "/" + credentialScope + ", "
                    + "SignedHeaders=" + signedHeaders + ", "
                    + "Signature=" + signature;

            // 组装所有请求头
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", authorization);
            headers.put("Content-Type", CONTENT_TYPE);
            headers.put("Host", host);
            headers.put("X-TC-Action", action);
            headers.put("X-TC-Timestamp", String.valueOf(timestampSec));
            headers.put("X-TC-Version", version);
            headers.put("X-TC-Region", region);
            return headers;
        }
        catch (Exception e)
        {
            // 签名计算失败属于编程/环境异常，向上抛运行时异常由调用方处理
            throw new RuntimeException("腾讯云签名计算失败", e);
        }
    }

    /**
     * 计算字符串的 SHA256 十六进制摘要（小写）
     *
     * @param input 原始字符串
     * @return 十六进制摘要
     * @throws Exception 算法不可用时抛出
     */
    private static String sha256Hex(String input) throws Exception
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        return HexUtil.encodeHexStr(hash);
    }

    /**
     * HmacSHA256 计算
     *
     * @param key 密钥字节数组
     * @param msg 待签名消息
     * @return 签名字节数组
     * @throws Exception 算法不可用时抛出
     */
    private static byte[] hmac256(byte[] key, String msg) throws Exception
    {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(key, mac.getAlgorithm());
        mac.init(secretKeySpec);
        return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 将秒级时间戳格式化为 UTC 的 yyyy-MM-dd 日期
     *
     * @param timestampSec 秒级时间戳
     * @return UTC 日期字符串
     */
    private static String formatUtcDate(long timestampSec)
    {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
        // 必须使用 UTC 时区，否则签名与服务端不一致
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        return formatter.format(new Date(timestampSec * 1000L));
    }
}
