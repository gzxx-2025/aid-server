package com.aid.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * Agent 默认参数应用器。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class AgentDefaultParamsApplier
{
    /** 规格参数。 */
    public static final String PARAM_SIZE = "size";
    /** 宽高比参数。 */
    public static final String PARAM_ASPECT_RATIO = "aspectRatio";
    /** 图片输出数量参数。 */
    public static final String PARAM_OUTPUT_COUNT = "outputCount";
    /** 视频时长参数。 */
    public static final String PARAM_DURATION_SECONDS = "durationSeconds";

    /** 扩展参数中的宽高比键。 */
    private static final String OPTIONS_ASPECT_RATIO = "aspect_ratio";
    /** 扩展参数中的视频时长键。 */
    private static final String OPTIONS_DURATION = "duration";

    /**
     * 应用图片生成默认参数。
     *
     * @param defaults    默认参数
     * @param request     图片生成请求
     * @param modelConfig 模型配置
     */
    public void applyToImage(AgentModelDefault defaults,
                             MediaImageGenerateRequest request,
                             AiModelConfigVo modelConfig)
    {
        if (defaults == null || !defaults.hasDefaultParams() || Objects.isNull(request) || Objects.isNull(modelConfig))
        {
            return;
        }
        Map<String, Object> params = defaults.getDefaultParams();

        if (Boolean.TRUE.equals(modelConfig.getSupportsSizePreset())
                && StrUtil.isBlank(request.getSize())
                && params.get(PARAM_SIZE) instanceof String size && StrUtil.isNotBlank(size))
        {
            request.setSize(size);
            log.info("Agent默认参数兜底: image.size={}, modelCode={}", size, modelConfig.getModelCode());
        }

        if (Boolean.TRUE.equals(modelConfig.getSupportsAspectRatio())
                && params.get(PARAM_ASPECT_RATIO) instanceof String aspectRatio && StrUtil.isNotBlank(aspectRatio))
        {
            Map<String, Object> options = ensureOptions(request);
            Object existing = options.putIfAbsent(OPTIONS_ASPECT_RATIO, aspectRatio);
            if (existing == null)
            {
                log.info("Agent默认参数兜底: image.options.aspect_ratio={}, modelCode={}",
                        aspectRatio, modelConfig.getModelCode());
            }
        }

        if (params.get(PARAM_OUTPUT_COUNT) instanceof Number outputCount)
        {
            int target = outputCount.intValue();
            Integer maxOutput = modelConfig.getMaxOutputCount();
            if (maxOutput != null && maxOutput > 0)
            {
                target = Math.min(target, maxOutput);
            }
            if (target > 0 && (request.getExpectedImageCount() == null || request.getExpectedImageCount() <= 0))
            {
                request.setExpectedImageCount(target);
                log.info("Agent默认参数兜底: image.expectedImageCount={}, modelCode={}",
                        target, modelConfig.getModelCode());
            }
        }
    }

    /**
     * 应用视频生成默认参数。
     *
     * @param defaults    默认参数
     * @param request     视频生成请求
     * @param modelConfig 模型配置
     */
    public void applyToVideo(AgentModelDefault defaults,
                             MediaVideoGenerateRequest request,
                             AiModelConfigVo modelConfig)
    {
        if (defaults == null || !defaults.hasDefaultParams() || Objects.isNull(request) || Objects.isNull(modelConfig))
        {
            return;
        }
        Map<String, Object> params = defaults.getDefaultParams();

        if (Boolean.TRUE.equals(modelConfig.getSupportsSizePreset())
                && params.get(PARAM_SIZE) instanceof String size && StrUtil.isNotBlank(size))
        {
            Map<String, Object> options = ensureVideoOptions(request);
            Object existing = options.putIfAbsent(PARAM_SIZE, size);
            if (existing == null)
            {
                log.info("Agent默认参数兜底: video.options.size={}, modelCode={}", size, modelConfig.getModelCode());
            }
        }

        if (Boolean.TRUE.equals(modelConfig.getSupportsAspectRatio())
                && StrUtil.isBlank(request.getAspectRatio())
                && params.get(PARAM_ASPECT_RATIO) instanceof String aspectRatio && StrUtil.isNotBlank(aspectRatio))
        {
            request.setAspectRatio(aspectRatio);
            log.info("Agent默认参数兜底: video.aspectRatio={}, modelCode={}", aspectRatio, modelConfig.getModelCode());
        }

        if (Boolean.TRUE.equals(modelConfig.getSupportsDuration())
                && request.getDurationSeconds() == null
                && params.get(PARAM_DURATION_SECONDS) instanceof Number duration)
        {
            int intValue = duration.intValue();
            if (intValue > 0)
            {
                request.setDurationSeconds(intValue);
                log.info("Agent默认参数兜底: video.durationSeconds={}, modelCode={}",
                        intValue, modelConfig.getModelCode());
            }
        }
    }

    private Map<String, Object> ensureOptions(MediaImageGenerateRequest request)
    {
        Map<String, Object> options = request.getOptions();
        if (options == null)
        {
            options = new LinkedHashMap<>();
            request.setOptions(options);
        }
        return options;
    }

    private Map<String, Object> ensureVideoOptions(MediaVideoGenerateRequest request)
    {
        Map<String, Object> options = request.getOptions();
        if (options == null)
        {
            options = new LinkedHashMap<>();
            request.setOptions(options);
        }
        return options;
    }
}
