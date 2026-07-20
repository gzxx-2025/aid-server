package com.aid.media.yyz.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.common.annotation.Anonymous;
import com.aid.common.aid.crypto.annotation.CryptoIgnore;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.redis.RedisCache;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.ViduConstants;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.ViduCallbackSignatureUtil;
import com.aid.media.provider.ViduStatusMapper;
import com.aid.media.service.TaskCompletionService;
import com.aid.service.IAiModelConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * Vidu 回调接收端点：Vidu 在任务状态变化时按官方回调签名算法 POST 通知本平台。
 * 回调仅作加速、轮询始终兜底，故任一异常一律 ACK（返回成功）避免上游无意义重试；
 * 采用 HMAC-SHA256 验签（secret 为创建任务所用 token，即模型配置 apiKey）+ x-request-nonce 防重放，
 * 验签失败拒绝处理，命中后与轮询复用同一幂等收口 {@link TaskCompletionService#completeTask}。
 */
@Slf4j
@RestController
@RequestMapping("/api/media/callback")
@RequiredArgsConstructor
public class ViduCallbackController {

    /** nonce 防重放缓存前缀 */
    private static final String NONCE_CACHE_PREFIX = "vidu:callback:nonce:";
    /** nonce 缓存有效期（分钟）：覆盖上游重试窗口即可 */
    private static final int NONCE_TTL_MINUTES = 30;
    /** 固定回调方法 */
    private static final String HTTP_METHOD_POST = "POST";

    private final AidMediaTaskMapper aidMediaTaskMapper;
    private final TaskCompletionService taskCompletionService;
    private final IAiModelConfigService aiModelConfigService;
    private final RedisCache redisCache;

    /**
     * 接收 Vidu 回调。URL：POST /api/media/callback/vidu
     * @CryptoIgnore：回调为上游明文 JSON，不参与本平台 API 加解密。
     */
    @Anonymous
    @CryptoIgnore
    @PostMapping("/vidu")
    public AjaxResult onViduCallback(@RequestBody(required = false) String rawBody, HttpServletRequest httpRequest) {
        JsonNode root = ProviderResponseHelper.readTree(rawBody);
        if (root == null) {
            log.warn("vidu 回调体为空或非法 JSON");
            return AjaxResult.success();
        }
        String providerTaskId = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_ID, ViduConstants.JSON_TASK_ID,
            ViduConstants.JSON_PATH_DATA_ID, ViduConstants.JSON_PATH_DATA_TASK_ID);
        if (StrUtil.isBlank(providerTaskId)) {
            log.warn("vidu 回调缺少 task_id/id，忽略");
            return AjaxResult.success();
        }

        AidMediaTask task = findActiveTask(providerTaskId);
        if (task == null) {
            log.info("vidu 回调未命中待处理任务, providerTaskId={}", providerTaskId);
            return AjaxResult.success();
        }

        AiModelConfigVo modelConfig = resolveModelConfig(task.getModelName());
        if (modelConfig == null || StrUtil.isBlank(modelConfig.getApiKey())) {
            log.warn("vidu 回调无法解析模型配置或密钥缺失, taskId={}, modelName={}", task.getId(), task.getModelName());
            return AjaxResult.success();
        }
        // 防串厂商：仅处理归属 vidu 的任务。
        if (!ViduConstants.PROVIDER_CODE.equalsIgnoreCase(StrUtil.trimToEmpty(modelConfig.getProviderCode()))) {
            log.warn("vidu 回调供应商不匹配, taskId={}, providerCode={}", task.getId(), modelConfig.getProviderCode());
            return AjaxResult.success();
        }

        String callbackUrl = com.aid.media.provider.ViduCallbackSupport.resolveCallbackBaseUrl(modelConfig);
        if (StrUtil.isBlank(callbackUrl)) {
            log.warn("vidu 回调未配置 callbackBaseUrl（供应商/模型 schedule_strategy_json），无法验签，拒绝处理（轮询兜底）, taskId={}", task.getId());
            return AjaxResult.success();
        }
        if (!verifySignature(httpRequest, callbackUrl, modelConfig.getApiKey())) {
            log.warn("vidu 回调验签失败，拒绝处理, taskId={}, providerTaskId={}", task.getId(), providerTaskId);
            return AjaxResult.success();
        }

        if (!checkAndStoreNonce(httpRequest.getHeader(ViduConstants.HDR_REQUEST_NONCE))) {
            log.info("vidu 回调 nonce 重复，幂等忽略, taskId={}", task.getId());
            return AjaxResult.success();
        }

        String state = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_STATE, ViduConstants.JSON_STATUS,
            ViduConstants.JSON_PATH_DATA_STATE, ViduConstants.JSON_PATH_DATA_STATUS);
        String errCode = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_ERR_CODE, ViduConstants.JSON_PATH_DATA_ERR_CODE);
        String normalized = ViduStatusMapper.applyErrorCodeClassification(
            ViduStatusMapper.normalizeStatus(state), errCode);
        // 非终态（仍 processing）无需收口，等待后续回调或轮询。
        if (!MediaTaskStatus.SUCCEEDED.name().equals(normalized)
            && !MediaTaskStatus.FAILED.name().equals(normalized)) {
            log.info("vidu 回调任务仍处理中, taskId={}, state={}", task.getId(), state);
            return AjaxResult.success();
        }

        String url = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_PATH_CREATIONS_0_URL, ViduConstants.JSON_PATH_DATA_CREATIONS_0_URL,
            ViduConstants.JSON_URL, ViduConstants.JSON_PATH_DATA_URL);
        if (StrUtil.isBlank(url)) {
            url = ProviderResponseHelper.findFirstUrl(root);
        }
        String error = ProviderResponseHelper.readText(root,
            ViduConstants.JSON_PATH_ERROR_MESSAGE, ViduConstants.JSON_MESSAGE, ViduConstants.JSON_PATH_DATA_MESSAGE);
        if (StrUtil.isNotBlank(errCode)) {
            error = StrUtil.isBlank(error) ? errCode : (errCode + ":" + error);
        }

        ProviderTaskResult taskResult = ProviderTaskResult.builder()
            .status(normalized)
            .resultUrl(url)
            .errorMessage(error)
            .rawResponse(rawBody)
            .build();
        taskCompletionService.completeTask(task.getId(), taskResult);
        log.info("vidu 回调收口完成, taskId={}, status={}", task.getId(), normalized);
        return AjaxResult.success();
    }

    /** 按 providerTaskId 查找非终态任务（WAIT_CALLBACK/WAIT_POLL/PROCESSING）。 */
    private AidMediaTask findActiveTask(String providerTaskId) {
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AidMediaTask::getProviderTaskId, providerTaskId);
        wrapper.in(AidMediaTask::getStatus,
            MediaTaskStatus.WAIT_CALLBACK.name(),
            MediaTaskStatus.WAIT_POLL.name(),
            MediaTaskStatus.PROCESSING.name());
        wrapper.last("LIMIT 1");
        return aidMediaTaskMapper.selectOne(wrapper);
    }

    /** 解析模型配置，异常降级为 null。 */
    private AiModelConfigVo resolveModelConfig(String modelCode) {
        if (StrUtil.isBlank(modelCode)) {
            return null;
        }
        try {
            return aiModelConfigService.selectByModelCode(modelCode);
        } catch (Exception ex) {
            log.warn("vidu 回调解析模型配置异常, modelCode={}, err={}", modelCode, ex.getMessage());
            return null;
        }
    }

    /** 签名 Date 头允许的时间偏移（毫秒）：超窗视为过期请求拒绝，缩小重放窗口 */
    private static final long SIGNATURE_DATE_SKEW_MS = 15L * 60L * 1000L;

    /** 执行 HMAC 验签（含 Date 头新鲜度校验），从请求头读取签名相关字段。 */
    private boolean verifySignature(HttpServletRequest request, String callbackUrl, String secretKey) {
        String date = request.getHeader("Date");
        if (!isDateFresh(date)) {
            log.warn("vidu 回调 Date 头缺失或超出时间窗，拒绝处理, date={}", date);
            return false;
        }
        String signedHeaders = request.getHeader(ViduConstants.HDR_HMAC_SIGNED_HEADERS);
        String signature = request.getHeader(ViduConstants.HDR_HMAC_SIGNATURE);
        return ViduCallbackSignatureUtil.verify(
            HTTP_METHOD_POST, callbackUrl, date, signedHeaders, signature, secretKey,
            request::getHeader);
    }

    /**
     * 校验签名 Date 头新鲜度：RFC 1123 格式，允许 ±15 分钟偏移；解析失败按不新鲜拒绝。
     *
     * @param date 请求 Date 头
     * @return true=在时间窗内
     */
    private boolean isDateFresh(String date) {
        if (StrUtil.isBlank(date)) {
            return false;
        }
        try {
            long requestMillis = java.time.ZonedDateTime
                    .parse(date, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
            return Math.abs(System.currentTimeMillis() - requestMillis) <= SIGNATURE_DATE_SKEW_MS;
        } catch (Exception ex) {
            log.warn("vidu 回调 Date 头解析失败, date={}, err={}", date, ex.getMessage());
            return false;
        }
    }

    /**
     * nonce 防重放：首次出现返回 true 并写入缓存；已存在返回 false。
     * nonce 缺失时不阻断（部分实现可能不带），但缺失则失去防重放能力，由 completeTask 的 CAS 幂等兜底。
     */
    private boolean checkAndStoreNonce(String nonce) {
        if (StrUtil.isBlank(nonce)) {
            log.warn("vidu 回调缺少 nonce 头，防重放退化为终态 CAS 幂等兜底");
            return true;
        }
        String key = NONCE_CACHE_PREFIX + nonce;
        try {
            if (Boolean.TRUE.equals(redisCache.hasKey(key))) {
                return false;
            }
            redisCache.setCacheObject(key, "1", NONCE_TTL_MINUTES, TimeUnit.MINUTES);
            return true;
        } catch (Exception ex) {
            // 缓存异常不阻断（completeTask CAS 仍保证幂等）。
            log.warn("vidu 回调 nonce 缓存异常, err={}", ex.getMessage());
            return true;
        }
    }
}
