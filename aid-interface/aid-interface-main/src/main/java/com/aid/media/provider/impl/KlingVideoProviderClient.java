package com.aid.media.provider.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.KlingConstants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.KlingErrorClassifier;
import com.aid.media.provider.KlingStatusMapper;
import com.aid.media.provider.KlingVideoRequestBuilder;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.VideoProviderClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/** 可灵 3.0 新版视频 API 客户端。 */
@Slf4j
@Component
public class KlingVideoProviderClient implements VideoProviderClient {

    private static final int SUBMIT_MAX_ATTEMPTS = 3;

    @Override
    public String protocol() {
        return KlingConstants.PROTOCOL_VIDEO;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        return KlingConstants.PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(providerCode));
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        validateBaseUrl(modelConfig);
        Map<String, Object> body = prepareSubmissionBody(modelConfig, request);
        String raw = submitWithRetry(buildSubmitUrl(modelConfig), modelConfig.getApiKey(), JSONUtil.toJsonStr(body));
        return parseSubmitResponse(raw);
    }

    static Map<String, Object> prepareSubmissionBody(AiModelConfigVo modelConfig,
                                                     MediaVideoGenerateRequest request) {
        return KlingVideoRequestBuilder.buildSubmissionBody(modelConfig, request);
    }

    static ProviderSubmitResult parseSubmitResponse(String raw) {
        JsonNode root = ProviderResponseHelper.readTree(raw);
        int code = businessCode(root);
        if (code != 0) {
            throw submissionRejectedException(200, code, raw);
        }
        String taskId = ProviderResponseHelper.readText(root, "data.id");
        if (StrUtil.isBlank(taskId)) {
            taskId = ProviderResponseHelper.readText(root, "data.task_id");
        }
        if (StrUtil.isBlank(taskId)) {
            log.error("Kling submit succeeded without task id, responseLength={}", StrUtil.length(raw));
            throw new ServiceException("上游任务号缺失");
        }
        return ProviderSubmitResult.builder().providerTaskId(taskId).rawResponse(raw).build();
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        validateBaseUrl(modelConfig);
        if (StrUtil.isBlank(providerTaskId)) {
            return anomaly(null, "任务编号为空", null);
        }
        String url = buildQueryUrl(modelConfig, providerTaskId);
        HttpResult response;
        try {
            response = doGet(url, modelConfig.getApiKey());
        } catch (Exception ex) {
            log.warn("Kling query network anomaly, taskId={}, error={}", providerTaskId, ex.getClass().getSimpleName());
            return anomaly(null, "上游查询暂不可用", null);
        }
        return parseQueryResponse(response.statusCode(), response.body(), providerTaskId);
    }

    static ProviderTaskResult parseQueryResponse(int httpStatus, String raw, String providerTaskId) {
        if (httpStatus < 200 || httpStatus >= 300 || !JSONUtil.isTypeJSON(raw)) {
            log.warn("Kling query HTTP/document anomaly, taskId={}, httpStatus={}, responseLength={}",
                providerTaskId, httpStatus, StrUtil.length(raw));
            return anomaly(raw, KlingErrorClassifier.safeMessage(httpStatus, -1), null);
        }
        JsonNode root = ProviderResponseHelper.readTree(raw);
        int code = businessCode(root);
        if (code != 0) {
            log.warn("Kling query business anomaly, taskId={}, code={}", providerTaskId, code);
            return anomaly(raw, KlingErrorClassifier.safeMessage(httpStatus, code), null);
        }
        JsonNode task = root == null ? null : root.path("data");
        if (task != null && task.isArray()) {
            task = task.isEmpty() ? null : task.get(0);
        }
        if (task == null || !task.isObject()) {
            return anomaly(raw, "上游响应缺少任务数据", null);
        }
        String providerStatus = firstText(task, "status", "task_status");
        if (!KlingStatusMapper.isKnown(providerStatus)) {
            log.warn("Kling query unknown status, taskId={}, status={}", providerTaskId, providerStatus);
            return anomaly(raw, "上游返回未知任务状态", providerStatus);
        }
        String normalized = KlingStatusMapper.normalize(providerStatus);
        VideoOutput output = findVideoOutput(task);
        if (KlingConstants.TASK_STATUS_SUCCEEDED.equals(normalized) && StrUtil.isBlank(output.url())) {
            log.warn("Kling succeeded task has no video output yet, taskId={}", providerTaskId);
            return anomaly(raw, "上游成功产物尚未就绪", providerStatus);
        }
        String rawError = KlingConstants.TASK_STATUS_FAILED.equals(normalized)
            ? firstText(task, "message", "task_status_msg") : null;
        String error = KlingConstants.TASK_STATUS_FAILED.equals(normalized)
            ? safeTaskFailure(rawError) : null;
        return ProviderTaskResult.builder()
            .status(normalized)
            .resultUrl(output.url())
            .videoDurationSeconds(output.durationSeconds())
            .errorMessage(error)
            .rawErrorMessage(rawError)
            .rawResponse(raw)
            .querySuccessful(Boolean.TRUE)
            .providerStatus(providerStatus)
            .terminalConfirmed(KlingStatusMapper.isTerminal(providerStatus))
            .build();
    }

    private String submitWithRetry(String url, String apiKey, String body) {
        HttpResult last = null;
        int code = -1;
        for (int attempt = 1; attempt <= SUBMIT_MAX_ATTEMPTS; attempt++) {
            try {
                last = doPost(url, apiKey, body);
                JsonNode root = JSONUtil.isTypeJSON(last.body()) ? ProviderResponseHelper.readTree(last.body()) : null;
                code = businessCode(root);
                if (KlingErrorClassifier.isSuccess(last.statusCode(), code)) {
                    return last.body();
                }
                // 创建任务没有幂等承诺。仅 1302/1303 明确表示限流/并发未受理，可安全重试；
                // 网络异常、5xx/500x 可能已创建成功，绝不自动重放 POST，避免重复扣费。
                if (!isExplicitlyNotAccepted(code) || attempt == SUBMIT_MAX_ATTEMPTS) {
                    break;
                }
                log.warn("Kling submit transient reject, attempt={}, httpStatus={}, code={}",
                    attempt, last.statusCode(), code);
                sleepBackoff(attempt);
            } catch (ServiceException ex) {
                throw ex;
            } catch (Exception ex) {
                log.warn("Kling submit outcome unknown; POST will not retry, error={}", ex.getClass().getSimpleName());
                throw new ServiceException("提交结果未知");
            }
        }
        int httpStatus = last == null ? 0 : last.statusCode();
        throw submissionRejectedException(httpStatus, code, last == null ? null : last.body());
    }

    /**
     * 创建阶段被拒绝时仅保留排障所需字段，避免完整代理响应中的未知敏感字段进入日志/错误样本。
     */
    static ServiceException submissionRejectedException(int httpStatus, int businessCode, String raw) {
        JsonNode root = StrUtil.isNotBlank(raw) && JSONUtil.isTypeJSON(raw)
            ? ProviderResponseHelper.readTree(raw) : null;
        String upstreamMessage = firstNonBlank(
            ProviderResponseHelper.readText(root, "message"),
            ProviderResponseHelper.readText(root, "error.message"));
        String requestId = firstNonBlank(
            ProviderResponseHelper.readText(root, "request_id"),
            ProviderResponseHelper.readText(root, "requestId"));
        String safeRequestId = StrUtil.sub(StrUtil.trimToEmpty(requestId)
            .replace("\r", "").replace("\n", "").replace("\t", ""), 0, 128);

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("http_status", httpStatus);
        audit.put("business_code", businessCode);
        if (StrUtil.isNotBlank(upstreamMessage)) {
            audit.put("message", StrUtil.sub(upstreamMessage, 0, 1000));
        } else if (StrUtil.isNotBlank(raw)) {
            audit.put("response_excerpt", StrUtil.sub(raw, 0, 1000));
        }
        if (StrUtil.isNotBlank(safeRequestId)) {
            audit.put("request_id", safeRequestId);
        }
        String auditDetail = JSONUtil.toJsonStr(audit);
        log.warn("Kling submit rejected, httpStatus={}, code={}, requestId={}, responseLength={}",
            httpStatus, businessCode, StrUtil.blankToDefault(safeRequestId, "-"), StrUtil.length(raw));
        return new ServiceException(
            KlingErrorClassifier.safeMessage(httpStatus, businessCode, upstreamMessage),
            httpStatus > 0 ? httpStatus : null)
            .setDetailMessage(auditDetail);
    }

    static int countActuallyDispatchedImages(AiModelConfigVo config, MediaVideoGenerateRequest request) {
        return KlingVideoRequestBuilder.validateRequestInputs(config, request);
    }

    static boolean isExplicitlyNotAccepted(int businessCode) {
        return businessCode == 1302 || businessCode == 1303;
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(1000L << (attempt - 1));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Kling submit backoff interrupted");
            throw new ServiceException("任务提交已取消");
        }
    }

    private HttpResult doPost(String url, String apiKey, String body) {
        requireApiKey(apiKey);
        try (HttpResponse response = HttpRequest.post(url)
            .header("Authorization", KlingConstants.AUTH_PREFIX + apiKey.trim())
            .header("Content-Type", "application/json")
            .body(body)
            .timeout(KlingConstants.HTTP_TIMEOUT_MS)
            .execute()) {
            return new HttpResult(response.getStatus(), response.body());
        }
    }

    private HttpResult doGet(String url, String apiKey) {
        requireApiKey(apiKey);
        try (HttpResponse response = HttpRequest.get(url)
            .header("Authorization", KlingConstants.AUTH_PREFIX + apiKey.trim())
            .header("Content-Type", "application/json")
            .timeout(KlingConstants.HTTP_TIMEOUT_MS)
            .execute()) {
            return new HttpResult(response.getStatus(), response.body());
        }
    }

    static String buildSubmitUrl(AiModelConfigVo config) {
        requireConfig(config);
        return buildEndpointUrl(config.getBaseUrl(), config.getApiSuffix(), false, null);
    }

    static String buildQueryUrl(AiModelConfigVo config, String providerTaskId) {
        requireConfig(config);
        return buildEndpointUrl(config.getBaseUrl(), config.getTaskQuerySuffix(), true, providerTaskId);
    }

    static void validateBaseUrl(AiModelConfigVo config) {
        requireConfig(config);
        buildEndpointUrl(config.getBaseUrl(), config.getApiSuffix(), false, null);
        buildEndpointUrl(config.getBaseUrl(), config.getTaskQuerySuffix(), true, "endpoint-validation");
    }

    private static void requireConfig(AiModelConfigVo config) {
        if (config == null || StrUtil.isBlank(config.getBaseUrl())) {
            log.warn("Kling base URL missing");
            throw new ServiceException("可灵地址未配置");
        }
    }

    private static String buildEndpointUrl(String baseUrl, String endpoint, boolean query, String taskId) {
        try {
            return query
                ? ProviderEndpointUtils.buildTaskQueryUrl(baseUrl, endpoint, taskId)
                : ProviderEndpointUtils.buildSubmitUrl(baseUrl, endpoint);
        } catch (IllegalArgumentException ex) {
            log.warn("Kling endpoint rejected, query={}, reason={}", query, ex.getMessage());
            throw new ServiceException(query ? "查询路径无效" : "模型路径无效");
        }
    }

    private static int businessCode(JsonNode root) {
        if (root == null || !root.has("code")) {
            return -1;
        }
        JsonNode code = root.get("code");
        if (code.isInt() || code.isLong()) {
            return code.asInt();
        }
        try {
            return Integer.parseInt(code.asText());
        } catch (Exception ex) {
            return -1;
        }
    }

    private static VideoOutput findVideoOutput(JsonNode task) {
        JsonNode outputs = task == null ? null : task.path("outputs");
        if (outputs != null && outputs.isArray()) {
            for (JsonNode item : outputs) {
                if ("video".equalsIgnoreCase(item.path("type").asText())) {
                    return new VideoOutput(item.path("url").asText(null), parseDuration(item.path("duration").asText(null)));
                }
            }
        }
        JsonNode legacyVideos = task == null ? null : task.path("task_result").path("videos");
        if (legacyVideos != null && legacyVideos.isArray() && !legacyVideos.isEmpty()) {
            JsonNode item = legacyVideos.get(0);
            return new VideoOutput(item.path("url").asText(null), parseDuration(item.path("duration").asText(null)));
        }
        return new VideoOutput(null, null);
    }

    private static String firstText(JsonNode root, String... fields) {
        if (root == null) return null;
        for (String field : fields) {
            String value = root.path(field).asText(null);
            if (StrUtil.isNotBlank(value)) return value;
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static Integer parseDuration(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim()).setScale(0, RoundingMode.CEILING).intValueExact();
        } catch (Exception ex) {
            return null;
        }
    }

    private static String safeTaskFailure(String upstreamMessage) {
        String text = StrUtil.trimToEmpty(upstreamMessage).toLowerCase();
        if (text.contains("content") || text.contains("policy") || text.contains("safety")
            || text.contains("risk") || text.contains("审核")) {
            return "生成内容未通过安全校验";
        }
        return "上游任务执行失败";
    }

    private static ProviderTaskResult anomaly(String raw, String message, String providerStatus) {
        return ProviderTaskResult.builder()
            .status(KlingConstants.TASK_STATUS_PROCESSING)
            .errorMessage(message)
            .rawResponse(raw)
            .querySuccessful(Boolean.FALSE)
            .providerStatus(providerStatus)
            .terminalConfirmed(Boolean.FALSE)
            .build();
    }

    private void requireApiKey(String apiKey) {
        if (StrUtil.isBlank(apiKey)) {
            log.warn("Kling API key missing");
            throw new ServiceException("可灵 API Key 未配置");
        }
    }

    private record HttpResult(int statusCode, String body) {
    }

    private record VideoOutput(String url, Integer durationSeconds) {
    }
}
