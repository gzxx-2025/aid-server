package com.aid.media.provider.impl;

import com.aid.common.exception.ServiceException;
import com.aid.media.constants.MediaInternalOptionKeys;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/** DeepSeek 官方采样参数和组合约束。 */
@Slf4j
final class DeepSeekRequestOptions {
    private DeepSeekRequestOptions() { }

    static boolean internal(String key) {
        return key != null && (key.startsWith("_aid_") || MediaInternalOptionKeys.isInternal(key)
                || Set.of("thinking_level", "thinkingConfig").contains(key));
    }

    static void validate(Map<String, Object> options, Set<String> fields) {
        if (options == null) return;
        for (String key : options.keySet())
            if (!internal(key) && !fields.contains(key)) fail("请求参数不支持");
        range(options, "temperature", 0, 2, false);
        range(options, "top_p", 0, 1, false);
        if (options.get("top_p") instanceof Number n && n.doubleValue() == 0) fail("采样参数超限");
        for (String key : Set.of("max_tokens", "max_completion_tokens", "maxOutputTokens"))
            range(options, key, 1, 393216, true);
        range(options, "top_logprobs", 0, 20, true);
        if (fields.contains("top_logprobs") && options.get("logprobs") != null
                && !(options.get("logprobs") instanceof Boolean)) fail("概率参数类型错误");
        if (options.get("top_logprobs") != null && !Boolean.TRUE.equals(options.get("logprobs"))) fail("概率参数组合无效");
        Object stop = options.get("stop");
        if (stop != null && !(stop instanceof String)
                && (!(stop instanceof Collection<?> values) || values.size() > 16
                || values.stream().anyMatch(value -> !(value instanceof String)))) fail("停止词格式错误");
        Object user = options.get("user_id");
        if (user != null && (!(user instanceof String value) || !value.matches("[a-zA-Z0-9_-]{1,512}"))) fail("用户标识格式错误");
    }

    static void range(Map<String, Object> options, String key, double min, double max, boolean integer) {
        Object value = options.get(key);
        if (value == null) return;
        if (!(value instanceof Number n)) fail("参数类型错误");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number < min || number > max || integer && number != Math.floor(number))
            fail("参数取值超限");
    }

    static void fail(String message) {
        log.info("DeepSeek 参数校验拒绝: {}", message);
        throw new ServiceException(message);
    }
}
