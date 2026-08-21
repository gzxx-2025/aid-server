package com.aid.media.provider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Provider 文本用量的可信识别与合并工具。 */
public final class ProviderUsageSupport {

    private static final Set<String> TOKEN_KEYS = Set.of(
            "input_tokens", "prompt_tokens", "output_tokens", "completion_tokens", "total_tokens",
            "uncached_input_tokens", "cached_input_tokens", "cache_read_input_tokens",
            "cache_write_input_tokens", "visible_output_tokens", "reasoning_tokens");

    private ProviderUsageSupport() {
    }

    /**
     * 任一真实 token 字段出现即说明上游已返回用量；数值为零仍是可信回执。
     * 估算字段和仅为 false 的完整性标记不代表真实调用。
     */
    public static boolean hasAnyProviderUsage(Map<String, Object> usage) {
        if (usage == null || usage.isEmpty()) {
            return false;
        }
        for (String key : TOKEN_KEYS) {
            if (usage.containsKey(key) && isValidTokenValue(usage.get(key))) {
                return true;
            }
        }
        return false;
    }

    /** 合并分段 usage，避免后续不完整回调覆盖已采集字段。 */
    public static Map<String, Object> merge(Map<String, Object> current,
                                             Map<String, Object> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return current;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (current != null) {
            merged.putAll(current);
        }
        merged.putAll(incoming);
        if (hasAnyProviderUsage(current) || hasAnyProviderUsage(incoming)) {
            merged.put("has_any_provider_usage", true);
        }
        return merged;
    }

    private static boolean isValidTokenValue(Object value) {
        if (value instanceof Number number) {
            double parsed = number.doubleValue();
            return Double.isFinite(parsed) && parsed >= 0D;
        }
        if (value == null) {
            return false;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim()) >= 0L;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
