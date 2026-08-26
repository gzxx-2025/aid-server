package com.aid.media.provider.impl;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.aid.common.constant.HttpConstants;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.common.utils.image.ImageUrlValidator;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.ConfigurableAsyncMediaConstants;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.provider.ImageProviderClient;
import com.aid.media.provider.ModelCodeResolver;
import com.aid.media.provider.ProviderErrorSanitizer;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.ReferenceImageLimiter;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.util.ModelCapabilityResolver;
import com.fasterxml.jackson.databind.JsonNode;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 通过数据库端点配置接入的异步图片任务客户端。 */
@Slf4j
@Component
public class ConfigurableAsyncImageProviderClient implements ImageProviderClient {

    @Override
    public String protocol() {
        return ConfigurableAsyncMediaConstants.PROTOCOL_IMAGE;
    }

    @Override
    public Integer fallbackMaxReferenceImages(AiModelConfigVo modelConfig) {
        return 1;
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        requireConfig(modelConfig);
        List<String> references = referenceImages(modelConfig, request);
        ReferencePromptSanitizer.sanitizeInPlace(request, references.size());
        boolean edit = !references.isEmpty();
        HttpResult response = edit
                ? submitEdit(modelConfig, request, references.get(0))
                : submitGeneration(modelConfig, request);
        if (!isSuccess(response.statusCode()) || !JSONUtil.isTypeJSON(response.body())) {
            log.error("可配置异步图片提交失败, modelCode={}, detail={}", modelConfig.getModelCode(),
                    ProviderErrorSanitizer.fromHttp(response.statusCode(), response.body()));
            throw new ServiceException("上游提交失败");
        }
        JsonNode root = ProviderResponseHelper.readTree(response.body());
        String taskId = ProviderResponseHelper.readText(root, "task_id", "id", "data.task_id", "data.id");
        if (StrUtil.isNotBlank(taskId)) {
            return ProviderSubmitResult.builder()
                    .providerTaskId(taskId)
                    .rawResponse(response.body())
                    .build();
        }
        List<String> resultUrls = resultUrls(modelConfig, root);
        if (!resultUrls.isEmpty()) {
            return ProviderSubmitResult.builder()
                    .directUrl(resultUrls.get(0))
                    .resultUrls(resultUrls)
                    .resultCount(resultUrls.size())
                    .rawResponse(response.body())
                    .build();
        }
        log.error("可配置异步图片提交响应缺少任务编号, modelCode={}, responseLength={}",
                modelConfig.getModelCode(), StrUtil.length(response.body()));
        throw new ServiceException("上游提交失败");
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        requireConfig(modelConfig);
        if (StrUtil.isBlank(providerTaskId)) {
            return queryAnomaly(null, null, "任务编号为空");
        }
        HttpResult response;
        try {
            response = executeGet(buildQueryUrl(modelConfig, providerTaskId), modelConfig);
        } catch (Exception ex) {
            log.warn("可配置异步图片查询不可用, taskId={}, error={}",
                    providerTaskId, ex.getClass().getSimpleName());
            return queryAnomaly(null, null, "上游查询暂不可用");
        }
        if (!isSuccess(response.statusCode()) || !JSONUtil.isTypeJSON(response.body())) {
            log.warn("可配置异步图片查询异常, taskId={}, httpStatus={}, responseLength={}",
                    providerTaskId, response.statusCode(), StrUtil.length(response.body()));
            return queryAnomaly(response.body(), null, "上游查询暂不可用");
        }
        JsonNode root = ProviderResponseHelper.readTree(response.body());
        String providerStatus = ProviderResponseHelper.readText(root, "status", "data.status");
        String normalizedStatus = normalizeStatus(providerStatus);
        if (normalizedStatus == null) {
            log.warn("可配置异步图片返回未知任务状态, taskId={}, status={}", providerTaskId, providerStatus);
            return queryAnomaly(response.body(), providerStatus, "上游返回未知状态");
        }
        if (ConfigurableAsyncMediaConstants.STATUS_PROCESSING.equals(normalizedStatus)) {
            return ProviderTaskResult.builder()
                    .status(normalizedStatus)
                    .progress(readInteger(root, "progress", "data.progress"))
                    .rawResponse(response.body())
                    .querySuccessful(Boolean.TRUE)
                    .providerStatus(providerStatus)
                    .terminalConfirmed(Boolean.FALSE)
                    .build();
        }
        if (ConfigurableAsyncMediaConstants.STATUS_FAILED.equals(normalizedStatus)) {
            String rawError = ProviderResponseHelper.readText(root,
                    "error.message", "message", "detail", "error", "data.error.message", "data.message");
            return ProviderTaskResult.builder()
                    .status(normalizedStatus)
                    .errorMessage("上游任务执行失败")
                    .rawErrorMessage(ProviderErrorSanitizer.safeMessage(rawError, "上游任务执行失败"))
                    .rawResponse(response.body())
                    .querySuccessful(Boolean.TRUE)
                    .providerStatus(providerStatus)
                    .terminalConfirmed(Boolean.TRUE)
                    .build();
        }
        List<String> urls = resultUrls(modelConfig, root);
        if (urls.isEmpty()) {
            log.warn("可配置异步图片成功响应缺少产物地址, taskId={}", providerTaskId);
            return queryAnomaly(response.body(), providerStatus, "上游产物尚未就绪");
        }
        return ProviderTaskResult.builder()
                .status(normalizedStatus)
                .resultUrl(urls.get(0))
                .resultUrls(urls)
                .resultCount(urls.size())
                .progress(readInteger(root, "progress", "data.progress"))
                .rawResponse(response.body())
                .querySuccessful(Boolean.TRUE)
                .providerStatus(providerStatus)
                .terminalConfirmed(Boolean.TRUE)
                .build();
    }

    static String buildSubmitUrl(AiModelConfigVo modelConfig, boolean edit) {
        String path = operationPath(modelConfig.getApiSuffix(), edit
                ? ConfigurableAsyncMediaConstants.OPERATION_EDITS
                : ConfigurableAsyncMediaConstants.OPERATION_GENERATIONS);
        return ProviderEndpointUtils.buildSubmitUrl(modelConfig.getBaseUrl(), path);
    }

    static String buildQueryUrl(AiModelConfigVo modelConfig, String providerTaskId) {
        String generationPath = operationPath(modelConfig.getApiSuffix(),
                ConfigurableAsyncMediaConstants.OPERATION_GENERATIONS);
        String queryTemplate = generationPath.endsWith("/")
                ? generationPath + "%s" : generationPath + "/%s";
        return ProviderEndpointUtils.buildTaskQueryUrl(
                modelConfig.getBaseUrl(), queryTemplate, providerTaskId);
    }

    private static String operationPath(String template, String operation) {
        String normalized = ProviderEndpointUtils.normalizeSubmitPath(template);
        int first = normalized.indexOf(ConfigurableAsyncMediaConstants.OPERATION_PLACEHOLDER);
        if (first >= 0) {
            if (first != normalized.lastIndexOf(ConfigurableAsyncMediaConstants.OPERATION_PLACEHOLDER)) {
                throw new IllegalArgumentException("模型路径模板无效");
            }
            return normalized.replace(ConfigurableAsyncMediaConstants.OPERATION_PLACEHOLDER, operation);
        }
        String generationSegment = "/" + ConfigurableAsyncMediaConstants.OPERATION_GENERATIONS + "/";
        String editSegment = "/" + ConfigurableAsyncMediaConstants.OPERATION_EDITS + "/";
        if (ConfigurableAsyncMediaConstants.OPERATION_EDITS.equals(operation)
                && normalized.contains(generationSegment)) {
            return normalized.replace(generationSegment, editSegment);
        }
        if (ConfigurableAsyncMediaConstants.OPERATION_GENERATIONS.equals(operation)
                && normalized.contains(editSegment)) {
            return normalized.replace(editSegment, generationSegment);
        }
        return normalized;
    }

    private static HttpResult submitGeneration(AiModelConfigVo modelConfig,
                                                MediaImageGenerateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", ModelCodeResolver.resolveUpstreamModel(modelConfig,
                request == null ? null : request.getModelName()));
        body.put("prompt", request == null ? "" : StrUtil.nullToEmpty(request.getPrompt()));
        body.put("n", resolveImageCount(modelConfig, request));
        String size = resolveSize(modelConfig, request);
        if (StrUtil.isNotBlank(size)) {
            body.put("size", size);
        }
        return executeJsonPost(buildSubmitUrl(modelConfig, false), modelConfig, JSONUtil.toJsonStr(body));
    }

    private static HttpResult submitEdit(AiModelConfigVo modelConfig,
                                         MediaImageGenerateRequest request, String imageUrl) {
        byte[] imageBytes = downloadReferenceImage(imageUrl);
        String fileName = resolveFileName(imageUrl);
        HttpRequest httpRequest = applyAuth(HttpRequest.post(buildSubmitUrl(modelConfig, true)), modelConfig)
                .form("model", ModelCodeResolver.resolveUpstreamModel(modelConfig,
                        request == null ? null : request.getModelName()))
                .form("prompt", request == null ? "" : StrUtil.nullToEmpty(request.getPrompt()))
                .form("image", imageBytes, fileName);
        String size = resolveSize(modelConfig, request);
        if (StrUtil.isNotBlank(size)) {
            httpRequest.form("size", size);
        }
        try (HttpResponse response = httpRequest
                .timeout(ConfigurableAsyncMediaConstants.HTTP_TIMEOUT_MS)
                .execute()) {
            return new HttpResult(response.getStatus(), response.body());
        } catch (Exception ex) {
            log.error("可配置异步图片编辑提交网络异常, modelCode={}, error={}",
                    modelConfig.getModelCode(), ex.getClass().getSimpleName());
            throw new ServiceException("上游提交失败");
        }
    }

    static byte[] downloadReferenceImage(String imageUrl) {
        if (!ImageUrlValidator.validateImageUrlFormat(imageUrl).isValid()
                || !ImageUrlValidator.validateRemoteImageUrl(imageUrl).isValid()) {
            throw new ServiceException("参考图地址无效");
        }
        try (HttpResponse response = HttpRequest.get(imageUrl)
                .setFollowRedirects(false)
                .timeout(ConfigurableAsyncMediaConstants.IMAGE_DOWNLOAD_TIMEOUT_MS)
                .executeAsync()) {
            if (!response.isOk()) {
                throw new ServiceException("参考图下载失败");
            }
            String contentType = StrUtil.trimToEmpty(response.header("Content-Type")).toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("image/")) {
                throw new ServiceException("参考图格式无效");
            }
            String contentLength = response.header("Content-Length");
            if (StrUtil.isNotBlank(contentLength)) {
                try {
                    if (Long.parseLong(contentLength) > ConfigurableAsyncMediaConstants.MAX_REFERENCE_IMAGE_BYTES) {
                        throw new ServiceException("参考图文件过大");
                    }
                } catch (NumberFormatException ignored) {
                    // 未知长度由实际字节上限继续校验。
                }
            }
            try (InputStream input = response.bodyStream()) {
                return readBoundedReferenceImage(input);
            }
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("可配置异步图片参考图下载失败, error={}", ex.getClass().getSimpleName());
            throw new ServiceException("参考图下载失败");
        }
    }

    static byte[] readBoundedReferenceImage(InputStream input) throws Exception {
        if (input == null) {
            throw new ServiceException("参考图下载失败");
        }
        byte[] bytes = input.readNBytes(ConfigurableAsyncMediaConstants.MAX_REFERENCE_IMAGE_BYTES + 1);
        if (bytes.length == 0) {
            throw new ServiceException("参考图下载失败");
        }
        if (bytes.length > ConfigurableAsyncMediaConstants.MAX_REFERENCE_IMAGE_BYTES) {
            throw new ServiceException("参考图文件过大");
        }
        return bytes;
    }

    private static String resolveFileName(String imageUrl) {
        String path = imageUrl.split("[?#]", 2)[0];
        int slash = path.lastIndexOf('/');
        String candidate = slash >= 0 ? path.substring(slash + 1) : path;
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp")) {
            return candidate;
        }
        return "reference.png";
    }

    private static List<String> referenceImages(AiModelConfigVo modelConfig,
                                                 MediaImageGenerateRequest request) {
        List<String> result = new ArrayList<>();
        if (request == null) {
            return result;
        }
        addUrl(result, request.getReferenceImageUrl());
        if (request.getOptions() != null) {
            addUrls(result, request.getOptions().get("referenceImages"));
            addUrls(result, request.getOptions().get("images"));
        }
        return ReferenceImageLimiter.limit(result, modelConfig, 1, "可配置异步图片");
    }

    private static int resolveImageCount(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        int count = request == null || request.getExpectedImageCount() == null
                ? 1 : Math.max(1, request.getExpectedImageCount());
        if (modelConfig.getMaxOutputCount() != null && modelConfig.getMaxOutputCount() > 0) {
            count = Math.min(count, modelConfig.getMaxOutputCount());
        }
        return count;
    }

    private static String resolveSize(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        JsonNode capability = ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson());
        String configured = ModelCapabilityResolver.readText(capability, "upstreamSize");
        if (StrUtil.isNotBlank(configured)) {
            return configured;
        }
        String resolved = ModelCapabilityResolver.resolveSize(modelConfig,
                request == null ? null : request.getSize());
        return resolved != null && "1k".equalsIgnoreCase(resolved.trim()) ? "1024x1024" : resolved;
    }

    private static List<String> resultUrls(AiModelConfigVo modelConfig, JsonNode root) {
        List<String> result = new ArrayList<>();
        addResultUrl(result, modelConfig, ProviderResponseHelper.readText(root, "url", "data.url"));
        collectResultArray(result, modelConfig, ProviderResponseHelper.nodeByPath(root, "data"));
        collectResultArray(result, modelConfig, ProviderResponseHelper.nodeByPath(root, "images"));
        collectResultArray(result, modelConfig, ProviderResponseHelper.nodeByPath(root, "output"));
        collectResultArray(result, modelConfig, ProviderResponseHelper.nodeByPath(root, "results"));
        return result;
    }

    private static void collectResultArray(List<String> target, AiModelConfigVo modelConfig, JsonNode node) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            if (item.isTextual()) {
                addResultUrl(target, modelConfig, item.asText());
            } else if (item.isObject()) {
                addResultUrl(target, modelConfig,
                        ProviderResponseHelper.readText(item, "url", "image_url"));
            }
        }
    }

    private static void addResultUrl(List<String> target, AiModelConfigVo modelConfig, String rawUrl) {
        if (StrUtil.isBlank(rawUrl)) {
            return;
        }
        String value = rawUrl.trim();
        String resolved = value.startsWith("http://") || value.startsWith("https://")
                ? value : ProviderEndpointUtils.buildSubmitUrl(modelConfig.getBaseUrl(), value);
        if (!target.contains(resolved)) {
            target.add(resolved);
        }
    }

    private static String normalizeStatus(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (ConfigurableAsyncMediaConstants.PROCESSING_STATES.contains(normalized)) {
            return ConfigurableAsyncMediaConstants.STATUS_PROCESSING;
        }
        if (ConfigurableAsyncMediaConstants.SUCCEEDED_STATES.contains(normalized)) {
            return ConfigurableAsyncMediaConstants.STATUS_SUCCEEDED;
        }
        if (ConfigurableAsyncMediaConstants.FAILED_STATES.contains(normalized)) {
            return ConfigurableAsyncMediaConstants.STATUS_FAILED;
        }
        return null;
    }

    private static ProviderTaskResult queryAnomaly(String raw, String providerStatus, String message) {
        return ProviderTaskResult.builder()
                .status(ConfigurableAsyncMediaConstants.STATUS_PROCESSING)
                .errorMessage(message)
                .rawResponse(raw)
                .querySuccessful(Boolean.FALSE)
                .providerStatus(providerStatus)
                .terminalConfirmed(Boolean.FALSE)
                .build();
    }

    private static Integer readInteger(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = ProviderResponseHelper.nodeByPath(root, path);
            if (node != null && !node.isNull() && (node.isNumber() || node.isTextual())) {
                try {
                    int value = Integer.parseInt(node.asText());
                    if (value >= 0) {
                        return value;
                    }
                } catch (NumberFormatException ignored) {
                    // 非整数进度忽略。
                }
            }
        }
        return null;
    }

    private static void addUrls(List<String> target, Object raw) {
        if (!(raw instanceof List<?> values)) {
            return;
        }
        for (Object value : values) {
            addUrl(target, value == null ? null : String.valueOf(value));
        }
    }

    private static void addUrl(List<String> target, String value) {
        String url = StrUtil.trimToNull(value);
        if (url != null && !target.contains(url)) {
            target.add(url);
        }
    }

    private static HttpResult executeJsonPost(String url, AiModelConfigVo modelConfig, String body) {
        try (HttpResponse response = applyAuth(HttpRequest.post(url), modelConfig)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON, true)
                .body(body)
                .timeout(ConfigurableAsyncMediaConstants.HTTP_TIMEOUT_MS)
                .execute()) {
            return new HttpResult(response.getStatus(), response.body());
        } catch (Exception ex) {
            log.error("可配置异步图片提交网络异常, modelCode={}, error={}",
                    modelConfig.getModelCode(), ex.getClass().getSimpleName());
            throw new ServiceException("上游提交失败");
        }
    }

    private static HttpResult executeGet(String url, AiModelConfigVo modelConfig) {
        try (HttpResponse response = applyAuth(HttpRequest.get(url), modelConfig)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON, true)
                .timeout(ConfigurableAsyncMediaConstants.HTTP_TIMEOUT_MS)
                .execute()) {
            return new HttpResult(response.getStatus(), response.body());
        }
    }

    private static HttpRequest applyAuth(HttpRequest request, AiModelConfigVo modelConfig) {
        String header = StrUtil.blankToDefault(modelConfig.getAuthHeader(),
                HttpConstants.HEADER_AUTHORIZATION);
        String prefix = modelConfig.getAuthPrefix() == null
                ? HttpConstants.AUTH_BEARER_PREFIX : modelConfig.getAuthPrefix();
        return request.header(header, prefix + modelConfig.getApiKey(), true);
    }

    private static void requireConfig(AiModelConfigVo modelConfig) {
        if (modelConfig == null || StrUtil.isBlank(modelConfig.getBaseUrl())
                || StrUtil.isBlank(modelConfig.getApiKey())) {
            log.error("可配置异步图片配置不完整, modelCode={}",
                    modelConfig == null ? null : modelConfig.getModelCode());
            throw new ServiceException("模型配置不完整");
        }
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private record HttpResult(int statusCode, String body) {
    }
}
