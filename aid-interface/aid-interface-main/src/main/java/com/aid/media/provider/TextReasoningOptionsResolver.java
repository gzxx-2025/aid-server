package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 将内部思考配置映射为各文本协议的白名单参数。 */
public final class TextReasoningOptionsResolver {

    public static final String ENABLED_KEY = "_aid_reasoning_enabled";
    public static final String LEVEL_KEY = "_aid_reasoning_level";
    public static final String BUDGET_KEY = "_aid_reasoning_budget_tokens";
    public static final String INCLUDE_KEY = "_aid_include_reasoning";
    /** 业务层统一输出 token 上限；Provider 在协议边界映射为厂商字段。 */
    public static final String MAX_OUTPUT_TOKENS_KEY = "_aid_max_output_tokens";
    private static final int TEXT_OUTPUT_TOKENS_HARD_CAP = 1_600_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TextReasoningOptionsResolver() {
    }

    public static Map<String, Object> resolveOpenAiCompatible(AiModelConfigVo model,
                                                               Map<String, Object> source) {
        return resolveOpenAiCompatible(model, null, source);
    }

    public static Map<String, Object> resolveOpenAiCompatible(AiModelConfigVo model,
                                                               MediaTextGenerateRequest request,
                                                               Map<String, Object> source) {
        Map<String, Object> options = source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
        Object legacyEnabledValue = options.remove(ENABLED_KEY);
        Object legacyLevelValue = options.remove(LEVEL_KEY);
        Object legacyBudgetValue = options.remove(BUDGET_KEY);
        Object maxOutputTokens = options.remove(MAX_OUTPUT_TOKENS_KEY);
        Object requestedApiField = options.remove(TextOutputLimitResolver.OUTPUT_TOKEN_API_FIELD_KEY);
        options.remove(TextOutputLimitResolver.PROVIDER_OUTPUT_TOKENS_KEY);
        options.remove(TextOutputLimitResolver.BILLING_OUTPUT_TOKENS_KEY);
        Object legacyIncludeValue = options.remove(INCLUDE_KEY);
        removeRuntimeReasoningOptions(options);
        Object enabledValue = request != null && request.getReasoningEnabled() != null
                ? request.getReasoningEnabled() : legacyEnabledValue;
        Object levelValue = request != null && StrUtil.isNotBlank(request.getReasoningLevel())
                ? request.getReasoningLevel() : legacyLevelValue;
        Object budgetValue = request != null && request.getReasoningBudgetTokens() != null
                ? request.getReasoningBudgetTokens() : legacyBudgetValue;
        Object includeValue = request != null && request.getIncludeReasoning() != null
                ? request.getIncludeReasoning() : legacyIncludeValue;
        int outputLimit = boundedOutputTokens(maxOutputTokens);
        if (maxOutputTokens != null) {
            String targetField = outputTokenApiField(model, options, requestedApiField);
            options.remove("max_tokens");
            options.remove("max_completion_tokens");
            options.put(targetField, outputLimit);
        }
        boolean enabled = enabledValue != null && Boolean.parseBoolean(String.valueOf(enabledValue));
        if (!enabled && !hasReasoningCapabilityOrConfig(model, options)) {
            return options.isEmpty() ? null : options;
        }
        boolean levelOverridePresent = levelValue != null && StrUtil.isNotBlank(String.valueOf(levelValue));
        String level = levelOverridePresent
                ? String.valueOf(levelValue).trim().toLowerCase(Locale.ROOT)
                : "medium";
        String style = reasoningApiStyle(model);
        switch (style) {
            case "QWEN" -> {
                int requestedBudget = boundedReasoningBudget(budgetValue);
                options.put("enable_thinking", enabled);
                if (enabled && requestedBudget > 0) {
                    options.put("thinking_budget", requestedBudget);
                }
            }
            case "DEEPSEEK" -> {
                options.put("thinking", Map.of("type", enabled ? "enabled" : "disabled"));
                if (enabled) {
                    options.put("reasoning_effort", normalizeDeepSeekLevel(level));
                }
            }
            case "AGNES" -> {
                Map<String, Object> templateOptions = new LinkedHashMap<>();
                templateOptions.put("enable_thinking", enabled);
                options.put("chat_template_kwargs", templateOptions);
            }
            default -> {
                if (!enabled) {
                    options.put("reasoning_effort", "none");
                } else {
                    options.put("reasoning_effort", normalizeOpenAiLevel(level));
                }
            }
        }
        if (includeValue != null && !Boolean.parseBoolean(String.valueOf(includeValue))) {
            options.remove("reasoning_content");
        }
        return options;
    }

    public static Map<String, Object> resolveGemini(AiModelConfigVo model, Map<String, Object> source) {
        return resolveGemini(model, null, source);
    }

    public static Map<String, Object> resolveGemini(AiModelConfigVo model,
                                                     MediaTextGenerateRequest request,
                                                     Map<String, Object> source) {
        Map<String, Object> options = source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
        Object legacyEnabledValue = options.remove(ENABLED_KEY);
        Object legacyLevelValue = options.remove(LEVEL_KEY);
        Object legacyBudgetValue = options.remove(BUDGET_KEY);
        Object legacyIncludeValue = options.remove(INCLUDE_KEY);
        Object maxOutputTokens = options.remove(MAX_OUTPUT_TOKENS_KEY);
        options.remove(TextOutputLimitResolver.OUTPUT_TOKEN_API_FIELD_KEY);
        options.remove(TextOutputLimitResolver.PROVIDER_OUTPUT_TOKENS_KEY);
        options.remove(TextOutputLimitResolver.BILLING_OUTPUT_TOKENS_KEY);
        removeRuntimeReasoningOptions(options);
        Object enabledValue = request != null && request.getReasoningEnabled() != null
                ? request.getReasoningEnabled() : legacyEnabledValue;
        Object levelValue = request != null && StrUtil.isNotBlank(request.getReasoningLevel())
                ? request.getReasoningLevel() : legacyLevelValue;
        Object budgetValue = request != null && request.getReasoningBudgetTokens() != null
                ? request.getReasoningBudgetTokens() : legacyBudgetValue;
        Object includeValue = request != null && request.getIncludeReasoning() != null
                ? request.getIncludeReasoning() : legacyIncludeValue;
        int outputLimit = boundedOutputTokens(maxOutputTokens);
        if (maxOutputTokens != null) {
            options.remove("max_tokens");
            options.remove("max_completion_tokens");
            options.remove("maxOutputTokens");
            options.put("maxOutputTokens", outputLimit);
        }
        boolean enabled = enabledValue != null && Boolean.parseBoolean(String.valueOf(enabledValue));
        if (!enabled && !hasReasoningCapabilityOrConfig(model, options)) {
            return options;
        }
        Map<String, Object> thinking = new LinkedHashMap<>();
        boolean levelOverridePresent = levelValue != null && StrUtil.isNotBlank(String.valueOf(levelValue));
        int requestedBudget = boundedReasoningBudget(budgetValue);
        if (!enabled) {
            thinking.clear();
            if (capabilityBoolean(model, "supportsReasoning")
                    && !capabilityBoolean(model, "supportsReasoningDisable")) {
                // 部分 Gemini 型号不能真正关闭思考：降到能力声明的最低合法档，仅隐藏 thought 展示。
                thinking.put("thinkingLevel", minimumReasoningLevel(model));
                thinking.put("includeThoughts", false);
            } else {
                thinking.put("thinkingBudget", 0);
            }
        } else if (requestedBudget > 0) {
            thinking.remove("thinkingLevel");
            thinking.put("thinkingBudget", requestedBudget);
        } else if (levelOverridePresent) {
            thinking.remove("thinkingBudget");
            thinking.put("thinkingLevel", normalizeGeminiLevel(String.valueOf(levelValue)));
        } else if (thinking.isEmpty()) {
            thinking.put("thinkingLevel", "medium");
        }
        if (includeValue != null) {
            thinking.put("includeThoughts", enabled && Boolean.parseBoolean(String.valueOf(includeValue)));
        }
        options.put("thinkingConfig", thinking);
        return options;
    }

    /** 运行态开关只能来自本次请求，模型和供应商 extra_body 中的同名配置一律忽略。 */
    private static void removeRuntimeReasoningOptions(Map<String, Object> options) {
        options.remove("stream");
        options.remove("stream_options");
        options.remove("enable_thinking");
        options.remove("thinking");
        options.remove("thinking_budget");
        options.remove("reasoning_effort");
        options.remove("thinking_level");
        options.remove("thinkingConfig");
        Object template = options.get("chat_template_kwargs");
        if (template instanceof Map<?, ?> map) {
            Map<String, Object> retained = copyMap(map);
            retained.remove("enable_thinking");
            if (retained.isEmpty()) {
                options.remove("chat_template_kwargs");
            } else {
                options.put("chat_template_kwargs", retained);
            }
        }
    }

    private static Map<String, Object> copyMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                if (key != null && item != null) {
                    result.put(String.valueOf(key), item);
                }
            });
        }
        return result;
    }

    private static String reasoningApiStyle(AiModelConfigVo model) {
        if (model != null && StrUtil.isNotBlank(model.getCapabilityJson())) {
            try {
                JsonNode value = MAPPER.readTree(model.getCapabilityJson()).get("reasoningApiStyle");
                if (value != null && value.isTextual() && StrUtil.isNotBlank(value.asText())) {
                    return value.asText().trim().toUpperCase(Locale.ROOT);
                }
            } catch (Exception ignored) {
                // 非法能力 JSON 由模型管理保存校验负责；运行时使用安全兜底。
            }
        }
        String provider = model == null ? "" : StrUtil.blankToDefault(model.getProviderCode(), "").toLowerCase(Locale.ROOT);
        String modelCode = model == null ? "" : (StrUtil.blankToDefault(model.getRealModelCode(), "")
                + " " + StrUtil.blankToDefault(model.getModelCode(), "")).toLowerCase(Locale.ROOT);
        if (modelCode.contains("qwen") || modelCode.contains("qwq")) {
            return "QWEN";
        }
        if (modelCode.contains("deepseek")) {
            return "DEEPSEEK";
        }
        if (modelCode.contains("agnes")) {
            return "AGNES";
        }
        if (provider.contains("dashscope") || provider.contains("aliyun") || provider.contains("alibaba")) {
            return "QWEN";
        }
        if (provider.contains("deepseek")) {
            return "DEEPSEEK";
        }
        if (provider.contains("agnes")) {
            return "AGNES";
        }
        return "OPENAI";
    }

    private static boolean configuredThinkingDisabled(Map<String, Object> thinking) {
        return isDisabledReasoningValue(thinking.get("thinkingBudget"))
                || isDisabledReasoningValue(thinking.get("thinkingLevel"));
    }

    private static boolean capabilityBoolean(AiModelConfigVo model, String key) {
        if (model == null || StrUtil.isBlank(model.getCapabilityJson())) {
            return false;
        }
        try {
            return MAPPER.readTree(model.getCapabilityJson()).path(key).asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String minimumReasoningLevel(AiModelConfigVo model) {
        if (model != null && StrUtil.isNotBlank(model.getCapabilityJson())) {
            try {
                JsonNode levels = MAPPER.readTree(model.getCapabilityJson()).path("allowedReasoningLevels");
                if (levels.isArray() && !levels.isEmpty() && levels.get(0).isTextual()) {
                    return normalizeGeminiLevel(levels.get(0).asText());
                }
            } catch (Exception ignored) {
                // 运行时回退到 Gemini 普遍支持的 low 档。
            }
        }
        return "low";
    }

    private static boolean isDisabledReasoningValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Number number) {
            return number.longValue() <= 0;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "none".equals(normalized) || "off".equals(normalized)
                || "disabled".equals(normalized) || "false".equals(normalized)
                || "0".equals(normalized);
    }

    private static String outputTokenApiField(AiModelConfigVo model, Map<String, Object> options,
                                              Object requestedApiField) {
        if (model != null && StrUtil.isNotBlank(model.getCapabilityJson())) {
            try {
                JsonNode value = MAPPER.readTree(model.getCapabilityJson()).get("outputTokenApiField");
                if (value != null && value.isTextual()
                        && ("max_tokens".equals(value.asText()) || "max_completion_tokens".equals(value.asText()))) {
                    return value.asText();
                }
            } catch (Exception ignored) {
                // 非法能力 JSON 由模型管理保存校验负责；运行时使用协议兜底。
            }
        }
        String modelCode = model == null ? "" : (StrUtil.blankToDefault(model.getRealModelCode(), "")
                + " " + StrUtil.blankToDefault(model.getModelCode(), "")).toLowerCase(Locale.ROOT);
        if (modelCode.contains("gpt-5") || modelCode.contains("gpt5.")) {
            return "max_completion_tokens";
        }
        if ("max_tokens".equals(requestedApiField) || "max_completion_tokens".equals(requestedApiField)) {
            return String.valueOf(requestedApiField);
        }
        if (options.containsKey("max_completion_tokens")) {
            return "max_completion_tokens";
        }
        if (options.containsKey("max_tokens")) {
            return "max_tokens";
        }
        String style = reasoningApiStyle(model);
        return "QWEN".equals(style) ? "max_completion_tokens" : "max_tokens";
    }

    private static boolean hasReasoningCapabilityOrConfig(AiModelConfigVo model, Map<String, Object> options) {
        if (model != null && StrUtil.isNotBlank(model.getCapabilityJson())) {
            try {
                JsonNode capability = MAPPER.readTree(model.getCapabilityJson());
                if (capability.path("supportsReasoning").asBoolean(false)
                        || (capability.hasNonNull("reasoningApiStyle")
                        && StrUtil.isNotBlank(capability.get("reasoningApiStyle").asText()))) {
                    return true;
                }
            } catch (Exception ignored) {
                // 非法能力 JSON 由模型管理保存校验负责。
            }
        }
        return options.containsKey("reasoning_effort") || options.containsKey("enable_thinking")
                || options.containsKey("thinking") || options.containsKey("thinkingConfig")
                || options.containsKey("thinking_level") || options.containsKey("chat_template_kwargs");
    }

    private static String normalizeDeepSeekLevel(String level) {
        return "max".equals(level) || "xhigh".equals(level) ? "max" : "high";
    }

    private static String normalizeOpenAiLevel(String level) {
        return switch (level) {
            case "minimal", "low", "medium", "high", "xhigh", "max" -> level;
            default -> "medium";
        };
    }

    private static String normalizeGeminiLevel(String level) {
        String normalized = StrUtil.blankToDefault(level, "medium").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "minimal", "low", "medium", "high" -> normalized;
            case "xhigh", "max" -> "high";
            default -> "medium";
        };
    }

    private static int positiveInt(Object value) {
        long parsed;
        try {
            parsed = value instanceof Number number
                    ? number.longValue() : Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
        return (int) Math.max(0L, Math.min(parsed, Integer.MAX_VALUE));
    }

    private static int boundedOutputTokens(Object value) {
        return Math.max(1, Math.min(positiveInt(value), TEXT_OUTPUT_TOKENS_HARD_CAP));
    }

    private static int boundedReasoningBudget(Object value) {
        return Math.min(positiveInt(value), TEXT_OUTPUT_TOKENS_HARD_CAP);
    }
}
