package com.aid.media.provider.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.aid.common.constant.HttpConstants;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.ConfigurableAsyncMediaConstants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceAudioInput;
import com.aid.media.provider.ModelCodeResolver;
import com.aid.media.provider.ProviderErrorSanitizer;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.ReferenceAudioLimiter;
import com.aid.media.provider.ReferenceImageLimiter;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.provider.VideoProviderClient;
import com.aid.media.util.ModelCapabilityResolver;
import com.fasterxml.jackson.databind.JsonNode;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 通过数据库端点配置接入的异步视频任务客户端。 */
@Slf4j
@Component
public class ConfigurableAsyncVideoProviderClient implements VideoProviderClient {

    @Override
    public String protocol() {
        return ConfigurableAsyncMediaConstants.PROTOCOL_VIDEO;
    }

    @Override
    public Integer fallbackMaxReferenceImages(AiModelConfigVo modelConfig) {
        return 1;
    }

    @Override
    public Integer fallbackMaxReferenceVideos(AiModelConfigVo modelConfig) {
        return 0;
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        requireConfig(modelConfig);
        validateFullRequest(modelConfig, request);
        List<String> images = referenceImages(modelConfig, request);
        List<String> videos = referenceVideos(modelConfig, request);
        List<ReferenceAudioInput> audios = referenceAudios(modelConfig, request);
        ReferencePromptSanitizer.sanitizeInPlaceForIndexedMedia(
                request, images.size(), videos.size(), audios.size());

        Map<String, Object> body = new LinkedHashMap<>();
        String upstreamModel = ModelCodeResolver.resolveUpstreamModel(modelConfig,
                request == null ? null : request.getModelName());
        body.put("model", upstreamModel);
        body.put("prompt", request == null ? "" : StrUtil.nullToEmpty(request.getPrompt()));
        if (request != null && request.getDurationSeconds() != null) {
            body.put("duration", request.getDurationSeconds());
        }
        String ratio = ModelCapabilityResolver.resolveVideoAspectRatio(modelConfig,
                request == null ? null : request.getAspectRatio());
        if (StrUtil.isNotBlank(ratio)) {
            body.put("ratio", ratio);
        }
        String resolution = resolveResolution(modelConfig, request);
        if (StrUtil.isNotBlank(resolution)) {
            body.put("resolution", resolution);
        }
        if (!images.isEmpty()) {
            body.put("referenceImages", images);
        }
        if (!videos.isEmpty()) {
            body.put("referenceVideos", videos);
        }
        List<String> audioUrls = audios.stream()
                .map(ReferenceAudioInput::getSampleUrl)
                .filter(StrUtil::isNotBlank)
                .toList();
        if (!audioUrls.isEmpty()) {
            body.put("referenceAudios", audioUrls);
        }
        appendGenerateAudio(body, modelConfig, request);

        HttpResult response = executePost(buildSubmitUrl(modelConfig), modelConfig,
                JSONUtil.toJsonStr(body));
        if (!isSuccess(response.statusCode()) || !JSONUtil.isTypeJSON(response.body())) {
            log.error("可配置异步视频提交失败, modelCode={}, upstreamModel={}, detail={}",
                    modelConfig.getModelCode(), upstreamModel,
                    ProviderErrorSanitizer.fromHttp(response.statusCode(), response.body()));
            throw new ServiceException("上游提交失败");
        }
        JsonNode root = ProviderResponseHelper.readTree(response.body());
        String taskId = ProviderResponseHelper.readText(root, "task_id", "id", "data.task_id", "data.id");
        if (StrUtil.isBlank(taskId)) {
            log.error("可配置异步视频提交响应缺少任务编号, modelCode={}, upstreamModel={}, responseLength={}",
                    modelConfig.getModelCode(), upstreamModel, StrUtil.length(response.body()));
            throw new ServiceException("上游提交失败");
        }
        return ProviderSubmitResult.builder()
                .providerTaskId(taskId)
                .rawResponse(response.body())
                .build();
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
            log.warn("可配置异步视频查询不可用, taskId={}, error={}",
                    providerTaskId, ex.getClass().getSimpleName());
            return queryAnomaly(null, null, "上游查询暂不可用");
        }
        if (!isSuccess(response.statusCode()) || !JSONUtil.isTypeJSON(response.body())) {
            log.warn("可配置异步视频查询异常, taskId={}, httpStatus={}, responseLength={}",
                    providerTaskId, response.statusCode(), StrUtil.length(response.body()));
            return queryAnomaly(response.body(), null, "上游查询暂不可用");
        }
        JsonNode root = ProviderResponseHelper.readTree(response.body());
        String providerStatus = ProviderResponseHelper.readText(root, "status", "data.status");
        String normalizedStatus = normalizeStatus(providerStatus);
        if (normalizedStatus == null) {
            log.warn("可配置异步视频返回未知任务状态, taskId={}, status={}", providerTaskId, providerStatus);
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
        String resultUrl = ProviderResponseHelper.readText(root, "url", "data.url");
        if (StrUtil.isBlank(resultUrl)) {
            log.warn("可配置异步视频成功响应缺少产物地址, taskId={}", providerTaskId);
            return queryAnomaly(response.body(), providerStatus, "上游产物尚未就绪");
        }
        return ProviderTaskResult.builder()
                .status(normalizedStatus)
                .resultUrl(resolveResultUrl(modelConfig, resultUrl))
                .videoDurationSeconds(readPositiveSeconds(root, "seconds", "data.seconds"))
                .progress(readInteger(root, "progress", "data.progress"))
                .rawResponse(response.body())
                .querySuccessful(Boolean.TRUE)
                .providerStatus(providerStatus)
                .terminalConfirmed(Boolean.TRUE)
                .build();
    }

    /** 协议级前置校验；由统一生成入口在任务落库和预冻结前调用。 */
    public static void validateFullRequest(AiModelConfigVo modelConfig,
                                           MediaVideoGenerateRequest request) {
        int maxPromptLength = capabilityInteger(modelConfig,
                ConfigurableAsyncMediaConstants.CAPABILITY_MAX_PROMPT_CHARACTERS, -1);
        String prompt = request == null ? null : request.getPrompt();
        if (maxPromptLength >= 0 && StrUtil.length(prompt) > maxPromptLength) {
            log.error("可配置异步视频提示词超过上限, modelCode={}, max={}, actual={}",
                    modelConfig == null ? null : modelConfig.getModelCode(),
                    maxPromptLength, StrUtil.length(prompt));
            throw new ServiceException("提示词过长");
        }
    }

    static String buildSubmitUrl(AiModelConfigVo modelConfig) {
        return ProviderEndpointUtils.buildSubmitUrl(modelConfig.getBaseUrl(), modelConfig.getApiSuffix());
    }

    static String buildQueryUrl(AiModelConfigVo modelConfig, String providerTaskId) {
        return ProviderEndpointUtils.buildTaskQueryUrl(
                modelConfig.getBaseUrl(), modelConfig.getTaskQuerySuffix(), providerTaskId);
    }

    private static List<String> referenceImages(AiModelConfigVo modelConfig,
                                                 MediaVideoGenerateRequest request) {
        List<String> result = new ArrayList<>();
        if (request == null) {
            return result;
        }
        addUrl(result, request.getImageUrl());
        addUrls(result, option(request, "referenceImages"));
        addUrls(result, option(request, "images"));
        return ReferenceImageLimiter.limit(result, modelConfig, 1, "可配置异步视频");
    }

    private static List<String> referenceVideos(AiModelConfigVo modelConfig,
                                                 MediaVideoGenerateRequest request) {
        List<String> result = new ArrayList<>();
        if (request == null) {
            return result;
        }
        addUrl(result, stringOption(request, "referenceVideoUrl", "featureVideoUrl"));
        addUrl(result, stringOption(request, "baseVideoUrl", "inputVideoUrl"));
        addUrl(result, stringOption(request, "videoUrl", "video_url"));
        addUrls(result, option(request, "referenceVideos"));
        addUrls(result, option(request, "videos"));
        int max = capabilityInteger(modelConfig,
                ConfigurableAsyncMediaConstants.CAPABILITY_MAX_REFERENCE_VIDEOS, 0);
        if (max == 0) {
            if (!result.isEmpty()) {
                log.warn("可配置异步视频模型禁止参考视频，已丢弃输入, modelCode={}, actual={}",
                        modelConfig.getModelCode(), result.size());
            }
            return new ArrayList<>();
        }
        if (max > 0 && result.size() > max) {
            log.warn("可配置异步视频参考视频超过上限按顺序截断, modelCode={}, max={}, actual={}",
                    modelConfig.getModelCode(), max, result.size());
            return new ArrayList<>(result.subList(0, max));
        }
        return result;
    }

    private static List<ReferenceAudioInput> referenceAudios(AiModelConfigVo modelConfig,
                                                              MediaVideoGenerateRequest request) {
        ReferenceAudioLimiter.ReferenceAudioCapability capability =
                ReferenceAudioLimiter.readCapability(modelConfig);
        if (!capability.isUsable()) {
            if (request != null && request.getReferenceAudios() != null
                    && !request.getReferenceAudios().isEmpty()) {
                log.warn("可配置异步视频模型禁止或未完整配置参考音频，已丢弃输入, modelCode={}, actual={}",
                        modelConfig.getModelCode(), request.getReferenceAudios().size());
            }
            return new ArrayList<>();
        }
        return ReferenceAudioLimiter.limit(
                        request == null ? null : request.getReferenceAudios(),
                        modelConfig, "可配置异步视频")
                .stream()
                .filter(audio -> audio != null && StrUtil.isNotBlank(audio.getSampleUrl()))
                .toList();
    }

    private static String resolveResolution(AiModelConfigVo modelConfig,
                                            MediaVideoGenerateRequest request) {
        String configured = capabilityText(modelConfig,
                ConfigurableAsyncMediaConstants.CAPABILITY_UPSTREAM_RESOLUTION);
        if (StrUtil.isNotBlank(configured)) {
            return configured;
        }
        String requested = stringOption(request, "resolution", "size");
        String resolved = ModelCapabilityResolver.resolveSize(modelConfig, requested);
        if (StrUtil.isBlank(resolved)) {
            return null;
        }
        String mapped = capabilityMappedText(modelConfig,
                ConfigurableAsyncMediaConstants.CAPABILITY_UPSTREAM_RESOLUTION_MAP, resolved);
        if (StrUtil.isNotBlank(mapped)) {
            return mapped;
        }
        String normalized = resolved.trim().toLowerCase(Locale.ROOT);
        return "2k".equals(normalized) ? "1440p" : normalized;
    }

    private static String capabilityMappedText(AiModelConfigVo modelConfig, String key, String source) {
        JsonNode capability = ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson());
        JsonNode mapping = capability == null ? null : capability.get(key);
        if (mapping == null || mapping.isNull()) {
            return null;
        }
        if (!mapping.isObject()) {
            log.error("可配置异步视频映射配置非法, modelCode={}, key={}", modelConfig.getModelCode(), key);
            throw new ServiceException("模型配置错误");
        }
        var fields = mapping.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getKey().equalsIgnoreCase(source.trim())) {
                continue;
            }
            String target = entry.getValue().isTextual() ? StrUtil.trim(entry.getValue().asText()) : null;
            if (StrUtil.isBlank(target)) {
                log.error("可配置异步视频映射值非法, modelCode={}, key={}, source={}",
                        modelConfig.getModelCode(), key, source);
                throw new ServiceException("模型配置错误");
            }
            return target;
        }
        return null;
    }

    private static void appendGenerateAudio(Map<String, Object> body, AiModelConfigVo modelConfig,
                                            MediaVideoGenerateRequest request) {
        boolean forceGenerateAudio = capabilityBoolean(modelConfig,
                ConfigurableAsyncMediaConstants.CAPABILITY_FORCE_GENERATE_AUDIO);
        boolean supportsAudio = capabilityBoolean(modelConfig,
                ConfigurableAsyncMediaConstants.CAPABILITY_SUPPORTS_AUDIO);
        if (!forceGenerateAudio && !supportsAudio) {
            return;
        }
        String upstreamAudioField = resolveUpstreamAudioField(modelConfig);
        if (ConfigurableAsyncMediaConstants.UPSTREAM_AUDIO_FIELD_NONE.equals(upstreamAudioField)) {
            return;
        }
        if (forceGenerateAudio) {
            body.put(upstreamAudioField, true);
            return;
        }
        Boolean requested = request == null ? null : request.getAudio();
        if (requested == null) {
            requested = booleanOption(request, "generate_audio");
        }
        if (requested != null) {
            body.put(upstreamAudioField, requested);
        }
    }

    private static String resolveUpstreamAudioField(AiModelConfigVo modelConfig) {
        String configured = StrUtil.blankToDefault(capabilityText(modelConfig,
                ConfigurableAsyncMediaConstants.CAPABILITY_UPSTREAM_AUDIO_FIELD), "generate_audio").trim();
        if ("audio".equals(configured) || "generate_audio".equals(configured)
                || ConfigurableAsyncMediaConstants.UPSTREAM_AUDIO_FIELD_NONE.equals(configured)) {
            return configured;
        }
        log.error("可配置异步视频音频字段非法, modelCode={}, field={}",
                modelConfig == null ? null : modelConfig.getModelCode(), configured);
        throw new ServiceException("模型配置错误");
    }

    private static Boolean booleanOption(MediaVideoGenerateRequest request, String key) {
        Object value = option(request, key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value != null) {
            String text = String.valueOf(value).trim();
            if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static Object option(MediaVideoGenerateRequest request, String key) {
        return request == null || request.getOptions() == null ? null : request.getOptions().get(key);
    }

    private static String stringOption(MediaVideoGenerateRequest request, String... keys) {
        if (request == null || request.getOptions() == null) {
            return null;
        }
        for (String key : keys) {
            Object value = request.getOptions().get(key);
            if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value);
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
            Integer value = parseInteger(node, false);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer readPositiveSeconds(JsonNode root, String... paths) {
        for (String path : paths) {
            Integer value = parseInteger(ProviderResponseHelper.nodeByPath(root, path), true);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer parseInteger(JsonNode node, boolean positive) {
        if (node == null || node.isNull() || (!node.isNumber() && !node.isTextual())) {
            return null;
        }
        try {
            int value = new BigDecimal(node.asText()).setScale(0, RoundingMode.CEILING).intValueExact();
            return positive ? (value > 0 ? value : null) : (value >= 0 ? value : null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String resolveResultUrl(AiModelConfigVo modelConfig, String rawUrl) {
        String value = rawUrl.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        return ProviderEndpointUtils.buildSubmitUrl(modelConfig.getBaseUrl(), value);
    }

    private static boolean capabilityBoolean(AiModelConfigVo modelConfig, String key) {
        JsonNode capability = ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson());
        return capability != null && capability.path(key).asBoolean(false);
    }

    private static int capabilityInteger(AiModelConfigVo modelConfig, String key, int defaultValue) {
        JsonNode capability = ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson());
        return capability == null ? defaultValue : capability.path(key).asInt(defaultValue);
    }

    private static String capabilityText(AiModelConfigVo modelConfig, String key) {
        return ModelCapabilityResolver.readText(
                ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson()), key);
    }

    private static HttpResult executePost(String url, AiModelConfigVo modelConfig, String body) {
        try (HttpResponse response = applyAuth(HttpRequest.post(url), modelConfig)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON, true)
                .body(body)
                .timeout(ConfigurableAsyncMediaConstants.HTTP_TIMEOUT_MS)
                .execute()) {
            return new HttpResult(response.getStatus(), response.body());
        } catch (Exception ex) {
            log.error("可配置异步视频提交网络异常, modelCode={}, error={}",
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
            log.error("可配置异步视频配置不完整, modelCode={}",
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
