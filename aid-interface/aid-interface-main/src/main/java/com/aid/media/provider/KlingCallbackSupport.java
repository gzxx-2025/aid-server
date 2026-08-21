package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.KlingConstants;
import com.aid.media.enums.DispatchMode;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** 从模型/供应商调度配置解析可灵回调地址。 */
public final class KlingCallbackSupport {

    private KlingCallbackSupport() {
    }

    public static String resolveCallbackUrlForSubmission(AiModelConfigVo config) {
        if (!isCallbackDispatchEnabled(config)
            || !KlingCallbackSignatureUtil.hasValidSecret(config.getApiSecret())) {
            return null;
        }
        String value = readString(config.getScheduleStrategyJson(), KlingConstants.STRATEGY_CALLBACK_BASE_URL);
        if (StrUtil.isBlank(value)) {
            value = readString(config.getProviderScheduleStrategyJson(), KlingConstants.STRATEGY_CALLBACK_BASE_URL);
        }
        return isValidKlingCallbackUrl(value) ? value.trim() : null;
    }

    public static boolean isCallbackDispatchEnabled(AiModelConfigVo config) {
        if (config == null || !Boolean.TRUE.equals(config.getSupportsCallback())) {
            return false;
        }
        Boolean override = readBoolean(config.getScheduleStrategyJson(), "supportsCallback");
        if (Boolean.FALSE.equals(override)) {
            return false;
        }
        String mode = readString(config.getScheduleStrategyJson(), "dispatchMode");
        if (StrUtil.isBlank(mode)) {
            mode = readString(config.getProviderScheduleStrategyJson(), "dispatchMode");
        }
        return Objects.equals(DispatchMode.CALLBACK_FIRST.name(),
            StrUtil.trimToEmpty(mode).toUpperCase(Locale.ROOT));
    }

    public static boolean isValidHttpUrl(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && StrUtil.isNotBlank(uri.getHost()) && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (Exception ex) {
            return false;
        }
    }

    public static boolean isValidKlingCallbackUrl(String value) {
        if (!isValidHttpUrl(value)) {
            return false;
        }
        URI uri = URI.create(value.trim());
        return "https".equalsIgnoreCase(uri.getScheme())
            && StrUtil.removeSuffix(uri.getPath(), "/").endsWith("/api/media/callback/kling");
    }

    private static String readString(String json, String key) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return JSONUtil.parseObj(json).getStr(key);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Boolean readBoolean(String json, String key) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            JSONObject object = JSONUtil.parseObj(json);
            return object.containsKey(key) && object.get(key) instanceof Boolean value ? value : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
