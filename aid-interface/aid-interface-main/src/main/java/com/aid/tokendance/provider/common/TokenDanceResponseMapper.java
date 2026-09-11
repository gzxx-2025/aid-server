package com.aid.tokendance.provider.common;

import cn.hutool.core.util.StrUtil;
import com.aid.common.error.TaskErrorResult;
import com.aid.common.error.TaskErrorSnapshot;
import com.aid.media.provider.ProviderErrorSanitizer;
import com.aid.media.provider.ProviderUsageSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** TokenDance 响应、错误与用量的公共无状态解析器。 */
public final class TokenDanceResponseMapper
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int RAW_SNAPSHOT_CHARS = 100_000;

    private TokenDanceResponseMapper()
    {
    }

    public static JsonNode readTree(String raw)
    {
        if (StrUtil.isBlank(raw))
        {
            return null;
        }
        try
        {
            return MAPPER.readTree(raw);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /** 把恢复动作并入审计快照；只记录受控短 header，不记录密钥。 */
    public static String auditBody(TokenDanceHttpResponse response)
    {
        if (response == null)
        {
            return null;
        }
        String raw = response.bodyUtf8();
        String recoveryAction = StrUtil.trimToNull(response.getRecoveryAction());
        if (recoveryAction != null)
        {
            try
            {
                JsonNode parsed = readTree(raw);
                ObjectNode root = parsed != null && parsed.isObject()
                        ? ((ObjectNode) parsed).deepCopy() : MAPPER.createObjectNode();
                if (parsed == null && StrUtil.isNotBlank(raw))
                {
                    root.put("upstream_message", ProviderErrorSanitizer.safeMessage(raw, "上游请求失败"));
                }
                root.put("tokendance_recovery_action", recoveryAction);
                raw = MAPPER.writeValueAsString(root);
            }
            catch (Exception ignored)
            {
                raw = ProviderErrorSanitizer.safeMessage(raw, "上游请求失败")
                        + " [recovery=" + recoveryAction + "]";
            }
        }
        return StrUtil.sub(raw, 0, RAW_SNAPSHOT_CHARS);
    }

    public static String errorMessage(TokenDanceHttpResponse response, String fallback)
    {
        if (response == null)
        {
            return fallback;
        }
        TaskErrorResult recovery = TokenDanceRecoveryAction.toTaskError(
                response.getRecoveryAction(), response.bodyUtf8());
        return recovery == null ? ProviderErrorSanitizer.fromHttp(
                response.getStatusCode(), response.bodyUtf8()) : recovery.getUserMessage();
    }

    public static TaskErrorResult recoveryError(TokenDanceHttpResponse response)
    {
        return response == null ? null : TokenDanceRecoveryAction.toTaskError(
                response.getRecoveryAction(), response.bodyUtf8());
    }

    /** 从服务端审计快照恢复供应商已确认的恢复动作。 */
    public static TaskErrorResult recoveryErrorFromAudit(String protocol, String audit)
    {
        if (protocol == null || !protocol.trim().toLowerCase(java.util.Locale.ROOT)
                .startsWith(TokenDanceProtocols.PREFIX))
        {
            return null;
        }
        JsonNode root = readTree(audit);
        return root == null ? null : TokenDanceRecoveryAction.toTaskError(
                root.path("tokendance_recovery_action").asText(null), audit);
    }

    /** 错误快照只保留官方恢复动作，不保存供应商响应原文。 */
    public static String recoverySnapshot(String protocol, String audit)
    {
        TaskErrorResult error = recoveryErrorFromAudit(protocol, audit);
        if (error == null) return null;
        ObjectNode snapshot = (ObjectNode) readTree(TaskErrorSnapshot.write(error));
        snapshot.put("tokenDanceRecoveryAction", readTree(audit).path("tokendance_recovery_action").asText());
        snapshot.put("requestRejectedWithoutUsage", !ProviderUsageSupport.hasAnyProviderUsage(usage(readTree(audit))));
        return snapshot.toString();
    }

    public static boolean isConfirmedRejection(String protocol, String snapshot)
    {
        if (protocol == null || !protocol.trim().toLowerCase(java.util.Locale.ROOT)
                .startsWith(TokenDanceProtocols.PREFIX)) return false;
        JsonNode root = readTree(snapshot);
        if (root == null || !root.path("requestRejectedWithoutUsage").asBoolean(false)) return false;
        TaskErrorResult recovery = TokenDanceRecoveryAction.toTaskError(
                root.path("tokenDanceRecoveryAction").asText(null), null);
        return recovery != null && recovery.getErrorCode().equals(root.path("errorCode").asText());
    }

    /** 流中已观察到用量时撤销“无用量拒绝”，使异步补偿也沿用同一证据。 */
    public static String withObservedUsage(String snapshot, Map<String, Object> usage)
    {
        if (!ProviderUsageSupport.hasAnyProviderUsage(usage)) return snapshot;
        JsonNode node = readTree(snapshot);
        if (!(node instanceof ObjectNode object) || !object.has("requestRejectedWithoutUsage")) return snapshot;
        object.put("requestRejectedWithoutUsage", false);
        return object.toString();
    }

    /** OpenAI、Responses 与 Anthropic 常见 token 字段归一到现有结算键。 */
    public static Map<String, Object> usage(JsonNode root)
    {
        if (root == null)
        {
            return null;
        }
        JsonNode usage = root.path("usage");
        if (!usage.isObject())
        {
            JsonNode responseUsage = root.path("response").path("usage");
            usage = responseUsage.isObject() ? responseUsage : usage;
        }
        if (!usage.isObject())
        {
            JsonNode messageUsage = root.path("message").path("usage");
            usage = messageUsage.isObject() ? messageUsage : usage;
        }
        if (!usage.isObject())
        {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        Integer input = firstNonNegative(usage, "input_tokens", "prompt_tokens");
        Integer output = firstNonNegative(usage, "output_tokens", "completion_tokens");
        Integer total = firstNonNegative(usage, "total_tokens");
        putAliases(result, input, "input_tokens", "prompt_tokens");
        putAliases(result, output, "output_tokens", "completion_tokens");
        if (total == null && input != null && output != null)
        {
            total = saturatedAdd(input, output);
        }
        if (total != null)
        {
            result.put("total_tokens", total);
        }
        Integer cacheRead = firstNonNegative(usage, "cache_read_input_tokens", "cached_input_tokens");
        if (cacheRead == null)
        {
            cacheRead = firstNonNegative(usage.path("prompt_tokens_details"), "cached_tokens");
        }
        putAliases(result, cacheRead, "cache_read_input_tokens", "cached_input_tokens");
        Integer cacheWrite = firstNonNegative(usage, "cache_creation_input_tokens", "cache_write_input_tokens");
        putAliases(result, cacheWrite, "cache_write_input_tokens");
        Integer reasoning = firstNonNegative(usage.path("output_tokens_details"), "reasoning_tokens");
        putAliases(result, reasoning, "reasoning_tokens");
        if (ProviderUsageSupport.hasAnyProviderUsage(result))
        {
            result.put("has_any_provider_usage", true);
        }
        return result.isEmpty() ? null : result;
    }

    public static Integer firstNonNegative(JsonNode root, String... names)
    {
        if (root == null)
        {
            return null;
        }
        for (String name : names)
        {
            JsonNode node = root.path(name);
            if (node.isIntegralNumber() && node.canConvertToInt() && node.intValue() >= 0)
            {
                return node.intValue();
            }
        }
        return null;
    }

    public static Integer firstPositive(JsonNode root, String... paths)
    {
        for (String path : paths)
        {
            JsonNode current = root;
            for (String part : path.split("\\."))
            {
                current = current == null ? null : current.path(part);
            }
            if (current != null && current.isNumber())
            {
                int value = (int) Math.ceil(current.doubleValue());
                if (value > 0)
                {
                    return value;
                }
            }
        }
        return null;
    }

    private static void putAliases(Map<String, Object> target, Integer value, String... keys)
    {
        if (value == null)
        {
            return;
        }
        for (String key : keys)
        {
            target.put(key, value);
        }
    }

    private static int saturatedAdd(int left, int right)
    {
        return (int) Math.min((long) left + right, Integer.MAX_VALUE);
    }
}
