package com.aid.media.provider.impl;

import com.aid.media.provider.ModelIoDump; // 【临时调试】入参/出参落盘工具

import cn.hutool.json.JSONUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.VolcengineConstants;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.provider.ImageProviderClient;
import com.aid.media.provider.ReferenceImageLimiter;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.provider.SubmitTimeoutResolver;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.volcengine.VolcengineServiceManager;
import com.volcengine.ark.runtime.model.images.generation.GenerateImagesRequest;
import com.volcengine.ark.runtime.model.images.generation.ImagesResponse;
import com.volcengine.ark.runtime.model.images.generation.ResponseFormat;
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
 * 火山引擎 Seedream（豆包）图片生成：基于方舟 Ark Java SDK，同步返回图片 URL。
 */
@Slf4j
@Component
public class VolcengineImageProviderClient implements ImageProviderClient {

    /** Seedream 5.0 Pro 官方默认参考图上限 10；Lite/4.x 为 14。运营可在 capability_json.maxReferenceImages 覆盖。 */
    private static final int DEFAULT_MAX_REFERENCE_IMAGES = 10;

    @Autowired
    private VolcengineServiceManager volcengineServiceManager;

    @Override
    public String protocol() {
        return VolcengineConstants.PROTOCOL_SEEDREAM_IMAGE;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        // 火山方舟 Seedream 图片：按 provider_code 精确归属
        return providerCode != null
                && VolcengineConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        ReferencePromptSanitizer.sanitizeInPlace(request);
        // 单次调用超时按模型 capability_json.submitTimeoutSeconds 取值（秒），缺省回退 SDK 默认。
        int timeoutSeconds = SubmitTimeoutResolver.resolveMs(modelConfig,
                VolcengineConstants.SDK_TIMEOUT_SECONDS * 1000) / 1000;
        ArkService service = volcengineServiceManager.getService(
                modelConfig.getApiKey(), modelConfig.getBaseUrl(), timeoutSeconds);
        String effectiveModel = resolveEffectiveModel(modelConfig, request);
        GenerateImagesRequest generateRequest = buildRequest(effectiveModel, request, modelConfig);

        log.info("Volcengine 图片生成提交(Seedream), model={}, prompt={}", effectiveModel,
                StringUtils.abbreviate(request.getPrompt(), VolcengineConstants.LOG_PROMPT_ABBREV_MAX));

        ImagesResponse response;
        ModelIoDump.req(effectiveModel, JSONUtil.toJsonStr(generateRequest)); // 【临时调试】记录下发上游入参(SDK)
        try {
            response = service.generateImages(generateRequest);
        } catch (Exception e) {
            log.error("Volcengine 图片生成调用失败, model={}", effectiveModel, e);
            return ProviderSubmitResult.builder()
                    .rawResponse(e.getMessage())
                    .build();
        }
        ModelIoDump.resp(effectiveModel, JSONUtil.toJsonStr(response)); // 【临时调试】记录上游出参(SDK)

        // Seedream 返回 response.getData() 为 URL 列表，按实际张数全部采集，供图片计费按量结算。
        List<String> resultUrls = new ArrayList<>();
        if (response.getData() != null) {
            response.getData().forEach(item -> {
                if (item != null && StringUtils.isNotBlank(item.getUrl())) {
                    resultUrls.add(item.getUrl());
                }
            });
        }
        String directUrl = resultUrls.isEmpty() ? null : resultUrls.get(0);

        if (StringUtils.isBlank(directUrl) && response.getError() != null) {
            log.error("Volcengine 图片生成返回错误, error={}", response.getError());
        }

        return ProviderSubmitResult.builder()
                .directUrl(directUrl)
                .resultUrls(resultUrls)
                .resultCount(resultUrls.isEmpty() ? null : resultUrls.size())
                .rawResponse(JSONUtil.toJsonStr(response))
                .build();
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        // Seedream 为同步提交，业务上不会轮询；此处仅兜底。
        return ProviderTaskResult.builder()
                .status(VolcengineConstants.TASK_STATUS_SUCCEEDED)
                .build();
    }

    /**
     * 组装方舟 Seedream 请求：支持文生图、单图参考、多图融合（image 为 URL 列表）。
     */
    private GenerateImagesRequest buildRequest(String model, MediaImageGenerateRequest request,
                                               AiModelConfigVo modelConfig) {
        // 业务含义：按约定合并「直连 images」「分镜 referenceImages」「单字段 referenceImageUrl」三处来源，保证分镜多资产参考能落到方舟多图接口。
        List<String> imageInputs = resolveSeedreamImageInputs(request, modelConfig);
        // Base64 传图开关：官方 image 支持 data URI（data:image/<格式>;base64,...），启用时下载转内联下发
        if (com.aid.media.provider.ReferenceImageBase64Support.isBase64Enabled(modelConfig)
                && !imageInputs.isEmpty()) {
            imageInputs = com.aid.media.provider.ReferenceImageBase64Support.toDataUris(imageInputs);
            log.info("Seedream 参考图按 base64 内联下发, count={}", imageInputs.size());
        }

        GenerateImagesRequest.Builder builder = GenerateImagesRequest.builder()
                .model(model)
                .prompt(request.getPrompt())
                .size(StringUtils.defaultIfBlank(request.getSize(), VolcengineConstants.DEFAULT_IMAGE_SIZE))
                .outputFormat(VolcengineConstants.DEFAULT_OUTPUT_FORMAT)
                .responseFormat(ResponseFormat.Url)
                .stream(VolcengineConstants.DEFAULT_STREAM)
                .watermark(VolcengineConstants.DEFAULT_WATERMARK);

        // 业务含义：无参考图时不传 image，走纯文生图。
        if (!imageInputs.isEmpty()) {
            if (imageInputs.size() == 1) {
                // 业务含义：单图时走 SDK 单字符串重载，与历史调用兼容。
                builder.image(imageInputs.get(0));
            } else {
                // 业务含义：多图时走 URL 数组，对应官方「多图输入、单图输出」融合能力。
                builder.image(imageInputs);
            }
        }

        // 业务含义：厂商扩展字段（尺寸覆盖、水印、组图策略等）仍从 options 透传；image 列表已在上方一次性写入，避免与 applyOptions 重复冲突。
        applyOptions(builder, request.getOptions());

        // 业务含义：多图融合场景官方示例将 sequential_image_generation 设为 disabled，避免误走组图/连续出图；若调用方已在 options 中显式配置组图策略，则尊重调用方，不覆盖。
        Map<String, Object> options = request.getOptions();
        if (imageInputs.size() > 1
                && (options == null || !options.containsKey(VolcengineConstants.JSON_SEQUENTIAL_IMAGE_GENERATION))) {
            builder.sequentialImageGeneration("disabled");
        }

        return builder.build();
    }

    /**
     * 解析 Seedream 参考图 URL 列表（顺序影响「图1/图2」类提示词语义）。
     */
    private List<String> resolveSeedreamImageInputs(MediaImageGenerateRequest request,
                                                    AiModelConfigVo modelConfig) {
        Map<String, Object> options = request == null ? null : request.getOptions();
        // 业务含义：直连媒体接口可在 options.images 中传 URL 列表，优先级最高。
        List<String> explicitImages = extractNonBlankStringList(options, VolcengineConstants.JSON_IMAGES);
        if (!explicitImages.isEmpty()) {
            return ReferenceImageLimiter.limit(explicitImages, modelConfig, DEFAULT_MAX_REFERENCE_IMAGES, "Volcengine");
        }
        // 业务含义：分镜生图链路将多资产参考写入 options.referenceImages，与视频侧字段名一致。
        List<String> bizReferenceImages = extractNonBlankStringList(options, VolcengineConstants.OPTIONS_REFERENCE_IMAGES);
        if (!bizReferenceImages.isEmpty()) {
            return ReferenceImageLimiter.limit(bizReferenceImages, modelConfig, DEFAULT_MAX_REFERENCE_IMAGES, "Volcengine");
        }
        // 业务含义：仅有一张时业务常只填 referenceImageUrl，保持向后兼容。
        if (request != null && StringUtils.isNotBlank(request.getReferenceImageUrl())) {
            return ReferenceImageLimiter.limit(
                    Collections.singletonList(request.getReferenceImageUrl()),
                    modelConfig, DEFAULT_MAX_REFERENCE_IMAGES, "Volcengine");
        }
        return Collections.emptyList();
    }

    /**
     * 从 options 中读取字符串列表，过滤空串，保持列表顺序。
     */
    private List<String> extractNonBlankStringList(Map<String, Object> options, String key) {
        if (options == null || key == null || !options.containsKey(key)) {
            return Collections.emptyList();
        }
        Object raw = options.get(key);
        if (!(raw instanceof List<?> rawList)) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (Object item : rawList) {
            if (item != null && StringUtils.isNotBlank(String.valueOf(item))) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    /**
     * 将 options 中除「参考图列表」外的 Seedream 字段应用到 Builder（参考图仅在 buildRequest 中统一设置）。
     */
    private void applyOptions(GenerateImagesRequest.Builder builder, Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return;
        }

        if (options.containsKey(VolcengineConstants.JSON_SEQUENTIAL_IMAGE_GENERATION)) {
            builder.sequentialImageGeneration(String.valueOf(options.get(VolcengineConstants.JSON_SEQUENTIAL_IMAGE_GENERATION)));
        }
        if (options.get(VolcengineConstants.JSON_SEQUENTIAL_IMAGE_GENERATION_OPTIONS) instanceof Map<?, ?> seqOpts) {
            GenerateImagesRequest.SequentialImageGenerationOptions opts =
                    new GenerateImagesRequest.SequentialImageGenerationOptions();
            Object maxImages = seqOpts.get(VolcengineConstants.JSON_MAX_IMAGES);
            if (maxImages instanceof Number num) {
                opts.setMaxImages(num.intValue());
            }
            builder.sequentialImageGenerationOptions(opts);
        }

        if (options.containsKey(VolcengineConstants.JSON_SIZE)) {
            builder.size(String.valueOf(options.get(VolcengineConstants.JSON_SIZE)));
        }

        if (options.containsKey(VolcengineConstants.JSON_WATERMARK)) {
            builder.watermark(Boolean.parseBoolean(String.valueOf(options.get(VolcengineConstants.JSON_WATERMARK))));
        }

        if (options.containsKey(VolcengineConstants.JSON_OUTPUT_FORMAT)) {
            builder.outputFormat(String.valueOf(options.get(VolcengineConstants.JSON_OUTPUT_FORMAT)));
        }

        if (options.get(VolcengineConstants.JSON_SEED) instanceof Number seed) {
            builder.seed(seed.intValue());
        }

        if (options.get(VolcengineConstants.JSON_GUIDANCE_SCALE) instanceof Number gs) {
            builder.guidanceScale(gs.doubleValue());
        }
    }

    private String resolveEffectiveModel(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        // 解析真实上游模型名：展示码 model_code 与真实模型名 real_model_code 解耦
        String resolved = com.aid.media.provider.ModelCodeResolver.resolveUpstreamModel(modelConfig,
                request == null ? null : request.getModelName());
        if (StringUtils.isNotBlank(resolved)) {
            return resolved;
        }
        return VolcengineConstants.DEFAULT_IMAGE_MODEL;
    }
}
