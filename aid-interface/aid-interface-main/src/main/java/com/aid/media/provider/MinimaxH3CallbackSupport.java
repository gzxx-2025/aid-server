package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.MinimaxH3Constants;
import com.aid.media.enums.DispatchMode;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** MiniMax H3 回调配置解析；该协议没有回调签名。 */
public final class MinimaxH3CallbackSupport {

    private MinimaxH3CallbackSupport() {
    }

    public static String resolveCallbackUrlForSubmission(AiModelConfigVo config) {
        if (!isCallbackDispatchEnabled(config)) {
            return null;
        }
        String value = readString(config.getScheduleStrategyJson(), MinimaxH3Constants.STRATEGY_CALLBACK_BASE_URL);
        if (StrUtil.isBlank(value)) {
            value = readString(config.getProviderScheduleStrategyJson(), MinimaxH3Constants.STRATEGY_CALLBACK_BASE_URL);
        }
        return isValidCallbackUrl(value) ? value.trim() : null;
    }

    public static boolean isCallbackEnabled(AiModelConfigVo config) {
        if (config == null || !Boolean.TRUE.equals(config.getSupportsCallback())) {
            return false;
        }
        Boolean override = readBoolean(config.getScheduleStrategyJson(), "supportsCallback");
        return !Boolean.FALSE.equals(override);
    }

    public static boolean isCallbackDispatchEnabled(AiModelConfigVo config) {
        if (!isCallbackEnabled(config)) {
            return false;
        }
        String mode = readString(config.getScheduleStrategyJson(), "dispatchMode");
        if (StrUtil.isBlank(mode)) {
            mode = readString(config.getProviderScheduleStrategyJson(), "dispatchMode");
        }
        return Objects.equals(DispatchMode.CALLBACK_FIRST.name(),
            StrUtil.trimToEmpty(mode).toUpperCase(Locale.ROOT));
    }

    public static boolean isValidCallbackUrl(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme())
                && StrUtil.isNotBlank(uri.getHost()) && uri.getUserInfo() == null
                && uri.getQuery() == null && uri.getFragment() == null
                && StrUtil.removeSuffix(uri.getPath(), "/").endsWith(MinimaxH3Constants.CALLBACK_PATH);
        } catch (Exception ex) {
            return false;
        }
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
