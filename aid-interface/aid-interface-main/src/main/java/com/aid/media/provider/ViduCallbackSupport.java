package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.ViduConstants;
import com.aid.media.enums.DispatchMode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Vidu 回调地址解析支持：回调基地址跟随「供应商 / 模型」配置走，不放配置中心，与其它厂商口径一致。
 */
@Slf4j
public final class ViduCallbackSupport {

    private ViduCallbackSupport() {
    }

    /**
     * 解析回调基地址：模型级优先，回退供应商级；任何解析异常一律按「未配置」返回 null。
     *
     * @param modelConfig 模型聚合配置（含模型级与供应商级 schedule_strategy_json）
     * @return 回调基地址；未配置/解析失败返回 null
     */
    public static String resolveCallbackBaseUrl(AiModelConfigVo modelConfig) {
        if (!isCallbackEnabled(modelConfig)) {
            return null;
        }
        String modelUrl = readConfiguredCallbackBaseUrl(modelConfig.getScheduleStrategyJson());
        if (StrUtil.isNotBlank(modelUrl)) {
            return normalizeCallbackBaseUrl(modelUrl);
        }
        String providerUrl = readConfiguredCallbackBaseUrl(modelConfig.getProviderScheduleStrategyJson());
        return normalizeCallbackBaseUrl(providerUrl);
    }

    /** 解析任务提交时可下发的回调地址。 */
    public static String resolveCallbackUrlForSubmission(AiModelConfigVo modelConfig) {
        if (!isCallbackDispatchEnabled(modelConfig)) {
            return null;
        }
        return resolveCallbackBaseUrl(modelConfig);
    }

    /** 判断模型是否启用 Vidu 回调能力。 */
    public static boolean isCallbackEnabled(AiModelConfigVo modelConfig) {
        if (modelConfig == null || !Boolean.TRUE.equals(modelConfig.getSupportsCallback())) {
            return false;
        }
        Boolean modelOverride = readSupportsCallback(modelConfig.getScheduleStrategyJson());
        return modelOverride == null || Boolean.TRUE.equals(modelOverride);
    }

    /** 判断模型当前调度策略是否选择回调优先。 */
    public static boolean isCallbackDispatchEnabled(AiModelConfigVo modelConfig) {
        if (!isCallbackEnabled(modelConfig)) {
            return false;
        }
        String dispatchMode = readDispatchMode(modelConfig.getScheduleStrategyJson());
        if (StrUtil.isBlank(dispatchMode)) {
            dispatchMode = readDispatchMode(modelConfig.getProviderScheduleStrategyJson());
        }
        return Objects.equals(DispatchMode.CALLBACK_FIRST.name(),
            StrUtil.trimToEmpty(dispatchMode).toUpperCase(Locale.ROOT));
    }

    /** 校验完整 HTTP/HTTPS 回调地址。 */
    public static boolean isValidCallbackBaseUrl(String callbackUrl) {
        if (StrUtil.isBlank(callbackUrl)) {
            return false;
        }
        try {
            URI uri = URI.create(callbackUrl.trim());
            String scheme = uri.getScheme();
            boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            return http && StrUtil.isNotBlank(uri.getHost())
                && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (Exception ex) {
            return false;
        }
    }

    /** 从单段 schedule_strategy_json 读取 callbackBaseUrl。 */
    private static String readConfiguredCallbackBaseUrl(String strategyJson) {
        if (StrUtil.isBlank(strategyJson)) {
            return null;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(strategyJson);
            return obj.getStr(ViduConstants.STRATEGY_KEY_CALLBACK_BASE_URL);
        } catch (Exception e) {
            log.warn("vidu 解析 schedule_strategy_json.callbackBaseUrl 失败，按未配置处理：{}", e.getMessage());
            return null;
        }
    }

    /** 规范化已配置的回调地址。 */
    private static String normalizeCallbackBaseUrl(String callbackUrl) {
        if (StrUtil.isBlank(callbackUrl)) {
            return null;
        }
        if (!isValidCallbackBaseUrl(callbackUrl)) {
            log.warn("vidu callbackBaseUrl 非法，按未配置处理");
            return null;
        }
        return callbackUrl.trim();
    }

    /** 读取模型级 supportsCallback 显式覆盖值。 */
    private static Boolean readSupportsCallback(String strategyJson) {
        if (StrUtil.isBlank(strategyJson)) {
            return null;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(strategyJson);
            if (!obj.containsKey("supportsCallback")) {
                return null;
            }
            Object value = obj.get("supportsCallback");
            return value instanceof Boolean bool ? bool : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /** 读取调度模式。 */
    private static String readDispatchMode(String strategyJson) {
        if (StrUtil.isBlank(strategyJson)) {
            return null;
        }
        try {
            return JSONUtil.parseObj(strategyJson).getStr("dispatchMode");
        } catch (Exception ex) {
            return null;
        }
    }
}
