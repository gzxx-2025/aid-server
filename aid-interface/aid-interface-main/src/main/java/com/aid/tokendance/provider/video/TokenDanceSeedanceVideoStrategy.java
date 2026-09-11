package com.aid.tokendance.provider.video;

import cn.hutool.core.util.StrUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.dto.ReferenceVideoInput;
import com.aid.media.util.ModelCapabilityResolver;
import com.aid.tokendance.provider.common.TokenDanceEndpoints;
import com.aid.tokendance.provider.common.TokenDancePayloadSupport;
import com.aid.tokendance.provider.common.TokenDanceProtocols;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;

/** TokenDance Seedance Generations 请求方言。 */
final class TokenDanceSeedanceVideoStrategy implements TokenDanceVideoProtocolStrategy
{
    private static final Set<String> TASK_TYPES = Set.of("reference", "edit", "extend", "auto");
    private static final Set<String> OPTIONAL_FIELDS = Set.of(
            "seed", "watermark", "camera_fixed", "return_last_frame", "output_format");

    @Override
    public String protocol()
    {
        return TokenDanceProtocols.SEEDANCE_GENERATIONS;
    }

    @Override
    public String submitPath()
    {
        return TokenDanceEndpoints.SEEDANCE_SUBMIT;
    }

    @Override
    public String queryTemplate()
    {
        return TokenDanceEndpoints.SEEDANCE_QUERY;
    }

    @Override
    public TokenDanceVideoResponseMapper.Shape responseShape()
    {
        return TokenDanceVideoResponseMapper.Shape.SEEDANCE;
    }

    @Override
    public void sanitizePrompt(MediaVideoGenerateRequest request, TokenDanceVideoInputs inputs)
    {
        int imageCount = inputs.referenceImages.size()
                + (inputs.firstFrame == null ? 0 : 1)
                + (inputs.lastFrame == null ? 0 : 1);
        ReferencePromptSanitizer.sanitizeInPlaceForSeedance(request, imageCount,
                inputs.referenceVideos.size(), inputs.referenceAudios.size());
    }

    @Override
    public Map<String, Object> buildBody(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request)
    {
        TokenDanceVideoInputs.rejectUnknownRequestOptions(modelConfig, request,
                Set.of("omni_reference_task_type", "output_format"));
        TokenDanceVideoInputs inputs = TokenDanceVideoInputs.from(modelConfig, request);
        String taskType = TokenDancePayloadSupport.text(inputs.options, "omni_reference_task_type");
        if (StrUtil.isNotBlank(taskType))
        {
            taskType = taskType.trim().toLowerCase();
            TokenDanceVideoBodySupport.require(TASK_TYPES.contains(taskType), "任务类型不支持");
        }
        validateModelContract(modelConfig, request, inputs, taskType);
        boolean explicitReference = StrUtil.isNotBlank(taskType);
        boolean hasReferenceMedia = TokenDanceVideoBodySupport.hasAny(
                inputs.referenceImages, inputs.referenceVideos, inputs.referenceAudios);
        TokenDanceVideoBodySupport.reject(StrUtil.isNotBlank(inputs.lastFrame)
                && (explicitReference || hasReferenceMedia), "输入组合不支持");
        TokenDanceVideoBodySupport.reject(!explicitReference && hasReferenceMedia
                && StrUtil.isNotBlank(inputs.firstFrame), "输入组合不支持");

        List<Map<String, Object>> content = new ArrayList<>();
        if (StrUtil.isNotBlank(inputs.prompt))
        {
            content.add(TokenDanceVideoBodySupport.content("text", inputs.prompt, null, null, null));
        }
        if (explicitReference)
        {
            if (StrUtil.isNotBlank(inputs.firstFrame))
            {
                content.add(TokenDanceVideoBodySupport.nestedMedia(
                        "image_url", inputs.firstFrame, "reference_image"));
            }
            addReferenceMedia(content, inputs);
        }
        else if (hasReferenceMedia)
        {
            addReferenceMedia(content, inputs);
        }
        else
        {
            if (StrUtil.isNotBlank(inputs.firstFrame))
            {
                content.add(TokenDanceVideoBodySupport.nestedMedia(
                        "image_url", inputs.firstFrame, "first_frame"));
            }
            if (StrUtil.isNotBlank(inputs.lastFrame))
            {
                TokenDanceVideoBodySupport.require(StrUtil.isNotBlank(inputs.firstFrame), "缺少首帧");
                content.add(TokenDanceVideoBodySupport.nestedMedia(
                        "image_url", inputs.lastFrame, "last_frame"));
            }
        }
        TokenDanceVideoBodySupport.require(!content.isEmpty(), "缺少生成内容");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", inputs.model);
        body.put("content", content);
        TokenDanceVideoBodySupport.putIfPresent(body, "resolution",
                TokenDanceVideoBodySupport.lowerResolution(inputs.resolution));
        String effectiveRatio = requiresAdaptiveRatio(inputs, taskType) ? "adaptive" : inputs.ratio;
        TokenDanceVideoBodySupport.putIfPresent(body, "ratio", effectiveRatio);
        TokenDanceVideoBodySupport.putIfPresent(body, "duration", inputs.duration);
        TokenDanceVideoBodySupport.putIfPresent(body, "generate_audio", inputs.generateAudio);
        TokenDanceVideoBodySupport.putIfPresent(body, "omni_reference_task_type", taskType);
        TokenDancePayloadSupport.copyAllowlisted(body, inputs.options, OPTIONAL_FIELDS);
        return body;
    }

    /**
     * Seedance 同一路径承载多个版本，任务类型、首尾帧和编辑规则必须从当前模型能力读取，
     * 不能因为协议相同就把 2.5 的参数下发给 2.0。
     */
    private void validateModelContract(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request,
            TokenDanceVideoInputs inputs, String taskType)
    {
        JsonNode capability = ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson());
        if (StrUtil.isNotBlank(taskType))
        {
            List<String> allowed = ModelCapabilityResolver.readOptions(capability,
                    "seedanceTaskTypeOptions");
            TokenDanceVideoBodySupport.require(!allowed.isEmpty()
                    && ModelCapabilityResolver.matchOption(allowed, taskType) != null,
                    "任务类型不支持");
            TokenDanceVideoBodySupport.reject(StrUtil.isNotBlank(inputs.firstFrame)
                    || StrUtil.isNotBlank(inputs.lastFrame), "输入组合不支持");
            TokenDanceVideoBodySupport.require(!inputs.referenceImages.isEmpty()
                    || !inputs.referenceVideos.isEmpty() || !inputs.referenceAudios.isEmpty(),
                    "缺少参考素材");
            if ("edit".equals(taskType) || "extend".equals(taskType))
            {
                TokenDanceVideoBodySupport.require(!inputs.referenceVideos.isEmpty(), "缺少参考视频");
            }
            if ("edit".equals(taskType))
            {
                TokenDanceVideoBodySupport.require(Integer.valueOf(-1).equals(inputs.duration),
                        "编辑时长须自动");
                validateEditVideoDuration(request, inputs);
            }
        }
        String outputFormat = TokenDancePayloadSupport.text(inputs.options, "output_format");
        if (StrUtil.isNotBlank(outputFormat))
        {
            List<String> allowedFormats = ModelCapabilityResolver.readOptions(capability,
                    "outputFormatOptions");
            TokenDanceVideoBodySupport.require(!allowedFormats.isEmpty()
                    && ModelCapabilityResolver.matchOption(allowedFormats, outputFormat) != null,
                    "输出格式不支持");
        }
    }

    private boolean requiresAdaptiveRatio(TokenDanceVideoInputs inputs, String taskType)
    {
        return StrUtil.isNotBlank(inputs.firstFrame)
                || StrUtil.isNotBlank(inputs.lastFrame) || "edit".equals(taskType)
                || "extend".equals(taskType);
    }

    private void validateEditVideoDuration(MediaVideoGenerateRequest request, TokenDanceVideoInputs inputs)
    {
        Map<String, ReferenceVideoInput> metadata = new LinkedHashMap<>();
        if (request.getResolvedReferenceVideos() != null)
        {
            for (ReferenceVideoInput input : request.getResolvedReferenceVideos())
            {
                if (input != null && StrUtil.isNotBlank(input.getVideoUrl()))
                {
                    metadata.put(input.getVideoUrl().trim(), input);
                }
            }
        }
        for (String url : inputs.referenceVideos)
        {
            ReferenceVideoInput input = metadata.get(url);
            TokenDanceVideoBodySupport.require(input != null && input.getDurationMs() != null
                    && input.getDurationMs() >= 4_000L && input.getDurationMs() <= 30_000L,
                    input == null || input.getDurationMs() == null
                            ? "视频元数据不可用" : "参考视频时长超限");
        }
    }

    private void addReferenceMedia(List<Map<String, Object>> content, TokenDanceVideoInputs inputs)
    {
        for (String url : inputs.referenceImages)
        {
            content.add(TokenDanceVideoBodySupport.nestedMedia("image_url", url, "reference_image"));
        }
        for (String url : inputs.referenceVideos)
        {
            content.add(TokenDanceVideoBodySupport.nestedMedia("video_url", url, "reference_video"));
        }
        for (String url : inputs.referenceAudios)
        {
            content.add(TokenDanceVideoBodySupport.nestedMedia("audio_url", url, "reference_audio"));
        }
    }
}
