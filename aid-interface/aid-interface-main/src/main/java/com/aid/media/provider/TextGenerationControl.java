package com.aid.media.provider;

import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaTextGenerateRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/** 归一化单次文本模型调用的流式与思考策略。 */
public final class TextGenerationControl {

    private TextGenerationControl() {
    }

    /**
     * 将旧版 options 控制键迁移到强类型字段，并按服务入口补齐安全默认值。
     *
     * @param request 文本请求
     * @param streamingEntry 是否由流式服务入口调用
     */
    public static void normalize(MediaTextGenerateRequest request, boolean streamingEntry) {
        if (request == null) {
            return;
        }
        Map<String, Object> options = request.getOptions() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getOptions());
        if (request.getReasoningEnabled() == null) {
            request.setReasoningEnabled(booleanValue(options.remove(TextReasoningOptionsResolver.ENABLED_KEY)));
        } else {
            options.remove(TextReasoningOptionsResolver.ENABLED_KEY);
        }
        if (request.getReasoningLevel() == null) {
            request.setReasoningLevel(stringValue(options.remove(TextReasoningOptionsResolver.LEVEL_KEY)));
        } else {
            options.remove(TextReasoningOptionsResolver.LEVEL_KEY);
        }
        if (request.getReasoningBudgetTokens() == null) {
            request.setReasoningBudgetTokens(integerValue(options.remove(TextReasoningOptionsResolver.BUDGET_KEY)));
        } else {
            options.remove(TextReasoningOptionsResolver.BUDGET_KEY);
        }
        if (request.getIncludeReasoning() == null) {
            request.setIncludeReasoning(booleanValue(options.remove(TextReasoningOptionsResolver.INCLUDE_KEY)));
        } else {
            options.remove(TextReasoningOptionsResolver.INCLUDE_KEY);
        }
        request.setReasoningEnabled(Boolean.TRUE.equals(request.getReasoningEnabled()));
        request.setIncludeReasoning(Boolean.TRUE.equals(request.getIncludeReasoning())
                && Boolean.TRUE.equals(request.getReasoningEnabled()));
        Integer reasoningBudgetTokens = request.getReasoningBudgetTokens();
        if (reasoningBudgetTokens != null && reasoningBudgetTokens < 0) {
            throw new ServiceException("思考预算无效");
        }
        if (Integer.valueOf(0).equals(reasoningBudgetTokens)) {
            request.setReasoningBudgetTokens(null);
        }
        if (streamingEntry) {
            if (Boolean.FALSE.equals(request.getStream())) {
                throw new ServiceException("流式参数无效");
            }
            request.setStream(Boolean.TRUE);
        } else if (request.getStream() == null) {
            request.setStream(request.getPreferNonStream() != null
                    && !Boolean.TRUE.equals(request.getPreferNonStream()));
        }
        request.setOptions(options.isEmpty() ? null : options);
    }

    public static boolean isStreaming(MediaTextGenerateRequest request) {
        return request != null && Boolean.TRUE.equals(request.getStream());
    }

    private static Boolean booleanValue(Object value) {
        return value == null ? null : Boolean.parseBoolean(String.valueOf(value));
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            long parsed = value instanceof Number number
                    ? number.longValue() : Long.parseLong(String.valueOf(value));
            return parsed > Integer.MAX_VALUE ? Integer.MAX_VALUE
                    : parsed < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) parsed;
        } catch (Exception ignored) {
            throw new ServiceException("思考预算无效");
        }
    }
}
