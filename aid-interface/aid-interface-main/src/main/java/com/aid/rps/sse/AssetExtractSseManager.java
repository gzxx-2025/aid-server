package com.aid.rps.sse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import cn.hutool.core.util.StrUtil;

import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.common.aid.oss.util.MediaPayloadUrlNormalizer;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.error.TaskErrorResult;
import com.aid.common.utils.DateUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * AI任务 SSE 推送管理器：管理 SseEmitter 生命周期并实时推送各阶段进度，适用于所有任务类型（同 JVM 内 MQ/非 MQ 均可）。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class AssetExtractSseManager
{
    /** Redis 进度快照 key 前缀，完整 key: asset:task:progress:{taskId} */
    private static final String PROGRESS_KEY_PREFIX = "asset:task:progress:";
    /** 进度快照 TTL（分钟） */
    private static final int PROGRESS_TTL_MINUTES = 30;

    /** taskId → SseEmitter（本地内存，仅同 JVM 有效） */
    private final ConcurrentHashMap<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    /** taskId → 最近一次本地 emitter 成功推送的 progress 快照 updateMillis（同 JVM 去重，防止 Redis 轮询重复推送） */
    private final ConcurrentHashMap<Long, Long> lastLocalPushMillis = new ConcurrentHashMap<>();

    @Resource
    private RedisCache redisCache;

    @Resource
    private MediaPayloadUrlNormalizer mediaPayloadUrlNormalizer;

    @Resource
    private IAidExtractTaskService extractTaskService;

    /**
     * 注册 SSE 连接（不设置 emitter 回调，清理由调用方在自己的回调中调 {@link #unregister(Long, SseEmitter)}）；同一 taskId 重连时先 complete 旧 emitter。
     *
     * @param taskId  任务ID
     * @param emitter SSE 发射器
     */
    public void register(Long taskId, SseEmitter emitter)
    {
        // 覆盖式注册：若同 taskId 已有旧 emitter（前端刷新重连），主动关闭旧的，避免双连接残留
        SseEmitter old = emitters.put(taskId, emitter);
        if (Objects.nonNull(old) && old != emitter)
        {
            try
            {
                old.complete();
            }
            catch (Exception ignore)
            {
                // 旧连接可能已断开，忽略
            }
        }
        // 新连接的本地去重基准清零，避免沿用旧连接的 lastLocalPushMillis 导致首批进度被误去重
        lastLocalPushMillis.remove(taskId);
        log.info("SSE注册成功: taskId={}", taskId);
    }

    /**
     * 反注册 SSE 连接（无条件按 taskId 移除）；前端刷新重连场景请用 {@link #unregister(Long, SseEmitter)} 做值比对删除。
     */
    public void unregister(Long taskId)
    {
        emitters.remove(taskId);
        lastLocalPushMillis.remove(taskId);
    }

    /**
     * 反注册 SSE 连接（值比对删除）：仅当当前存的就是 {@code expected} 时才原子移除，避免旧连接回调误删刷新重连后的新 emitter。
     *
     * @param taskId   任务ID
     * @param expected 调用方持有的自己那个 emitter 引用
     */
    public void unregister(Long taskId, SseEmitter expected)
    {
        if (Objects.isNull(expected))
        {
            return;
        }
        boolean removed = emitters.remove(taskId, expected);
        if (removed)
        {
            // 只有确实移除了自己的 emitter 才清本地去重基准，避免误清新连接的基准
            lastLocalPushMillis.remove(taskId);
        }
    }

    /**
     * 获取最近一次本地 emitter 成功推送 progress 的快照 updateMillis，用于 Redis 轮询去重。
     *
     * @return 最近推送的 updateMillis；无记录时返回 0
     */
    public long getLastLocalPushMillis(Long taskId)
    {
        return lastLocalPushMillis.getOrDefault(taskId, 0L);
    }

    /**
     * 推送进度事件（无步骤信息的简化重载）。
     *
     * @param taskId    任务ID
     * @param stage     当前阶段
     * @param progress  进度百分比(0-100)
     * @param message   进度描述
     */
    public void sendProgress(Long taskId, String stage, int progress, String message)
    {
        // 写 Redis 快照（跨进程保底）
        Map<String, Object> snapshot = new LinkedHashMap<>();
        // taskId 必带：前端可能同时开多个 EventSource，payload 带 taskId 用于严格绑定、防止多任务内容串写
        snapshot.put("taskId", taskId);
        snapshot.put("status", "PROCESSING");
        snapshot.put("stage", stage);
        snapshot.put("progress", progress);
        snapshot.put("message", message);
        enrichTaskContext(taskId, snapshot, false);
        snapshot.put("updateTime", DateUtils.getTime());
        snapshot.put("updateMillis", System.currentTimeMillis());
        saveProgressSnapshot(taskId, snapshot);

        // 本地 emitter 推送（同 JVM 即时优化）
        SseEmitter emitter = emitters.get(taskId);
        if (Objects.isNull(emitter))
        {
            return;
        }
        try
        {
            // 本地直推发送同一份完整 snapshot，与 Redis 轮询/重连补发字段一致
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(normalizePayload(snapshot), MediaType.APPLICATION_JSON));
            // 记录本次成功推送的快照时间戳，供 Redis 轮询去重（同 JVM 场景避免重复推送）
            lastLocalPushMillis.put(taskId, (Long) snapshot.get("updateMillis"));
        }
        catch (Exception e)
        {
            log.warn("SSE推送进度失败: taskId={}, stage={}", taskId, stage);
            // 值比对删除，避免误删刷新重连后的新 emitter
            emitters.remove(taskId, emitter);
        }
    }

    /**
     * 推送「已获得执行名额，准备开始」事件（ADMITTED 阶段），在调度出队后、真正派发前覆盖排队旧快照；含防阶段倒退保护。
     *
     * @param taskId 任务ID
     */
    public void sendAdmitted(Long taskId)
    {
        // 防倒退：仅当无快照或快照仍为 QUEUED 时才写 admitted，避免覆盖 worker 已推的真实进度
        try
        {
            Map<String, Object> existing = redisCache.getCacheObject(buildProgressKey(taskId));
            if (Objects.nonNull(existing))
            {
                String status = String.valueOf(existing.getOrDefault("status", ""));
                if (!"QUEUED".equals(status))
                {
                    return;
                }
            }
        }
        catch (Exception ignore)
        {
            // 读快照失败不阻断 admitted 推送（按正常流程继续）
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("taskId", taskId);
        snapshot.put("status", "PROCESSING");
        snapshot.put("stage", "admitted");
        snapshot.put("progress", 5);
        snapshot.put("message", "已获得执行名额，准备开始");
        enrichTaskContext(taskId, snapshot, false);
        snapshot.put("updateTime", DateUtils.getTime());
        snapshot.put("updateMillis", System.currentTimeMillis());
        saveProgressSnapshot(taskId, snapshot);

        SseEmitter emitter = emitters.get(taskId);
        if (Objects.isNull(emitter))
        {
            return;
        }
        try
        {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(normalizePayload(snapshot), MediaType.APPLICATION_JSON));
            lastLocalPushMillis.put(taskId, (Long) snapshot.get("updateMillis"));
        }
        catch (Exception e)
        {
            log.warn("SSE推送准备开始事件失败: taskId={}", taskId);
            emitters.remove(taskId, emitter);
        }
    }

    /**
     * 推送步骤感知进度事件（含 stepId/stepTitle/stepIndex/stepTotal，供前端展示多步骤进度）。
     *
     * @param taskId     任务ID
     * @param stage      当前阶段标识
     * @param progress   进度百分比(0-100)
     * @param stepId     步骤ID（如 "char_chunk_0"）
     * @param stepTitle  步骤标题（如 "角色分析 1/3"）
     * @param stepIndex  当前步骤序号（1-based，可null）
     * @param stepTotal  总步骤数（可null）
     */
    public void sendStepProgress(Long taskId, String stage, int progress,
                                 String stepId, String stepTitle,
                                 Integer stepIndex, Integer stepTotal)
    {
        // 写 Redis 快照（跨进程保底）
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("taskId", taskId);
        snapshot.put("status", "PROCESSING");
        snapshot.put("stage", stage);
        snapshot.put("progress", progress);
        snapshot.put("message", stepTitle);
        snapshot.put("stepId", stepId);
        snapshot.put("stepTitle", stepTitle);
        snapshot.put("stepIndex", stepIndex);
        snapshot.put("stepTotal", stepTotal);
        enrichTaskContext(taskId, snapshot, false);
        snapshot.put("updateTime", DateUtils.getTime());
        snapshot.put("updateMillis", System.currentTimeMillis());
        saveProgressSnapshot(taskId, snapshot);

        // 本地 emitter 推送（同 JVM 即时优化）
        SseEmitter emitter = emitters.get(taskId);
        if (Objects.isNull(emitter))
        {
            return;
        }
        try
        {
            // 本地直推发送同一份完整 snapshot，与 Redis 轮询/重连补发字段一致
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(normalizePayload(snapshot), MediaType.APPLICATION_JSON));
            // 记录本次成功推送的快照时间戳，供 Redis 轮询去重（同 JVM 场景避免重复推送）
            lastLocalPushMillis.put(taskId, (Long) snapshot.get("updateMillis"));
        }
        catch (Exception e)
        {
            log.warn("SSE推送步骤进度失败: taskId={}, stage={}", taskId, stage);
            // 值比对删除，避免误删刷新重连后的新 emitter
            emitters.remove(taskId, emitter);
        }
    }

    /**
     * 推送步骤感知进度事件，并允许业务层携带额外的"项目级"结构化字段（如已完成项列表 / 失败明细）。
     *
     * @param taskId     任务ID
     * @param stage      当前阶段标识
     * @param progress   进度百分比(0-100)
     * @param stepId     步骤ID
     * @param stepTitle  步骤标题
     * @param stepIndex  当前步骤序号（1-based，可null）
     * @param stepTotal  总步骤数（可null）
     * @param extras     业务自定义扩展字段（可空）；键冲突时业务字段不覆盖 step* 核心字段
     */
    public void sendStepProgressWithData(Long taskId, String stage, int progress,
                                          String stepId, String stepTitle,
                                          Integer stepIndex, Integer stepTotal,
                                          Map<String, Object> extras)
    {
        // 写 Redis 快照（跨进程保底）——把 extras 合并进去，保证前端首次连接补发也能看到完整上下文
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("taskId", taskId);
        snapshot.put("status", "PROCESSING");
        snapshot.put("stage", stage);
        snapshot.put("progress", progress);
        snapshot.put("message", stepTitle);
        snapshot.put("stepId", stepId);
        snapshot.put("stepTitle", stepTitle);
        snapshot.put("stepIndex", stepIndex);
        snapshot.put("stepTotal", stepTotal);
        enrichTaskContext(taskId, snapshot, false);
        if (Objects.nonNull(extras) && !extras.isEmpty())
        {
            // 业务字段不覆盖核心 step* 字段，保证前端字段语义稳定
            extras.forEach(snapshot::putIfAbsent);
        }
        snapshot.put("updateTime", DateUtils.getTime());
        snapshot.put("updateMillis", System.currentTimeMillis());
        saveProgressSnapshot(taskId, snapshot);

        // 本地 emitter 推送（同 JVM 即时优化）
        SseEmitter emitter = emitters.get(taskId);
        if (Objects.isNull(emitter))
        {
            return;
        }
        try
        {
            // 本地直推发送同一份完整 snapshot（extras 已在快照构建时合并），与 Redis 轮询/重连补发字段一致
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(normalizePayload(snapshot), MediaType.APPLICATION_JSON));
            // 记录本次成功推送的快照时间戳，供 Redis 轮询去重（同 JVM 场景避免重复推送）
            lastLocalPushMillis.put(taskId, (Long) snapshot.get("updateMillis"));
        }
        catch (Exception e)
        {
            log.warn("SSE推送步骤进度(带data)失败: taskId={}, stage={}", taskId, stage);
            // 值比对删除，避免误删刷新重连后的新 emitter
            emitters.remove(taskId, emitter);
        }
    }

    /**
     * 推送完成事件（携带结果数据）
     *
     * @param taskId  任务ID
     * @param data    任务结果（任意类型，会序列化为JSON）
     */
    public void sendComplete(Long taskId, Object data)
    {
        sendComplete(taskId, data, (Long) null, null);
    }

    /**
     * 推送完成事件（携带结果数据 + 可选链式子任务信息）。
     *
     * @param taskId             任务ID
     * @param data               任务结果（任意类型，会序列化为JSON）
     * @param chainChildTaskId   链式子任务 ID（无则传 null）
     * @param chainChildTaskType 链式子任务类型（无则传 null）
     */
    public void sendComplete(Long taskId, Object data, Long chainChildTaskId, String chainChildTaskType)
    {
        sendComplete(taskId, data, singletonTaskId(chainChildTaskId), chainChildTaskType);
    }

    public void sendComplete(Long taskId, Object data, List<Long> chainChildTaskIds, String chainChildTaskType)
    {
        Long chainChildTaskId = firstTaskId(chainChildTaskIds);
        // 写终态快照（跨进程保底，含链式子任务信息供断线重连补发）
        saveTerminalSnapshot(taskId, "SUCCEEDED", "任务完成", chainChildTaskIds, chainChildTaskType);

        // 本地 emitter 推送
        SseEmitter emitter = emitters.get(taskId);
        if (Objects.isNull(emitter))
        {
            return;
        }
        try
        {
            Object payload = withChain(withTaskId(data, taskId), chainChildTaskIds, chainChildTaskType);
            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(normalizePayload(payload), MediaType.APPLICATION_JSON));
            emitter.complete();
            log.info("SSE推送完成: taskId={}, chainChildTaskId={}", taskId, chainChildTaskId);
        }
        catch (Exception e)
        {
            log.warn("SSE推送完成失败: taskId={}", taskId);
            // 值比对删除，避免误删刷新重连后的新 emitter
            emitters.remove(taskId, emitter);
        }
    }

    public void sendPartialFailed(Long taskId, Object data, String message)
    {
        sendPartialFailed(taskId, data, message, (Long) null, null);
    }

    /**
     * 推送部分失败终态事件（携带已成功部分的结果数据 + 可选链式子任务信息）。
     *
     * @param taskId             任务ID
     * @param data               已成功部分的结果数据（与 complete 同结构）
     * @param message            部分失败说明
     * @param chainChildTaskId   链式子任务 ID（无则传 null）
     * @param chainChildTaskType 链式子任务类型（无则传 null）
     */
    public void sendPartialFailed(Long taskId, Object data, String message, Long chainChildTaskId, String chainChildTaskType)
    {
        sendPartialFailed(taskId, data, message, singletonTaskId(chainChildTaskId), chainChildTaskType);
    }

    public void sendPartialFailed(Long taskId, Object data, String message, List<Long> chainChildTaskIds, String chainChildTaskType)
    {
        Long chainChildTaskId = firstTaskId(chainChildTaskIds);
        // 写 PARTIAL_FAILED 终态快照（跨进程 + 刷新重连补发，含链式子任务信息）
        saveTerminalSnapshot(taskId, "PARTIAL_FAILED",
                blankToDefault(message, "部分完成"), chainChildTaskIds, chainChildTaskType);

        // 本地 emitter 推送
        SseEmitter emitter = emitters.get(taskId);
        if (Objects.isNull(emitter))
        {
            return;
        }
        try
        {
            Object payload = withChain(withTaskId(data, taskId), chainChildTaskIds, chainChildTaskType);
            emitter.send(SseEmitter.event()
                    .name("partial_failed")
                    .data(normalizePayload(payload), MediaType.APPLICATION_JSON));
            emitter.complete();
            log.info("SSE推送部分失败终态: taskId={}, chainChildTaskId={}", taskId, chainChildTaskId);
        }
        catch (Exception e)
        {
            log.warn("SSE推送部分失败终态失败: taskId={}", taskId);
            // 值比对删除，避免误删刷新重连后的新 emitter
            emitters.remove(taskId, emitter);
        }
    }

    /** 内部小工具：空白兜底（避免引入额外依赖） */
    private static String blankToDefault(String s, String def)
    {
        return (s == null || s.trim().isEmpty()) ? def : s;
    }

    /**
     * 把链式子任务信息合并进终态事件 payload（withTaskId 已保证 data 是 Map）。
     * chainChildTaskId 为 null 时原样返回，不影响普通任务。
     */
    private Object withChain(Object payload, Long chainChildTaskId, String chainChildTaskType)
    {
        return withChain(payload, singletonTaskId(chainChildTaskId), chainChildTaskType);
    }

    private Object withChain(Object payload, List<Long> chainChildTaskIds, String chainChildTaskType)
    {
        List<Long> safeTaskIds = normalizeTaskIds(chainChildTaskIds);
        Long chainChildTaskId = firstTaskId(safeTaskIds);
        if (chainChildTaskId != null && payload instanceof Map)
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) payload;
            map.put("chainChildTaskId", chainChildTaskId);
            map.put("chainChildTaskIds", safeTaskIds);
            map.put("chainChildTaskType", chainChildTaskType);
        }
        return payload;
    }

    /**
     * 推送警告事件（如角色视觉描述生成失败，不影响整体流程）
     *
     * @param taskId   任务ID
     * @param message  警告信息
     */
    public void sendWarning(Long taskId, String message)
    {
        // warning 不覆盖主进度快照，仅推本地 emitter
        SseEmitter emitter = emitters.get(taskId);
        if (Objects.isNull(emitter))
        {
            return;
        }
        try
        {
            emitter.send(SseEmitter.event()
                    .name("warning")
                    .data(Map.of("taskId", taskId, "message", message), MediaType.APPLICATION_JSON));
        }
        catch (Exception e)
        {
            log.warn("SSE推送警告失败: taskId={}", taskId);
            // 值比对删除，避免误删刷新重连后的新 emitter
            emitters.remove(taskId, emitter);
        }
    }

    /**
     * 推送取消事件（用户主动停止，区分于 error）
     *
     * @param taskId   任务ID
     * @param message  取消原因
     */
    public void sendCancelled(Long taskId, String message)
    {
        // 写终态快照（跨进程保底）
        saveTerminalSnapshot(taskId, "CANCELLED", message);

        // 本地 emitter 推送
        SseEmitter emitter = emitters.get(taskId);
        if (Objects.isNull(emitter))
        {
            return;
        }
        try
        {
            emitter.send(SseEmitter.event()
                    .name("cancelled")
                    .data(Map.of("taskId", taskId, "message", message), MediaType.APPLICATION_JSON));
            emitter.complete();
            log.info("SSE推送取消: taskId={}", taskId);
        }
        catch (Exception e)
        {
            log.warn("SSE推送取消失败: taskId={}", taskId);
            // 值比对删除，避免误删刷新重连后的新 emitter
            emitters.remove(taskId, emitter);
        }
    }

    /**
     * 推送失败事件
     *
     * @param taskId        任务ID
     * @param errorMessage  错误信息
     */
    public void sendError(Long taskId, String errorMessage)
    {
        // 写终态快照（跨进程保底）
        saveTerminalSnapshot(taskId, "FAILED", errorMessage);

        // 本地 emitter 推送
        SseEmitter emitter = emitters.get(taskId);
        if (Objects.isNull(emitter))
        {
            return;
        }
        try
        {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", taskId);
            payload.put("errorMessage", errorMessage);
            enrichTaskContext(taskId, payload, false);
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(payload, MediaType.APPLICATION_JSON));
            emitter.complete();
            log.info("SSE推送错误: taskId={}", taskId);
        }
        catch (Exception e)
        {
            log.warn("SSE推送错误失败: taskId={}", taskId);
            // 值比对删除，避免误删刷新重连后的新 emitter
            emitters.remove(taskId, emitter);
        }
    }

    /**
     * 推送结构化失败事件（新协议，前端可机器判断）。
     * 事件 payload 包含 errorCode / errorSource / userMessage / rawMessage /
     * needRecharge / rechargeOwner / retryable，同时保留 errorMessage 字段向后兼容。
     *
     * @param taskId      任务ID
     * @param errorResult 结构化错误结果
     */
    public void sendError(Long taskId, TaskErrorResult errorResult)
    {
        // 写终态快照（跨进程保底），message 用 userMessage
        saveTerminalSnapshot(taskId, "FAILED", errorResult);

        // 本地 emitter 推送
        SseEmitter emitter = emitters.get(taskId);
        if (Objects.isNull(emitter))
        {
            return;
        }
        try
        {
            Map<String, Object> payload = buildErrorPayload(errorResult);
            payload.put("taskId", taskId);
            enrichTaskContext(taskId, payload, false);
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(payload, MediaType.APPLICATION_JSON));
            emitter.complete();
            log.info("SSE推送结构化错误: taskId={}, errorCode={}", taskId, errorResult.getErrorCode());
        }
        catch (Exception e)
        {
            log.warn("SSE推送结构化错误失败: taskId={}", taskId);
            // 值比对删除，避免误删刷新重连后的新 emitter
            emitters.remove(taskId, emitter);
        }
    }

    /**
     * 统一构建 error 事件 payload,所有结构化字段都在这里产出。
     */
    static Map<String, Object> buildErrorPayload(TaskErrorResult errorResult)
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskStatus", errorResult.getTaskStatus() != null ? errorResult.getTaskStatus() : "FAILED");
        payload.put("errorCode", errorResult.getErrorCode());
        payload.put("errorType", errorResult.getErrorType());
        payload.put("errorSource", errorResult.getErrorSource());
        payload.put("userMessage", errorResult.getUserMessage());
        payload.put("rawMessage", errorResult.getRawMessage());
        payload.put("needRecharge", errorResult.isNeedRecharge());
        payload.put("rechargeOwner", errorResult.getRechargeOwner());
        payload.put("retryable", errorResult.isRetryable());
        payload.put("billingStatus", errorResult.getBillingStatus());
        payload.put("refundStatus", errorResult.getRefundStatus());
        // errorMessage 与结构化字段并存：只读 errorMessage 的前端也能拿到友好文案
        payload.put("errorMessage", errorResult.getUserMessage());
        return payload;
    }
    /**
     * 构建 Redis 进度快照 key
     */
    public static String buildProgressKey(Long taskId)
    {
        return PROGRESS_KEY_PREFIX + taskId;
    }

    /** Normalizes media URL fields before sending payloads to clients. */
    private Object normalizePayload(Object payload)
    {
        return Objects.isNull(mediaPayloadUrlNormalizer) ? payload : mediaPayloadUrlNormalizer.normalize(payload);
    }

    /**
     * 保存进度快照到 Redis（覆盖写，仅存最新一条）
     */
    private void saveProgressSnapshot(Long taskId, Map<String, Object> snapshot)
    {
        try
        {
            String key = buildProgressKey(taskId);
            redisCache.setCacheObject(key, snapshot, PROGRESS_TTL_MINUTES, TimeUnit.MINUTES);
        }
        catch (Exception e)
        {
            log.warn("写入Redis进度快照失败: taskId={}", taskId, e);
        }
    }

    /**
     * 推送排队事件（任务排队中，含实时位次与受限维度）。
     *
     * @param taskId     任务ID
     * @param position   排队位次（1-based，全局 ZSet rank）
     * @param ahead      前面还有多少个任务
     * @param queueTotal 当前队列总长度
     * @param blockedBy  当前受限的并发维度（GLOBAL_LIMIT/USER_LIMIT/MODEL_LIMIT/PROVIDER_LIMIT/LOCAL_EXECUTOR_LIMIT），
     */
    public void sendQueued(Long taskId, int position, int ahead, int queueTotal, String blockedBy)
    {
        // 写排队快照（跨进程保底，前端首次连接 / 断线重连可立即拿到排队态）
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("taskId", taskId);
        snapshot.put("status", "QUEUED");
        snapshot.put("stage", "queued");
        snapshot.put("progress", 0);
        snapshot.put("position", position);
        snapshot.put("ahead", ahead);
        snapshot.put("queueTotal", queueTotal);
        // 受限维度（可空），前端据此区分"纯排队"与"等待执行名额"
        snapshot.put("blockedBy", blockedBy);
        // 文案不再承诺"即将开始"：始终如实告知排队名次；受限时再补"等待执行名额"标注受限原因
        snapshot.put("message", blockedBy != null
                ? ("正在排队，当前第 " + position + " 位，等待执行名额")
                : ("正在排队，当前第 " + position + " 位"));
        enrichTaskContext(taskId, snapshot, false);
        snapshot.put("updateTime", DateUtils.getTime());
        snapshot.put("updateMillis", System.currentTimeMillis());
        saveProgressSnapshot(taskId, snapshot);

        // 本地 emitter 推送（同 JVM 即时优化）
        SseEmitter emitter = emitters.get(taskId);
        if (Objects.isNull(emitter))
        {
            return;
        }
        try
        {
            // 本地直推与 Redis 快照发送同一份 payload（含 status/updateMillis 等），
            // 保证同 JVM 直推、跨进程轮询、刷新重连三种路径前端收到的字段一致。
            emitter.send(SseEmitter.event()
                    .name("queued")
                    .data(snapshot, MediaType.APPLICATION_JSON));
            // 更新本地去重基准，避免 Redis 轮询 1.5s 后再推同一条排队快照
            lastLocalPushMillis.put(taskId, (Long) snapshot.get("updateMillis"));
        }
        catch (Exception e)
        {
            log.warn("SSE推送排队事件失败: taskId={}", taskId);
            // 值比对删除，避免误删刷新重连后的新 emitter
            emitters.remove(taskId, emitter);
        }
    }

    /**
     * 保存终态快照到 Redis（complete / error / cancelled），便于 SSE 首次连接时补发
     */
    private void saveTerminalSnapshot(Long taskId, String status, String message)
    {
        saveTerminalSnapshot(taskId, status, message, (Long) null, null);
    }

    /**
     * 补充任务上下文，保证 Redis 快照和 SSE 事件都能直接展示项目、剧集、步骤。
     */
    private void enrichTaskContext(Long taskId, Map<String, Object> snapshot, boolean prefixMessage)
    {
        AidExtractTask task = loadTask(taskId);
        if (Objects.isNull(task))
        {
            return;
        }
        snapshot.putIfAbsent("projectId", task.getProjectId());
        snapshot.putIfAbsent("episodeId", task.getEpisodeId());
        snapshot.putIfAbsent("taskType", task.getTaskType());
        snapshot.putIfAbsent("taskTitle", taskTitle(task.getTaskType()));
        snapshot.putIfAbsent("taskScope", taskScope(task));
        if (prefixMessage)
        {
            Object message = snapshot.get("message");
            snapshot.put("message", prefixTaskMessage(task, Objects.isNull(message) ? "" : String.valueOf(message)));
            Object stepTitle = snapshot.get("stepTitle");
            if (Objects.nonNull(stepTitle))
            {
                snapshot.put("stepTitle", prefixTaskMessage(task, String.valueOf(stepTitle)));
            }
        }
    }

    /**
     * 加载任务元信息；SSE 推送失败不应影响主流程。
     */
    private AidExtractTask loadTask(Long taskId)
    {
        if (Objects.isNull(taskId) || Objects.isNull(extractTaskService))
        {
            return null;
        }
        try
        {
            return extractTaskService.getById(taskId);
        }
        catch (Exception e)
        {
            log.warn("SSE任务上下文加载失败: taskId={}", taskId);
            return null;
        }
    }

    /**
     * 生成用户可读的任务范围。
     */
    private String taskScope(AidExtractTask task)
    {
        String project = Objects.nonNull(task.getProjectId()) ? "项目" + task.getProjectId() : "未知项目";
        String episode = Objects.nonNull(task.getEpisodeId()) && task.getEpisodeId() > 0
                ? "第" + task.getEpisodeId() + "集"
                : "主项目";
        return project + "·" + episode + "·" + taskTitle(task.getTaskType());
    }

    /**
     * 在用户消息前加任务范围，避免多个任务同时跑时看不清属于哪个项目。
     */
    private String prefixTaskMessage(AidExtractTask task, String message)
    {
        String scope = taskScope(task);
        if (StrUtil.isBlank(message))
        {
            return scope;
        }
        if (message.startsWith(scope) || message.startsWith("项目"))
        {
            return message;
        }
        return scope + "：" + message;
    }

    /**
     * 任务类型转展示名。
     */
    private String taskTitle(String taskType)
    {
        if (Objects.isNull(taskType))
        {
            return "任务";
        }
        return switch (taskType)
        {
            case "storyboard_script_batch" -> "分镜脚本";
            case "storyboard_image_prompt_batch" -> "分镜图脚本";
            case "storyboard_video_prompt_batch" -> "视频提示词";
            case "storyboard_image_generate" -> "分镜图生成";
            case "storyboard_video_generate" -> "分镜视频生成";
            case "form_generate_batch" -> "形象生成";
            case "form_image_batch" -> "形象图生成";
            case "asset_extract" -> "素材提取";
            default -> taskType;
        };
    }

    /**
     * 保存终态快照到 Redis（complete / partial_failed / cancelled），便于 SSE 首次连接时补发。
     * 携带链式子任务信息（chainChildTaskId / chainChildTaskType）供合并接口断线重连补发。
     */
    private void saveTerminalSnapshot(Long taskId, String status, String message,
                                      Long chainChildTaskId, String chainChildTaskType)
    {
        saveTerminalSnapshot(taskId, status, message, singletonTaskId(chainChildTaskId), chainChildTaskType);
    }

    private void saveTerminalSnapshot(Long taskId, String status, String message,
                                      List<Long> chainChildTaskIds, String chainChildTaskType)
    {
        try
        {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("taskId", taskId);
            snapshot.put("status", status);
            snapshot.put("progress", 100);
            snapshot.put("message", message);
            enrichTaskContext(taskId, snapshot, false);
            snapshot.put("updateTime", DateUtils.getTime());
            snapshot.put("updateMillis", System.currentTimeMillis());
            // 链式子任务信息（合并接口专用；普通任务为 null，前端读不到该字段即无链式）
            List<Long> safeTaskIds = normalizeTaskIds(chainChildTaskIds);
            Long chainChildTaskId = firstTaskId(safeTaskIds);
            if (chainChildTaskId != null)
            {
                snapshot.put("chainChildTaskId", chainChildTaskId);
                snapshot.put("chainChildTaskIds", safeTaskIds);
                snapshot.put("chainChildTaskType", chainChildTaskType);
            }
            String key = buildProgressKey(taskId);
            // 终态快照设置较短 TTL（5 分钟），前端连上后即消费
            redisCache.setCacheObject(key, snapshot, 5, TimeUnit.MINUTES);
        }
        catch (Exception e)
        {
            log.warn("写入Redis终态快照失败: taskId={}", taskId, e);
        }
    }

    /**
     * 保存结构化终态错误快照到 Redis。
     * 包含完整 TaskErrorResult,前端 SSE 断线重连/首包补发时能一次拿到完整终态语义。
     */
    private static List<Long> singletonTaskId(Long taskId)
    {
        List<Long> result = new ArrayList<>();
        if (taskId != null && taskId > 0)
        {
            result.add(taskId);
        }
        return result;
    }

    private static List<Long> normalizeTaskIds(List<Long> taskIds)
    {
        List<Long> result = new ArrayList<>();
        if (taskIds == null)
        {
            return result;
        }
        for (Long taskId : taskIds)
        {
            if (taskId != null && taskId > 0 && !result.contains(taskId))
            {
                result.add(taskId);
            }
        }
        return result;
    }

    private static Long firstTaskId(List<Long> taskIds)
    {
        return taskIds == null || taskIds.isEmpty() ? null : taskIds.get(0);
    }

    private void saveTerminalSnapshot(Long taskId, String status, TaskErrorResult errorResult)
    {
        try
        {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("taskId", taskId);
            snapshot.put("status", status);
            snapshot.put("progress", 100);
            snapshot.put("message", errorResult.getUserMessage());
            enrichTaskContext(taskId, snapshot, false);
            snapshot.put("updateTime", DateUtils.getTime());
            snapshot.put("updateMillis", System.currentTimeMillis());
            // 结构化错误 payload（与 SSE error 事件结构完全一致，前端消费路径统一）
            Map<String, Object> errorPayload = buildErrorPayload(errorResult);
            errorPayload.put("taskId", taskId);
            enrichTaskContext(taskId, errorPayload, false);
            snapshot.put("errorPayload", errorPayload);
            String key = buildProgressKey(taskId);
            redisCache.setCacheObject(key, snapshot, 5, TimeUnit.MINUTES);
        }
        catch (Exception e)
        {
            log.warn("写入Redis结构化终态快照失败: taskId={}", taskId, e);
        }
    }

    /**
     * 终态结果补 taskId：Map 结果无损追加；数组/字符串等包进 data，保证所有 SSE 事件都可按 taskId 绑定。
     */
    private Object withTaskId(Object data, Long taskId)
    {
        if (data instanceof Map<?, ?> map)
        {
            Map<String, Object> payload = new LinkedHashMap<>();
            map.forEach((key, value) -> payload.put(String.valueOf(key), value));
            payload.putIfAbsent("taskId", taskId);
            return payload;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("data", data);
        return payload;
    }
}
