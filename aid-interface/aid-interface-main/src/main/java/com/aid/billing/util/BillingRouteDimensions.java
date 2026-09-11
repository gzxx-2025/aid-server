package com.aid.billing.util;

import com.aid.billing.dto.BillingInput;
import com.aid.domain.vo.AiModelConfigVo;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 从实际模型路由及已归一计费输入生成不可由扩展参数覆盖的匹配维度。 */
public final class BillingRouteDimensions {
    private BillingRouteDimensions() { }

    public static void apply(AiModelConfigVo model, BillingInput input) {
        Map<String, Object> params = input.getParams() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input.getParams());
        String type = input.getMediaType() == null ? "" : input.getMediaType().toUpperCase(Locale.ROOT);
        String operation = switch (type) {
            case "TEXT" -> "TEXT_GENERATE";
            case "IMAGE" -> "IMAGE_GENERATE";
            case "VIDEO" -> "VIDEO_GENERATE";
            case "AUDIO" -> "SYNTHESIZE";
            default -> "UNKNOWN";
        };
        if ("AUDIO".equals(type) && "tokendance".equalsIgnoreCase(model.getProviderCode())) {
            if ("tokendance:minimax:voice_clone".equals(model.getProtocol())) operation = "REGISTER_CLONE";
            else if ("tokendance:openai:chat-completions".equals(model.getProtocol())) {
                if ("mimo-v2.5-tts-voiceclone".equals(model.getRealModelCode())) operation = "REFERENCE_CLONE";
                else if ("mimo-v2.5-tts-voicedesign".equals(model.getRealModelCode())) operation = "DESIGN";
            }
        }
        params.put("protocol", model.getProtocol());
        params.put("operation", operation);
        params.put("scene", params.getOrDefault("generateMode", operation));
        input.setParams(params);
    }
}
