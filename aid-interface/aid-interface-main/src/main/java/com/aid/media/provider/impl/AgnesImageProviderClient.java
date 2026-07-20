package com.aid.media.provider.impl;

import com.aid.media.provider.ModelIoDump; // 【临时调试】入参/出参落盘工具

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.common.constant.HttpConstants;
import com.aid.common.oss.entity.UploadResult;
import com.aid.common.oss.factory.OssFactory;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.AgnesConstants;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.provider.ImageProviderClient;
import com.aid.media.provider.ModelCodeResolver;
import com.aid.media.provider.ReferenceImageLimiter;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.provider.SubmitTimeoutResolver;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.reference.ImageReferenceRenderContext;
import com.aid.media.reference.ImageReferenceRenderPlan;
import com.aid.media.reference.ImageReferenceRenderPlanner;
import com.aid.rps.resolver.StoryboardImageReferenceResolver.ResolvedImageReference;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agnes 图片生成 Provider：支持 agnes-image-2.0-flash / agnes-image-2.1-flash。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class AgnesImageProviderClient implements ImageProviderClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 富化引用清单在 options 中的 key（与 StoryboardImageGenerationServiceImpl 约定一致）。 */
    private static final String OPTION_REFERENCE_MANIFEST = "referenceManifest";

    /** 图片参考渲染策略路由器：按 providerCode 渲染参考引用（manifest 存在时使用）。 */
    @Autowired
    private ImageReferenceRenderPlanner imageReferenceRenderPlanner;

    /** 图片生成超时（毫秒）：Agnes 文档建议 60s-360s，这里取 300s */
    private static final int IMAGE_TIMEOUT_MS = 300_000;

    /** Agnes 参考图安全上限；官方未声明最大数量，运营可在 capability_json.maxReferenceImages 覆盖 */
    private static final int MAX_REFERENCE_IMAGES = 10;
    @Override
    public String protocol() {
        return AgnesConstants.PROTOCOL_IMAGE;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        // Agnes 图片：按 provider_code 精确归属
        return providerCode != null
                && AgnesConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }
    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        //    manifest 存在时由「Agnes 渲染策略」产出干净 prompt（需保留 @图片N[name] 给策略解析），故此处跳过早清洗。
        boolean hasManifest = hasReferenceManifest(request);
        if (!hasManifest) {
            ReferencePromptSanitizer.sanitizeInPlace(request);
        }
        String apiKey = modelConfig != null ? modelConfig.getApiKey() : null;
        if (StringUtils.isBlank(apiKey)) {
            log.error("Agnes 图片提交失败: apiKey 为空, modelCode={}",
                    modelConfig == null ? null : modelConfig.getModelCode());
            return ProviderSubmitResult.builder().rawResponse(AgnesConstants.ERROR_API_KEY_EMPTY).build();
        }

        String model = resolveEffectiveModel(modelConfig, request);
        validateSizeAndRatio(model, request);
        String url = buildApiUrl(modelConfig.getBaseUrl(), modelConfig.getApiSuffix());

        Map<String, Object> body = buildRequestBody(model, request, modelConfig);
        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            log.error("Agnes 图片请求体序列化失败, model={}", model, e);
            return ProviderSubmitResult.builder().rawResponse("序列化失败").build();
        }
        // hasImage / imageCount 改从 extra_body 读取（image 已从顶层移入 extra_body）
        Object extraForLog = body.get(AgnesConstants.JSON_EXTRA_BODY);
        List<?> imgsForLog = (extraForLog instanceof Map<?, ?> em
                && em.get(AgnesConstants.JSON_IMAGE) instanceof List<?> el) ? el : null;
        // 日志打"实际下发上游的 prompt"（body 内已渲染/清洗后的值），而非 request 原始带占位 prompt，
        // 便于排查"出图与参考图无关"时直接看到模型真正收到的文本。
        Object finalPromptForLog = body.get(AgnesConstants.JSON_PROMPT);
        log.info("Agnes 图片提交, url={}, model={}, hasImage={}, imageCount={}, prompt={}", url, model,
                imgsForLog != null,
                imgsForLog != null ? imgsForLog.size() : 0,
                StringUtils.abbreviate(finalPromptForLog == null ? null : finalPromptForLog.toString(),
                        AgnesConstants.LOG_RESPONSE_SNIPPET_MAX));

        String respBody;
        try {
            // 单次 HTTP 超时按模型 capability_json.submitTimeoutSeconds 取值，缺省回退本类 300s 常量。
            int timeoutMs = SubmitTimeoutResolver.resolveMs(modelConfig, IMAGE_TIMEOUT_MS);
            respBody = doPost(url, apiKey, modelConfig.getAuthHeader(), modelConfig.getAuthPrefix(), json, timeoutMs);
        } catch (Exception e) {
            log.error("Agnes 图片提交网络异常, model={}, error={}", model, e.getMessage(), e);
            return ProviderSubmitResult.builder().rawResponse(e.getMessage()).build();
        }

        try {
            return parseImageResponse(respBody, model);
        } catch (Exception e) {
            log.error("Agnes 图片响应解析失败, model={}", model, e);
            return ProviderSubmitResult.builder()
                    .rawResponse(StringUtils.abbreviate(respBody, AgnesConstants.LOG_RESPONSE_SNIPPET_MAX))
                    .build();
        }
    }

    /**
     * Agnes 图片生成为同步接口，无需异步轮询，兜底返回 SUCCEEDED。
     */
    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        return ProviderTaskResult.builder().status(AgnesConstants.TASK_STATUS_SUCCEEDED).build();
    }
    /**
     * 组装 Agnes Images 请求体：。
     */
    private Map<String, Object> buildRequestBody(String model, MediaImageGenerateRequest request, AiModelConfigVo modelConfig) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(AgnesConstants.JSON_MODEL, model);                                 // 模型名
        applySizeAndRatio(body, request);                                           // 尺寸（档位+ratio 或精确 WxH）

        // 参考引用渲染：manifest 存在 → 走「厂商渲染策略」（Agnes：名称（类型）+ 参考图说明段 + 多图）；
        //               缺失 → 退回现状逻辑（prompt 原样 + resolveReferenceImages）。
        String finalPrompt;
        List<String> images;
        List<ResolvedImageReference> manifest = readReferenceManifest(request);
        if (manifest != null) {
            ImageReferenceRenderContext ctx = new ImageReferenceRenderContext(
                    request == null ? null : request.getPrompt(), manifest, modelConfig, MAX_REFERENCE_IMAGES);
            ImageReferenceRenderPlan plan = imageReferenceRenderPlanner.plan(ctx);
            finalPrompt = plan.getFinalPrompt();
            images = plan.getReferenceImageUrls();
        } else {
            // manifest 缺失 / 解析失败 → 退回现状逻辑：此处对 prompt 兜底清洗（submit 在 hasManifest=true 时跳过了早清洗，
            // 若 manifest 实际解析失败会落到本分支，必须在这里清洗，否则原始 @图片N[name] + 映射段会带噪声下发上游）。
            finalPrompt = ReferencePromptSanitizer.sanitize(request == null ? "" : request.getPrompt());
            images = resolveReferenceImages(request, modelConfig);
        }
        body.put(AgnesConstants.JSON_PROMPT, finalPrompt);                          // 提示词（渲染后）

        if (!images.isEmpty()) {
            // 图生图 / 多图：image[] 与 response_format 都必须放进 extra_body（Agnes 官方写法，
            //   放顶层 image 会被网关忽略 → 退化成文生图、出图与参考图无关）。
            // 参考图输入默认公网 URL；模型 capability 开启 base64 传图时下载转 Data URI 内联下发
            //   （官方 image 支持「公网 URL 或 Data URI Base64」），用于网关无法回源业务 CDN 的场景。
            List<String> effectiveImages = images;
            if (com.aid.media.provider.ReferenceImageBase64Support.isBase64Enabled(modelConfig)) {
                effectiveImages = com.aid.media.provider.ReferenceImageBase64Support.toDataUris(images);
                log.info("Agnes 参考图按 base64 内联下发, model={}, count={}", model, effectiveImages.size());
            }
            Map<String, Object> extraBody = new LinkedHashMap<>();
            extraBody.put(AgnesConstants.JSON_IMAGE, effectiveImages);
            extraBody.put(AgnesConstants.JSON_RESPONSE_FORMAT, AgnesConstants.RESPONSE_FORMAT_URL);
            body.put(AgnesConstants.JSON_EXTRA_BODY, extraBody);
        } else {
            // 文生图：输出用 URL（response_format=url 放 extra_body；放顶层会被 Agnes 网关 400）。
            Map<String, Object> extraBody = new LinkedHashMap<>();
            extraBody.put(AgnesConstants.JSON_RESPONSE_FORMAT, AgnesConstants.RESPONSE_FORMAT_URL);
            body.put(AgnesConstants.JSON_EXTRA_BODY, extraBody);
        }
        return body;
    }

    /** options.referenceManifest 是否存在非空 JSON（决定走渲染策略还是现状逻辑）。 */
    private boolean hasReferenceManifest(MediaImageGenerateRequest request) {
        if (request == null || request.getOptions() == null) {
            return false;
        }
        Object raw = request.getOptions().get(OPTION_REFERENCE_MANIFEST);
        return raw instanceof String && StringUtils.isNotBlank((String) raw);
    }

    /** 反序列化 options.referenceManifest 为富化引用清单；缺失 / 失败返回 null（退回现状逻辑，不阻断出图）。 */
    private List<ResolvedImageReference> readReferenceManifest(MediaImageGenerateRequest request) {
        if (!hasReferenceManifest(request)) {
            return null;
        }
        String json = (String) request.getOptions().get(OPTION_REFERENCE_MANIFEST);
        try {
            return MAPPER.readValue(json, new TypeReference<List<ResolvedImageReference>>() {});
        } catch (Exception e) {
            log.warn("Agnes referenceManifest 反序列化失败, 退回现状渲染逻辑, err={}", e.getMessage());
            return null;
        }
    }

    /**
     * 合并参考图 URL：referenceImageUrl + options.referenceImages + options.images。
     */
    @SuppressWarnings("unchecked")
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
        // 统一上限：读 capability_json.maxReferenceImages，缺省回退 Provider 安全上限；超限截断 + warn
        return ReferenceImageLimiter.limit(result, modelConfig, MAX_REFERENCE_IMAGES, "Agnes");
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
     * 组装输出尺寸：
     * 1) 档位式 size（1K/2K/3K/4K）→ 原样下发 size + ratio（官方推荐写法，输出尺寸可预期；
     *    此前档位被降级成 1K 量级精确 WxH，导致用户选 2K 实际只出 1K 图）；
     * 2) 精确 WxH → 规范化后下发；
     * 3) 仅有比例 → 按官方映射走 1K 档位 + ratio；
     * 4) 都没有 → 默认 1024x1024。
     */
    private void applySizeAndRatio(Map<String, Object> body, MediaImageGenerateRequest request) {
        Map<String, Object> options = request == null ? null : request.getOptions();
        String optSize = getStringOption(options, "size");
        String reqSize = request == null ? null : request.getSize();
        String ratio = resolveRatio(options);

        // 1) 档位式尺寸：options.size 优先，其次顶层 size
        String tier = normalizeSizeTier(optSize);
        if (tier == null) {
            tier = normalizeSizeTier(reqSize);
        }
        if (tier != null) {
            body.put(AgnesConstants.JSON_SIZE, tier);
            if (ratio != null) {
                body.put(AgnesConstants.JSON_RATIO, ratio);
            }
            return;
        }

        // 2) 精确 WxH：options.size 优先，其次顶层 size
        String normalized = normalizeSize(optSize);
        if (normalized == null) {
            normalized = normalizeSize(reqSize);
        }
        if (normalized != null) {
            body.put(AgnesConstants.JSON_SIZE, normalized);
            return;
        }

        // 3) 仅有比例：走 1K 档位 + ratio（官方映射，替代自造 WxH）
        if (ratio != null) {
            body.put(AgnesConstants.JSON_SIZE, AgnesConstants.SIZE_TIER_1K);
            body.put(AgnesConstants.JSON_RATIO, ratio);
            return;
        }

        // 4) 兜底默认
        body.put(AgnesConstants.JSON_SIZE, AgnesConstants.DEFAULT_IMAGE_SIZE);
    }

    /**
     * 按上游模型版本校验尺寸与比例，避免非法参数被静默忽略或错误下发。
     */
    private void validateSizeAndRatio(String model, MediaImageGenerateRequest request) {
        String size = resolveRequestedSize(request);
        String ratio = resolveRequestedRatio(request);

        if (Objects.equals(AgnesConstants.IMAGE_MODEL_20_FLASH, model)) {
            validateImage20Parameters(model, size, ratio);
            return;
        }
        if (Objects.equals(AgnesConstants.IMAGE_MODEL_21_FLASH, model)) {
            validateImage21Parameters(model, size, ratio);
        }
    }

    /** Agnes Image 2.0 仅接受文档列出的精确像素尺寸，且不支持 ratio。 */
    private void validateImage20Parameters(String model, String size, String ratio) {
        if (StringUtils.isNotBlank(ratio)) {
            log.error("Agnes 图片参数校验失败: 模型不支持比例, model={}, ratio={}", model, ratio);
            throw new IllegalArgumentException(AgnesConstants.ERROR_RATIO_DISABLED);
        }
        if (StringUtils.isBlank(size)) {
            return;
        }
        String normalizedSize = normalizeSize(size);
        if (!AgnesConstants.IMAGE_20_SIZES.contains(normalizedSize)) {
            log.error("Agnes 图片参数校验失败: 图片尺寸不支持, model={}, size={}", model, size);
            throw new IllegalArgumentException(AgnesConstants.ERROR_SIZE_UNSUPPORTED);
        }
    }

    /** Agnes Image 2.1 接受 1K～4K 档位或历史精确像素尺寸，ratio 仅能与档位尺寸配合。 */
    private void validateImage21Parameters(String model, String size, String ratio) {
        String sizeTier = normalizeSizeTier(size);
        String normalizedSize = normalizeSize(size);
        if (StringUtils.isNotBlank(size) && sizeTier == null && normalizedSize == null) {
            log.error("Agnes 图片参数校验失败: 图片尺寸不支持, model={}, size={}", model, size);
            throw new IllegalArgumentException(AgnesConstants.ERROR_SIZE_UNSUPPORTED);
        }
        if (StringUtils.isNotBlank(ratio) && !AgnesConstants.SUPPORTED_RATIOS.contains(ratio.trim())) {
            log.error("Agnes 图片参数校验失败: 图片比例不支持, model={}, ratio={}", model, ratio);
            throw new IllegalArgumentException(AgnesConstants.ERROR_RATIO_UNSUPPORTED);
        }
        if (normalizedSize != null && StringUtils.isNotBlank(ratio)) {
            log.error("Agnes 图片参数校验失败: 精确尺寸与比例不能同时下发, model={}, size={}, ratio={}",
                    model, size, ratio);
            throw new IllegalArgumentException(AgnesConstants.ERROR_SIZE_RATIO_CONFLICT);
        }
    }

    /** options.size 优先于顶层 size，与请求体组装规则保持一致。 */
    private String resolveRequestedSize(MediaImageGenerateRequest request) {
        Map<String, Object> options = Objects.isNull(request) ? null : request.getOptions();
        String optionSize = getStringOption(options, "size");
        return StringUtils.isNotBlank(optionSize)
                ? optionSize : Objects.isNull(request) ? null : request.getSize();
    }

    /** options.aspect_ratio 优先于 options.aspectRatio，与请求体组装规则保持一致。 */
    private String resolveRequestedRatio(MediaImageGenerateRequest request) {
        Map<String, Object> options = Objects.isNull(request) ? null : request.getOptions();
        String ratio = getStringOption(options, "aspect_ratio");
        return StringUtils.isNotBlank(ratio) ? ratio : getStringOption(options, "aspectRatio");
    }

    /**
     * 规范化档位式尺寸：1K/2K/3K/4K（忽略大小写），非档位返回 null。
     */
    private String normalizeSizeTier(String size) {
        if (StringUtils.isBlank(size)) {
            return null;
        }
        String upper = size.trim().toUpperCase();
        return AgnesConstants.SIZE_TIERS.contains(upper) ? upper : null;
    }

    /**
     * 解析宽高比：options.aspect_ratio / options.aspectRatio，仅接受官方支持集合，其余返回 null。
     */
    private String resolveRatio(Map<String, Object> options) {
        String ratio = getStringOption(options, "aspect_ratio");
        if (StringUtils.isBlank(ratio)) {
            ratio = getStringOption(options, "aspectRatio");
        }
        if (StringUtils.isBlank(ratio)) {
            return null;
        }
        String trimmed = ratio.trim();
        return AgnesConstants.SUPPORTED_RATIOS.contains(trimmed) ? trimmed : null;
    }

    /**
     * 把 "1024*1024" / "1024x1024" / "1024X1024" / "1024×1024" 规范化为 "1024x1024"；
     * 非法或档位（1K/2K/4K）返回 null。
     */
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
     * 解析 Agnes Images 响应：
     * <pre>
     * { "created": ..., "data": [ { "url": "...", "b64_json": "...", "revised_prompt": null } ] }
     * </pre>
     * b64_json 优先（解码落 OSS）；否则取 url 作为 directUrl（系统后续下载转存）。
     */
    private ProviderSubmitResult parseImageResponse(String respBody, String model) throws Exception {
        if (StringUtils.isBlank(respBody)) {
            log.error("Agnes 图片响应为空, model={}", model);
            return ProviderSubmitResult.builder().rawResponse(AgnesConstants.ERROR_NO_IMAGE).build();
        }
        JsonNode root = MAPPER.readTree(respBody);

        // 上游错误透传（OpenAI 风格 error.message）
        JsonNode errorNode = root.path("error");
        if (errorNode.isObject() && !errorNode.isNull()) {
            String errMsg = errorNode.path("message").asText("未知错误");
            log.error("Agnes 图片上游错误, model={}, error={}", model, errMsg);
            return ProviderSubmitResult.builder().rawResponse(StringUtils.abbreviate(respBody, AgnesConstants.LOG_RESPONSE_SNIPPET_MAX)).build();
        }

        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            log.error("Agnes 图片未返回 data, model={}, resp={}", model,
                    StringUtils.abbreviate(respBody, AgnesConstants.LOG_RESPONSE_SNIPPET_MAX));
            return ProviderSubmitResult.builder().rawResponse(AgnesConstants.ERROR_NO_IMAGE).build();
        }

        List<String> ossUrls = new ArrayList<>();   // b64 落库模式
        List<String> directUrls = new ArrayList<>(); // url 落库模式
        for (JsonNode item : data) {
            String b64 = item.path("b64_json").asText(null);
            if (StringUtils.isNotBlank(b64)) {
                String ossUrl = uploadBase64ToOss(b64);
                if (StringUtils.isNotBlank(ossUrl)) {
                    ossUrls.add(ossUrl);
                }
                continue;
            }
            String directUrl = item.path("url").asText(null);
            if (StringUtils.isNotBlank(directUrl)) {
                directUrls.add(directUrl);
            }
        }

        if (!ossUrls.isEmpty()) {
            // Base64 只在内存中解码上传，任务仅保存系统 OSS URL，不设 directUrl。
            log.info("Agnes 图片生成成功(b64→OSS), model={}, imageCount={}", model, ossUrls.size());
            return ProviderSubmitResult.builder()
                    .ossUrl(ossUrls.get(0))
                    .resultUrls(ossUrls)
                    .resultCount(ossUrls.size())
                    .rawResponse(StringUtils.abbreviate(respBody, AgnesConstants.LOG_RESPONSE_SNIPPET_MAX))
                    .build();
        }
        if (!directUrls.isEmpty()) {
            // url 落库模式：directUrl 为上游可下载 URL，系统后续下载转存 OSS
            log.info("Agnes 图片生成成功(url), model={}, imageCount={}", model, directUrls.size());
            return ProviderSubmitResult.builder()
                    .directUrl(directUrls.get(0))
                    .resultUrls(directUrls)
                    .resultCount(directUrls.size())
                    .rawResponse(StringUtils.abbreviate(respBody, AgnesConstants.LOG_RESPONSE_SNIPPET_MAX))
                    .build();
        }

        log.error("Agnes 图片生成未返回图片, model={}, resp={}", model,
                StringUtils.abbreviate(respBody, AgnesConstants.LOG_RESPONSE_SNIPPET_MAX));
        return ProviderSubmitResult.builder().rawResponse(AgnesConstants.ERROR_NO_IMAGE).build();
    }

    /**
     * 将 base64 图片解码后上传 OSS，返回可访问 URL。
     */
    private String uploadBase64ToOss(String base64Data) {
        try {
            // 兼容 data URI 前缀（data:image/png;base64,xxx）
            String pure = base64Data;
            int comma = base64Data.indexOf(',');
            if (base64Data.startsWith("data:") && comma > 0) {
                pure = base64Data.substring(comma + 1);
            }
            byte[] imageBytes = Base64.getDecoder().decode(pure);
            if (imageBytes.length == 0) {
                return null;
            }
            UploadResult uploadResult = OssFactory.instance()
                    .uploadSuffix(imageBytes, AgnesConstants.IMAGE_SUFFIX_PNG, AgnesConstants.IMAGE_CONTENT_TYPE_PNG);
            return uploadResult.getUrl();
        } catch (Exception e) {
            log.error("Agnes 图片 OSS 上传失败, error={}", e.getMessage(), e);
            return null;
        }
    }
    /**
     * 发起 Agnes Images POST 请求，返回响应正文。
     */
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
    /**
     * 解析最终下发上游的模型名：经 {@link ModelCodeResolver} 解析，兜底默认图片模型。
     */
    private String resolveEffectiveModel(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        String resolved = ModelCodeResolver.resolveUpstreamModel(modelConfig,
                request == null ? null : request.getModelName());
        return StringUtils.isNotBlank(resolved) ? resolved : AgnesConstants.DEFAULT_IMAGE_MODEL;
    }

    /**
     * 构建 API URL：base_url + api_suffix（路径全部来自数据库配置）。
     */
    private String buildApiUrl(String baseUrl, String apiSuffix) {
        if (StringUtils.isBlank(baseUrl)) {
            log.error("Agnes 图片 baseUrl 为空，请在 aid_ai_provider 表配置 base_url");
            throw new IllegalArgumentException(AgnesConstants.ERROR_BASE_URL_EMPTY);
        }
        if (StringUtils.isBlank(apiSuffix)) {
            log.error("Agnes 图片 apiSuffix 为空，请在 aid_ai_model 表配置 api_suffix");
            throw new IllegalArgumentException(AgnesConstants.ERROR_API_SUFFIX_EMPTY);
        }
        String base = baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String suffix = apiSuffix.trim();
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return base + suffix;
    }

    /**
     * 安全读取 options 中的 String 值。
     */
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
