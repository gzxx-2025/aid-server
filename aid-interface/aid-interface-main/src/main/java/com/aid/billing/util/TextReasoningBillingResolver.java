package com.aid.billing.util;

import cn.hutool.core.util.StrUtil;
import com.aid.billing.dto.BillingInput;
import com.aid.domain.vo.AiModelConfigVo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** 将模型级思考与输出上限配置补充进文本预冻结快照。 */
public final class TextReasoningBillingResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final int TEXT_OUTPUT_TOKENS_HARD_CAP = 1_600_000;

    private TextReasoningBillingResolver() {
    }

    public static void enrich(BillingInput input, AiModelConfigVo model) {
        if (input == null || input.getParams() == null || model == null
                || !"TEXT".equalsIgnoreCase(model.getModelType())) {
            return;
        }
        Map<String, Object> params = input.getParams();
        Map<String, Object> options = new LinkedHashMap<>();
        merge(options, model.getExtraBodyJson());
        merge(options, model.getModelExtraBodyJson());
        boolean explicit = Boolean.parseBoolean(String.valueOf(params.get("reasoningEnabled")));
        boolean overridePresent = Boolean.parseBoolean(String.valueOf(params.get("reasoningOverridePresent")));
        boolean configured = configuredReasoningEnabled(model.getCapabilityJson(), options);
        boolean requested = overridePresent ? explicit : configured;
        boolean inherentlyReasoning = capabilityBoolean(model.getCapabilityJson(), "supportsReasoning")
                && !capabilityBoolean(model.getCapabilityJson(), "supportsReasoningDisable");
        boolean enabled = inherentlyReasoning || requested;
        if (inherentlyReasoning && !requested) {
            params.put("reasoningForcedHidden", true);
        }
        params.put("reasoningEnabled", enabled);

        int configuredMax = positiveInt(options.get("max_completion_tokens"));
        if (configuredMax <= 0) {
            configuredMax = positiveInt(options.get("max_tokens"));
        }
        if (configuredMax <= 0) {
            configuredMax = positiveInt(options.get("maxOutputTokens"));
        }
        configuredMax = Math.min(configuredMax, TEXT_OUTPUT_TOKENS_HARD_CAP);
        boolean requestOutputLimitPresent = Boolean.parseBoolean(
                String.valueOf(params.get("outputLimitPresent")));
        if (configuredMax > 0 && !requestOutputLimitPresent) {
            int current = positiveInt(params.get("outputTokens"));
            // Provider 合并优先级为 model extra < request options；只有请求未显式指定时才回退模型上限。
            params.put("outputTokens", configuredMax > 0 ? configuredMax : current);
            params.put("outputLimitPresent", true);
        } else if (configuredMax <= 0 && enabled && !requestOutputLimitPresent
                && !Boolean.parseBoolean(String.valueOf(params.get("reasoningBudgetOverridePresent")))) {
            int budget = configuredReasoningBudget(model.getCapabilityJson(), options);
            if (budget > 0) {
                int current = positiveInt(params.get("outputTokens"));
                params.put("outputTokens", safeAdd(current, budget));
                params.put("reasoningBudgetTokens", Math.max(
                        positiveInt(params.get("reasoningBudgetTokens")), budget));
            }
        }
    }

    private static int configuredReasoningBudget(String capabilityJson, Map<String, Object> options) {
        int budget = positiveInt(options.get("thinking_budget"));
        if (budget <= 0 && options.get("thinkingConfig") instanceof Map<?, ?> map) {
            budget = positiveInt(map.get("thinkingBudget"));
        }
        if (budget <= 0 && StrUtil.isNotBlank(capabilityJson)) {
            try {
                JsonNode capability = MAPPER.readTree(capabilityJson);
                budget = positiveInt(capability.path("defaultReasoningBudgetTokens").asText());
            } catch (Exception ignored) {
                // 非法能力 JSON 由模型管理保存校验负责。
            }
        }
        return Math.min(budget, TEXT_OUTPUT_TOKENS_HARD_CAP);
    }

    private static int safeAdd(int left, int right) {
        long result = (long) left + right;
        return result > TEXT_OUTPUT_TOKENS_HARD_CAP ? TEXT_OUTPUT_TOKENS_HARD_CAP : (int) result;
    }

    private static boolean configuredReasoningEnabled(String capabilityJson, Map<String, Object> options) {
        Object qwen = options.get("enable_thinking");
        if (qwen != null) {
            return Boolean.parseBoolean(String.valueOf(qwen));
        }
        Object effort = options.get("reasoning_effort");
        if (effort != null) {
            String value = String.valueOf(effort).trim();
            return !(value.isEmpty() || "none".equalsIgnoreCase(value)
                    || "off".equalsIgnoreCase(value) || "disabled".equalsIgnoreCase(value)
                    || "0".equals(value));
        }
        Object thinking = options.get("thinking");
        if (thinking instanceof Map<?, ?> map) {
            Object type = map.get("type");
            return type != null && !"disabled".equalsIgnoreCase(String.valueOf(type));
        }
        Object gemini = options.get("thinkingConfig");
        if (gemini instanceof Map<?, ?> map) {
            Object budget = map.get("thinkingBudget");
            return budget == null || positiveInt(budget) != 0;
        }
        Object thinkingLevel = options.get("thinking_level");
        if (thinkingLevel != null) {
            String level = String.valueOf(thinkingLevel).trim();
            return !(level.isEmpty() || "disabled".equalsIgnoreCase(level)
                    || "off".equalsIgnoreCase(level) || "none".equalsIgnoreCase(level)
                    || "0".equals(level));
        }
        Object template = options.get("chat_template_kwargs");
        if (template instanceof Map<?, ?> map && map.containsKey("enable_thinking")) {
            return Boolean.parseBoolean(String.valueOf(map.get("enable_thinking")));
        }
        if (StrUtil.isNotBlank(capabilityJson)) {
            try {
                return MAPPER.readTree(capabilityJson).path("defaultReasoningEnabled").asBoolean(false);
            } catch (Exception ignored) {
                // 模型管理保存阶段负责 JSON 校验；无显式协议配置时安全回退为关闭。
            }
        }
        return false;
    }

    private static void merge(Map<String, Object> target, String json) {
        if (StrUtil.isBlank(json)) {
            return;
        }
        try {
            Map<String, Object> values = MAPPER.readValue(json, MAP_TYPE);
            if (values != null) {
                target.putAll(values);
            }
        } catch (Exception ignored) {
            // 非法 JSON 由模型管理保存校验负责。
        }
    }

    private static boolean capabilityBoolean(String capabilityJson, String key) {
        if (StrUtil.isBlank(capabilityJson)) {
            return false;
        }
        try {
            return MAPPER.readTree(capabilityJson).path(key).asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int positiveInt(Object value) {
        long parsed;
        try {
            parsed = value instanceof Number number
                    ? number.longValue() : Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
        return (int) Math.max(0L, Math.min(parsed, TEXT_OUTPUT_TOKENS_HARD_CAP));
    }
}
