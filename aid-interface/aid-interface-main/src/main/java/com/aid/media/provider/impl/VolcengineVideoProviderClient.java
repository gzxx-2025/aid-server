package com.aid.media.provider.impl;


import cn.hutool.json.JSONUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.VolcengineConstants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceAudioInput;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.ReferenceAudioLimiter;
import com.aid.media.provider.ReferenceImageBase64Support;
import com.aid.media.provider.ReferenceImageLimiter;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.provider.VideoProviderClient;
import com.aid.media.provider.volcengine.VolcengineServiceManager;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskResult;
import com.volcengine.ark.runtime.model.content.generation.GetContentGenerationTaskRequest;
import com.volcengine.ark.runtime.model.content.generation.GetContentGenerationTaskResponse;
import com.volcengine.ark.runtime.service.ArkService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 火山引擎 Seedance（豆包）视频生成：基于方舟 Ark Java SDK，异步提交 + 轮询查询。
 */
@Slf4j
@Component
public class VolcengineVideoProviderClient implements VideoProviderClient {

    /** Seedance 2.0 多模态参考图官方上限 9；运营可在 capability_json.maxReferenceImages 覆盖。 */
    private static final int DEFAULT_MAX_REFERENCE_IMAGES = 9;

    @Autowired
    private VolcengineServiceManager volcengineServiceManager;

    @Override
    public String protocol() {
        return VolcengineConstants.PROTOCOL_SEEDANCE_VIDEO;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        // 火山方舟 Seedance 视频：按 provider_code 精确归属
        return providerCode != null
                && VolcengineConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        // Seedance 是唯一真会下发参考音频的视频通道：条数按 capability 截断后，正文里编号越界的
        // @音频N 必须一并降级，否则模型读到「参考音频3」却在 contents 里找不到第 3 条。
        // 参考图同理，两者共用一套编号越界规则。
        ReferencePromptSanitizer.sanitizeInPlace(request,
                ReferenceImageLimiter.resolveMax(modelConfig, DEFAULT_MAX_REFERENCE_IMAGES),
                ReferenceAudioLimiter.limit(request.getReferenceAudios(), modelConfig, "Volcengine").size());
        ArkService service = volcengineServiceManager.getService(modelConfig.getApiKey(), modelConfig.getBaseUrl());
        String effectiveModel = resolveEffectiveModel(modelConfig, request);
        List<CreateContentGenerationTaskRequest.Content> contents = buildContents(request, modelConfig);
        CreateContentGenerationTaskRequest createRequest = buildCreateRequest(effectiveModel, contents, request, modelConfig);

        log.info("Volcengine 视频生成提交(Seedance), model={}, promptLen={}", effectiveModel,
                StringUtils.length(request.getPrompt()));

        CreateContentGenerationTaskResult result;
        try {
            result = service.createContentGenerationTask(createRequest);
        } catch (Exception e) {
            log.error("Volcengine 视频生成提交失败, model={}", effectiveModel, e);
            return ProviderSubmitResult.builder()
                    .rawResponse(e.getMessage())
                    .build();
        }

        String taskId = result.getId();
        log.info("Volcengine 视频生成任务已创建, taskId={}", taskId);

        return ProviderSubmitResult.builder()
                .providerTaskId(taskId)
                .rawResponse(JSONUtil.toJsonStr(result))
                .build();
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        ArkService service = volcengineServiceManager.getService(modelConfig.getApiKey(), modelConfig.getBaseUrl());

        GetContentGenerationTaskRequest req = GetContentGenerationTaskRequest.builder()
                .taskId(providerTaskId)
                .build();

        GetContentGenerationTaskResponse resp;
        try {
            resp = service.getContentGenerationTask(req);
        } catch (Exception e) {
            log.error("Volcengine 视频生成查询失败, taskId={}", providerTaskId, e);
            return ProviderTaskResult.builder()
                    .status(VolcengineConstants.TASK_STATUS_PROCESSING)
                    .errorMessage(e.getMessage())
                    .querySuccessful(Boolean.FALSE)
                    .terminalConfirmed(Boolean.FALSE)
                    .build();
        }
        String videoUrl = null;
        if (resp.getContent() != null) {
            videoUrl = resp.getContent().getVideoUrl();
        }

        String errorMessage = null;
        if (resp.getError() != null) {
            errorMessage = resp.getError().getMessage();
        }

        String normalized = normalizeStatus(resp.getStatus());
        log.info("Volcengine 视频生成查询, taskId={}, status={}, normalized={}", providerTaskId, resp.getStatus(), normalized);

        if (!isKnownStatus(resp.getStatus())) {
            return ProviderTaskResult.builder()
                    .status(VolcengineConstants.TASK_STATUS_PROCESSING)
                    .errorMessage(StringUtils.isBlank(resp.getStatus())
                            ? "上游响应缺少状态" : "上游返回未知状态:" + resp.getStatus())
                    .rawResponse(JSONUtil.toJsonStr(resp))
                    .querySuccessful(Boolean.FALSE)
                    .providerStatus(resp.getStatus())
                    .terminalConfirmed(Boolean.FALSE)
                    .build();
        }
        if (VolcengineConstants.TASK_STATUS_SUCCEEDED.equals(normalized) && StringUtils.isBlank(videoUrl)) {
            return ProviderTaskResult.builder()
                    .status(VolcengineConstants.TASK_STATUS_PROCESSING)
                    .errorMessage("上游成功但结果链接未就绪")
                    .rawResponse(JSONUtil.toJsonStr(resp))
                    .querySuccessful(Boolean.FALSE)
                    .providerStatus(resp.getStatus())
                    .terminalConfirmed(Boolean.FALSE)
                    .build();
        }
        return ProviderTaskResult.builder()
                .status(normalized)
                .resultUrl(videoUrl)
                .errorMessage(errorMessage)
                .rawResponse(JSONUtil.toJsonStr(resp))
                .querySuccessful(Boolean.TRUE)
                .providerStatus(resp.getStatus())
                .terminalConfirmed(isTerminalStatus(resp.getStatus()))
                .build();
    }

    private List<CreateContentGenerationTaskRequest.Content> buildContents(MediaVideoGenerateRequest request,
                                                                           AiModelConfigVo modelConfig) {
        List<CreateContentGenerationTaskRequest.Content> contents = new ArrayList<>();
        Map<String, Object> options = request.getOptions();
        // Base64 传图开关：官方 image 支持 data URI（data:image/<格式>;base64,...），启用时下载转内联下发
        boolean useBase64 = ReferenceImageBase64Support.isBase64Enabled(modelConfig);

        if (StringUtils.isNotBlank(request.getPrompt())) {
            contents.add(CreateContentGenerationTaskRequest.Content.builder()
                    .type(VolcengineConstants.CONTENT_TYPE_TEXT)
                    .text(request.getPrompt())
                    .build());
        }

        String lastFrameUrl = getOptionString(options, VolcengineConstants.OPTIONS_LAST_FRAME_IMAGE_URL);
        if (StringUtils.isNotBlank(request.getImageUrl()) && StringUtils.isNotBlank(lastFrameUrl)) {
            contents.add(CreateContentGenerationTaskRequest.Content.builder()
                    .type(VolcengineConstants.CONTENT_TYPE_IMAGE_URL)
                    .imageUrl(CreateContentGenerationTaskRequest.ImageUrl.builder()
                            .url(toBase64IfEnabled(request.getImageUrl(), useBase64)).build())
                    .role(VolcengineConstants.ROLE_FIRST_FRAME)
                    .build());
            contents.add(CreateContentGenerationTaskRequest.Content.builder()
                    .type(VolcengineConstants.CONTENT_TYPE_IMAGE_URL)
                    .imageUrl(CreateContentGenerationTaskRequest.ImageUrl.builder()
                            .url(toBase64IfEnabled(lastFrameUrl, useBase64)).build())
                    .role(VolcengineConstants.ROLE_LAST_FRAME)
                    .build());
        } else {
            List<String> referenceImages = ReferenceImageLimiter.limit(
                    getOptionStringList(options, VolcengineConstants.OPTIONS_REFERENCE_IMAGES),
                    modelConfig, DEFAULT_MAX_REFERENCE_IMAGES, "Volcengine");
            if (!referenceImages.isEmpty()) {
                for (String refUrl : referenceImages) {
                    contents.add(CreateContentGenerationTaskRequest.Content.builder()
                            .type(VolcengineConstants.CONTENT_TYPE_IMAGE_URL)
                            .imageUrl(CreateContentGenerationTaskRequest.ImageUrl.builder()
                                    .url(toBase64IfEnabled(refUrl, useBase64)).build())
                            .role(VolcengineConstants.ROLE_REFERENCE)
                            .build());
                }
            } else if (StringUtils.isNotBlank(request.getImageUrl())) {
                contents.add(CreateContentGenerationTaskRequest.Content.builder()
                        .type(VolcengineConstants.CONTENT_TYPE_IMAGE_URL)
                        .imageUrl(CreateContentGenerationTaskRequest.ImageUrl.builder()
                                .url(toBase64IfEnabled(request.getImageUrl(), useBase64)).build())
                        .build());
            }
        }

        List<ReferenceAudioInput> referenceAudios = ReferenceAudioLimiter.limit(
                request.getReferenceAudios(), modelConfig, "Volcengine");
        for (ReferenceAudioInput audio : referenceAudios) {
            if (audio == null || StringUtils.isBlank(audio.getSampleUrl())) {
                continue;
            }
            // Seedance 官方协议：type=audio_url、role=reference_audio。
            contents.add(CreateContentGenerationTaskRequest.Content.builder()
                    .type(VolcengineConstants.CONTENT_TYPE_AUDIO_URL)
                    .audioUrl(CreateContentGenerationTaskRequest.AudioUrl.builder()
                            .url(audio.getSampleUrl()).build())
                    .role(VolcengineConstants.ROLE_REFERENCE_AUDIO)
                    .build());
        }

        return contents;
    }

    /**
     * 按开关把单张图片 URL 转 data URI；未启用或转换失败时原样返回 URL。
     */
    private String toBase64IfEnabled(String imageUrl, boolean useBase64) {
        if (!useBase64 || StringUtils.isBlank(imageUrl)) {
            return imageUrl;
        }
        List<String> converted = ReferenceImageBase64Support
                .toDataUris(Collections.singletonList(imageUrl));
        return converted.isEmpty() ? imageUrl : converted.get(0);
    }

    private CreateContentGenerationTaskRequest buildCreateRequest(
            String model,
            List<CreateContentGenerationTaskRequest.Content> contents,
            MediaVideoGenerateRequest request,
            AiModelConfigVo modelConfig) {

        CreateContentGenerationTaskRequest.Builder builder = CreateContentGenerationTaskRequest.builder()
                .model(model)
                .content(contents)
                .watermark(VolcengineConstants.DEFAULT_WATERMARK);

        if (StringUtils.isNotBlank(request.getAspectRatio())) {
            builder.ratio(request.getAspectRatio());
        }

        if (request.getDurationSeconds() != null) {
            builder.duration(request.getDurationSeconds().longValue());
        } else {
            builder.duration(VolcengineConstants.DEFAULT_VIDEO_DURATION_SECONDS);
        }

        applyVideoOptions(builder, request, modelConfig);

        return builder.build();
    }

    private void applyVideoOptions(CreateContentGenerationTaskRequest.Builder builder,
                                   MediaVideoGenerateRequest request, AiModelConfigVo modelConfig) {
        Map<String, Object> options = request == null ? null : request.getOptions();
        // 音画同出：仅 capability.supportsAudio=true 时下发；优先顶层 audio，其次 options.generate_audio
        Boolean generateAudio = resolveGenerateAudio(request);
        if (generateAudio != null) {
            builder.generateAudio(generateAudio);
        }

        if (options == null || options.isEmpty()) {
            return;
        }

        if (options.containsKey(VolcengineConstants.OPTIONS_RESOLUTION)) {
            // 官方枚举为小写 480p/720p/1080p/4k；业务层/capability 多为 720P，下发前统一转小写
            String resolution = String.valueOf(options.get(VolcengineConstants.OPTIONS_RESOLUTION)).trim();
            if (StringUtils.isNotBlank(resolution)) {
                builder.resolution(resolution.toLowerCase());
            }
        }

        if (options.containsKey(VolcengineConstants.JSON_WATERMARK)) {
            builder.watermark(Boolean.parseBoolean(String.valueOf(options.get(VolcengineConstants.JSON_WATERMARK))));
        }

        if (options.containsKey(VolcengineConstants.OPTIONS_RETURN_LAST_FRAME)) {
            builder.returnLastFrame(Boolean.parseBoolean(String.valueOf(options.get(VolcengineConstants.OPTIONS_RETURN_LAST_FRAME))));
        }

        if (options.get(VolcengineConstants.JSON_SEED) instanceof Number seed) {
            builder.seed(seed.longValue());
        }

        if (options.containsKey(VolcengineConstants.OPTIONS_CAMERA_FIXED)) {
            builder.cameraFixed(Boolean.parseBoolean(String.valueOf(options.get(VolcengineConstants.OPTIONS_CAMERA_FIXED))));
        }

        if (options.containsKey(VolcengineConstants.OPTIONS_CALLBACK_URL)) {
            builder.callbackUrl(String.valueOf(options.get(VolcengineConstants.OPTIONS_CALLBACK_URL)));
        }
    }

    /**
     * 解析 Seedance 音画同出开关：能力门禁与顶层 audio / options.generate_audio 的归一
     * 已由 ModelCapabilityValidator 在建任务前统一完成，此处只读归一结果，不重复解析 capability_json。
     */
    private Boolean resolveGenerateAudio(MediaVideoGenerateRequest request) {
        if (request == null) {
            return null;
        }
        if (request.getAudio() != null) {
            return request.getAudio();
        }
        Map<String, Object> options = request.getOptions();
        if (options == null || !options.containsKey(VolcengineConstants.OPTIONS_GENERATE_AUDIO)) {
            return null;
        }
        return Boolean.parseBoolean(String.valueOf(options.get(VolcengineConstants.OPTIONS_GENERATE_AUDIO)));
    }

    private String normalizeStatus(String status) {
        if (StringUtils.isBlank(status)) {
            return VolcengineConstants.TASK_STATUS_PROCESSING;
        }
        String lower = status.toLowerCase();
        if (VolcengineConstants.VENDOR_STATUS_SUCCEEDED.equals(lower)) {
            return VolcengineConstants.TASK_STATUS_SUCCEEDED;
        }
        if (VolcengineConstants.VENDOR_STATUS_FAILED.equals(lower)) {
            return VolcengineConstants.TASK_STATUS_FAILED;
        }
        return VolcengineConstants.TASK_STATUS_PROCESSING;
    }

    private boolean isKnownStatus(String status) {
        if (StringUtils.isBlank(status)) {
            return false;
        }
        String lower = status.trim().toLowerCase();
        return VolcengineConstants.VENDOR_STATUS_SUCCEEDED.equals(lower)
                || VolcengineConstants.VENDOR_STATUS_FAILED.equals(lower)
                || "queued".equals(lower)
                || "running".equals(lower);
    }

    private boolean isTerminalStatus(String status) {
        String lower = StringUtils.defaultString(status).trim().toLowerCase();
        return VolcengineConstants.VENDOR_STATUS_SUCCEEDED.equals(lower)
                || VolcengineConstants.VENDOR_STATUS_FAILED.equals(lower);
    }

    private String resolveEffectiveModel(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        // 解析真实上游模型名：展示码 model_code 与真实模型名 real_model_code 解耦
        String resolved = com.aid.media.provider.ModelCodeResolver.resolveUpstreamModel(modelConfig,
                request == null ? null : request.getModelName());
        if (StringUtils.isNotBlank(resolved)) {
            return resolved;
        }
        return VolcengineConstants.DEFAULT_VIDEO_MODEL;
    }

    private String getOptionString(Map<String, Object> options, String key) {
        if (options == null || !options.containsKey(key)) {
            return null;
        }
        Object value = options.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private List<String> getOptionStringList(Map<String, Object> options, String key) {
        if (options == null || !options.containsKey(key)) {
            return Collections.emptyList();
        }
        Object value = options.get(key);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null && StringUtils.isNotBlank(String.valueOf(item))) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return Collections.emptyList();
    }
}
