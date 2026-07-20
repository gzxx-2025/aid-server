package com.aid.media.provider.impl;

import com.aid.media.provider.ModelIoDump; // 【临时调试】入参/出参落盘工具

import cn.hutool.json.JSONUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.VolcengineConstants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
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
        ReferencePromptSanitizer.sanitizeInPlace(request);
        ArkService service = volcengineServiceManager.getService(modelConfig.getApiKey(), modelConfig.getBaseUrl());
        String effectiveModel = resolveEffectiveModel(modelConfig, request);
        List<CreateContentGenerationTaskRequest.Content> contents = buildContents(request, modelConfig);
        CreateContentGenerationTaskRequest createRequest = buildCreateRequest(effectiveModel, contents, request);

        log.info("Volcengine 视频生成提交(Seedance), model={}, prompt={}", effectiveModel,
                StringUtils.abbreviate(request.getPrompt(), VolcengineConstants.LOG_PROMPT_ABBREV_MAX));

        CreateContentGenerationTaskResult result;
        ModelIoDump.req(effectiveModel, JSONUtil.toJsonStr(createRequest)); // 【临时调试】记录下发上游入参(SDK)
        try {
            result = service.createContentGenerationTask(createRequest);
        } catch (Exception e) {
            log.error("Volcengine 视频生成提交失败, model={}", effectiveModel, e);
            return ProviderSubmitResult.builder()
                    .rawResponse(e.getMessage())
                    .build();
        }

        String taskId = result.getId();
        ModelIoDump.resp(effectiveModel, JSONUtil.toJsonStr(result)); // 【临时调试】记录上游出参(SDK)
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
                    .build();
        }
        ModelIoDump.resp("ark:getContentGenerationTask:" + providerTaskId, JSONUtil.toJsonStr(resp)); // 【临时调试】记录上游出参(SDK轮询)

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

        return ProviderTaskResult.builder()
                .status(normalized)
                .resultUrl(videoUrl)
                .errorMessage(errorMessage)
                .rawResponse(JSONUtil.toJsonStr(resp))
                .build();
    }

    private List<CreateContentGenerationTaskRequest.Content> buildContents(MediaVideoGenerateRequest request,
                                                                           AiModelConfigVo modelConfig) {
        List<CreateContentGenerationTaskRequest.Content> contents = new ArrayList<>();
        Map<String, Object> options = request.getOptions();
        // Base64 传图开关：官方 image 支持 data URI（data:image/<格式>;base64,...），启用时下载转内联下发
        boolean useBase64 = com.aid.media.provider.ReferenceImageBase64Support.isBase64Enabled(modelConfig);

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
            return contents;
        }

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
            return contents;
        }

        if (StringUtils.isNotBlank(request.getImageUrl())) {
            contents.add(CreateContentGenerationTaskRequest.Content.builder()
                    .type(VolcengineConstants.CONTENT_TYPE_IMAGE_URL)
                    .imageUrl(CreateContentGenerationTaskRequest.ImageUrl.builder()
                            .url(toBase64IfEnabled(request.getImageUrl(), useBase64)).build())
                    .build());
            return contents;
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
        List<String> converted = com.aid.media.provider.ReferenceImageBase64Support
                .toDataUris(java.util.Collections.singletonList(imageUrl));
        return converted.isEmpty() ? imageUrl : converted.get(0);
    }

    private CreateContentGenerationTaskRequest buildCreateRequest(
            String model,
            List<CreateContentGenerationTaskRequest.Content> contents,
            MediaVideoGenerateRequest request) {

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

        applyVideoOptions(builder, request.getOptions());

        return builder.build();
    }

    private void applyVideoOptions(CreateContentGenerationTaskRequest.Builder builder,
                                   Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return;
        }

        if (options.containsKey(VolcengineConstants.OPTIONS_GENERATE_AUDIO)) {
            builder.generateAudio(Boolean.parseBoolean(String.valueOf(options.get(VolcengineConstants.OPTIONS_GENERATE_AUDIO))));
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

    private String normalizeStatus(String status) {
        if (StringUtils.isBlank(status)) {
            return VolcengineConstants.TASK_STATUS_PROCESSING;
        }
        String lower = status.toLowerCase();
        if (VolcengineConstants.VENDOR_STATUS_SUCCEEDED.equals(lower)) {
            return VolcengineConstants.TASK_STATUS_SUCCEEDED;
        }
        if (VolcengineConstants.VENDOR_STATUS_FAILED.equals(lower) || VolcengineConstants.VENDOR_STATUS_CANCELLED.equals(lower)) {
            return VolcengineConstants.TASK_STATUS_FAILED;
        }
        return VolcengineConstants.TASK_STATUS_PROCESSING;
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
