package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.aid.billing.util.TextTokenEstimator;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** 文本任务冻结和持久化前的统一输出上限解析器。 */
public final class TextOutputLimitResolver {

    public static final String BILLING_OUTPUT_TOKENS_KEY = "_aid_billing_output_tokens";
    public static final String PROVIDER_OUTPUT_TOKENS_KEY = "_aid_provider_output_tokens";
    public static final String OUTPUT_TOKEN_API_FIELD_KEY = "_aid_output_token_api_field";
    public static final int FALLBACK_OUTPUT_TOKENS = 4_096;
    /** Qwen 文档声明实际输出最多可比配置多约 10 token，统一取 16 留出结算余量。 */
    public static final int OUTPUT_OVERSHOOT_TOLERANCE = 16;
    public static final int ABSOLUTE_OUTPUT_TOKENS = 1_600_000;
    public static final int ABSOLUTE_BILLING_OUTPUT_TOKENS =
            ABSOLUTE_OUTPUT_TOKENS + OUTPUT_OVERSHOOT_TOLERANCE;
    public static final int ABSOLUTE_INPUT_TOKENS = 1_000_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TextOutputLimitResolver() {
    }

    public static void normalize(MediaTextGenerateRequest request, AiModelConfigVo model) {
        if (request == null) {
            return;
        }
        int providerCap = resolveProviderCap(model, request.getOptions());
        int billingCeiling = billingCeiling(providerCap);
        String apiField = resolveRequestedApiField(model, request.getOptions());
        Map<String, Object> options = request.getOptions() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getOptions());
        removeOutputAliases(options);
        options.put(TextReasoningOptionsResolver.MAX_OUTPUT_TOKENS_KEY, providerCap);
        options.put(PROVIDER_OUTPUT_TOKENS_KEY, providerCap);
        options.put(BILLING_OUTPUT_TOKENS_KEY, billingCeiling);
        if (apiField != null) {
            options.put(OUTPUT_TOKEN_API_FIELD_KEY, apiField);
        }
        request.setOptions(options);

        int conservativeInputTokens = TextTokenEstimator.estimateRequestConservative(request);
        int balancedInputTokens = TextTokenEstimator.estimateRequestBalanced(request);
        int contextLimit = positiveCapability(model, "contextWindowTokens");
        int contextSafetyMargin = Math.max(256, contextLimit / 50);
        if (conservativeInputTokens > ABSOLUTE_INPUT_TOKENS
                || (contextLimit > 0
                && (long) balancedInputTokens + providerCap + contextSafetyMargin > contextLimit)) {
            throw new ServiceException("文本上下文过长");
        }
    }

    public static int resolveProviderCap(AiModelConfigVo model, Map<String, Object> requestOptions) {
        int requested = firstPositive(requestOptions,
                TextReasoningOptionsResolver.MAX_OUTPUT_TOKENS_KEY,
                "max_completion_tokens", "max_tokens", "maxOutputTokens");
        int configured = firstPositive(OpenAiCompatiblePayloadResolver.mergeExtraBody(
                        model == null ? null : model.getExtraBodyJson(),
                        model == null ? null : model.getModelExtraBodyJson(), null),
                "max_completion_tokens", "max_tokens", "maxOutputTokens");
        int effective = requested > 0 ? requested : configured > 0 ? configured : FALLBACK_OUTPUT_TOKENS;
        int capabilityMax = positiveCapability(model, "maxOutputTokens");
        if (capabilityMax > 0) {
            effective = Math.min(effective, capabilityMax);
        }
        return Math.max(1, Math.min(effective, ABSOLUTE_OUTPUT_TOKENS));
    }

    public static int billingCeiling(int providerCap) {
        return (int) Math.min((long) Math.max(1, providerCap) + OUTPUT_OVERSHOOT_TOLERANCE,
                ABSOLUTE_BILLING_OUTPUT_TOKENS);
    }

    private static int positiveCapability(AiModelConfigVo model, String key) {
        if (model == null || StrUtil.isBlank(model.getCapabilityJson())) {
            return 0;
        }
        try {
            JsonNode value = MAPPER.readTree(model.getCapabilityJson()).get(key);
            if (value == null || !value.canConvertToLong()) {
                return 0;
            }
            long parsed = value.asLong(0L);
            return (int) Math.max(0L, Math.min(parsed, Integer.MAX_VALUE));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String resolveRequestedApiField(AiModelConfigVo model, Map<String, Object> requestOptions) {
        if (requestOptions != null) {
            Object normalized = requestOptions.get(OUTPUT_TOKEN_API_FIELD_KEY);
            if ("max_tokens".equals(normalized) || "max_completion_tokens".equals(normalized)) {
                return String.valueOf(normalized);
            }
            if (requestOptions.containsKey("max_completion_tokens")) {
                return "max_completion_tokens";
            }
            if (requestOptions.containsKey("max_tokens")) {
                return "max_tokens";
            }
        }
        Map<String, Object> configured = OpenAiCompatiblePayloadResolver.mergeExtraBody(
                model == null ? null : model.getExtraBodyJson(),
                model == null ? null : model.getModelExtraBodyJson(), null);
        if (configured != null && configured.containsKey("max_completion_tokens")) {
            return "max_completion_tokens";
        }
        if (configured != null && configured.containsKey("max_tokens")) {
            return "max_tokens";
        }
        return null;
    }

    private static int firstPositive(Map<String, Object> options, String... keys) {
        if (options == null) {
            return 0;
        }
        for (String key : keys) {
            int value = positiveInt(options.get(key));
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    private static int positiveInt(Object value) {
        try {
            long parsed = value instanceof Number number
                    ? number.longValue() : Long.parseLong(String.valueOf(value));
            return (int) Math.max(0L, Math.min(parsed, Integer.MAX_VALUE));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static void removeOutputAliases(Map<String, Object> options) {
        options.remove(TextReasoningOptionsResolver.MAX_OUTPUT_TOKENS_KEY);
        options.remove("max_completion_tokens");
        options.remove("max_tokens");
        options.remove("maxOutputTokens");
        options.remove(PROVIDER_OUTPUT_TOKENS_KEY);
        options.remove(BILLING_OUTPUT_TOKENS_KEY);
        options.remove(OUTPUT_TOKEN_API_FIELD_KEY);
    }
}
