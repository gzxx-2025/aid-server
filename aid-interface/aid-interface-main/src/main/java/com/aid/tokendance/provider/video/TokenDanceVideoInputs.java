package com.aid.tokendance.provider.video;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceAudioInput;
import com.aid.media.dto.ReferenceVideoInput;
import com.aid.tokendance.provider.common.TokenDancePayloadSupport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 从已通过公共能力校验的请求中读取视频协议输入，不执行截断或降级。 */
final class TokenDanceVideoInputs
{
    private static final java.util.Set<String> SAFE_CONFIG_DEFAULTS = java.util.Set.of(
            "seed", "watermark", "watermark_info", "aigc_watermark",
            "camera_fixed", "return_last_frame", "output_format");
    private static final Set<String> COMMON_REQUEST_OPTION_KEYS = Set.of(
            "lastFrameImageUrl", "endImageUrl", "end_image_url",
            "referenceImages", "images",
            "featureVideoUrl", "referenceVideoUrl", "baseVideoUrl", "inputVideoUrl",
            "videoUrl", "video_url", "referenceVideos", "videos",
            "referenceVideoDurations", "videoDurations", "inputVideoDurations",
            "inputVideoSeconds", "referenceVideoSeconds", "videoSeconds",
            "referenceAudios", "resolution", "size", "ratio", "aspect_ratio",
            "generate_audio", "audio", "watermark", "seed", "aigc_watermark",
            "camera_fixed", "return_last_frame", "start_end",
            "generateMode");
    private static final Set<String> GENERATE_MODES = Set.of(
            "text_to_video", "texttovideo",
            "image_to_video", "imagetovideo",
            "edge_to_video", "edgetovideo", "start_end_to_video", "startendtovideo",
            "multi_to_video", "multitovideo", "reference_to_video", "referencetovideo",
            "video_to_video", "videotovideo");

    final String model;
    final String prompt;
    final String firstFrame;
    final String lastFrame;
    final List<String> referenceImages;
    final List<String> referenceVideos;
    final List<String> referenceAudios;
    final String resolution;
    final String ratio;
    final Integer duration;
    final Boolean generateAudio;
    final Boolean watermark;
    final Map<String, Object> options;

    private TokenDanceVideoInputs(String model, String prompt, String firstFrame, String lastFrame,
            List<String> referenceImages, List<String> referenceVideos, List<String> referenceAudios,
            String resolution, String ratio, Integer duration, Boolean generateAudio, Boolean watermark,
            Map<String, Object> options)
    {
        this.model = model;
        this.prompt = prompt;
        this.firstFrame = firstFrame;
        this.lastFrame = lastFrame;
        this.referenceImages = referenceImages;
        this.referenceVideos = referenceVideos;
        this.referenceAudios = referenceAudios;
        this.resolution = resolution;
        this.ratio = ratio;
        this.duration = duration;
        this.generateAudio = generateAudio;
        this.watermark = watermark;
        this.options = options;
    }

    static TokenDanceVideoInputs from(AiModelConfigVo config, MediaVideoGenerateRequest request)
    {
        if (Boolean.TRUE.equals(request.getBgm()) || StrUtil.isNotBlank(request.getAudioType())
                || StrUtil.isNotBlank(request.getVoiceId()))
        {
            throw new com.aid.common.exception.ServiceException("视频音频参数未适配");
        }
        Map<String, Object> requestOptions = request.getOptions() == null
                ? Map.of() : request.getOptions();
        validateGenerateMode(requestOptions.get("generateMode"));
        Map<String, Object> options = TokenDancePayloadSupport.requestScopedOptions(
                config, requestOptions, SAFE_CONFIG_DEFAULTS);
        String firstFrame = StrUtil.trimToNull(request.getImageUrl());
        String lastFrame = TokenDancePayloadSupport.text(requestOptions,
                "lastFrameImageUrl", "endImageUrl", "end_image_url");
        List<String> referenceImages = distinct(TokenDancePayloadSupport.concatUrls(null,
                requestOptions.get("referenceImages"), requestOptions.get("images")));
        referenceImages.remove(firstFrame);
        referenceImages.remove(lastFrame);
        List<String> referenceVideos = distinct(TokenDancePayloadSupport.concatUrls(null,
                requestOptions.get("featureVideoUrl"), requestOptions.get("referenceVideoUrl"),
                requestOptions.get("baseVideoUrl"), requestOptions.get("inputVideoUrl"),
                requestOptions.get("videoUrl"), requestOptions.get("video_url"),
                requestOptions.get("referenceVideos"), requestOptions.get("videos")));
        requireResolvedVideoRecords(request, referenceVideos);
        List<String> referenceAudios = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(request.getReferenceAudios()))
        {
            for (ReferenceAudioInput input : request.getReferenceAudios())
            {
                if (input != null && StrUtil.isNotBlank(input.getSampleUrl()))
                {
                    referenceAudios.add(input.getSampleUrl().trim());
                }
            }
        }
        referenceAudios.addAll(TokenDancePayloadSupport.urls(requestOptions.get("referenceAudios")));
        String resolution = TokenDancePayloadSupport.text(requestOptions, "resolution", "size");
        String ratio = StrUtil.blankToDefault(request.getAspectRatio(),
                TokenDancePayloadSupport.text(requestOptions, "ratio", "aspect_ratio"));
        Boolean generateAudio = request.getAudio() != null ? request.getAudio()
                : TokenDancePayloadSupport.bool(requestOptions, "generate_audio", "audio");
        Boolean watermark = TokenDancePayloadSupport.bool(requestOptions, "watermark");
        return new TokenDanceVideoInputs(
                TokenDancePayloadSupport.upstreamModel(config, request.getModelName()), request.getPrompt(),
                firstFrame, lastFrame, List.copyOf(referenceImages), List.copyOf(referenceVideos),
                List.copyOf(distinct(referenceAudios)), resolution, ratio, request.getDurationSeconds(),
                generateAudio, watermark, options);
    }

    boolean hasReferences()
    {
        return StrUtil.isNotBlank(firstFrame) || StrUtil.isNotBlank(lastFrame)
                || !referenceImages.isEmpty() || !referenceVideos.isEmpty() || !referenceAudios.isEmpty();
    }

    boolean videoEditMode(AiModelConfigVo config)
    {
        String mode = StrUtil.blankToDefault(config.getGenerateMode(), "").trim().toLowerCase();
        String taskType = TokenDancePayloadSupport.text(options,
                "omni_reference_task_type", "videoScenario", "operation");
        return mode.contains("video_to_video") || mode.contains("video_edit") || "edit".equals(mode)
                || "edit".equalsIgnoreCase(taskType);
    }

    static void rejectUnknownRequestOptions(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request,
            Set<String> strategyOptionKeys)
    {
        Set<String> allowed = new java.util.LinkedHashSet<>(COMMON_REQUEST_OPTION_KEYS);
        if (strategyOptionKeys != null)
        {
            allowed.addAll(strategyOptionKeys);
        }
        TokenDancePayloadSupport.rejectUnknownRequestOptions(modelConfig,
                request == null ? null : request.getOptions(), allowed);
    }

    private static List<String> distinct(List<String> source)
    {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (source != null)
        {
            source.stream().filter(Objects::nonNull).map(StrUtil::trimToNull)
                    .filter(Objects::nonNull).forEach(values::add);
        }
        return new ArrayList<>(values);
    }

    private static void validateGenerateMode(Object value)
    {
        if (value == null)
        {
            return;
        }
        if (!(value instanceof CharSequence sequence)
                || !GENERATE_MODES.contains(sequence.toString().trim()
                        .toLowerCase(java.util.Locale.ROOT).replace('-', '_')))
        {
            throw new com.aid.common.exception.ServiceException("生成场景无效");
        }
    }

    /** TokenDance 参考视频只接受当前请求按记录 ID 重新解析的服务端对象。 */
    private static void requireResolvedVideoRecords(MediaVideoGenerateRequest request,
            List<String> referenceVideos)
    {
        if (referenceVideos.isEmpty())
        {
            return;
        }
        Set<String> trusted = new LinkedHashSet<>();
        if (request.getResolvedReferenceVideos() != null)
        {
            for (ReferenceVideoInput input : request.getResolvedReferenceVideos())
            {
                if (input != null && input.isTrusted() && StrUtil.isNotBlank(input.getVideoUrl()))
                {
                    trusted.add(input.getVideoUrl().trim());
                }
            }
        }
        // 任务入库时会显式剔除不可反序列化的内部元数据 DTO；排队拉起时
        // 仅接受已经在创建任务前解析并留存的源记录 ID 快照。
        if (trusted.isEmpty() && request.getReferenceVideoRecordIds() != null)
        {
            long recordCount = request.getReferenceVideoRecordIds().stream()
                    .filter(Objects::nonNull).filter(value -> value > 0).distinct().count();
            if (recordCount >= referenceVideos.size())
            {
                return;
            }
        }
        if (trusted.size() != referenceVideos.size() || !trusted.containsAll(referenceVideos))
        {
            throw new com.aid.common.exception.ServiceException("请使用参考视频记录");
        }
    }
}
