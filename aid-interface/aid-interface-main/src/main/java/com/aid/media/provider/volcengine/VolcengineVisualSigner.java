package com.aid.media.provider.volcengine;

import cn.hutool.core.util.StrUtil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 火山引擎「视觉」服务（visual.volcengineapi.com）SigV4 签名工具。
 *
 * @author 视觉AID
 */
public final class VolcengineVisualSigner {

    // 签名算法：当前火山视觉只支持 HMAC-SHA256。
    private static final String ALGORITHM = "HMAC-SHA256";
    // scope 的结尾固定串。
    private static final String SCOPE_SUFFIX = "request";
    // 长时间戳（含小时分秒）：用于 X-Date 与 string_to_sign 第二行。
    private static final DateTimeFormatter LONG_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    // 短时间戳（仅日期）：用于 scope 与 kDate。
    private static final DateTimeFormatter SHORT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    // Mac 算法名，Java 标准名称。
    private static final String HMAC_SHA256 = "HmacSHA256";

    private VolcengineVisualSigner() {
    }

    /**
     * 对一次 POST JSON 调用进行签名，返回需要补充到 HTTP 请求上的所有头。
     *
     * @param accessKey      AK（必填）
     * @param secretKey      SK（必填）
     * @param region         区域，火山视觉为 cn-north-1
     * @param service        服务名，火山视觉为 cv
     * @param host           主机，例如 visual.volcengineapi.com
     * @param method         HTTP 方法，固定为 POST
     * @param canonicalUri   规范化 URI，通常为 "/"
     * @param queryParams    Query 参数（会按 key 字典序拼接）
     * @param contentType    Content-Type（例如 application/json）
     * @param body           HTTP 正文（String），null 视为空串
     * @return 需要追加到请求上的 HTTP 头 Map
     */
    public static Map<String, String> sign(String accessKey,
                                           String secretKey,
                                           String region,
                                           String service,
                                           String host,
                                           String method,
                                           String canonicalUri,
                                           Map<String, String> queryParams,
                                           String contentType,
                                           String body) {
        if (StrUtil.isBlank(accessKey) || StrUtil.isBlank(secretKey)) {
            throw new IllegalArgumentException("火山视觉签名缺少 AK/SK");
        }
        if (StrUtil.isBlank(region) || StrUtil.isBlank(service) || StrUtil.isBlank(host)) {
            throw new IllegalArgumentException("火山视觉签名缺少 region/service/host");
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String longDate = LONG_DATE_FORMATTER.format(now);
        String shortDate = SHORT_DATE_FORMATTER.format(now);

        String payload = body == null ? "" : body;
        String payloadHash = hexSha256(payload.getBytes(StandardCharsets.UTF_8));

        //    其他业务自定义头一律不参与签名，以免误伤。
        Map<String, String> headersForSign = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headersForSign.put("Host", host);
        if (StrUtil.isNotBlank(contentType)) {
            headersForSign.put("Content-Type", contentType);
        }
        headersForSign.put("X-Date", longDate);
        headersForSign.put("X-Content-Sha256", payloadHash);

        String canonicalQuery = buildCanonicalQuery(queryParams);

        //    canonicalHeaders：每行 "lower(key):value\n"
        //    signedHeaders：分号拼接的小写 key
        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeadersSb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : headersForSign.entrySet()) {
            String lowerKey = entry.getKey().toLowerCase(Locale.ROOT);
            canonicalHeaders.append(lowerKey).append(':').append(entry.getValue().trim()).append('\n');
            if (!first) {
                signedHeadersSb.append(';');
            }
            signedHeadersSb.append(lowerKey);
            first = false;
        }
        String signedHeaders = signedHeadersSb.toString();

        //    method \n uri \n query \n canonicalHeaders \n signedHeaders \n payloadHash
        String canonicalRequest = method.toUpperCase(Locale.ROOT) + '\n'
                + (StrUtil.isBlank(canonicalUri) ? "/" : canonicalUri) + '\n'
                + canonicalQuery + '\n'
                + canonicalHeaders + '\n'
                + signedHeaders + '\n'
                + payloadHash;

        String scope = shortDate + '/' + region + '/' + service + '/' + SCOPE_SUFFIX;
        String stringToSign = ALGORITHM + '\n'
                + longDate + '\n'
                + scope + '\n'
                + hexSha256(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] kDate = hmacSha256(secretKey.getBytes(StandardCharsets.UTF_8), shortDate);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        byte[] kSigning = hmacSha256(kService, SCOPE_SUFFIX);

        String signature = toHex(hmacSha256(kSigning, stringToSign));

        String authorization = ALGORITHM
                + " Credential=" + accessKey + '/' + scope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        Map<String, String> outHeaders = new LinkedHashMap<>();
        outHeaders.put("Host", host);
        if (StrUtil.isNotBlank(contentType)) {
            outHeaders.put("Content-Type", contentType);
        }
        outHeaders.put("X-Date", longDate);
        outHeaders.put("X-Content-Sha256", payloadHash);
        outHeaders.put("Authorization", authorization);
        return outHeaders;
    }

    /**
     * 将 queryParams 规范化为 "k=v&k=v" 形式：按 key 字典序，value 做 URL 编码。
     */
    private static String buildCanonicalQuery(Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>(queryParams);
        List<String> parts = new java.util.ArrayList<>(sorted.size());
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            String k = urlEncode(entry.getKey());
            String v = urlEncode(entry.getValue() == null ? "" : entry.getValue());
            parts.add(k + '=' + v);
        }
        return String.join("&", parts);
    }

    /**
     * URL 编码：使用 UTF-8，并把 Java 默认 +（空格）替换为 %20，与火山算法保持一致。
     */
    private static String urlEncode(String value) {
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        return encoded.replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    /**
     * 计算 sha256，返回小写 hex。
     */
    private static String hexSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(data));
        } catch (Exception e) {
            // 标准算法不存在属于环境问题，抛出便于立刻发现。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * HMAC-SHA256 原始 byte[]。
     */
    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 计算失败", e);
        }
    }

    /**
     * byte[] → 小写 hex 字符串。
     */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * 便捷方法：列举签名时需要的 header key，避免调用方硬编码。
     */
    public static List<String> signedHeaderKeys() {
        return Arrays.asList("Host", "Content-Type", "X-Date", "X-Content-Sha256", "Authorization");
    }
}
