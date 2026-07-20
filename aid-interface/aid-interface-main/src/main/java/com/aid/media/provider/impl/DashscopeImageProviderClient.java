package com.aid.media.provider.impl;

import com.aid.media.provider.ModelIoDump; // 【临时调试】入参/出参落盘工具

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import org.apache.commons.lang3.StringUtils;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.common.constant.HttpConstants;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.constants.DashscopeConstants;
import com.aid.media.constants.MediaImageScenario;
import com.aid.media.provider.ImageProviderClient;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope（模型服务灵积）是阿里云提供的模型即服务（Model-as-a-Service, MaaS）平台，主要为企业和开发者提供AI模型服务，涵盖模型推理、微调训练等能力。
 */
@Slf4j
@Component
public class DashscopeImageProviderClient implements ImageProviderClient {

    // 使用 DashscopeConstants 统一管理所有魔法值，避免重复定义和维护困难。
    // 协议名称：与 MediaGenerationServiceImpl 中的 DEFAULT_IMAGE_PROTOCOL 保持一致。
    // 所有路径、Header、模型名前缀均来自 DashscopeConstants，保证单一事实来源。

    // 模型族策略列表：按顺序匹配，前面的规则优先级更高。
    private static final List<ImageDialect> DIALECTS = initDialects();

    /**
     * HTTP POST 执行结果：状态码与正文，供 qwen-image/plus 双模分支判断是否回退异步。
     */
    private static final class HttpPostResult {
        // 上游 HTTP 状态码：用于日志与扩展；回退判定以业务 code/message 为主。
        private final int statusCode;
        // 原始响应正文：解析 task_id、图片 URL 或错误码。
        private final String body;

        private HttpPostResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    @Override
    public String protocol() {
        // 返回当前 provider 的协议标识。
        return DashscopeConstants.PROTOCOL_IMAGE;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        // 阿里百炼图片（qwen-image/wan*）：按 provider_code 精确归属
        return providerCode != null
                && DashscopeConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        //    原地清洗后下游所有方言 buildBody 自动拿到干净 prompt。
        ReferencePromptSanitizer.sanitizeInPlace(request);
        String effectiveModel = resolveEffectiveModel(modelConfig, request);
        if (isQwenImageDualModeFamily(effectiveModel)) {
            return submitQwenImageDualMode(modelConfig, request, effectiveModel);
        }
        ImageDialect dialect = resolveDialect(effectiveModel);
        String submitUrl = buildApiUrl(modelConfig.getBaseUrl(), modelConfig.getApiSuffix());
        Map<String, Object> body = dialect.buildSubmitBody(effectiveModel, request);
        String raw = doPost(submitUrl, modelConfig.getApiKey(), JSONUtil.toJsonStr(body), dialect.extraHeaders());
        JsonNode root = ProviderResponseHelper.readTree(raw);
        String taskId = ProviderResponseHelper.readText(root, "output.task_id", "task_id", "data.task_id");
        String directUrl = dialect.extractDirectUrl(root);
        if (StrUtil.isBlank(directUrl)) {
            directUrl = ProviderResponseHelper.findFirstUrl(root);
        }
        List<String> resultUrls = readAllImageUrls(root);
        if (resultUrls.isEmpty() && StrUtil.isNotBlank(directUrl)) {
            resultUrls = Collections.singletonList(directUrl);
        }
        return ProviderSubmitResult.builder()
            .providerTaskId(taskId)
            .directUrl(directUrl)
            .resultUrls(resultUrls)
            .resultCount(resultUrls.isEmpty() ? null : resultUrls.size())
            .rawResponse(raw)
            .build();
    }

    /**
     * qwen-image 与 qwen-image-plus（含快照）：先同步多模态，仅在约定错误下再异步 image-synthesis。
     */
    private ProviderSubmitResult submitQwenImageDualMode(AiModelConfigVo modelConfig,
                                                         MediaImageGenerateRequest request,
                                                         String effectiveModel) {
        String syncUrl = buildApiUrl(modelConfig.getBaseUrl(), modelConfig.getApiSuffix());
        Map<String, Object> syncBody = QwenSyncMultimodalDialect.INSTANCE.buildSubmitBody(effectiveModel, request);
        HttpPostResult syncHttp = doPostWithStatus(syncUrl, modelConfig.getApiKey(), JSONUtil.toJsonStr(syncBody), Collections.emptyMap());
        JsonNode syncRoot = ProviderResponseHelper.readTree(syncHttp.body);
        String syncTaskId = ProviderResponseHelper.readText(syncRoot, "output.task_id", "task_id", "data.task_id");
        String directUrl = QwenSyncMultimodalDialect.INSTANCE.extractDirectUrl(syncRoot);
        if (StrUtil.isBlank(directUrl)) {
            directUrl = ProviderResponseHelper.findFirstUrl(syncRoot);
        }
        if (StrUtil.isNotBlank(syncTaskId) || StrUtil.isNotBlank(directUrl)) {
            // 采集全部结果图 URL 列表，供图片计费按张数结算
            List<String> syncUrls = readAllImageUrls(syncRoot);
            if (syncUrls.isEmpty() && StrUtil.isNotBlank(directUrl)) {
                syncUrls = Collections.singletonList(directUrl);
            }
            return ProviderSubmitResult.builder()
                .providerTaskId(syncTaskId)
                .directUrl(directUrl)
                .resultUrls(syncUrls)
                .resultCount(syncUrls.isEmpty() ? null : syncUrls.size())
                .rawResponse(syncHttp.body)
                .build();
        }
        if (!shouldFallbackSyncToAsync(syncRoot)) {
            return ProviderSubmitResult.builder()
                .providerTaskId(null)
                .directUrl(null)
                .rawResponse(syncHttp.body)
                .build();
        }
        log.warn("qwen-image dual-mode: sync failed, fallback async text2image, model={}, httpStatus={}, snippet={}",
            effectiveModel, syncHttp.statusCode, StringUtils.abbreviate(syncHttp.body, DashscopeConstants.LOG_RESPONSE_SNIPPET_MAX));
        String asyncUrl = buildApiUrl(modelConfig.getBaseUrl(), modelConfig.getApiSuffix());
        Map<String, Object> asyncBody = QwenLegacyAsyncDialect.INSTANCE.buildSubmitBody(effectiveModel, request);
        HttpPostResult asyncHttp = doPostWithStatus(asyncUrl, modelConfig.getApiKey(), JSONUtil.toJsonStr(asyncBody),
            Collections.singletonMap(DashscopeConstants.HEADER_ASYNC, DashscopeConstants.HEADER_ASYNC_ENABLE));
        JsonNode asyncRoot = ProviderResponseHelper.readTree(asyncHttp.body);
        String asyncTaskId = ProviderResponseHelper.readText(asyncRoot, "output.task_id", "task_id", "data.task_id");
        String asyncDirect = QwenLegacyAsyncDialect.INSTANCE.extractDirectUrl(asyncRoot);
        if (StrUtil.isBlank(asyncDirect)) {
            asyncDirect = ProviderResponseHelper.findFirstUrl(asyncRoot);
        }
        List<String> asyncUrls = readAllImageUrls(asyncRoot);
        if (asyncUrls.isEmpty() && StrUtil.isNotBlank(asyncDirect)) {
            asyncUrls = Collections.singletonList(asyncDirect);
        }
        return ProviderSubmitResult.builder()
            .providerTaskId(asyncTaskId)
            .directUrl(asyncDirect)
            .resultUrls(asyncUrls)
            .resultCount(asyncUrls.isEmpty() ? null : asyncUrls.size())
            .rawResponse(asyncHttp.body)
            .build();
    }

    /** 是否 qwen-image 精确名或 qwen-image-plus 前缀（含快照）。 */
    private static boolean isQwenImageDualModeFamily(String modelName) {
        if (StrUtil.isBlank(modelName)) {
            return false;
        }
        String n = modelName.toLowerCase();
        if (DashscopeConstants.MODEL_QWEN_IMAGE.equals(n)) {
            return true;
        }
        return n.startsWith(DashscopeConstants.MODEL_QWEN_IMAGE_PLUS_PREFIX);
    }

    /**
     * 同步无 task_id 且无图时：是否满足回退条件（url error 文案或 InvalidParameter*）。
     */
    private static boolean shouldFallbackSyncToAsync(JsonNode root) {
        if (root == null) {
            return false;
        }
        String code = ProviderResponseHelper.readText(root,
            DashscopeConstants.JSON_KEY_CODE, DashscopeConstants.JSON_PATH_OUTPUT_CODE);
        String message = ProviderResponseHelper.readText(root,
            DashscopeConstants.JSON_KEY_MESSAGE, DashscopeConstants.JSON_PATH_OUTPUT_MESSAGE);
        if (StrUtil.isNotBlank(code) && (DashscopeConstants.ERROR_INVALID_PARAMETER.equals(code)
            || code.startsWith(DashscopeConstants.ERROR_INVALID_PARAMETER_PREFIX))) {
            return true;
        }
        if (StrUtil.isNotBlank(message) && StringUtils.containsIgnoreCase(message, DashscopeConstants.ERROR_URL_ERROR_SUBSTRING)) {
            return true;
        }
        return false;
    }

    /**
     * 吸收业务层设定卡参数适配。
     */
    @Override
    public void applyScenarioOverrides(MediaImageGenerateRequest request, String scenario) {
        if (request == null || !MediaImageScenario.CARD_IMAGE.equals(scenario)) {
            return;
        }
        String model = StringUtils.defaultString(request.getModelName()).toLowerCase();
        boolean isWanNew = model.startsWith(DashscopeConstants.MODEL_WAN_26_PREFIX)
            || model.startsWith(DashscopeConstants.MODEL_WAN_27_PREFIX);
        if (!isWanNew) {
            return;
        }
        request.setSize(DashscopeConstants.CARD_IMAGE_SIZE_2K_21_9);
        if (request.getOptions() != null) {
            request.getOptions().remove("aspect_ratio");
        }
        log.info("DashscopeImageProviderClient 设定卡场景覆盖生效: model={}, size={}",
            model, DashscopeConstants.CARD_IMAGE_SIZE_2K_21_9);
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        String queryUrl = buildTaskUrl(modelConfig.getBaseUrl(), modelConfig.getTaskQuerySuffix(), providerTaskId);
        String raw = doGet(queryUrl, modelConfig.getApiKey());
        JsonNode root = ProviderResponseHelper.readTree(raw);
        String taskStatus = ProviderResponseHelper.readText(root, "output.task_status", "task_status", "data.status", "status");
        String normalized = normalizeStatus(taskStatus);
        String url = ProviderResponseHelper.readText(root,
            "output.choices.0.message.content.0.image",
            "output.results.0.url",
            "output.video_url",
            "output.url",
            "data.url");
        if (StrUtil.isBlank(url)) {
            url = ProviderResponseHelper.findFirstUrl(root);
        }
        String error = ProviderResponseHelper.readText(root, "output.message", "message", "error.message", "error");
        List<String> resultUrls = readAllImageUrls(root);
        if (resultUrls.isEmpty() && StrUtil.isNotBlank(url)) {
            resultUrls = Collections.singletonList(url);
        }
        return ProviderTaskResult.builder()
            .status(normalized)
            .resultUrl(url)
            .resultUrls(resultUrls)
            .resultCount(resultUrls.isEmpty() ? null : resultUrls.size())
            .errorMessage(error)
            .rawResponse(raw)
            .build();
    }

    /**
     * 遍历常见路径读取全部结果图 URL，兼容 qwen 多模态（choices.content.image）/ 万相 text2image（results.url）。
     * 找不到任何数组路径时返回空列表，交由调用方根据 resultUrl 做单图兜底。
     */
    private List<String> readAllImageUrls(JsonNode root) {
        if (root == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        JsonNode results = ProviderResponseHelper.nodeByPath(root, "output.results");
        collectUrlsFromArray(results, "url", out);
        // wan2.6-image/wan2.7 多张结果按 choices[0..n] 逐个返回（每个 choice 一张图），必须遍历全部 choice
        JsonNode choices = ProviderResponseHelper.nodeByPath(root, "output.choices");
        if (choices != null && choices.isArray()) {
            for (JsonNode choice : choices) {
                if (choice == null) {
                    continue;
                }
                collectUrlsFromArray(choice.path("message").path("content"), "image", out);
            }
        }
        JsonNode imageUrls = ProviderResponseHelper.nodeByPath(root, "output.data.image_urls");
        if (imageUrls != null && imageUrls.isArray()) {
            for (JsonNode n : imageUrls) {
                if (n != null && n.isTextual()) {
                    String v = n.asText();
                    if (StrUtil.isNotBlank(v)) {
                        out.add(v);
                    }
                }
            }
        }
        return out;
    }

    private void collectUrlsFromArray(JsonNode arr, String field, List<String> out) {
        if (arr == null || !arr.isArray()) {
            return;
        }
        for (JsonNode node : arr) {
            if (node == null) {
                continue;
            }
            JsonNode v = node.get(field);
            if (v != null && v.isTextual()) {
                String s = v.asText();
                if (StrUtil.isNotBlank(s)) {
                    out.add(s);
                }
            }
        }
    }

    private String normalizeStatus(String status) {
        // 空状态统一按处理中返回，避免误判失败。
        if (StrUtil.isBlank(status)) {
            return DashscopeConstants.TASK_STATUS_PROCESSING;
        }
        // 统一转大写做包含判断，兼容厂商大小写差异。
        String upper = status.toUpperCase();
        // 成功关键字归一化为 SUCCEEDED。
        if (upper.contains(DashscopeConstants.VENDOR_TOKEN_SUCC)
            || DashscopeConstants.VENDOR_STATUS_SUCCESS.equals(upper)
            || DashscopeConstants.VENDOR_STATUS_COMPLETED.equals(upper)) {
            return DashscopeConstants.TASK_STATUS_SUCCEEDED;
        }
        // 取消与未知统一按失败处理，避免前端无限轮询过期任务。
        if (upper.contains(DashscopeConstants.VENDOR_TOKEN_CANCEL) || upper.contains(DashscopeConstants.VENDOR_TOKEN_UNKNOWN)) {
            return DashscopeConstants.TASK_STATUS_FAILED;
        }
        // 失败关键字归一化为 FAILED。
        if (upper.contains(DashscopeConstants.VENDOR_TOKEN_FAIL) || upper.contains(DashscopeConstants.VENDOR_TOKEN_ERROR)) {
            return DashscopeConstants.TASK_STATUS_FAILED;
        }
        // 其余状态视为处理中。
        return DashscopeConstants.TASK_STATUS_PROCESSING;
    }

    /**
     * 构建 API 请求 URL：base_url + api_suffix（路径全部来自数据库配置）
     */
    private String buildApiUrl(String baseUrl, String apiSuffix) {
        if (StrUtil.isBlank(baseUrl)) {
            throw new IllegalArgumentException("dashscope image model baseUrl 不能为空");
        }
        if (StrUtil.isBlank(apiSuffix)) {
            throw new IllegalArgumentException("dashscope image model apiSuffix 不能为空，请在 aid_ai_model 表配置");
        }
        return trimSlash(baseUrl.trim()) + apiSuffix;
    }

    private String buildTaskUrl(String baseUrl, String taskQuerySuffix, String providerTaskId) {
        if (StrUtil.isBlank(baseUrl)) {
            throw new IllegalArgumentException("dashscope image model baseUrl 不能为空");
        }
        if (StrUtil.isBlank(taskQuerySuffix)) {
            throw new IllegalArgumentException("dashscope image model taskQuerySuffix 不能为空，请在 aid_ai_provider 表配置");
        }
        if (StrUtil.isBlank(providerTaskId)) {
            return baseUrl;
        }
        // 完整查询 URL = base_url + String.format(task_query_suffix, taskId)
        return trimSlash(baseUrl.trim()) + String.format(taskQuerySuffix, providerTaskId);
    }

    private String doPost(String url, String apiKey, String json, Map<String, String> extraHeaders) {
        // 非双模场景仅需 body，与历史行为一致。
        return doPostWithStatus(url, apiKey, json, extraHeaders).body;
    }

    private HttpPostResult doPostWithStatus(String url, String apiKey, String json, Map<String, String> extraHeaders) {
        ModelIoDump.req(url, json); // 【临时调试】记录下发上游入参
        HttpRequest post = HttpRequest.post(url)
            .header(HttpConstants.HEADER_AUTHORIZATION, HttpConstants.AUTH_BEARER_PREFIX + apiKey)
            .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
            .body(json)
            .timeout(HttpConstants.DEFAULT_TIMEOUT_MS);
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                post.header(entry.getKey(), entry.getValue());
            }
        }
        try (HttpResponse response = post.execute()) {
            return new HttpPostResult(response.getStatus(), ModelIoDump.resp(url, response.body())); // 【临时调试】记录上游出参
        }
    }

    private String doGet(String url, String apiKey) {
        // 统一 GET 调用：鉴权头 + 超时。
        try (HttpResponse response = HttpRequest.get(url)
            .header(HttpConstants.HEADER_AUTHORIZATION, HttpConstants.AUTH_BEARER_PREFIX + apiKey)
            .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
            .timeout(HttpConstants.DEFAULT_TIMEOUT_MS)
            .execute()) {
            // 返回原始响应给上层自行解析。
            return ModelIoDump.resp(url, response.body()); // 【临时调试】记录上游出参（轮询）
        }
    }

    private String trimSlash(String base) {
        // 去掉末尾 /，避免拼接路径时出现 //。
        if (base.endsWith("/")) {
            return base.substring(0, base.length() - 1);
        }
        return base;
    }

    private String resolveEffectiveModel(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        // 解析真实上游模型名：展示码 model_code 与真实模型名 real_model_code 解耦
        String resolved = com.aid.media.provider.ModelCodeResolver.resolveUpstreamModel(modelConfig,
                request == null ? null : request.getModelName());
        if (StrUtil.isNotBlank(resolved)) {
            return resolved;
        }
        // 双方都为空时返回空串，由下游接口返回参数错误，避免 NPE。
        return "";
    }

    private ImageDialect resolveDialect(String modelName) {
        // 对模型名做小写标准化，避免大小写差异影响路由判定。
        String normalized = StringUtils.defaultString(modelName).toLowerCase();
        // 顺序匹配方言规则：优先命中特定模型族，再回退默认兼容策略。
        for (ImageDialect dialect : DIALECTS) {
            if (dialect.supports(normalized)) {
                return dialect;
            }
        }
        // 理论上不会走到这里；若发生则回退旧异步方言确保兼容历史调用。
        return LegacyText2ImageAsyncDialect.INSTANCE;
    }

    private static List<ImageDialect> initDialects() {
        List<ImageDialect> dialects = new ArrayList<>();

        dialects.add(KlingImageGenerationAsyncDialect.INSTANCE);
        dialects.add(Wan26ImageGenerationAsyncDialect.INSTANCE);
        dialects.add(QwenSyncMultimodalDialect.INSTANCE);
        dialects.add(WanxImageEditAsyncDialect.INSTANCE);
        dialects.add(WanLegacyAsyncDialect.INSTANCE);
        dialects.add(LegacyText2ImageAsyncDialect.INSTANCE);
        return Collections.unmodifiableList(dialects);
    }

    private interface ImageDialect {

        // 判断当前方言是否支持该模型名（模型名已经过小写标准化）。
        boolean supports(String normalizedModelName);

        // 构建提交请求体：按模型协议差异生成 input/messages/parameters 结构。
        Map<String, Object> buildSubmitBody(String modelName, MediaImageGenerateRequest request);

        // 返回额外请求头：异步模式通常返回 X-DashScope-Async，默认无额外头。
        Map<String, String> extraHeaders();

        // 从提交响应中提取直出 URL（同步模式常用）；异步模式通常返回空。
        String extractDirectUrl(JsonNode root);
    }

    private abstract static class AbstractImageDialect implements ImageDialect {

        // 构建标准 parameters：先放通用字段，再合并 options，保证可扩展而不丢默认值。
        protected Map<String, Object> buildParameters(MediaImageGenerateRequest request) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put(DashscopeConstants.JSON_SIZE, StringUtils.defaultIfBlank(request.getSize(), DashscopeConstants.DEFAULT_IMAGE_SIZE));
            if (StrUtil.isNotBlank(request.getNegativePrompt())) {
                parameters.put(DashscopeConstants.JSON_NEGATIVE_PROMPT, request.getNegativePrompt());
            }
            //    但平台级计费语义字段（force_single / n / expectedImageCount）即便放在
            //    options.parameters 子对象里，也必须被拦截，避免绕过顶层过滤污染上游请求体。
            Map<String, Object> options = request.getOptions();
            if (options != null && options.get(DashscopeConstants.OPTIONS_KEY_PARAMETERS) instanceof Map<?, ?> optionParamsRaw) {
                Map<String, Object> optionParams = toStringObjectMap(optionParamsRaw);
                optionParams.keySet().removeIf(this::isPlatformBillingOnlyKey);
                parameters.putAll(optionParams);
            }
            return parameters;
        }

        // 将 options 里非 parameters 的顶层字段合并到 body/input/parameters，支持协议差异透传。
        protected void mergeOptions(Map<String, Object> body,
                                    Map<String, Object> input,
                                    Map<String, Object> parameters,
                                    MediaImageGenerateRequest request) {
            Map<String, Object> options = request.getOptions();
            if (options == null || options.isEmpty()) {
                return;
            }
            for (Map.Entry<String, Object> entry : options.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                // 跳过 parameters：该字段已在 buildParameters 阶段专门处理。
                if (DashscopeConstants.OPTIONS_KEY_PARAMETERS.equals(key)) {
                    continue;
                }
                // 平台级计费语义字段：force_single / n / expectedImageCount 仅用于本地预扣解析，
                // 非所有上游模型都支持，blind putAll 会把未知字段打到请求体导致 400 或行为异常，因此跳过。
                if (isPlatformBillingOnlyKey(key)) {
                    continue;
                }
                // 若传入 input 对象则合并到 input，支持高级入参透传（如 image/content 等）。
                //    同步拦截平台级计费字段，避免通过 options.input 旁路注入。
                if (DashscopeConstants.OPTIONS_KEY_INPUT.equals(key) && value instanceof Map<?, ?> inputRaw) {
                    Map<String, Object> inputMap = toStringObjectMap(inputRaw);
                    inputMap.keySet().removeIf(this::isPlatformBillingOnlyKey);
                    input.putAll(inputMap);
                    continue;
                }
                // 若传入 body 级 model 等字段则保留覆盖能力，兼容后续协议扩展。
                body.put(key, value);
            }
        }

        /**
         * 判定 option 键是否属于"平台级计费/语义/内部上下文字段"，不应透传给上游。
         * 统一收口 {@link com.aid.media.constants.MediaInternalOptionKeys}：扇入上下文
         * （sbzImageGenCtx 等）曾被顶层透传给 dashscope，属脏参数且泄漏内部结构。
         * aspect_ratio 为平台字段：万相新方言已在 normalize 阶段翻译消费，其余方言不得下发。
         */
        protected boolean isPlatformBillingOnlyKey(String key) {
            return com.aid.media.constants.MediaInternalOptionKeys.isInternal(key)
                || "n".equals(key) || "aspect_ratio".equals(key) || "aspectRatio".equals(key);
        }

        // 将任意 Map<?,?> 转为 Map<String,Object>，避免泛型擦除导致的编译告警与强转风险。
        protected Map<String, Object> toStringObjectMap(Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }

        @Override
        public String extractDirectUrl(JsonNode root) {
            // 默认先按常见路径读取首图 URL。
            String url = ProviderResponseHelper.readText(root,
                "output.choices.0.message.content.0.image",
                "output.results.0.url",
                "output.url",
                "data.url");
            // 若常见路径未命中则递归扫描兜底。
            if (StrUtil.isBlank(url)) {
                url = ProviderResponseHelper.findFirstUrl(root);
            }
            return url;
        }
    }

    /**
     * Qwen 同步多模态协议实现。
     */
    private static final class QwenSyncMultimodalDialect extends AbstractImageDialect {

        // 单例实例：避免重复创建对象并固定策略行为。
        private static final QwenSyncMultimodalDialect INSTANCE = new QwenSyncMultimodalDialect();

        @Override
        public boolean supports(String normalizedModelName) {
            // qwen-image-max / qwen-image-2.0* 走同步多模态；去掉 startsWith("qwen-image-2") 以免误匹配 qwen-image-2026 等。
            return normalizedModelName.startsWith(DashscopeConstants.MODEL_QWEN_IMAGE_MAX_PREFIX)
                || normalizedModelName.startsWith(DashscopeConstants.MODEL_QWEN_IMAGE_2_0_PREFIX)
                || DashscopeConstants.MODEL_QWEN_IMAGE_2.equals(normalizedModelName);
        }

        @Override
        public Map<String, Object> buildSubmitBody(String modelName, MediaImageGenerateRequest request) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put(DashscopeConstants.JSON_MODEL, modelName);
            Map<String, Object> contentItem = new LinkedHashMap<>();
            contentItem.put(DashscopeConstants.JSON_TEXT, request.getPrompt());
            Map<String, Object> message = new LinkedHashMap<>();
            message.put(DashscopeConstants.JSON_ROLE, DashscopeConstants.JSON_ROLE_USER);
            message.put(DashscopeConstants.JSON_CONTENT, Collections.singletonList(contentItem));
            Map<String, Object> input = new LinkedHashMap<>();
            input.put(DashscopeConstants.JSON_MESSAGES, Collections.singletonList(message));
            body.put(DashscopeConstants.JSON_INPUT, input);
            Map<String, Object> parameters = buildParameters(request);
            body.put(DashscopeConstants.JSON_PARAMETERS, parameters);
            mergeOptions(body, input, parameters, request);
            return body;
        }

        @Override
        public Map<String, String> extraHeaders() {
            // 同步协议不需要异步头。
            return Collections.emptyMap();
        }
    }

    /** 千问 text2image 异步 body 构造：供双模回退；不参与方言 supports 链。 */
    private static final class QwenLegacyAsyncDialect extends AbstractImageDialect {

        // 单例实例。
        private static final QwenLegacyAsyncDialect INSTANCE = new QwenLegacyAsyncDialect();

        @Override
        public boolean supports(String normalizedModelName) {
            // qwen-image/plus 已由 submit 双模处理；此类仅提供 buildSubmitBody，不参与 supports 链。
            return false;
        }

        @Override
        public Map<String, Object> buildSubmitBody(String modelName, MediaImageGenerateRequest request) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put(DashscopeConstants.JSON_MODEL, modelName);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put(DashscopeConstants.JSON_PROMPT, request.getPrompt());
            if (StrUtil.isNotBlank(request.getNegativePrompt())) {
                input.put(DashscopeConstants.JSON_NEGATIVE_PROMPT, request.getNegativePrompt());
            }
            body.put(DashscopeConstants.JSON_INPUT, input);
            Map<String, Object> parameters = buildParameters(request);
            parameters.putIfAbsent(DashscopeConstants.JSON_N, DashscopeConstants.DEFAULT_N);
            parameters.putIfAbsent(DashscopeConstants.JSON_PROMPT_EXTEND, DashscopeConstants.DEFAULT_PROMPT_EXTEND);
            parameters.putIfAbsent(DashscopeConstants.JSON_WATERMARK, DashscopeConstants.DEFAULT_WATERMARK);
            if (!parameters.containsKey(DashscopeConstants.JSON_NEGATIVE_PROMPT)) {
                parameters.put(DashscopeConstants.JSON_NEGATIVE_PROMPT, DashscopeConstants.DEFAULT_NEGATIVE_PROMPT);
            }
            body.put(DashscopeConstants.JSON_PARAMETERS, parameters);
            mergeOptions(body, input, parameters, request);
            return body;
        }

        @Override
        public Map<String, String> extraHeaders() {
            // 异步任务创建必须带 X-DashScope-Async: enable。
            return Collections.singletonMap(DashscopeConstants.HEADER_ASYNC, DashscopeConstants.HEADER_ASYNC_ENABLE);
        }

        @Override
        public String extractDirectUrl(JsonNode root) {
            // 旧异步提交通常不返回直出 URL，这里保留兼容读取能力。
            return super.extractDirectUrl(root);
        }
    }

    private static final class Wan26ImageGenerationAsyncDialect extends AbstractImageDialect {

        // 单例实例：固定 wan2.6 异步 image-generation 行为。
        private static final Wan26ImageGenerationAsyncDialect INSTANCE = new Wan26ImageGenerationAsyncDialect();

        @Override
        public boolean supports(String normalizedModelName) {
            // wan2.6 / wan2.7 模型统一走新版 image-generation 协议。
            return normalizedModelName.startsWith(DashscopeConstants.MODEL_WAN_26_PREFIX)
                || normalizedModelName.startsWith(DashscopeConstants.MODEL_WAN_27_PREFIX);
        }

        @Override
        public Map<String, Object> buildSubmitBody(String modelName, MediaImageGenerateRequest request) {
            //    仅传 size=1K/2K + aspect_ratio=1:1 不会强制 1:1（会跟随最后一张输入图）。
            //    在合并 options 之前统一翻译并剔除 aspect_ratio，避免污染顶层 body。
            normalizeWanNewSizeAndAspectRatio(request);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put(DashscopeConstants.JSON_MODEL, modelName);
            //    包含 1 个 {text: prompt} + N 个 {image: url}，是参考图进入上游的唯一路径。
            List<Map<String, Object>> contentList = new ArrayList<>();
            Map<String, Object> textItem = new LinkedHashMap<>();
            textItem.put(DashscopeConstants.JSON_TEXT, request.getPrompt());
            contentList.add(textItem);
            String singleRef = request.getReferenceImageUrl();
            if (StrUtil.isNotBlank(singleRef)) {
                Map<String, Object> imageItem = new LinkedHashMap<>();
                imageItem.put(DashscopeConstants.JSON_IMAGE, singleRef);
                contentList.add(imageItem);
            }
            Map<String, Object> rawOptions = request.getOptions();
            if (rawOptions != null) {
                Object refImagesValue = rawOptions.get("referenceImages");
                if (refImagesValue instanceof List<?> refList) {
                    for (Object url : refList) {
                        if (url == null) {
                            continue;
                        }
                        String urlStr = String.valueOf(url);
                        if (StrUtil.isBlank(urlStr) || urlStr.equals(singleRef)) {
                            // 跳过空值以及与单参考图重复的项
                            continue;
                        }
                        Map<String, Object> imageItem = new LinkedHashMap<>();
                        imageItem.put(DashscopeConstants.JSON_IMAGE, urlStr);
                        contentList.add(imageItem);
                    }
                }
            }
            Map<String, Object> message = new LinkedHashMap<>();
            message.put(DashscopeConstants.JSON_ROLE, DashscopeConstants.JSON_ROLE_USER);
            message.put(DashscopeConstants.JSON_CONTENT, contentList);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put(DashscopeConstants.JSON_MESSAGES, Collections.singletonList(message));
            body.put(DashscopeConstants.JSON_INPUT, input);
            Map<String, Object> parameters = buildParameters(request);
            mergeOptions(body, input, parameters, request);
            finalizeWanNewParameters(modelName, request, parameters);
            body.put(DashscopeConstants.JSON_PARAMETERS, parameters);
            return body;
        }

        @Override
        public Map<String, String> extraHeaders() {
            // 异步创建任务必须带异步头。
            return Collections.singletonMap(DashscopeConstants.HEADER_ASYNC, DashscopeConstants.HEADER_ASYNC_ENABLE);
        }

        /**
         * wan2.6-image / wan2.7 系列上游 parameters 收口，防止上游默认值造成多扣成本或参数被拒：
         * 1. n 显式下发为计费预期张数（wan2.6-image 编辑模式官方默认 4 张，不显式会按 4 张生成、平台只收 1 张的钱）；
         * 2. wan2.7 固定 thinking_mode=false（官方默认 true，增加耗时）；组图模式下该参数不可用，改为剔除；
         * 3. wan2.7 不支持 negative_prompt / prompt_extend，必须剥离；
         * 4. wan2.6-image 显式 enable_interleave=false 锁定图像编辑模式。
         */
        private void finalizeWanNewParameters(String modelName, MediaImageGenerateRequest request,
                                              Map<String, Object> parameters) {
            if (parameters == null) {
                return;
            }
            String normalized = StringUtils.defaultString(modelName).toLowerCase();
            boolean isWan27 = normalized.startsWith(DashscopeConstants.MODEL_WAN_27_PREFIX);
            boolean isWan26Image = normalized.startsWith(DashscopeConstants.MODEL_WAN_26_IMAGE_PREFIX);
            parameters.putIfAbsent(DashscopeConstants.JSON_WATERMARK, DashscopeConstants.DEFAULT_WATERMARK);
            // n 与计费 expectedImageCount 对齐（1~4 收口），确保上游生成张数等于预扣张数
            parameters.put(DashscopeConstants.JSON_N, resolveOutputCount(request));
            if (isWan27) {
                boolean sequentialOn = Boolean.parseBoolean(
                    String.valueOf(parameters.get(DashscopeConstants.JSON_ENABLE_SEQUENTIAL)));
                if (sequentialOn) {
                    // 官方约束：组图模式下 thinking_mode 不可用，下发会被拒
                    parameters.remove(DashscopeConstants.JSON_THINKING_MODE);
                } else {
                    parameters.put(DashscopeConstants.JSON_ENABLE_SEQUENTIAL, Boolean.FALSE);
                    parameters.put(DashscopeConstants.JSON_THINKING_MODE, Boolean.FALSE);
                }
                parameters.remove(DashscopeConstants.JSON_NEGATIVE_PROMPT);
                parameters.remove(DashscopeConstants.JSON_PROMPT_EXTEND);
            }
            if (isWan26Image) {
                parameters.putIfAbsent(DashscopeConstants.JSON_ENABLE_INTERLEAVE, Boolean.FALSE);
            }
        }

        /**
         * 解析上游 n：取计费口径的 expectedImageCount，缺省 1，并按官方区间 1~4 收口。
         */
        private int resolveOutputCount(MediaImageGenerateRequest request) {
            Integer expected = request == null ? null : request.getExpectedImageCount();
            if (expected == null || expected < 1) {
                return DashscopeConstants.DEFAULT_N;
            }
            return Math.min(expected, DashscopeConstants.WAN_NEW_MAX_OUTPUT_N);
        }

        /**
         * wan2.6/wan2.7 比例 → 显式像素 size 映射表。
         */
        private static final Map<String, String> WAN_NEW_RATIO_TO_2K_SIZE;
        private static final Map<String, String> WAN_NEW_RATIO_TO_1K_SIZE;
        static {
            // 2K 档（≈ 2048*2048 总像素）
            Map<String, String> m2k = new HashMap<>();
            m2k.put("1:1", DashscopeConstants.FORM_IMAGE_SIZE_2K_1_1); // 2048*2048
            m2k.put("16:9", "2720*1536"); // 4,177,920，比 16:9≈1.7708
            m2k.put("9:16", "1536*2720");
            m2k.put("4:3", "2352*1760");  // 4,139,520，比 4:3≈1.3364
            m2k.put("3:4", "1760*2352");
            m2k.put("3:2", "2496*1664");  // 4,153,344，比 3:2 精确
            m2k.put("2:3", "1664*2496");
            m2k.put("7:9", "1792*2304");  // 4,128,768，比 7:9 精确（capability 选项，此前缺映射会退化为 1:1）
            m2k.put("9:7", "2304*1792");
            m2k.put("21:9", DashscopeConstants.CARD_IMAGE_SIZE_2K_21_9); // 2016*864（与设定卡同源）
            m2k.put("9:21", "864*2016");
            m2k.put("2:1", "2880*1440"); // 4,147,200，比 2:1 精确
            m2k.put("1:2", "1440*2880");
            m2k.put("4:5", "1792*2240"); // 4,014,080，比 4:5 精确
            m2k.put("5:4", "2240*1792");
            WAN_NEW_RATIO_TO_2K_SIZE = Collections.unmodifiableMap(m2k);

            // 1K 档（≈ 1024*1024 总像素，约 2K 档的 1/4 边长）
            Map<String, String> m1k = new HashMap<>();
            m1k.put("1:1", DashscopeConstants.FORM_IMAGE_SIZE_1K_1_1); // 1024*1024
            m1k.put("16:9", "1360*768");
            m1k.put("9:16", "768*1360");
            m1k.put("4:3", "1184*880");
            m1k.put("3:4", "880*1184");
            m1k.put("3:2", "1248*832");
            m1k.put("2:3", "832*1248");
            m1k.put("7:9", "896*1152");   // 1,032,192，比 7:9 精确
            m1k.put("9:7", "1152*896");
            // 21:9 必须 ≥ 768*768 总像素下限（wan2.6-image/wan2.7 官方约束），1008*432 会被上游拒绝
            m1k.put("21:9", "1568*672");  // 1,053,696，比 21:9 精确
            m1k.put("9:21", "672*1568");
            m1k.put("2:1", "1440*720");
            m1k.put("1:2", "720*1440");
            m1k.put("4:5", "896*1120");
            m1k.put("5:4", "1120*896");
            WAN_NEW_RATIO_TO_1K_SIZE = Collections.unmodifiableMap(m1k);
        }

        /**
         * wan2.6/wan2.7 专用：把 options.aspect_ratio 翻译成文档允许的显式 size 并剔除。
         */
        private void normalizeWanNewSizeAndAspectRatio(MediaImageGenerateRequest request) {
            if (request == null) {
                return;
            }
            Map<String, Object> options = request.getOptions();
            if (options == null || options.isEmpty()) {
                return;
            }
            Object ratioObj = options.get("aspect_ratio");
            if (ratioObj == null) {
                return;
            }
            String ratio = String.valueOf(ratioObj).trim();
            String size = StringUtils.defaultString(request.getSize()).trim();
            boolean sizeIsExplicitPixel = !StrUtil.isBlank(size) && size.contains("*");
            boolean sizeIsTokenOrBlank = StrUtil.isBlank(size)
                || "1K".equalsIgnoreCase(size) || "2K".equalsIgnoreCase(size);
            if (sizeIsExplicitPixel) {
                // 调用方已显式像素尺寸（如设定卡 2016*864）：保留 size，仅剔除 ratio
                log.info("DashscopeImageProviderClient wan新版已显式 size={}，剔除 options.aspect_ratio={}, model={}",
                    size, ratio, request.getModelName());
            } else if (sizeIsTokenOrBlank) {
                Map<String, String> table = "1K".equalsIgnoreCase(size)
                    ? WAN_NEW_RATIO_TO_1K_SIZE
                    : WAN_NEW_RATIO_TO_2K_SIZE;
                String explicit = table.get(ratio);
                if (explicit != null) {
                    request.setSize(explicit);
                    log.info("DashscopeImageProviderClient wan新版翻译 aspect_ratio={} + size={} -> size={}, model={}",
                        ratio, StrUtil.isBlank(size) ? "(默认2K)" : size, explicit, request.getModelName());
                } else {
                    log.warn("DashscopeImageProviderClient wan新版未知 aspect_ratio={}，已剔除并保留 size={}, model={}",
                        ratio, size, request.getModelName());
                }
            } else {
                log.warn("DashscopeImageProviderClient wan新版不支持顶层 aspect_ratio，已剔除: ratio={}, size={}, model={}",
                    ratio, size, request.getModelName());
            }
            options.remove("aspect_ratio");
        }
    }

    private static final class KlingImageGenerationAsyncDialect extends AbstractImageDialect {

        // 单例实例：固定 kling 模型异步行为。
        private static final KlingImageGenerationAsyncDialect INSTANCE = new KlingImageGenerationAsyncDialect();

        @Override
        public boolean supports(String normalizedModelName) {
            // kling 文档模型名通常以 kling/ 前缀出现。
            return normalizedModelName.startsWith(DashscopeConstants.MODEL_KLING_PREFIX);
        }

        @Override
        public Map<String, Object> buildSubmitBody(String modelName, MediaImageGenerateRequest request) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put(DashscopeConstants.JSON_MODEL, modelName);
            Map<String, Object> contentItem = new LinkedHashMap<>();
            contentItem.put(DashscopeConstants.JSON_TEXT, request.getPrompt());
            Map<String, Object> message = new LinkedHashMap<>();
            message.put(DashscopeConstants.JSON_ROLE, DashscopeConstants.JSON_ROLE_USER);
            message.put(DashscopeConstants.JSON_CONTENT, Collections.singletonList(contentItem));
            Map<String, Object> input = new LinkedHashMap<>();
            input.put(DashscopeConstants.JSON_MESSAGES, Collections.singletonList(message));
            body.put(DashscopeConstants.JSON_INPUT, input);
            Map<String, Object> parameters = buildParameters(request);
            body.put(DashscopeConstants.JSON_PARAMETERS, parameters);
            mergeOptions(body, input, parameters, request);
            return body;
        }

        @Override
        public Map<String, String> extraHeaders() {
            // 可灵 HTTP 创建任务也必须加异步头。
            return Collections.singletonMap(DashscopeConstants.HEADER_ASYNC, DashscopeConstants.HEADER_ASYNC_ENABLE);
        }
    }

    /**
     * wanx2.1-imageedit 图像编辑方言。
     */
    private static final class WanxImageEditAsyncDialect extends AbstractImageDialect {

        private static final WanxImageEditAsyncDialect INSTANCE = new WanxImageEditAsyncDialect();

        /** 默认编辑功能：指令编辑（无需指定区域，仅通过指令增加/修改图片内容） */
        private static final String DEFAULT_FUNCTION = "description_edit";

        @Override
        public boolean supports(String normalizedModelName) {
            // 精确匹配 wanx2.1-imageedit，避免误命中其他 wanx2.* 模型
            return DashscopeConstants.MODEL_WANX_IMAGE_EDIT.equals(normalizedModelName);
        }

        @Override
        public Map<String, Object> buildSubmitBody(String modelName, MediaImageGenerateRequest request) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put(DashscopeConstants.JSON_MODEL, modelName);

            Map<String, Object> input = new LinkedHashMap<>();
            Map<String, Object> options = request.getOptions();

            String function = DEFAULT_FUNCTION;
            if (options != null) {
                Object funcObj = options.get("function");
                if (funcObj != null && StrUtil.isNotBlank(String.valueOf(funcObj))) {
                    function = String.valueOf(funcObj);
                }
            }
            input.put("function", function);
            input.put(DashscopeConstants.JSON_PROMPT, request.getPrompt());

            String baseImageUrl = request.getReferenceImageUrl();
            if (StrUtil.isBlank(baseImageUrl) && options != null) {
                Object refImagesVal = options.get("referenceImages");
                if (refImagesVal instanceof List<?> refList && !refList.isEmpty()) {
                    Object first = refList.get(0);
                    if (first != null && StrUtil.isNotBlank(String.valueOf(first))) {
                        baseImageUrl = String.valueOf(first);
                    }
                }
            }
            if (StrUtil.isNotBlank(baseImageUrl)) {
                input.put("base_image_url", baseImageUrl);
            }

            if (options != null) {
                Object maskObj = options.get("mask_image_url");
                if (maskObj != null && StrUtil.isNotBlank(String.valueOf(maskObj))) {
                    input.put("mask_image_url", String.valueOf(maskObj));
                }
            }

            body.put(DashscopeConstants.JSON_INPUT, input);

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put(DashscopeConstants.JSON_N, 1);
            if (options != null) {
                Object optParamsObj = options.get(DashscopeConstants.OPTIONS_KEY_PARAMETERS);
                if (optParamsObj instanceof Map<?, ?> optParamsRaw) {
                    Map<String, Object> optParams = toStringObjectMap(optParamsRaw);
                    // 仅保留 wanx2.1-imageedit 官方支持的参数
                    for (String allowed : new String[]{"n", "seed", "watermark", "strength"}) {
                        Object v = optParams.get(allowed);
                        if (v != null) {
                            parameters.put(allowed, v);
                        }
                    }
                }
            }
            body.put(DashscopeConstants.JSON_PARAMETERS, parameters);

            // 不调用 mergeOptions：wanx2.1-imageedit 请求体结构固定，
            // 避免 aspect_ratio / force_single / size 等平台字段污染上游请求体
            return body;
        }

        @Override
        public Map<String, String> extraHeaders() {
            // 官方文档要求：HTTP 调用只支持异步，必须设置 X-DashScope-Async: enable
            return Collections.singletonMap(DashscopeConstants.HEADER_ASYNC, DashscopeConstants.HEADER_ASYNC_ENABLE);
        }
    }

    private static final class WanLegacyAsyncDialect extends AbstractImageDialect {

        // 单例实例：固定 wan2.5 及以下异步行为。
        private static final WanLegacyAsyncDialect INSTANCE = new WanLegacyAsyncDialect();

        @Override
        public boolean supports(String normalizedModelName) {
            // 匹配 wan2.5/wan2.2/wanx2.* 等旧模型族（排除 wan2.6 和 wan2.7）。
            return (normalizedModelName.startsWith(DashscopeConstants.MODEL_WAN_2_DOT_PREFIX)
                || normalizedModelName.startsWith(DashscopeConstants.MODEL_WANX_2_PREFIX))
                && !normalizedModelName.startsWith(DashscopeConstants.MODEL_WAN_26_PREFIX)
                && !normalizedModelName.startsWith(DashscopeConstants.MODEL_WAN_27_PREFIX);
        }

        @Override
        public Map<String, Object> buildSubmitBody(String modelName, MediaImageGenerateRequest request) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put(DashscopeConstants.JSON_MODEL, modelName);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put(DashscopeConstants.JSON_PROMPT, request.getPrompt());
            if (StrUtil.isNotBlank(request.getNegativePrompt())) {
                input.put(DashscopeConstants.JSON_NEGATIVE_PROMPT, request.getNegativePrompt());
            }
            body.put(DashscopeConstants.JSON_INPUT, input);
            Map<String, Object> parameters = buildParameters(request);
            body.put(DashscopeConstants.JSON_PARAMETERS, parameters);
            mergeOptions(body, input, parameters, request);
            return body;
        }

        @Override
        public Map<String, String> extraHeaders() {
            // 异步任务必须附带异步头。
            return Collections.singletonMap(DashscopeConstants.HEADER_ASYNC, DashscopeConstants.HEADER_ASYNC_ENABLE);
        }
    }

    private static final class LegacyText2ImageAsyncDialect extends AbstractImageDialect {

        // 单例实例：作为最终兜底，确保历史未知模型不至于直接不可用。
        private static final LegacyText2ImageAsyncDialect INSTANCE = new LegacyText2ImageAsyncDialect();

        @Override
        public boolean supports(String normalizedModelName) {
            // 兜底方言匹配全部模型。
            return true;
        }

        @Override
        public Map<String, Object> buildSubmitBody(String modelName, MediaImageGenerateRequest request) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put(DashscopeConstants.JSON_MODEL, modelName);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put(DashscopeConstants.JSON_PROMPT, request.getPrompt());
            if (StrUtil.isNotBlank(request.getNegativePrompt())) {
                input.put(DashscopeConstants.JSON_NEGATIVE_PROMPT, request.getNegativePrompt());
            }
            body.put(DashscopeConstants.JSON_INPUT, input);
            Map<String, Object> parameters = buildParameters(request);
            body.put(DashscopeConstants.JSON_PARAMETERS, parameters);
            mergeOptions(body, input, parameters, request);
            return body;
        }

        @Override
        public Map<String, String> extraHeaders() {
            // 默认按异步头处理，与历史 text2image 行为一致。
            return Collections.singletonMap(DashscopeConstants.HEADER_ASYNC, DashscopeConstants.HEADER_ASYNC_ENABLE);
        }
    }
}
