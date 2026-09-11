package com.aid.tokendance.client;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.aid.tokendance.config.TokenDanceAccountProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** TokenDance 官方账户接口客户端。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenDancePortalClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESPONSE_LENGTH = 64 * 1024;
    private static final Pattern STATUS_PATH = Pattern.compile(
            "^/portal/api/v1/payment/sessions/[A-Za-z0-9._~-]{1,256}$");
    private static final Set<String> PAYMENT_STATUSES = Set.of(
            "pending", "paid", "failed", "closed", "refunded");

    private final TokenDanceAccountProperties properties;

    public String exchangeApiKey(String code, String codeVerifier) {
        JsonNode root = post(endpoint("/portal/api/v1/auth/keys"), null,
                JSONUtil.toJsonStr(Map.of(
                        "code", code,
                        "code_verifier", codeVerifier,
                        "code_challenge_method", "S256")), "OAUTH_EXCHANGE");
        String key = root.path("key").asText("").trim();
        if (StrUtil.isBlank(key) || key.length() > 4096) {
            log.error("TokenDance OAuth 响应未包含有效 Key");
            throw new TokenDancePortalException("授权响应异常", "INVALID_KEY_RESPONSE", true);
        }
        return key;
    }

    public TokenDancePortalModels.Balance queryBalance(String apiKey) {
        JsonNode root = get(endpoint("/portal/api/v1/user/balance"), apiKey, "BALANCE_QUERY");
        JsonNode balance = root.path("balance");
        if (!balance.isObject()) {
            log.error("TokenDance 余额响应缺少 balance 对象");
            throw new TokenDancePortalException("余额响应异常", "INVALID_BALANCE_RESPONSE", false);
        }
        TokenDancePortalModels.Balance result = new TokenDancePortalModels.Balance();
        result.setCreditsMicros(requireMicroAmount(balance.get("credits"), "credits", false));
        result.setCreditsUsedMicros(requireMicroAmount(balance.get("credits_used"), "credits_used", false));
        // 可用余额是总额度减已消耗额度，透支时保留官方返回的负数。
        result.setBalanceMicros(requireMicroAmount(balance.get("balance"), "balance", true));
        return result;
    }

    public TokenDancePortalModels.PaymentSession createPaymentSession(String apiKey, int amount) {
        JsonNode root = post(endpoint("/portal/api/v1/payment/sessions"), apiKey,
                JSONUtil.toJsonStr(Map.of("amount", amount)), "PAYMENT_CREATE");
        return parsePaymentSession(root.path("session"), true, null);
    }

    public TokenDancePortalModels.PaymentSession queryPaymentSession(String apiKey, String statusUrl) {
        validateStatusUrl(statusUrl);
        JsonNode root = get(statusUrl, apiKey, "PAYMENT_QUERY");
        JsonNode session = root.has("session") ? root.path("session") : root;
        return parsePaymentSession(session, false, statusUrl);
    }

    public String authorizationEndpoint() {
        return endpoint("/auth");
    }

    public void validateStatusUrl(String statusUrl) {
        try {
            URI allowed = portalOrigin();
            URI actual = URI.create(StrUtil.trim(statusUrl));
            boolean sameOrigin = actual.isAbsolute()
                    && allowed.getScheme().equalsIgnoreCase(actual.getScheme())
                    && allowed.getHost().equalsIgnoreCase(actual.getHost())
                    && effectivePort(allowed) == effectivePort(actual);
            if (!sameOrigin || actual.getUserInfo() != null || actual.getFragment() != null
                    || actual.getQuery() != null || !STATUS_PATH.matcher(actual.getPath()).matches()) {
                throw new IllegalArgumentException("invalid status url");
            }
        } catch (Exception ex) {
            log.error("TokenDance 支付状态地址不在允许范围");
            throw new TokenDancePortalException("支付地址无效", "INVALID_STATUS_URL", false);
        }
    }

    private TokenDancePortalModels.PaymentSession parsePaymentSession(
            JsonNode node, boolean creation, String expectedStatusUrl) {
        try {
            return parsePaymentSessionValidated(node, creation, expectedStatusUrl);
        } catch (TokenDancePortalException exception) {
            if (creation && !exception.isOutcomeUncertain()) {
                // 创建接口已经返回响应时，任何无法安全解析的成功载荷都不能视为“可重试失败”。
                throw new TokenDancePortalException(
                        exception.getMessage(), exception.getErrorCode(), true);
            }
            throw exception;
        }
    }

    private TokenDancePortalModels.PaymentSession parsePaymentSessionValidated(
            JsonNode node, boolean creation, String expectedStatusUrl) {
        if (node == null || !node.isObject()) {
            log.error("TokenDance 充值响应缺少 session 对象");
            throw new TokenDancePortalException("充值响应异常", "INVALID_PAYMENT_RESPONSE", creation);
        }
        String sessionId = node.path("id").asText("").trim();
        String status = node.path("status").asText("").trim().toLowerCase();
        if (StrUtil.isBlank(sessionId) || sessionId.length() > 191 || !PAYMENT_STATUSES.contains(status)) {
            log.error("TokenDance 充值响应状态或会话编号无效");
            throw new TokenDancePortalException("充值响应异常", "INVALID_PAYMENT_RESPONSE", creation);
        }
        String statusUrl = StrUtil.blankToDefault(nullableText(node.get("status_url"), 2048), expectedStatusUrl);
        validateStatusUrl(statusUrl);
        Integer amount = optionalInteger(node.get("amount"));
        String paymentUrl = validatePaymentUrl(nullableText(node.get("payment_url"), 2048), false);
        Date expiredAt = unixSeconds(node.get("expired_at"));
        Date createdAt = unixSeconds(node.get("created_at"));
        if (creation && (amount == null || paymentUrl == null || expiredAt == null || createdAt == null)) {
            log.error("TokenDance 创建充值会话响应缺少必填字段");
            throw new TokenDancePortalException("充值响应异常", "INVALID_PAYMENT_RESPONSE", true);
        }
        TokenDancePortalModels.PaymentSession result = new TokenDancePortalModels.PaymentSession();
        result.setSessionId(sessionId);
        result.setAmount(amount);
        result.setStatus(status);
        result.setPaymentUrl(paymentUrl);
        result.setAlipayUrl(validatePaymentUrl(nullableText(node.get("alipay_url"), 2048), true));
        result.setStatusUrl(statusUrl);
        result.setExpiredAt(expiredAt);
        result.setCreatedAt(createdAt);
        result.setPaidAt(unixSeconds(node.get("paid_at")));
        return result;
    }

    private String validatePaymentUrl(String value, boolean alipay) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            boolean valid = alipay
                    ? "alipays".equalsIgnoreCase(uri.getScheme())
                    : "https".equalsIgnoreCase(uri.getScheme()) && StrUtil.isNotBlank(uri.getHost());
            if (!valid || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("invalid payment url");
            }
            return value;
        } catch (Exception ex) {
            log.error("TokenDance 支付链接格式无效");
            throw new TokenDancePortalException("支付地址无效", "INVALID_PAYMENT_URL", false);
        }
    }

    private JsonNode get(String url, String apiKey, String operation) {
        try (HttpResponse response = HttpRequest.get(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .setFollowRedirects(false)
                .timeout(timeoutMillis())
                .execute()) {
            return parseResponse(response, operation);
        } catch (TokenDancePortalException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("TokenDance 账户接口不可用，operation={}, error={}", operation,
                    ex.getClass().getSimpleName());
            throw new TokenDancePortalException("上游服务不可用", operation + "_UNAVAILABLE", true);
        }
    }

    private JsonNode post(String url, String apiKey, String body, String operation) {
        try {
            HttpRequest request = HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .setFollowRedirects(false)
                    .body(body)
                    .timeout(timeoutMillis());
            if (StrUtil.isNotBlank(apiKey)) {
                request.header("Authorization", "Bearer " + apiKey);
            }
            try (HttpResponse response = request.execute()) {
                return parseResponse(response, operation);
            }
        } catch (TokenDancePortalException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("TokenDance 账户接口不可用，operation={}, error={}", operation,
                    ex.getClass().getSimpleName());
            throw new TokenDancePortalException("上游服务不可用", operation + "_UNAVAILABLE", true);
        }
    }

    private JsonNode parseResponse(HttpResponse response, String operation) {
        String raw = response.body();
        int status = response.getStatus();
        if (StrUtil.length(raw) > MAX_RESPONSE_LENGTH) {
            log.warn("TokenDance 账户响应过大，operation={}, httpStatus={}, responseLength={}",
                    operation, status, StrUtil.length(raw));
            throw new TokenDancePortalException("上游响应异常", operation + "_OVERSIZED", true);
        }
        JsonNode root = null;
        try {
            if (JSONUtil.isTypeJSON(raw)) {
                root = MAPPER.readTree(raw);
            }
        } catch (Exception ignored) {
            root = null;
        }
        if (status < 200 || status >= 300 || root == null || !root.isObject()) {
            log.warn("TokenDance 账户请求失败，operation={}, httpStatus={}, responseLength={}",
                    operation, status, StrUtil.length(raw));
            String code = root == null ? "HTTP_" + status
                    : StrUtil.blankToDefault(root.path("error").path("code").asText(), "HTTP_" + status);
            boolean successfulWriteWithInvalidBody = status >= 200 && status < 300
                    && ("OAUTH_EXCHANGE".equals(operation) || "PAYMENT_CREATE".equals(operation));
            boolean uncertain = status >= 500 || status <= 0 || successfulWriteWithInvalidBody;
            throw new TokenDancePortalException(safeMessage(status), sanitizeCode(code), uncertain);
        }
        return root;
    }

    private BigDecimal requireMicroAmount(JsonNode node, String field, boolean allowNegative) {
        if (node == null || node.isNull()) {
            log.error("TokenDance 余额响应字段缺失，field={}", field);
            throw new TokenDancePortalException("余额响应异常", "INVALID_BALANCE_RESPONSE", false);
        }
        String text = node.asText("").trim();
        if (!text.matches(allowNegative ? "-?\\d+" : "\\d+")) {
            log.error("TokenDance 余额响应字段整数格式无效，field={}, allowNegative={}", field, allowNegative);
            throw new TokenDancePortalException("余额响应异常", "INVALID_BALANCE_RESPONSE", false);
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            log.error("TokenDance 余额响应字段超出范围，field={}", field);
            throw new TokenDancePortalException("余额响应异常", "INVALID_BALANCE_RESPONSE", false);
        }
    }

    private Integer optionalInteger(JsonNode node) {
        if (node == null || node.isNull() || !node.canConvertToInt()) {
            return null;
        }
        return node.asInt();
    }

    private Date unixSeconds(JsonNode node) {
        if (node == null || node.isNull() || !node.canConvertToLong()) {
            return null;
        }
        long seconds = node.asLong();
        if (seconds <= 0 || seconds > 253402300799L) {
            return null;
        }
        return Date.from(Instant.ofEpochSecond(seconds));
    }

    private String nullableText(JsonNode node, int maxLength) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        if (StrUtil.isBlank(value)) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new TokenDancePortalException("上游响应异常", "FIELD_TOO_LONG", false);
        }
        return value;
    }

    private String endpoint(String path) {
        URI origin = portalOrigin();
        return origin.toString().replaceAll("/+$", "") + path;
    }

    private URI portalOrigin() {
        try {
            URI uri = URI.create(StrUtil.trim(properties.getPortalBaseUrl())).normalize();
            boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
                    && ("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost()));
            String path = StrUtil.nullToEmpty(uri.getPath());
            if ((!"https".equalsIgnoreCase(uri.getScheme()) && !loopbackHttp)
                    || StrUtil.isBlank(uri.getHost()) || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getQuery() != null || !(path.isEmpty() || "/".equals(path))) {
                throw new IllegalArgumentException("invalid portal base url");
            }
            return uri;
        } catch (Exception ex) {
            log.error("TokenDance 官方账户地址配置无效");
            throw new TokenDancePortalException("账户地址无效", "INVALID_PORTAL_URL", false);
        }
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private int timeoutMillis() {
        return Math.max(1_000, Math.min(properties.getHttpTimeoutMillis(), 120_000));
    }

    private String safeMessage(int status) {
        if (status == 401 || status == 403) {
            return "上游鉴权无效";
        }
        if (status == 429) {
            return "请求过于频繁";
        }
        return status >= 500 ? "上游服务不可用" : "上游请求失败";
    }

    private String sanitizeCode(String code) {
        String value = StrUtil.blankToDefault(StrUtil.trim(code), "UNKNOWN").toUpperCase();
        return value.matches("[A-Z0-9_-]{1,64}") ? value : "UNKNOWN";
    }
}
