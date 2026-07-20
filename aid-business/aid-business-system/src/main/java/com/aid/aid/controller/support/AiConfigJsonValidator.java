package com.aid.aid.controller.support;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.common.exception.ServiceException;

import cn.hutool.core.util.StrUtil;

/**
 * AI 模型 / 服务商管理表单的 JSON 列入参校验器。
 * 把所有 JSON 列的轻量校验集中到写入侧：非空时必须以 {@code {} 包裹且能成功 JSON parse，
 * 避免运营粘贴非法字符串（如 UUID）落库，污染 billing_rule_json / capability_json 等关键字段
 * 导致计费 / 调度失败。任意校验失败抛 {@link ServiceException}（≤ 6 字文案，符合编码规范）。
 *
 * @author 视觉AID
 */
public final class AiConfigJsonValidator
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 文案统一 ≤6 字（编码规范），具体损坏内容由 log.error 打印给开发排查。 */
    private static final String ERR_INVALID_JSON = "JSON格式错";

    private AiConfigJsonValidator()
    {
    }
    /**
     * 校验 {@link AidAiProvider} 上的全部 JSON 列。
     * 涉及字段：{@code schedule_strategy_json / extra_headers / extra_body / extra_query}
     */
    public static void validate(AidAiProvider provider)
    {
        if (provider == null)
        {
            return;
        }
        validateJsonObjectIfPresent("schedule_strategy_json", provider.getScheduleStrategyJson());
        validateJsonObjectIfPresent("extra_headers", provider.getExtraHeaders());
        validateJsonObjectIfPresent("extra_body", provider.getExtraBody());
        validateJsonObjectIfPresent("extra_query", provider.getExtraQuery());
    }
    /**
     * 校验 {@link AidAiModel} 上的全部 JSON 列。
     * 涉及字段：{@code billing_rule_json / schedule_strategy_json / capability_json /
     * param_mapping_json / extra_body}
     */
    public static void validate(AidAiModel model)
    {
        if (model == null)
        {
            return;
        }
        validateJsonObjectIfPresent("billing_rule_json", model.getBillingRuleJson());
        validateJsonObjectIfPresent("schedule_strategy_json", model.getScheduleStrategyJson());
        validateJsonObjectIfPresent("capability_json", model.getCapabilityJson());
        validateJsonObjectIfPresent("param_mapping_json", model.getParamMappingJson());
        validateJsonObjectIfPresent("extra_body", model.getExtraBody());
    }
    /**
     * 单字段校验：空值放行；非空必须以 {@code {} 包裹（顶层强制 JSON 对象，杜绝裸 UUID / 数组 /
     * 字符串误填），并能成功 parse。
     */
    private static void validateJsonObjectIfPresent(String fieldName, String raw)
    {
        if (StrUtil.isBlank(raw))
        {
            return;
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}"))
        {
            // 不打 raw 全文，避免把可能的密钥 / 长串日志噪音；只截前 80 字便于排查
            String preview = trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed;
            throw structuredError(fieldName, "顶层非 JSON 对象", preview);
        }
        try
        {
            OBJECT_MAPPER.readTree(trimmed);
        }
        catch (Exception e)
        {
            String preview = trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed;
            throw structuredError(fieldName, e.getMessage(), preview);
        }
    }

    private static ServiceException structuredError(String fieldName, String reason, String preview)
    {
        // 控制台留详细原因供开发排查；用户侧只看到 6 字内文案
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("field", fieldName);
        ctx.put("reason", reason);
        ctx.put("preview", preview);
        org.slf4j.LoggerFactory.getLogger(AiConfigJsonValidator.class)
                .error("AI 配置 JSON 字段非法: {}", ctx);
        return new ServiceException(ERR_INVALID_JSON);
    }
}
