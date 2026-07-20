package com.aid.media.provider.impl;

import com.aid.media.provider.ModelIoDump; // 【临时调试】入参/出参落盘工具

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.common.constant.HttpConstants;
import com.aid.common.oss.entity.UploadResult;
import com.aid.common.oss.factory.OssFactory;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.OpenAiImageConstants;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.provider.ImageProviderClient;
import com.aid.media.provider.ModelCodeResolver;
import com.aid.media.provider.ReferenceImageLimiter;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.provider.SubmitTimeoutResolver;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI GPT Image 图片生成 Provider（gpt-image-2 等），严格遵循官方 Images API。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class OpenAiImageProviderClient implements ImageProviderClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String protocol() {
        return OpenAiImageConstants.PROTOCOL_IMAGE;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        return providerCode != null
                && OpenAiImageConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }
    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        ReferencePromptSanitizer.sanitizeInPlace(request);

        String apiKey = modelConfig != null ? modelConfig.getApiKey() : null;
        if (StringUtils.isBlank(apiKey)) {
            log.error("OpenAI 图片提交失败: apiKey 为空, modelCode={}",
                    modelConfig == null ? null : modelConfig.getModelCode());
            return ProviderSubmitResult.builder().rawResponse(OpenAiImageConstants.ERROR_API_KEY_EMPTY).build();
        }
        String base = normalizeBaseUrl(modelConfig.getBaseUrl());
        if (base == null) {
            return ProviderSubmitResult.builder().rawResponse(OpenAiImageConstants.ERROR_BASE_URL_EMPTY).build();
        }

        String model = resolveEffectiveModel(modelConfig, request);
        List<String> images = resolveReferenceImages(request, modelConfig);
        boolean edit = !images.isEmpty();

        Map<String, Object> body = buildRequestBody(model, request, images, edit, modelConfig);
        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            log.error("OpenAI 图片请求体序列化失败, model={}", model, e);
            return ProviderSubmitResult.builder().rawResponse(OpenAiImageConstants.ERROR_SERIALIZE).build();
        }

        String url = base + (edit ? OpenAiImageConstants.PATH_EDITS : resolveGenerationsPath(modelConfig));
        log.info("OpenAI 图片提交, url={}, model={}, edit={}, refImageCount={}, n={}, size={}, quality={}, prompt={}",
                url, model, edit, images.size(), body.get(OpenAiImageConstants.JSON_N),
                body.get(OpenAiImageConstants.JSON_SIZE), body.get(OpenAiImageConstants.JSON_QUALITY),
                StringUtils.abbreviate(request == null ? null : request.getPrompt(),
                        OpenAiImageConstants.LOG_RESPONSE_SNIPPET_MAX));

        String respBody;
        try {
            // 单次 HTTP 超时按模型 capability_json.submitTimeoutSeconds 取值，缺省回退官方 300s 常量。
            int timeoutMs = SubmitTimeoutResolver.resolveMs(modelConfig, OpenAiImageConstants.IMAGE_TIMEOUT_MS);
            respBody = doPost(url, apiKey, modelConfig.getAuthHeader(), modelConfig.getAuthPrefix(), json, timeoutMs);
        } catch (Exception e) {
            log.error("OpenAI 图片提交网络异常, model={}, error={}", model, e.getMessage(), e);
            return ProviderSubmitResult.builder().rawResponse(e.getMessage()).build();
        }

        try {
            // 按请求的 output_format 推导 OSS 落库后缀/MIME（缺省 png），保证文件后缀与实际编码一致
            String outputFormat = getStringOption(request == null ? null : request.getOptions(), "output_format");
            return parseImageResponse(respBody, model, outputFormat);
        } catch (Exception e) {
            log.error("OpenAI 图片响应解析失败, model={}", model, e);
            return ProviderSubmitResult.builder()
                    .rawResponse(StringUtils.abbreviate(respBody, OpenAiImageConstants.LOG_RESPONSE_SNIPPET_MAX))
                    .build();
        }
    }

    /**
     * OpenAI 图片生成为同步接口，无需异步轮询，兜底返回 SUCCEEDED。
     */
    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        return ProviderTaskResult.builder().status(OpenAiImageConstants.TASK_STATUS_SUCCEEDED).build();
    }
    /**
     * 组装官方 JSON 请求体。
     * <pre>
     * 文生图： { "model":"gpt-image-2", "prompt":"...", "n":1, "size":"1024x1024", "quality":"high" }
     * 图生图： { ...同上..., "images":[ {"image_url":"https://.../a.png"}, ... ] }
     * </pre>
     * 不传 seed（官方无此入参）。size/quality 仅在解析出有效值时下发，否则交由上游默认。
     */
    private Map<String, Object> buildRequestBody(String model, MediaImageGenerateRequest request,
                                                 List<String> images, boolean edit, AiModelConfigVo modelConfig) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(OpenAiImageConstants.JSON_MODEL, model);
        body.put(OpenAiImageConstants.JSON_PROMPT, request == null ? "" : StringUtils.defaultString(request.getPrompt()));
        body.put(OpenAiImageConstants.JSON_N, resolveImageCount(request, modelConfig));

        String size = resolveSize(request);
        if (StringUtils.isNotBlank(size)) {
            body.put(OpenAiImageConstants.JSON_SIZE, size);
        }
        String quality = getStringOption(request == null ? null : request.getOptions(), "quality");
        if (StringUtils.isNotBlank(quality)) {
            body.put(OpenAiImageConstants.JSON_QUALITY, quality.trim());
        }
        // 可选：背景 / 输出格式（仅在业务显式指定时下发）
        String background = getStringOption(request == null ? null : request.getOptions(), "background");
        if (StringUtils.isNotBlank(background)) {
            body.put(OpenAiImageConstants.JSON_BACKGROUND, background.trim());
        }
        // 输出格式仅允许 png / jpeg（jpg 归一为 jpeg）；其余（含 webp）不下发，由上游默认 png，
        // 与落库后缀(png/jpg)保持一致
        String outputFormat = getStringOption(request == null ? null : request.getOptions(), "output_format");
        if (StringUtils.isNotBlank(outputFormat)) {
            String fmt = outputFormat.trim().toLowerCase();
            if (OpenAiImageConstants.OUTPUT_FORMAT_JPEG.equals(fmt) || OpenAiImageConstants.OUTPUT_FORMAT_JPG.equals(fmt)) {
                body.put(OpenAiImageConstants.JSON_OUTPUT_FORMAT, OpenAiImageConstants.OUTPUT_FORMAT_JPEG);
            } else if (OpenAiImageConstants.OUTPUT_FORMAT_PNG.equals(fmt)) {
                body.put(OpenAiImageConstants.JSON_OUTPUT_FORMAT, OpenAiImageConstants.OUTPUT_FORMAT_PNG);
            }
            // 其余格式忽略，走上游默认 png
        }

        // 图生图：参考图 images[].image_url，官方支持「完整 URL 或 base64 data URL」。
        // 默认走远程 URL（体积小、由上游回源）；当模型 capability 开启 base64 传图时（网关无法回源业务 CDN 的场景），
        // 下载转 data URI 内联下发，规避上游"下载图片 404"。
        if (edit) {
            List<String> effectiveImages = images;
            if (com.aid.media.provider.ReferenceImageBase64Support.isBase64Enabled(modelConfig)) {
                effectiveImages = com.aid.media.provider.ReferenceImageBase64Support.toDataUris(images);
                log.info("gpt-image 参考图按 base64 内联下发, model={}, count={}", model, effectiveImages.size());
            }
            List<Map<String, Object>> imageRefs = new ArrayList<>();
            for (String imgUrl : effectiveImages) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put(OpenAiImageConstants.JSON_IMAGE_URL, imgUrl);
                imageRefs.add(ref);
            }
            body.put(OpenAiImageConstants.JSON_IMAGES, imageRefs);
        }
        return body;
    }

    /**
     * 合并参考图 URL：referenceImageUrl + options.referenceImages + options.images，
     * 统一读 capability_json.maxReferenceImages 截断（缺省回退官方 16 张）。
     */
    private List<String> resolveReferenceImages(MediaImageGenerateRequest request, AiModelConfigVo modelConfig) {
        if (request == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        if (StringUtils.isNotBlank(request.getReferenceImageUrl())) {
            result.add(request.getReferenceImageUrl());
        }
        Map<String, Object> options = request.getOptions();
        if (options != null) {
            addUrls(result, options.get("referenceImages"));
            addUrls(result, options.get("images"));
        }
        return ReferenceImageLimiter.limit(result, modelConfig, OpenAiImageConstants.MAX_REFERENCE_IMAGES, "OpenAI");
    }

    @SuppressWarnings("unchecked")
    private void addUrls(List<String> target, Object value) {
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                if (item instanceof String && StringUtils.isNotBlank((String) item) && !target.contains(item)) {
                    target.add((String) item);
                }
            }
        }
    }

    /**
     * 解析下发上游的出图张数 n。
     */
    private int resolveImageCount(MediaImageGenerateRequest request, AiModelConfigVo modelConfig) {
        Integer n = request == null ? null : request.getExpectedImageCount();
        int count = (n == null || n < 1) ? 1 : n;
        // 模型配置上限优先（max_output_count>0 时生效）
        Integer configuredMax = modelConfig == null ? null : modelConfig.getMaxOutputCount();
        if (configuredMax != null && configuredMax > 0) {
            count = Math.min(count, configuredMax);
        }
        // 官方硬上限兜底
        return Math.min(count, OpenAiImageConstants.MAX_IMAGE_COUNT);
    }

    /**
     * 解析输出尺寸为官方 "宽x高" 字符串或 "auto"。
     */
    private String resolveSize(MediaImageGenerateRequest request) {
        if (request == null) {
            return OpenAiImageConstants.DEFAULT_IMAGE_SIZE;
        }
        Map<String, Object> options = request.getOptions();
        String optSize = getStringOption(options, "size");
        if (StringUtils.isNotBlank(optSize) && "auto".equalsIgnoreCase(optSize.trim())) {
            return "auto";
        }
        String normalized = normalizeSize(optSize);
        if (normalized != null) {
            return normalized;
        }
        normalized = normalizeSize(request.getSize());
        if (normalized != null) {
            return normalized;
        }
        String ratio = getStringOption(options, "aspect_ratio");
        if (StringUtils.isBlank(ratio)) {
            ratio = getStringOption(options, "aspectRatio");
        }
        String byRatio = sizeFromAspectRatio(ratio);
        if (byRatio != null) {
            return byRatio;
        }
        return OpenAiImageConstants.DEFAULT_IMAGE_SIZE;
    }

    /** 把 "1024*1024"/"1024x1024"/"1024×1024" 规范化为 "1024x1024"；非法或档位返回 null。 */
    private String normalizeSize(String size) {
        if (StringUtils.isBlank(size)) {
            return null;
        }
        String[] dims = size.trim().split("[*xX×]");
        if (dims.length != 2) {
            return null;
        }
        try {
            int w = Integer.parseInt(dims[0].trim());
            int h = Integer.parseInt(dims[1].trim());
            if (w <= 0 || h <= 0) {
                return null;
            }
            return w + "x" + h;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 由常见比例推断官方合法尺寸（宽高均被 16 整除、比例合规、总像素在限制内），未知返回 null。
     * gpt-image-2 接受任意满足约束的分辨率：1:1→1024x1024、3:2→1536x1024、2:3→1024x1536、
     * 16:9→1536x864、9:16→864x1536。
     */
    private String sizeFromAspectRatio(String ratio) {
        if (StringUtils.isBlank(ratio)) {
            return null;
        }
        switch (ratio.trim()) {
            case "1:1":
                return "1024x1024";
            case "3:2":
                return "1536x1024";
            case "2:3":
                return "1024x1536";
            case "16:9":
                return "1536x864";
            case "9:16":
                return "864x1536";
            default:
                return null;
        }
    }
    /**
     * 解析官方 Images 响应：{@code { "data": [ { "b64_json": "...", "url": "..." } ] }}。
     * b64_json 优先（解码落 OSS）；GPT image 恒回 b64，url 仅作网关变体兜底。
     */
    private ProviderSubmitResult parseImageResponse(String respBody, String model, String outputFormat) throws Exception {
        if (StringUtils.isBlank(respBody)) {
            log.error("OpenAI 图片响应为空, model={}", model);
            return ProviderSubmitResult.builder().rawResponse(OpenAiImageConstants.ERROR_NO_IMAGE).build();
        }
        JsonNode root = MAPPER.readTree(respBody);

        // 上游错误透传（OpenAI 风格 error.message）
        JsonNode errorNode = root.path(OpenAiImageConstants.JSON_ERROR);
        if (errorNode.isObject() && !errorNode.isNull()) {
            String errMsg = errorNode.path(OpenAiImageConstants.JSON_MESSAGE).asText("未知错误");
            log.error("OpenAI 图片上游错误, model={}, error={}", model, errMsg);
            return ProviderSubmitResult.builder()
                    .rawResponse(StringUtils.abbreviate(respBody, OpenAiImageConstants.LOG_RESPONSE_SNIPPET_MAX)).build();
        }

        JsonNode data = root.path(OpenAiImageConstants.JSON_DATA);
        if (!data.isArray() || data.isEmpty()) {
            log.error("OpenAI 图片未返回 data, model={}, resp={}", model,
                    StringUtils.abbreviate(respBody, OpenAiImageConstants.LOG_RESPONSE_SNIPPET_MAX));
            return ProviderSubmitResult.builder().rawResponse(OpenAiImageConstants.ERROR_NO_IMAGE).build();
        }

        List<String> ossUrls = new ArrayList<>();    // b64 落库模式
        List<String> directUrls = new ArrayList<>(); // url 兜底模式
        for (JsonNode item : data) {
            String b64 = item.path(OpenAiImageConstants.JSON_B64).asText(null);
            if (StringUtils.isNotBlank(b64)) {
                String ossUrl = uploadBase64ToOss(b64, outputFormat);
                if (StringUtils.isNotBlank(ossUrl)) {
                    ossUrls.add(ossUrl);
                }
                continue;
            }
            String directUrl = item.path(OpenAiImageConstants.JSON_URL).asText(null);
            if (StringUtils.isNotBlank(directUrl)) {
                directUrls.add(directUrl);
            }
        }

        // 解析 usage（gpt-image 为 TOKEN 计费族，按 provider 真实 token 结算）
        Map<String, Object> usage = parseUsage(root);

        if (!ossUrls.isEmpty()) {
            log.info("OpenAI 图片生成成功(b64→OSS), model={}, imageCount={}, input_tokens={}, output_tokens={}",
                    model, ossUrls.size(), usage.get("input_tokens"), usage.get("output_tokens"));
            return ProviderSubmitResult.builder()
                    .ossUrl(ossUrls.get(0))
                    .resultUrls(ossUrls)
                    .resultCount(ossUrls.size())
                    .usage(usage)
                    .rawResponse(StringUtils.abbreviate(respBody, OpenAiImageConstants.LOG_RESPONSE_SNIPPET_MAX))
                    .build();
        }
        if (!directUrls.isEmpty()) {
            log.info("OpenAI 图片生成成功(url兜底), model={}, imageCount={}, input_tokens={}, output_tokens={}",
                    model, directUrls.size(), usage.get("input_tokens"), usage.get("output_tokens"));
            return ProviderSubmitResult.builder()
                    .directUrl(directUrls.get(0))
                    .resultUrls(directUrls)
                    .resultCount(directUrls.size())
                    .usage(usage)
                    .rawResponse(StringUtils.abbreviate(respBody, OpenAiImageConstants.LOG_RESPONSE_SNIPPET_MAX))
                    .build();
        }

        log.error("OpenAI 图片生成未返回图片, model={}, resp={}", model,
                StringUtils.abbreviate(respBody, OpenAiImageConstants.LOG_RESPONSE_SNIPPET_MAX));
        return ProviderSubmitResult.builder().rawResponse(OpenAiImageConstants.ERROR_NO_IMAGE).build();
    }

    /**
     * 解析官方 usage 对象 → 统一口径 token usage（input_tokens/output_tokens/total_tokens）。
     * OpenAI Images 响应：{@code usage:{input_tokens, output_tokens, total_tokens, input_tokens_details,...}}。
     * gpt-image 为 TOKEN 计费族，结算按此真实 token 重算（缺失返回空 Map，退化为按预扣结算）。
     */
    private Map<String, Object> parseUsage(JsonNode root) {
        Map<String, Object> usage = new LinkedHashMap<>();
        JsonNode meta = root.path("usage");
        if (meta.isMissingNode() || meta.isNull()) {
            return usage;
        }
        int inputTokens = meta.path("input_tokens").asInt(0);
        int outputTokens = meta.path("output_tokens").asInt(0);
        int totalTokens = meta.path("total_tokens").asInt(0);
        if (totalTokens <= 0) {
            totalTokens = inputTokens + outputTokens;
        }
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", outputTokens);
        usage.put("total_tokens", totalTokens);
        // 兼容文本 LLM 口径
        usage.put("prompt_tokens", inputTokens);
        usage.put("completion_tokens", outputTokens);
        return usage;
    }

    /** base64 解码后上传 OSS，返回可访问 URL。按 output_format 推导后缀/MIME（缺省 png）。 */
    private String uploadBase64ToOss(String base64Data, String outputFormat) {
        try {
            String pure = base64Data;
            int comma = base64Data.indexOf(',');
            if (base64Data.startsWith("data:") && comma > 0) {
                pure = base64Data.substring(comma + 1);
            }
            byte[] imageBytes = Base64.getDecoder().decode(pure);
            if (imageBytes.length == 0) {
                return null;
            }
            // 后缀/MIME 仅允许 png 或 jpg：jpeg/jpg → .jpg，其余（含 webp/png/空）一律 png
            String suffix = OpenAiImageConstants.IMAGE_SUFFIX_PNG;
            String contentType = OpenAiImageConstants.IMAGE_CONTENT_TYPE_PNG;
            if (StringUtils.isNotBlank(outputFormat)) {
                String fmt = outputFormat.trim().toLowerCase();
                if (OpenAiImageConstants.OUTPUT_FORMAT_JPEG.equals(fmt)
                        || OpenAiImageConstants.OUTPUT_FORMAT_JPG.equals(fmt)) {
                    suffix = OpenAiImageConstants.IMAGE_SUFFIX_JPG;
                    contentType = OpenAiImageConstants.IMAGE_CONTENT_TYPE_JPEG;
                }
            }
            UploadResult uploadResult = OssFactory.instance()
                    .uploadSuffix(imageBytes, suffix, contentType);
            return uploadResult.getUrl();
        } catch (Exception e) {
            log.error("OpenAI 图片 OSS 上传失败, error={}", e.getMessage(), e);
            return null;
        }
    }
    private String doPost(String url, String apiKey, String authHeader, String authPrefix, String json, int timeoutMs) {
        String headerName = StringUtils.isNotBlank(authHeader) ? authHeader : HttpConstants.HEADER_AUTHORIZATION;
        String prefix = authPrefix != null ? authPrefix : HttpConstants.AUTH_BEARER_PREFIX;
        ModelIoDump.req(url, json); // 【临时调试】记录下发上游入参
        try (HttpResponse response = HttpRequest.post(url)
                .header(headerName, prefix + apiKey)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
                .body(json)
                .timeout(timeoutMs)
                .execute()) {
            return ModelIoDump.resp(url, response.body()); // 【临时调试】记录上游出参
        }
    }

    /** 解析下发上游的模型名：经 {@link ModelCodeResolver} 解析，兜底默认模型。 */
    private String resolveEffectiveModel(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        String resolved = ModelCodeResolver.resolveUpstreamModel(modelConfig,
                request == null ? null : request.getModelName());
        return StringUtils.isNotBlank(resolved) ? resolved : OpenAiImageConstants.DEFAULT_IMAGE_MODEL;
    }

    /** 规范化 base_url：去尾部斜杠；为空返回 null。 */
    private String normalizeBaseUrl(String baseUrl) {
        if (StringUtils.isBlank(baseUrl)) {
            log.error("OpenAI 图片 baseUrl 为空，请在 aid_ai_provider 表配置 base_url");
            return null;
        }
        String base = baseUrl.trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** 文生图路径：优先用模型配置的 api_suffix，否则官方默认 /v1/images/generations。 */
    private String resolveGenerationsPath(AiModelConfigVo modelConfig) {
        String suffix = modelConfig == null ? null : modelConfig.getApiSuffix();
        if (StringUtils.isBlank(suffix)) {
            return OpenAiImageConstants.PATH_GENERATIONS;
        }
        String s = suffix.trim();
        return s.startsWith("/") ? s : "/" + s;
    }

    private String getStringOption(Map<String, Object> options, String key) {
        if (options == null || key == null) {
            return null;
        }
        Object val = options.get(key);
        if (val instanceof String) {
            return (String) val;
        }
        return val != null ? String.valueOf(val) : null;
    }
}
