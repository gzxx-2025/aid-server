package com.aid.tokendance.provider.text;

import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaTextGenerateRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** TokenDance Kimi Chat 协议的型号参数约束。 */
final class TokenDanceKimiOptions {
    private TokenDanceKimiOptions() { }

    static void apply(String model, MediaTextGenerateRequest request, Map<String, Object> options) {
        if (!Set.of("kimi-k2.5", "kimi-k2.6", "kimi-k2.7-code", "kimi-k3").contains(model)) return;
        boolean alwaysThinking = Set.of("kimi-k2.7-code", "kimi-k3").contains(model);
        boolean enabled = alwaysThinking || Boolean.TRUE.equals(request.getReasoningEnabled());
        options.remove("thinking");
        options.remove("reasoning_effort");
        if ("kimi-k3".equals(model)) {
            String level = request.getReasoningLevel();
            if (level == null || level.isBlank()) level = "max";
            if (!Set.of("low", "high", "max").contains(level)) throw new ServiceException("思考档位不支持");
            options.put("reasoning_effort", level);
        } else if (!alwaysThinking) {
            options.put("thinking", Map.of("type", enabled ? "enabled" : "disabled"));
        }
        if (!"kimi-k3".equals(model) && "required".equals(options.get("tool_choice"))) {
            throw new ServiceException("工具选择不支持");
        }
        // 模型/供应商通用默认采样值不适用于固定采样模型；显式请求非法值则拒绝，不静默改写。
        Map<String, Object> requested = request.getOptions() == null ? Map.of() : request.getOptions();
        for (String key : List.of("temperature", "top_p", "presence_penalty", "frequency_penalty")) {
            if (requested.get(key) != null) {
                BigDecimal expected = switch (key) {
                    case "temperature" -> new BigDecimal(enabled ? "1" : "0.6");
                    case "top_p" -> new BigDecimal("0.95");
                    default -> BigDecimal.ZERO;
                };
                try {
                    if (new BigDecimal(String.valueOf(requested.get(key))).compareTo(expected) != 0) {
                        throw new ServiceException("采样参数不支持");
                    }
                } catch (NumberFormatException ex) { throw new ServiceException("采样参数无效"); }
            }
            options.remove(key);
        }
    }
}
