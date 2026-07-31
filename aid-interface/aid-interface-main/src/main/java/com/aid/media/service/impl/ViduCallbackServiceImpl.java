package com.aid.media.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.service.IAidMediaTaskService;
import com.aid.common.core.redis.RedisCache;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.ViduConstants;
import com.aid.media.dto.ViduCallbackContext;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.ViduCallbackSignatureUtil;
import com.aid.media.provider.ViduCallbackSupport;
import com.aid.media.provider.ViduResultUrlResolver;
import com.aid.media.provider.ViduStatusMapper;
import com.aid.media.service.TaskCompletionService;
import com.aid.media.service.ViduCallbackService;
import com.aid.service.IAiModelConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Vidu 媒体任务回调处理服务实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViduCallbackServiceImpl implements ViduCallbackService {

    private static final String NONCE_CACHE_PREFIX = "vidu:callback:nonce:";
    private static final int NONCE_TTL_MINUTES = 30;
    private static final String HTTP_METHOD_POST = "POST";
    private static final String HEADER_DATE = "Date";
    private static final long SIGNATURE_DATE_SKEW_MS = 15L * 60L * 1000L;

    private final IAidMediaTaskService aidMediaTaskService;
    private final TaskCompletionService taskCompletionService;
    private final IAiModelConfigService aiModelConfigService;
    private final RedisCache redisCache;

    /** 中间态回调登记「上游仍在推进」，与轮询共用同一条进展时钟。 */
    private final com.aid.media.service.TaskDispatchService taskDispatchService;

    @Override
    public void handleViduCallback(String rawBody, ViduCallbackContext context) {
        try {
            handleCallback(rawBody, context);
        } catch (Exception ex) {
            log.error("Vidu 回调处理异常，交轮询兜底", ex);
        }
    }

    /** 处理已接收的 Vidu 回调。 */
    private void handleCallback(String rawBody, ViduCallbackContext context) {
        JsonNode root = ProviderResponseHelper.readTree(rawBody);
        if (root == null) {
            log.warn("Vidu 回调体为空或非法 JSON");
            return;
        }
        String providerTaskId = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_ID, ViduConstants.JSON_TASK_ID,
            ViduConstants.JSON_PATH_DATA_ID, ViduConstants.JSON_PATH_DATA_TASK_ID,
            ViduConstants.JSON_PATH_OUTPUT_TASK_ID);
        if (StrUtil.isBlank(providerTaskId)) {
            log.warn("Vidu 回调缺少任务ID");
            return;
        }

        AidMediaTask task = findActiveTask(providerTaskId);
        if (task == null) {
            log.info("Vidu 回调未命中待处理任务, providerTaskId={}", providerTaskId);
            return;
        }
        AiModelConfigVo modelConfig = resolveModelConfig(task.getModelName(), task.getUserId());
        if (!isExpectedProvider(modelConfig) || StrUtil.isBlank(modelConfig.getApiKey())) {
            log.warn("Vidu 回调模型配置无效, taskId={}, modelName={}", task.getId(), task.getModelName());
            return;
        }

        String callbackUrl = ViduCallbackSupport.resolveCallbackUrlForSubmission(modelConfig);
        if (StrUtil.isBlank(callbackUrl)) {
            log.warn("Vidu 回调调度未开启或地址无效, taskId={}", task.getId());
            return;
        }
        if (!verifySignature(context, callbackUrl, modelConfig.getApiKey())) {
            log.warn("Vidu 回调验签失败, taskId={}, providerTaskId={}", task.getId(), providerTaskId);
            return;
        }

        String state = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_STATE, ViduConstants.JSON_STATUS,
            ViduConstants.JSON_TASK_STATUS,
            ViduConstants.JSON_PATH_DATA_STATE, ViduConstants.JSON_PATH_DATA_STATUS,
            ViduConstants.JSON_PATH_DATA_TASK_STATUS, ViduConstants.JSON_PATH_OUTPUT_TASK_STATUS);
        String errCode = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_ERR_CODE, ViduConstants.JSON_PATH_DATA_ERR_CODE);
        if (!ViduStatusMapper.isKnownState(state)) {
            log.warn("Vidu 回调返回文档外状态，保留任务等待轮询, taskId={}, state={}", task.getId(), state);
            return;
        }
        String normalized = ViduStatusMapper.normalizeStatus(state);
        if (!isTerminal(normalized)) {
            // 中间态回调本身就是一次「上游仍在推进」的实证：登记进展，避免任务被无进展时钟误判超时。
            taskDispatchService.markUpstreamProgress(task);
            log.info("Vidu 回调任务仍在处理中, taskId={}, state={}", task.getId(), state);
            return;
        }

        String resultUrl = ViduResultUrlResolver.resolve(root);
        if (MediaTaskStatus.SUCCEEDED.name().equals(normalized) && StrUtil.isBlank(resultUrl)) {
            log.warn("Vidu 成功回调缺少有效输出，交轮询复查, taskId={}", task.getId());
            return;
        }

        String nonce = context == null ? null : context.getHeader(ViduConstants.HDR_REQUEST_NONCE);
        if (!checkAndStoreNonce(nonce)) {
            log.info("Vidu 回调 nonce 重复或缺失, taskId={}", task.getId());
            return;
        }

        String error = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_PATH_ERROR_MESSAGE, ViduConstants.JSON_PATH_ERROR_MSG,
            ViduConstants.JSON_ERROR, ViduConstants.JSON_MESSAGE,
            ViduConstants.JSON_PATH_DATA_ERROR, ViduConstants.JSON_PATH_DATA_MESSAGE);
        if (StrUtil.isNotBlank(errCode)) {
            error = StrUtil.isBlank(error) ? errCode : errCode + ":" + error;
        }
        ProviderTaskResult taskResult = ProviderTaskResult.builder()
            .status(normalized)
            .resultUrl(resultUrl)
            .errorMessage(error)
            .rawResponse(rawBody)
            .querySuccessful(Boolean.TRUE)
            .providerStatus(state)
            .terminalConfirmed(Boolean.TRUE)
            .build();
        boolean completed = taskCompletionService.completeTask(task.getId(), taskResult);
        log.info("Vidu 回调收口完成, taskId={}, status={}, completed={}",
            task.getId(), normalized, completed);
    }

    /** 查询 Vidu 图片或视频的非终态任务。 */
    private AidMediaTask findActiveTask(String providerTaskId) {
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        // 精简字段：回调只需要定位任务、解析模型并执行终态 CAS。
        wrapper.select(AidMediaTask::getId, AidMediaTask::getModelName,
            AidMediaTask::getProtocol, AidMediaTask::getProviderTaskId,
            AidMediaTask::getStatus, AidMediaTask::getUserId);
        wrapper.eq(AidMediaTask::getProviderTaskId, providerTaskId);
        wrapper.in(AidMediaTask::getProtocol,
            ViduConstants.PROTOCOL_IMAGE, ViduConstants.PROTOCOL_VIDEO);
        wrapper.in(AidMediaTask::getStatus,
            MediaTaskStatus.WAIT_CALLBACK.name(),
            MediaTaskStatus.WAIT_POLL.name(),
            MediaTaskStatus.PROCESSING.name());
        wrapper.last("LIMIT 1");
        return aidMediaTaskService.getOne(wrapper, false);
    }

    /** 解析任务使用的模型配置。 */
    private AiModelConfigVo resolveModelConfig(String modelCode, Long userId) {
        if (StrUtil.isBlank(modelCode)) {
            return null;
        }
        try {
            return aiModelConfigService.selectByModelCodeForUser(modelCode, userId);
        } catch (Exception ex) {
            log.warn("Vidu 回调解析模型配置异常, modelCode={}, userId={}, error={}",
                modelCode, userId, ex.getMessage());
            return null;
        }
    }

    /** 判断配置是否归属 Vidu。 */
    private boolean isExpectedProvider(AiModelConfigVo modelConfig) {
        return modelConfig != null && Objects.equals(ViduConstants.PROVIDER_CODE,
            StrUtil.trimToEmpty(modelConfig.getProviderCode()).toLowerCase(Locale.ROOT));
    }

    /** 校验签名相关请求头并执行 HMAC 验签。 */
    private boolean verifySignature(ViduCallbackContext context, String callbackUrl, String secretKey) {
        if (context == null) {
            return false;
        }
        String date = context.getHeader(HEADER_DATE);
        if (!isDateFresh(date)) {
            return false;
        }
        if (!Objects.equals(ViduConstants.HMAC_ALGORITHM_VALUE,
            StrUtil.trim(context.getHeader(ViduConstants.HDR_HMAC_ALGORITHM)))) {
            return false;
        }
        if (!Objects.equals(ViduConstants.CALLBACK_ACCESS_KEY,
            StrUtil.trim(context.getHeader(ViduConstants.HDR_HMAC_ACCESS_KEY)))) {
            return false;
        }
        String signedHeaders = context.getHeader(ViduConstants.HDR_HMAC_SIGNED_HEADERS);
        if (!containsSignedHeader(signedHeaders, ViduConstants.HDR_REQUEST_NONCE)) {
            return false;
        }
        return ViduCallbackSignatureUtil.verify(
            HTTP_METHOD_POST,
            callbackUrl,
            date,
            signedHeaders,
            context.getHeader(ViduConstants.HDR_HMAC_SIGNATURE),
            secretKey,
            context::getHeader);
    }

    /** 判断必需请求头是否已被 HMAC 签名覆盖。 */
    private boolean containsSignedHeader(String signedHeaders, String requiredHeader) {
        if (StrUtil.hasBlank(signedHeaders, requiredHeader)) {
            return false;
        }
        for (String header : signedHeaders.split(";")) {
            if (requiredHeader.equalsIgnoreCase(StrUtil.trim(header))) {
                return true;
            }
        }
        return false;
    }

    /** 校验 RFC 1123 Date 请求头的新鲜度。 */
    private boolean isDateFresh(String date) {
        if (StrUtil.isBlank(date)) {
            return false;
        }
        try {
            long requestMillis = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli();
            return Math.abs(System.currentTimeMillis() - requestMillis) <= SIGNATURE_DATE_SKEW_MS;
        } catch (Exception ex) {
            log.warn("Vidu 回调 Date 解析失败, date={}", date);
            return false;
        }
    }

    /** 判断归一化状态是否为终态。 */
    private boolean isTerminal(String status) {
        return MediaTaskStatus.SUCCEEDED.name().equals(status)
            || MediaTaskStatus.FAILED.name().equals(status);
    }

    /** 原子记录终态回调 nonce。 */
    private boolean checkAndStoreNonce(String nonce) {
        if (StrUtil.isBlank(nonce)) {
            return false;
        }
        String key = NONCE_CACHE_PREFIX + DigestUtil.sha256Hex(nonce);
        try {
            return redisCache.setCacheObjectIfAbsent(
                key, "1", NONCE_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception ex) {
            log.warn("Vidu 回调 nonce 缓存异常, error={}", ex.getMessage());
            return false;
        }
    }
}
