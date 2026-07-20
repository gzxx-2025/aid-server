package com.aid.compose.service.impl;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.common.core.redis.RedisCache;
import com.aid.compose.ComposeConstants;
import com.aid.compose.service.ComposeCompletionService;
import com.aid.compose.service.MpsCallbackService;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.impl.MpsVideoProviderClient;
import com.aid.media.service.TaskCompletionService;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 腾讯云 MPS 任务回调处理服务实现：防伪造闭环——终态一律反查 MPS 真实结果、
 * providerTaskId/SessionContext 一致性校验、终态 nonce 防重放、completeTask CAS 幂等。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MpsCallbackServiceImpl implements MpsCallbackService {

    /** nonce 防重放缓存前缀 */
    private static final String NONCE_CACHE_PREFIX = "mps:callback:nonce:";

    /** nonce 缓存有效期（分钟）：覆盖上游重试窗口即可 */
    private static final int NONCE_TTL_MINUTES = 30;

    /** 媒体任务 Mapper */
    private final AidMediaTaskMapper aidMediaTaskMapper;

    /** 统一终态收口（与轮询同一入口，幂等） */
    private final TaskCompletionService taskCompletionService;

    /** MPS 协议 Provider（复用其 DescribeTaskDetail 解析） */
    private final MpsVideoProviderClient mpsVideoProviderClient;

    /** 合成收口服务（中间态回调回写真实进度） */
    private final ComposeCompletionService composeCompletionService;

    /** Redis 缓存（nonce 防重放） */
    private final RedisCache redisCache;

    @Override
    public void handleMpsCallback(String rawBody) {
        try {
            JSONObject root = parse(rawBody);
            if (root == null) {
                log.warn("MPS 回调体为空或非法 JSON");
                return;
            }
            String providerTaskId = resolveProviderTaskId(root);
            String sessionContext = resolveSessionContext(root);
            if (StrUtil.isBlank(providerTaskId) && StrUtil.isBlank(sessionContext)) {
                log.warn("MPS 回调缺少 TaskId/SessionContext，忽略");
                return;
            }

            AidMediaTask task = findActiveComposeTask(providerTaskId, sessionContext);
            if (task == null) {
                log.info("MPS 回调未命中待处理 COMPOSE 任务, providerTaskId={}, sessionContext={}",
                        providerTaskId, sessionContext);
                return;
            }

            if (!verifyConsistency(task, providerTaskId, sessionContext)) {
                log.warn("MPS 回调来源校验不通过, taskId={}, providerTaskId={}, sessionContext={}",
                        task.getId(), providerTaskId, sessionContext);
                return;
            }

            ProviderTaskResult taskResult = mpsVideoProviderClient.query(null, task.getProviderTaskId());
            if (taskResult == null
                    || (!MediaTaskStatus.SUCCEEDED.name().equals(taskResult.getStatus())
                    && !MediaTaskStatus.FAILED.name().equals(taskResult.getStatus()))) {
                // 中间态回调：只回写真实进度，不消费防重放 nonce（否则后续终态回调会被误判为重放，只能等轮询兜底）
                if (taskResult != null) {
                    composeCompletionService.onProgress(task, taskResult.getProgress());
                }
                log.info("MPS 回调任务仍处理中, taskId={}, status={}, progress={}",
                        task.getId(), taskResult == null ? null : taskResult.getStatus(),
                        taskResult == null ? null : taskResult.getProgress());
                return;
            }

            // 终态才做防重放：nonce 只拦截重复的终态回调（completeTask CAS 仍兜底幂等）
            String replayKey = StrUtil.isNotBlank(providerTaskId) ? providerTaskId : String.valueOf(task.getId());
            if (!checkAndStoreNonce(replayKey)) {
                log.info("MPS 回调重放，幂等忽略, taskId={}, replayKey={}", task.getId(), replayKey);
                return;
            }

            taskCompletionService.completeTask(task.getId(), taskResult);
            log.info("MPS 回调收口完成, taskId={}, status={}", task.getId(), taskResult.getStatus());
        } catch (Exception ex) {
            // 任一异常吞掉，依赖轮询兜底，避免上游无意义重试。
            log.error("MPS 回调处理异常，交轮询兜底", ex);
        }
    }

    /**
     * 按 providerTaskId 或 SessionContext（我方 taskId）反查非终态 COMPOSE 任务。
     *
     * @param providerTaskId MPS TaskId
     * @param sessionContext 透传的我方 taskId
     * @return 非终态 COMPOSE 任务，未命中返回 null
     */
    private AidMediaTask findActiveComposeTask(String providerTaskId, String sessionContext) {
        // 优先按 providerTaskId 反查
        if (StrUtil.isNotBlank(providerTaskId)) {
            LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AidMediaTask::getProviderTaskId, providerTaskId);
            wrapper.eq(AidMediaTask::getMediaType, ComposeConstants.MEDIA_TYPE_COMPOSE);
            wrapper.in(AidMediaTask::getStatus,
                    MediaTaskStatus.WAIT_CALLBACK.name(),
                    MediaTaskStatus.WAIT_POLL.name(),
                    MediaTaskStatus.PROCESSING.name());
            wrapper.last("LIMIT 1");
            AidMediaTask task = aidMediaTaskMapper.selectOne(wrapper);
            if (task != null) {
                return task;
            }
        }
        // 回退按 SessionContext（我方 taskId）反查
        if (StrUtil.isNotBlank(sessionContext) && NumberUtil.isLong(sessionContext)) {
            AidMediaTask task = aidMediaTaskMapper.selectById(Long.parseLong(sessionContext));
            if (task != null
                    && ComposeConstants.MEDIA_TYPE_COMPOSE.equals(task.getMediaType())
                    && isActive(task.getStatus())) {
                return task;
            }
        }
        return null;
    }

    /**
     * 校验回调来源自洽：定位任务的 providerTaskId 与 SessionContext 必须与回调体一致。
     *
     * @param task           定位到的任务
     * @param providerTaskId 回调体内 providerTaskId
     * @param sessionContext 回调体内 SessionContext
     * @return true=自洽可信
     */
    private boolean verifyConsistency(AidMediaTask task, String providerTaskId, String sessionContext) {
        if (StrUtil.isNotBlank(providerTaskId) && StrUtil.isNotBlank(task.getProviderTaskId())
                && !providerTaskId.equals(task.getProviderTaskId())) {
            return false;
        }
        return !StrUtil.isNotBlank(sessionContext) || sessionContext.equals(String.valueOf(task.getId()));
    }

    /** 判断任务是否处于非终态。 */
    private boolean isActive(String status) {
        return MediaTaskStatus.WAIT_CALLBACK.name().equals(status)
                || MediaTaskStatus.WAIT_POLL.name().equals(status)
                || MediaTaskStatus.PROCESSING.name().equals(status);
    }

    /**
     * 解析 providerTaskId：兼容顶层 TaskId 与各类事件子结构中的 TaskId。
     *
     * @param root 回调体
     * @return providerTaskId，解析不到返回 null
     */
    private String resolveProviderTaskId(JSONObject root) {
        String taskId = root.getString("TaskId");
        if (StrUtil.isNotBlank(taskId)) {
            return taskId;
        }
        for (String eventKey : new String[]{"EditMediaTaskEvent", "EditMediaTask", "WorkflowTaskEvent"}) {
            JSONObject event = root.getJSONObject(eventKey);
            if (event != null && StrUtil.isNotBlank(event.getString("TaskId"))) {
                return event.getString("TaskId");
            }
        }
        return null;
    }

    /**
     * 解析 SessionContext：兼容顶层与事件子结构。
     *
     * @param root 回调体
     * @return SessionContext，解析不到返回 null
     */
    private String resolveSessionContext(JSONObject root) {
        String sessionContext = root.getString("SessionContext");
        if (StrUtil.isNotBlank(sessionContext)) {
            return sessionContext;
        }
        for (String eventKey : new String[]{"EditMediaTaskEvent", "EditMediaTask", "WorkflowTaskEvent"}) {
            JSONObject event = root.getJSONObject(eventKey);
            if (event != null && StrUtil.isNotBlank(event.getString("SessionContext"))) {
                return event.getString("SessionContext");
            }
        }
        return null;
    }

    /**
     * nonce 防重放：首次出现返回 true 并写入缓存；已存在返回 false。
     * 缓存异常不阻断（completeTask CAS 仍保证幂等）。
     *
     * @param nonce 去重键（providerTaskId 或我方 taskId）
     * @return true=首次出现可处理
     */
    private boolean checkAndStoreNonce(String nonce) {
        if (StrUtil.isBlank(nonce)) {
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
            log.warn("MPS 回调 nonce 缓存异常, err={}", ex.getMessage());
            return true;
        }
    }

    /**
     * 解析 JSON 回调体（解析失败返回 null）。
     *
     * @param raw 原始报文
     * @return JSON 对象
     */
    private JSONObject parse(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            return JSON.parseObject(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
