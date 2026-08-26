package com.aid.media.provider.impl;


import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.aid.common.constant.HttpConstants;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.VolcengineConstants;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.provider.ImageProviderClient;
import com.aid.media.provider.ReferenceImageLimiter;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.provider.SubmitTimeoutResolver;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.volcengine.ark.runtime.model.images.generation.GenerateImagesRequest;
import com.volcengine.ark.runtime.model.images.generation.ImagesResponse;
import com.volcengine.ark.runtime.model.images.generation.ResponseFormat;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 火山引擎 Seedream 图片生成，复用官方 DTO 映射并按配置的 HTTP 路径提交。
 */
@Slf4j
@Component
public class VolcengineImageProviderClient implements ImageProviderClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Seedream 5.0 Pro 官方默认参考图上限 10；Lite/4.x 为 14。运营可在 capability_json.maxReferenceImages 覆盖。 */
    private static final int DEFAULT_MAX_REFERENCE_IMAGES = 10;

    /** Seedream 5.0 Pro 官方 1K/2K 推荐尺寸，用于把平台“档位 + 比例”转换为单一 size 字段。 */
    private static final Map<String, String> SEEDREAM_1K_SIZES = createSeedream1KSizes();
    private static final Map<String, String> SEEDREAM_2K_SIZES = createSeedream2KSizes();

    @Override
    public String protocol() {
        return VolcengineConstants.PROTOCOL_SEEDREAM_IMAGE;
    }

    @Override
    public Integer fallbackMaxReferenceImages(AiModelConfigVo modelConfig) {
        return DEFAULT_MAX_REFERENCE_IMAGES;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        // 火山方舟 Seedream 图片：按 provider_code 精确归属
        return providerCode != null
                && VolcengineConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        ReferencePromptSanitizer.sanitizeInPlace(request,
                ReferenceImageLimiter.resolveMax(modelConfig, DEFAULT_MAX_REFERENCE_IMAGES));
        // 单次调用超时按模型 capability_json.submitTimeoutSeconds 取值（秒），缺省回退 HTTP 默认。
        int timeoutSeconds = SubmitTimeoutResolver.resolveMs(modelConfig,
                VolcengineConstants.HTTP_TIMEOUT_SECONDS * 1000) / 1000;
        String effectiveModel = resolveEffectiveModel(modelConfig, request);
        GenerateImagesRequest generateRequest = buildRequest(effectiveModel, request, modelConfig);

        log.info("Volcengine 图片生成提交(Seedream), model={}, promptLen={}", effectiveModel,
                StringUtils.length(request.getPrompt()));

        ImagesResponse response;
        String raw;
        try {
            HttpResult httpResponse = doPost(buildSubmitUrl(modelConfig), modelConfig.getApiKey(),
                    OBJECT_MAPPER.writeValueAsString(generateRequest), timeoutSeconds);
            raw = httpResponse.body();
            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                log.error("Volcengine 图片生成调用失败, model={}, httpStatus={}, responseLength={}",
                        effectiveModel, httpResponse.statusCode(), StringUtils.length(raw));
                return ProviderSubmitResult.builder().rawResponse(raw).build();
            }
            response = OBJECT_MAPPER.readValue(raw, ImagesResponse.class);
        } catch (Exception e) {
            log.error("Volcengine 图片生成调用失败, model={}", effectiveModel, e);
            return ProviderSubmitResult.builder()
                    .rawResponse(e.getMessage())
                    .build();
        }
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
                .rawResponse(raw)
                .build();
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        // Seedream 为同步提交，没有可供轮询的官方任务状态。
        return ProviderTaskResult.builder()
                .status("PROCESSING")
                .errorMessage("同步模型无上游查询状态")
                .querySuccessful(Boolean.FALSE)
                .terminalConfirmed(Boolean.FALSE)
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
                .size(resolveSeedreamSize(request))
                .outputFormat(VolcengineConstants.DEFAULT_OUTPUT_FORMAT)
                .responseFormat(ResponseFormat.Url)
                .stream(VolcengineConstants.DEFAULT_STREAM)
                .watermark(VolcengineConstants.DEFAULT_WATERMARK);

        // 业务含义：无参考图时不传 image，走纯文生图。
        if (!imageInputs.isEmpty()) {
            if (imageInputs.size() == 1) {
                // 业务含义：单图时使用单字符串字段，与历史请求体兼容。
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

    /**
     * Seedream 协议只有 size，没有独立比例字段；显式像素原样下发，档位则按比例映射官方推荐宽高。
     */
    private String resolveSeedreamSize(MediaImageGenerateRequest request) {
        Map<String, Object> options = request == null ? null : request.getOptions();
        String size = request == null ? null : request.getSize();
        if (options != null && options.get(VolcengineConstants.JSON_SIZE) != null
                && StringUtils.isNotBlank(String.valueOf(options.get(VolcengineConstants.JSON_SIZE)))) {
            size = String.valueOf(options.get(VolcengineConstants.JSON_SIZE)).trim();
        }
        size = StringUtils.defaultIfBlank(size, VolcengineConstants.DEFAULT_IMAGE_SIZE).trim();
        if (size.contains("*") || size.contains("×") || size.toLowerCase(Locale.ROOT).contains("x")) {
            return size.replace('*', 'x').replace('×', 'x').replace('X', 'x');
        }
        String ratio = readAspectRatio(options);
        if (StringUtils.isBlank(ratio)) {
            return size;
        }
        Map<String, String> table = "1K".equalsIgnoreCase(size) ? SEEDREAM_1K_SIZES
                : ("2K".equalsIgnoreCase(size) ? SEEDREAM_2K_SIZES : Collections.emptyMap());
        String explicitSize = table.get(ratio);
        if (StringUtils.isBlank(explicitSize)) {
            log.warn("Seedream 图片尺寸映射未命中, size={}, aspectRatio={}", size, ratio);
            return size;
        }
        log.info("Seedream 图片翻译 size={} + aspectRatio={} -> size={}", size, ratio, explicitSize);
        return explicitSize;
    }

    /** 读取平台比例键。 */
    private String readAspectRatio(Map<String, Object> options) {
        if (options == null) {
            return null;
        }
        Object value = options.get("aspect_ratio");
        if (value == null) {
            value = options.get("aspectRatio");
        }
        return value == null ? null : StringUtils.trimToNull(String.valueOf(value));
    }

    private static Map<String, String> createSeedream1KSizes() {
        Map<String, String> sizes = new LinkedHashMap<>();
        sizes.put("1:1", "1024x1024");
        sizes.put("4:3", "1152x864");
        sizes.put("3:4", "864x1152");
        sizes.put("16:9", "1424x800");
        sizes.put("9:16", "800x1424");
        sizes.put("3:2", "1248x832");
        sizes.put("2:3", "832x1248");
        sizes.put("21:9", "1568x672");
        return Collections.unmodifiableMap(sizes);
    }

    private static Map<String, String> createSeedream2KSizes() {
        Map<String, String> sizes = new LinkedHashMap<>();
        sizes.put("1:1", "2048x2048");
        sizes.put("4:3", "2368x1776");
        sizes.put("3:4", "1776x2368");
        sizes.put("16:9", "2816x1584");
        sizes.put("9:16", "1584x2816");
        sizes.put("3:2", "2496x1664");
        sizes.put("2:3", "1664x2496");
        sizes.put("21:9", "3136x1344");
        return Collections.unmodifiableMap(sizes);
    }

    static String buildSubmitUrl(AiModelConfigVo modelConfig) {
        if (modelConfig == null) {
            throw new IllegalArgumentException("模型配置不能为空");
        }
        return ProviderEndpointUtils.buildSubmitUrl(
                modelConfig.getBaseUrl(), modelConfig.getApiSuffix());
    }

    private HttpResult doPost(String url, String apiKey, String body, int timeoutSeconds) {
        if (StringUtils.isBlank(apiKey)) {
            throw new IllegalArgumentException("方舟密钥未配置");
        }
        int timeoutMs = Math.max(timeoutSeconds, 1) * 1000;
        try (HttpResponse response = HttpRequest.post(url)
                .header(HttpConstants.HEADER_AUTHORIZATION, HttpConstants.AUTH_BEARER_PREFIX + apiKey.trim())
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
                .body(body)
                .timeout(timeoutMs)
                .execute()) {
            return new HttpResult(response.getStatus(), response.body());
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

    private record HttpResult(int statusCode, String body) {
    }
}
