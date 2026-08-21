package com.aid.media.provider;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Set;

/** 统一判定文本供应商的生成终止原因。 */
public final class TextFinishReasonSupport {

    private static final Set<String> GEMINI_SAFETY_REASONS = Set.of(
            "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY");
    private static final Set<String> GEMINI_TOOL_REASONS = Set.of(
            "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS",
            "MISSING_THOUGHT_SIGNATURE");

    private TextFinishReasonSupport() {
    }

    /** OpenAI 兼容协议仅将 stop 视为完整文本结果。 */
    public static String openAiFailureMessage(String finishReason) {
        String reason = StringUtils.trimToEmpty(finishReason).toLowerCase(Locale.ROOT);
        return switch (reason) {
            case "stop" -> null;
            case "length" -> "生成内容不完整";
            case "content_filter" -> "生成被安全拦截";
            case "tool_calls", "function_call" -> "生成方式不支持";
            default -> "上游终止异常";
        };
    }

    /** Gemini 协议仅将 STOP 视为完整文本结果。 */
    public static String geminiFailureMessage(String finishReason) {
        String reason = StringUtils.trimToEmpty(finishReason).toUpperCase(Locale.ROOT);
        if ("STOP".equals(reason)) {
            return null;
        }
        if ("MAX_TOKENS".equals(reason)) {
            return "生成内容不完整";
        }
        if (GEMINI_SAFETY_REASONS.contains(reason)) {
            return "生成被安全拦截";
        }
        if (GEMINI_TOOL_REASONS.contains(reason)) {
            return "生成方式不支持";
        }
        return "上游终止异常";
    }
}
