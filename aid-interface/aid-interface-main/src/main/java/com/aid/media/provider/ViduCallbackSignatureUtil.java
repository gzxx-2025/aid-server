package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.aid.media.constants.ViduConstants;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.function.Function;

/**
 * Vidu 回调验签工具（HMAC-SHA256）。
 */
public final class ViduCallbackSignatureUtil {

    /** HMAC-SHA256 算法名 */
    private static final String HMAC_SHA256 = "HmacSHA256";
    /** 签名头分隔符 */
    private static final String SIGNED_HEADERS_SPLIT = ";";

    private ViduCallbackSignatureUtil() {
    }

    /**
     * 校验回调签名是否合法。
     *
     * @param httpMethod      请求方法（固定 POST，全大写）
     * @param callbackUrl     创建任务时设置的完整 callback_url（用于解析 uri/query，与上游签名口径一致）
     * @param date            请求头 Date（GMT 格式）
     * @param signedHeaders   X-HMAC-SIGNED-HEADERS 头原文（分号分隔，定义参与签名的头及顺序）
     * @param signature       X-HMAC-SIGNATURE 头（上游计算出的签名）
     * @param secretKey       密钥（供应商 api_key）
     * @param headerValueGetter 按 headerName 获取请求头值的函数（大小写不敏感由调用方保证）
     * @return true=验签通过
     */
    public static boolean verify(String httpMethod,
                                 String callbackUrl,
                                 String date,
                                 String signedHeaders,
                                 String signature,
                                 String secretKey,
                                 Function<String, String> headerValueGetter) {
        // 任一关键输入缺失则视为验签失败（fail-close）。
        if (StrUtil.hasBlank(httpMethod, callbackUrl, date, signedHeaders, signature, secretKey)
            || headerValueGetter == null) {
            return false;
        }
        String expected = sign(httpMethod, callbackUrl, date, signedHeaders, secretKey, headerValueGetter);
        if (expected == null) {
            return false;
        }
        // 定长比较，避免计时侧信道。
        return constantTimeEquals(expected, signature.trim());
    }

    /**
     * 计算签名值（base64）。失败返回 null。
     */
    public static String sign(String httpMethod,
                              String callbackUrl,
                              String date,
                              String signedHeaders,
                              String secretKey,
                              Function<String, String> headerValueGetter) {
        try {
            URI uri = URI.create(callbackUrl.trim());
            String path = StrUtil.isBlank(uri.getRawPath()) ? "/" : uri.getRawPath();
            String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
            StringBuilder sb = new StringBuilder();
            sb.append(httpMethod).append('\n');
            sb.append(path).append('\n');
            sb.append(query).append('\n');
            sb.append(ViduConstants.CALLBACK_ACCESS_KEY).append('\n');
            sb.append(date).append('\n');
            for (String header : signedHeaders.split(SIGNED_HEADERS_SPLIT)) {
                String name = header.trim();
                if (name.isEmpty()) {
                    continue;
                }
                String value = headerValueGetter.apply(name);
                sb.append(name).append(':').append(value == null ? "" : value).append('\n');
            }
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] digest = mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            // 任意异常（密钥非法/URL 非法等）一律视为验签失败。
            return null;
        }
    }

    /** 定长比较，避免按字符提前返回造成的计时侧信道。 */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8),
            b.getBytes(StandardCharsets.UTF_8));
    }
}
