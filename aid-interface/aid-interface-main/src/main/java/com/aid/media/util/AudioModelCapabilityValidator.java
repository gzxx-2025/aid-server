package com.aid.media.util;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 在报价和预扣前按模型能力校验语音合成参数。 */
@Slf4j
public final class AudioModelCapabilityValidator
{
    private AudioModelCapabilityValidator()
    {
    }

    /** 历史模型缺少声明时继续要求音色；仅显式 false 允许缺省。 */
    public static boolean requiresVoice(AiModelConfigVo config)
    {
        JsonNode capability = capability(config);
        JsonNode required = capability == null ? null : capability.get("ttsVoiceRequired");
        return required == null || !required.isBoolean() || required.booleanValue();
    }

    public static void validate(AiModelConfigVo config, MediaAudioGenerateRequest request)
    {
        if (config == null || request == null) return;
        JsonNode capability = capability(config);
        if (capability == null) return;

        if (capability.path("ttsTextRequired").asBoolean(false) && StrUtil.isBlank(request.getTtsText()))
        {
            reject(config, "缺少配音文本", "配音文本不能为空");
        }
        if (capability.path("ttsVoiceRequired").asBoolean(false) && StrUtil.isBlank(request.getVoiceCode()))
        {
            reject(config, "缺少必选音色", "音色不可用");
        }

        String format = StrUtil.trimToNull(request.getAudioFormat());
        if (format == null)
        {
            format = text(capability, "defaultAudioFormat");
            if (format != null) request.setAudioFormat(format.toLowerCase(Locale.ROOT));
        }
        if (format != null && !allowedText(capability.path("audioFormatOptions"), format))
        {
            reject(config, "音频格式不在允许范围", "音频格式不支持");
        }

        Integer sampleRate = request.getSampleRate();
        if (sampleRate == null && capability.path("defaultAudioSampleRate").isIntegralNumber())
        {
            sampleRate = capability.path("defaultAudioSampleRate").intValue();
            request.setSampleRate(sampleRate);
        }
        if (sampleRate != null && !allowedInteger(capability.path("audioSampleRateOptions"), sampleRate))
        {
            reject(config, "采样率不在允许范围", "采样率不支持");
        }

        range(request.getSpeechRate(), capability, "speechRateMin", "speechRateMax", "语速无效");
        range(request.getLoudnessRate(), capability, "loudnessRateMin", "loudnessRateMax", "音量无效");
        range(request.getPitch(), capability, "pitchMin", "pitchMax", "音调无效");
        if (Boolean.TRUE.equals(request.getEnableTimestamp())
                && capability.has("supportsTimestamp")
                && !capability.path("supportsTimestamp").asBoolean())
        {
            reject(config, "时间戳能力未启用", "该模型不支持时间戳");
        }
        if (StrUtil.isNotBlank(request.getEmotion())
                && !allowedText(capability.path("emotionOptions"), request.getEmotion()))
        {
            reject(config, "情感不在允许范围", "音色情感不支持");
        }
        if (request.getEmotionScale() != null
                && capability.has("supportsEmotionScale")
                && !capability.path("supportsEmotionScale").asBoolean())
        {
            reject(config, "情感强度能力未启用", "该模型不支持情感强度");
        }
        if (capability.path("voiceSampleRequired").asBoolean(false))
        {
            Object sample = request.getOptions() == null ? null : request.getOptions().get("referenceSampleUrl");
            if (!(sample instanceof String value) || StrUtil.isBlank(value))
            {
                reject(config, "缺少参考音色样本", "参考音色样本不可用");
            }
        }
    }

    private static JsonNode capability(AiModelConfigVo config)
    {
        return config == null ? null : ModelCapabilityResolver.parseCapability(config.getCapabilityJson());
    }

    private static String text(JsonNode node, String field)
    {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !value.textValue().isBlank()
                ? value.textValue().trim() : null;
    }

    /** 未配置白名单表示没有新的限制，不破坏历史模型。 */
    private static boolean allowedText(JsonNode options, String actual)
    {
        if (options == null || !options.isArray() || options.isEmpty()) return true;
        Set<String> allowed = new HashSet<>();
        options.forEach(value -> {
            if (value.isTextual()) allowed.add(value.textValue().trim().toLowerCase(Locale.ROOT));
        });
        return allowed.contains(actual.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean allowedInteger(JsonNode options, int actual)
    {
        if (options == null || !options.isArray() || options.isEmpty()) return true;
        for (JsonNode value : options)
        {
            if (value.isIntegralNumber() && value.intValue() == actual) return true;
        }
        return false;
    }

    private static void range(Integer actual, JsonNode capability, String minField,
            String maxField, String message)
    {
        if (actual == null) return;
        JsonNode minimum = capability.get(minField);
        JsonNode maximum = capability.get(maxField);
        if (minimum != null && minimum.isNumber() && actual < minimum.intValue()
                || maximum != null && maximum.isNumber() && actual > maximum.intValue())
        {
            log.info("音频模型能力范围校验拒绝: field={}/{}, actual={}, min={}, max={}",
                    minField, maxField, actual, minimum, maximum);
            throw new ServiceException(message);
        }
    }

    private static void reject(AiModelConfigVo config, String reason, String message)
    {
        log.info("音频模型能力校验拒绝: modelCode={}, reason={}",
                config == null ? null : config.getModelCode(), reason);
        throw new ServiceException(message);
    }
}
