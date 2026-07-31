package com.aid.storyboard.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aid.domain.vo.AiModelConfigVo;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 解析分镜建议时长并按视频模型能力归一化。
 *
 * @author 视觉AID
 */
public final class StoryboardDurationResolver
{
    public static final String SOURCE_STORYBOARD_SUGGESTION = "STORYBOARD_SUGGESTION";
    public static final String SOURCE_REQUEST = "REQUEST";
    public static final String SOURCE_MODEL_DEFAULT = "MODEL_DEFAULT";
    public static final String SOURCE_LEGACY_FALLBACK = "LEGACY_FALLBACK";

    private static final String SCRIPT_PARAM_DURATION_KEY = "视频时长建议秒";
    private static final String CAPABILITY_DURATION_OPTIONS = "durationOptions";
    private static final String CAPABILITY_DEFAULT_DURATION = "defaultDurationSeconds";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private StoryboardDurationResolver()
    {
    }

    /**
     * 从分镜脚本参数读取正整数建议时长。
     *
     * @param scriptParams 分镜脚本参数 JSON
     * @return 建议时长；缺失或非法时返回 null
     */
    public static Integer parseRecommendedDuration(String scriptParams)
    {
        if (StrUtil.isBlank(scriptParams))
        {
            return null;
        }
        try
        {
            JsonNode value = OBJECT_MAPPER.readTree(scriptParams).get(SCRIPT_PARAM_DURATION_KEY);
            if (Objects.isNull(value) || value.isNull())
            {
                return null;
            }
            if (value.isIntegralNumber() && value.canConvertToInt())
            {
                return positiveOrNull(value.intValue());
            }
            if (value.isTextual() && StrUtil.isNotBlank(value.textValue()))
            {
                return positiveOrNull(Integer.valueOf(value.textValue().trim()));
            }
        }
        catch (Exception ignore)
        {
            return null;
        }
        return null;
    }

    /**
     * 说明建议时长读取失败原因，供出片降级日志使用。
     *
     * @param scriptParams 分镜脚本参数 JSON
     * @return 失败原因文案
     */
    public static String explainRecommendedDurationMiss(String scriptParams)
    {
        if (StrUtil.isBlank(scriptParams))
        {
            return "script_params为空";
        }
        try
        {
            JsonNode value = OBJECT_MAPPER.readTree(scriptParams).get(SCRIPT_PARAM_DURATION_KEY);
            if (Objects.isNull(value) || value.isNull())
            {
                return "缺少视频时长建议秒";
            }
            if (value.isIntegralNumber() && value.canConvertToInt())
            {
                return value.intValue() <= 0 ? "视频时长建议秒<=0" : "未知";
            }
            if (value.isTextual())
            {
                String text = value.textValue();
                if (StrUtil.isBlank(text))
                {
                    return "视频时长建议秒不是有效整数";
                }
                try
                {
                    int parsed = Integer.parseInt(text.trim());
                    return parsed <= 0 ? "视频时长建议秒<=0" : "未知";
                }
                catch (NumberFormatException ex)
                {
                    return "视频时长建议秒不是有效整数";
                }
            }
            return "视频时长建议秒不是有效整数";
        }
        catch (Exception ex)
        {
            return "script_params格式错误";
        }
    }

    /**
     * 按单个或批量生成优先级解析最终时长。
     *
     * @param requestDuration      前端传入时长
     * @param recommendedDuration 分镜建议时长
     * @param useRecommendation   是否消费分镜建议时长
     * @param single              是否单分镜生成
     * @param modelConfig         视频模型配置
     * @return 最终时长及来源
     */
    public static Resolution resolve(Integer requestDuration, Integer recommendedDuration,
            boolean useRecommendation, boolean single, AiModelConfigVo modelConfig)
    {
        Integer requested = positiveOrNull(requestDuration);
        Integer recommended = positiveOrNull(recommendedDuration);
        Integer candidate;
        String source;

        if (useRecommendation && single && Objects.nonNull(requested))
        {
            candidate = requested;
            source = SOURCE_REQUEST;
        }
        else if (useRecommendation && Objects.nonNull(recommended))
        {
            candidate = recommended;
            source = SOURCE_STORYBOARD_SUGGESTION;
        }
        else if (Objects.nonNull(requested))
        {
            candidate = requested;
            source = SOURCE_REQUEST;
        }
        else
        {
            candidate = resolveModelDefaultCandidate(modelConfig);
            source = SOURCE_MODEL_DEFAULT;
        }
        return new Resolution(normalize(candidate, modelConfig), Objects.nonNull(candidate) ? source : null);
    }

    /**
     * 解析模型默认时长并归一化到模型支持档位。
     *
     * @param modelConfig 视频模型配置
     * @return 模型默认时长；未配置时返回 null
     */
    public static Integer resolveModelDefaultDuration(AiModelConfigVo modelConfig)
    {
        return normalize(resolveModelDefaultCandidate(modelConfig), modelConfig);
    }

    /**
     * 将候选秒数按模型白名单向上归一化。
     *
     * @param candidate   候选秒数
     * @param modelConfig 视频模型配置
     * @return 归一化后的秒数
     */
    public static Integer normalize(Integer candidate, AiModelConfigVo modelConfig)
    {
        Integer positiveCandidate = positiveOrNull(candidate);
        if (Objects.isNull(positiveCandidate))
        {
            return null;
        }
        List<Integer> allowed = readDurationOptions(modelConfig);
        if (CollectionUtil.isEmpty(allowed))
        {
            return positiveCandidate;
        }
        for (Integer option : allowed)
        {
            if (option >= positiveCandidate)
            {
                return option;
            }
        }
        return allowed.get(allowed.size() - 1);
    }

    private static Integer resolveModelDefaultCandidate(AiModelConfigVo modelConfig)
    {
        if (Objects.isNull(modelConfig))
        {
            return null;
        }
        Integer capabilityDefault = readPositiveInt(modelConfig.getCapabilityJson(), CAPABILITY_DEFAULT_DURATION);
        if (Objects.nonNull(capabilityDefault))
        {
            return capabilityDefault;
        }
        Integer modelDefault = positiveOrNull(modelConfig.getDefaultDurationSeconds());
        if (Objects.nonNull(modelDefault))
        {
            return modelDefault;
        }
        List<Integer> configuredOrder = readDurationOptionsInConfiguredOrder(modelConfig);
        return CollectionUtil.isEmpty(configuredOrder) ? null : configuredOrder.get(0);
    }

    private static List<Integer> readDurationOptions(AiModelConfigVo modelConfig)
    {
        List<Integer> options = readDurationOptionsInConfiguredOrder(modelConfig);
        if (CollectionUtil.isEmpty(options))
        {
            return Collections.emptyList();
        }
        List<Integer> sorted = new ArrayList<>(options);
        Collections.sort(sorted);
        return sorted;
    }

    private static List<Integer> readDurationOptionsInConfiguredOrder(AiModelConfigVo modelConfig)
    {
        if (Objects.isNull(modelConfig) || StrUtil.isBlank(modelConfig.getCapabilityJson()))
        {
            return Collections.emptyList();
        }
        try
        {
            JsonNode node = OBJECT_MAPPER.readTree(modelConfig.getCapabilityJson()).get(CAPABILITY_DURATION_OPTIONS);
            if (Objects.isNull(node) || !node.isArray())
            {
                return Collections.emptyList();
            }
            Set<Integer> options = new LinkedHashSet<>();
            for (JsonNode item : node)
            {
                if (item.isIntegralNumber() && item.canConvertToInt() && item.intValue() > 0)
                {
                    options.add(item.intValue());
                }
            }
            return new ArrayList<>(options);
        }
        catch (Exception ignore)
        {
            return Collections.emptyList();
        }
    }

    private static Integer readPositiveInt(String json, String field)
    {
        if (StrUtil.isBlank(json))
        {
            return null;
        }
        try
        {
            JsonNode value = OBJECT_MAPPER.readTree(json).get(field);
            if (Objects.nonNull(value) && value.isIntegralNumber() && value.canConvertToInt())
            {
                return positiveOrNull(value.intValue());
            }
        }
        catch (Exception ignore)
        {
            return null;
        }
        return null;
    }

    private static Integer positiveOrNull(Integer value)
    {
        return Objects.nonNull(value) && value > 0 ? value : null;
    }

    /**
     * 最终时长解析结果。
     *
     * @param durationSeconds 最终时长
     * @param source          时长来源
     */
    public record Resolution(Integer durationSeconds, String source)
    {
    }
}
