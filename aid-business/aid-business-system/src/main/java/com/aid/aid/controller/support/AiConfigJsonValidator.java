package com.aid.aid.controller.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.service.support.ModelBillingRuleValidator;
import com.aid.common.exception.ServiceException;
import com.aid.media.constants.ConfigurableAsyncMediaConstants;
import com.aid.media.constants.AgnesConstants;
import com.aid.media.constants.DashscopeConstants;
import com.aid.media.constants.MinimaxH3Constants;
import com.aid.media.constants.ViduConstants;
import com.aid.media.constants.VolcengineConstants;
import com.aid.media.provider.ReferenceAudioLimiter;
import com.aid.media.provider.ViduCallbackSupport;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 模型 / 服务商管理表单的 JSON 列入参校验器。
 * 把所有 JSON 列的轻量校验集中到写入侧：非空时必须以 {@code {} 包裹且能成功 JSON parse，
 * 避免运营粘贴非法字符串（如 UUID）落库，污染 billing_rule_json / capability_json 等关键字段
 * 导致计费 / 调度失败。任意校验失败抛 {@link ServiceException}（≤ 6 字文案，符合编码规范）。
 *
 * @author 视觉AID
 */
@Slf4j
public final class AiConfigJsonValidator
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 已实现参考音频下发的服务商白名单。
     * Provider 路由以 provider_code 为最高优先级（见 MediaGenerationServiceImpl 的四级解析），
     * protocol 仅是兜底信号且视频模型普遍留空，故能力位归属必须以 provider_code 判定。
     * 白名单外的服务商开启该位后音频会被静默丢弃却照常扣费，因此直接拒绝保存。
     * 新厂商实现参考音频下发后必须在此登记。
     */
    private static final Set<String> REFERENCE_AUDIO_PROVIDER_CODES =
            Set.of(VolcengineConstants.PROVIDER_CODE);

    /**
     * 已实现参考音频下发的协议白名单。
     * 与 {@link #REFERENCE_AUDIO_PROVIDER_CODES} 取并集：protocol 显式填了受支持协议时同样放行，
     * 避免只按服务商判定而漏掉按协议路由的配置方式。
     */
    private static final Set<String> REFERENCE_AUDIO_PROTOCOLS =
            Set.of(VolcengineConstants.PROTOCOL_SEEDANCE_VIDEO,
                    MinimaxH3Constants.PROTOCOL_VIDEO,
                    ConfigurableAsyncMediaConstants.PROTOCOL_VIDEO);

    /** 文案统一 ≤6 字（编码规范），具体损坏内容由 log.error 打印给开发排查。 */
    private static final String ERR_INVALID_JSON = "JSON格式错";

    /** 回调配置错误文案。 */
    private static final String ERR_INVALID_CALLBACK = "回调地址错误";

    /** 参考音频能力配置错误文案。 */
    private static final String ERR_INVALID_REFERENCE_AUDIO = "音频配置错误";

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
        validateViduCallback(provider);
    }

    /**
     * Vidu 开启回调时必须提供完整 HTTP/HTTPS 地址，避免配置表显示已开启但运行时只能降级轮询。
     */
    private static void validateViduCallback(AidAiProvider provider)
    {
        if (!"vidu".equalsIgnoreCase(StrUtil.trim(provider.getProviderCode()))
                || !Boolean.TRUE.equals(provider.getSupportsCallback()))
        {
            return;
        }
        String callbackUrl = null;
        try
        {
            JsonNode strategy = OBJECT_MAPPER.readTree(provider.getScheduleStrategyJson());
            callbackUrl = strategy == null ? null : strategy.path("callbackBaseUrl").asText(null);
            if (!ViduCallbackSupport.isValidCallbackBaseUrl(callbackUrl))
            {
                throw new IllegalArgumentException("地址不完整");
            }
        }
        catch (Exception e)
        {
            log.error("Vidu 回调地址非法: callbackUrl={}, reason={}", callbackUrl, e.getMessage());
            throw new ServiceException(ERR_INVALID_CALLBACK);
        }
    }
    /**
     * 校验 {@link AidAiModel} 上的全部 JSON 列。
     * 涉及字段：{@code billing_rule_json / schedule_strategy_json / capability_json /
     * param_mapping_json / extra_body}
     *
     * @param model        待校验模型
     * @param providerCode 模型所属服务商编码（{@code aid_ai_provider.provider_code}），
     *                     参考音频能力位按它判定归属；取不到时传 null，此时仅按 protocol 判定
     */
    public static void validate(AidAiModel model, String providerCode)
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
        ModelBillingRuleValidator.validate(model);
        validateViduModelCallback(model);
        validateReferenceAudioCapability(model, providerCode);
    }

    /** 校验 Vidu 模型级回调地址。 */
    private static void validateViduModelCallback(AidAiModel model)
    {
        boolean viduProtocol = Objects.equals(ViduConstants.PROTOCOL_IMAGE, model.getProtocol())
                || Objects.equals(ViduConstants.PROTOCOL_VIDEO, model.getProtocol());
        if (!viduProtocol || StrUtil.isBlank(model.getScheduleStrategyJson()))
        {
            return;
        }
        String callbackUrl = null;
        try
        {
            JsonNode strategy = OBJECT_MAPPER.readTree(model.getScheduleStrategyJson());
            callbackUrl = strategy == null ? null : strategy.path("callbackBaseUrl").asText(null);
            if (StrUtil.isNotBlank(callbackUrl)
                    && !ViduCallbackSupport.isValidCallbackBaseUrl(callbackUrl))
            {
                throw new IllegalArgumentException("地址不完整");
            }
        }
        catch (Exception e)
        {
            log.error("Vidu 模型回调地址非法: modelCode={}, callbackUrl={}, reason={}",
                            model.getModelCode(), callbackUrl, e.getMessage());
            throw new ServiceException(ERR_INVALID_CALLBACK);
        }
    }

    /** 视频参考音频能力开启时，服务商、数量、时长、格式和音画同出能力必须形成可执行配置。 */
    private static void validateReferenceAudioCapability(AidAiModel model, String providerCode)
    {
        if (StrUtil.isBlank(model.getCapabilityJson()))
        {
            return;
        }
        JsonNode capability;
        try
        {
            capability = OBJECT_MAPPER.readTree(model.getCapabilityJson());
        }
        catch (Exception e)
        {
            // JSON 语法错误与配置项不完整是两类问题，日志必须区分，否则排查方向被带偏
            log.error("capability_json 解析失败: modelCode={}, reason={}", model.getModelCode(), e.getMessage());
            throw new ServiceException(ERR_INVALID_JSON);
        }
        if (Objects.isNull(capability) || !capability.path("supportsReferenceAudio").asBoolean(false))
        {
            return;
        }
        String reason = resolveReferenceAudioConfigError(model, providerCode, capability);
        if (StrUtil.isNotBlank(reason))
        {
            log.error("视频参考音频能力配置非法: modelCode={}, reason={}", model.getModelCode(), reason);
            throw new ServiceException(ERR_INVALID_REFERENCE_AUDIO);
        }
    }

    /**
     * 逐项判定参考音频能力配置，返回可定位的具体原因。
     *
     * @param model        模型
     * @param providerCode 模型所属服务商编码，可为空
     * @param capability   已解析的能力 JSON
     * @return 不合法原因；配置合法返回 null
     */
    private static String resolveReferenceAudioConfigError(AidAiModel model, String providerCode, JsonNode capability)
    {
        if (!Objects.equals("video", StrUtil.trim(model.getModelType())))
        {
            return "仅视频模型可开启参考音频";
        }
        // Provider 侧一律按 equalsIgnoreCase 匹配，此处统一转小写后比对，避免大小写差异误判为未实现
        boolean deliverable = REFERENCE_AUDIO_PROVIDER_CODES.contains(normalizeCode(providerCode))
                || REFERENCE_AUDIO_PROTOCOLS.contains(normalizeCode(model.getProtocol()))
                || isAgnes25Model(model, providerCode)
                || isWan3DashscopeModel(model);
        if (!deliverable)
        {
            return "服务商未实现参考音频下发: providerCode=" + providerCode + ", protocol=" + model.getProtocol();
        }
        if (!capability.path("supportsAudio").asBoolean(false))
        {
            return "参考音频依赖音画同出,需先开启 supportsAudio";
        }
        int maxReferenceAudios = capability.path("maxReferenceAudios").asInt(0);
        if (maxReferenceAudios == 0 || maxReferenceAudios < -1)
        {
            return "maxReferenceAudios 必须为-1或正整数";
        }
        int minSeconds = capability.path("referenceAudioMinDurationSeconds").asInt(0);
        int maxSeconds = capability.path("referenceAudioMaxDurationSeconds").asInt(0);
        int maxTotalSeconds = capability.path("referenceAudioMaxTotalDurationSeconds").asInt(0);
        if (minSeconds < 0 || maxSeconds < 0 || maxTotalSeconds < 0
                || (minSeconds > 0 && maxSeconds > 0 && maxSeconds < minSeconds)
                || (minSeconds > 0 && maxTotalSeconds > 0 && maxTotalSeconds < minSeconds))
        {
            return "时长区间非法: min=" + minSeconds + ", max=" + maxSeconds + ", total=" + maxTotalSeconds;
        }
        List<String> formats = new ArrayList<>();
        JsonNode formatNode = capability.path("referenceAudioFormats");
        if (formatNode.isArray())
        {
            formatNode.forEach(item -> {
                if (item.isTextual() && StrUtil.isNotBlank(item.asText()))
                {
                    formats.add(item.asText());
                }
            });
        }
        if (formats.isEmpty())
        {
            return "referenceAudioFormats 不能为空";
        }
        if (formats.contains("*") && formats.size() > 1)
        {
            return "通配格式必须单独配置";
        }
        // "*" 表示厂商未公开格式白名单；其余格式仍须能由服务端解析时长。
        List<String> unsupported = formats.stream()
                .filter(format -> !"*".equals(format))
                .filter(format -> !ReferenceAudioLimiter.isProbeableFormat(format))
                .toList();
        if (!unsupported.isEmpty())
        {
            return "格式无法解析时长: " + unsupported + ", 可选=" + ReferenceAudioLimiter.probeableFormats();
        }
        return null;
    }

    /** Agnes 仅 Video 2.5 两款请求构造器实现结构化参考音频，不能按整个供应商放行。 */
    private static boolean isAgnes25Model(AidAiModel model, String providerCode)
    {
        boolean routedToAgnes = AgnesConstants.PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(providerCode))
                || AgnesConstants.PROTOCOL_VIDEO.equalsIgnoreCase(StrUtil.trim(model.getProtocol()));
        if (!routedToAgnes)
        {
            return false;
        }
        String upstream = StrUtil.blankToDefault(model.getRealModelCode(), model.getModelCode());
        String normalized = normalizeCode(upstream);
        return "agnes-video-2.5".equals(normalized) || "agnes-video-2.5-flash".equals(normalized);
    }

    private static boolean isWan3DashscopeModel(AidAiModel model)
    {
        if (model == null || !DashscopeConstants.PROTOCOL_VIDEO.equalsIgnoreCase(StrUtil.trim(model.getProtocol())))
        {
            return false;
        }
        String upstream = StrUtil.blankToDefault(model.getRealModelCode(), model.getModelCode());
        String normalized = normalizeCode(upstream);
        return DashscopeConstants.MODEL_WAN3.equals(normalized)
                || DashscopeConstants.MODEL_WAN3_PRIME.equals(normalized);
    }

    /**
     * 归一化服务商编码 / 协议标识，便于与白名单做大小写无关比对。
     *
     * @param code 原始值，可为空
     * @return 去空白并转小写后的值；空值返回空串
     */
    private static String normalizeCode(String code)
    {
        return StrUtil.trimToEmpty(code).toLowerCase(Locale.ROOT);
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
        log.error("AI 配置 JSON 字段非法: {}", ctx);
        return new ServiceException(ERR_INVALID_JSON);
    }
}
