package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.aid.media.constants.KlingConstants;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/** 可灵新版 Webhook HMAC-SHA256 验签。 */
public final class KlingCallbackSignatureUtil {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private KlingCallbackSignatureUtil() {
    }

    /** 至少包含一个符合 whsec_ + Base64 格式的密钥；支持轮换期多密钥。 */
    public static boolean hasValidSecret(String configuredSecrets) {
        if (StrUtil.isBlank(configuredSecrets)) {
            return false;
        }
        for (String secret : configuredSecrets.split("[,;\\r\\n]+")) {
            String trimmed = StrUtil.trim(secret);
            if (!StrUtil.startWith(trimmed, KlingConstants.WEBHOOK_SECRET_PREFIX)) {
                continue;
            }
            try {
                byte[] decoded = Base64.getDecoder().decode(
                    trimmed.substring(KlingConstants.WEBHOOK_SECRET_PREFIX.length()));
                if (decoded.length > 0) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // 继续检查轮换列表中的其它密钥。
            }
        }
        return false;
    }

    public static boolean verify(String webhookId, String timestamp, String signatureHeader,
                                 String rawBody, String configuredSecrets) {
        if (StrUtil.hasBlank(webhookId, timestamp, signatureHeader, rawBody, configuredSecrets)
            || !isFresh(timestamp)) {
            return false;
        }
        String signed = webhookId + "." + timestamp + "." + rawBody;
        for (String secret : configuredSecrets.split("[,;\\r\\n]+")) {
            String trimmed = StrUtil.trim(secret);
            if (!StrUtil.startWith(trimmed, KlingConstants.WEBHOOK_SECRET_PREFIX)) {
                continue;
            }
            String expected = sign(signed, trimmed);
            if (expected == null) {
                continue;
            }
            for (String candidate : signatureHeader.trim().split("\\s+")) {
                int comma = candidate.indexOf(',');
                if (comma > 0 && "v1".equals(candidate.substring(0, comma))
                    && constantTimeEquals(expected, candidate.substring(comma + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    static String sign(String signedPayload, String configuredSecret) {
        try {
            String encoded = configuredSecret.startsWith(KlingConstants.WEBHOOK_SECRET_PREFIX)
                ? configuredSecret.substring(KlingConstants.WEBHOOK_SECRET_PREFIX.length()) : configuredSecret;
            byte[] key = Base64.getDecoder().decode(encoded);
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return Base64.getEncoder().encodeToString(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean isFresh(String rawTimestamp) {
        try {
            long timestamp = Long.parseLong(rawTimestamp.trim());
            long now = Instant.now().getEpochSecond();
            long earliest = now - KlingConstants.CALLBACK_MAX_SKEW_SECONDS;
            long latest = now + KlingConstants.CALLBACK_MAX_SKEW_SECONDS;
            return timestamp >= earliest && timestamp <= latest;
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
