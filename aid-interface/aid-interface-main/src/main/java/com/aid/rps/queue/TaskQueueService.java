package com.aid.rps.queue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.common.utils.DateUtils;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.rps.sse.AssetExtractSseManager;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 任务排队调度核心服务：只做排队 + 多维并发准入 + 派发 + 名额释放机制，不含具体业务/计费。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class TaskQueueService
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String DEL_FLAG_NORMAL = "0";

    /** 终态集合：处于这些状态的任务必须释放名额 */
    private static final Set<String> TERMINAL_STATUS = Set.of(
            "SUCCEEDED", "FAILED", "CANCELLED", "PARTIAL_FAILED");

    /** MQ Consumer 能处理的 taskType 白名单，仅用于 ctx 丢失后重建为 MQ 派发 */
    private static final Set<String> MQ_CONSUMER_TASK_TYPES = Set.of(
            "asset_extract", "image_upscale", "form_generate_batch", "form_image_batch",
            "form_card_image_batch",
            "storyboard_script_batch", "storyboard_image_prompt_batch", "storyboard_video_prompt_batch");

    /** 单拍最多扫描的排队条数：按 score 升序取稳定快照，超出部分由后续调度拍接力处理 */
    private static final int MAX_DRAIN_SCAN = 1000;

    /**
     * 优先级换算权重：score = enqueueMillis - priority * 权重（越小越靠前）。
     */
    private static final long PRIORITY_WEIGHT = 86_400_000L;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private TaskSlotManager slotManager;

    @Autowired
    private TaskLeaseManager leaseManager;

    @Autowired
    private TaskConcurrencyConfig concurrencyConfig;

    @Autowired
    private IAidExtractTaskService extractTaskService;

    @Autowired
    private AssetExtractSseManager sseManager;

    /** 派发失败时退回预冻结资金（避免资金挂账） */
    @Autowired
    private com.aid.rps.service.IExtractBillingService extractBillingService;

    /** 派发执行器（MQ / LOCAL），由 Spring 注入全部实现，按 dispatchMode 索引 */
    @Autowired(required = false)
    private List<TaskDispatchExecutor> dispatchExecutors;

    /** 本地任务执行体注册表（叶子组件，单向依赖，打破循环） */
    @Autowired
    private LocalJobRegistry localJobRegistry;

    /** 队列层终态后的业务收尾回调（{@code @Lazy} 打破循环依赖，required=false 允许无实现降级） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private QueueTaskFinalizer taskFinalizer;

    @Autowired
    private IWechatNotifyService wechatNotifyService;

    private final Map<String, TaskDispatchExecutor> executorByMode = new ConcurrentHashMap<>();

    /** 调度专用执行器（自包含，定时拍由 Quartz 固定任务 systemCoreTask.queueDispatchTick 触发），防重入由 {@link #ticking} 合并 */
    private ScheduledExecutorService dispatchExecutor;

    /** 防重入：同一时刻仅一拍在跑 */
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    @PostConstruct
    public void initDispatchExecutor()
    {
        dispatchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "taskq-dispatch");
            t.setDaemon(true);
            return t;
        });
        log.info("任务排队调度执行器初始化完成");
    }

    @PreDestroy
    public void destroyDispatchExecutor()
    {
        if (dispatchExecutor != null)
        {
            dispatchExecutor.shutdownNow();
        }
    }

    private Map<String, TaskDispatchExecutor> executors()
    {
        if (executorByMode.isEmpty() && CollectionUtil.isNotEmpty(dispatchExecutors))
        {
            for (TaskDispatchExecutor exec : dispatchExecutors)
            {
                executorByMode.put(exec.dispatchMode(), exec);
            }
        }
        return executorByMode;
    }
    /** 触发一次调度拍（定时拍调用）：投递到专用执行器异步执行，不阻塞调用线程 */
    public void triggerDispatch()
    {
        submitTick(0L);
    }

    /** 主动唤醒调度（入队 / 释放名额后调用）：短延迟后触发一拍，降低排队延迟 */
    private void wakeup()
    {
        submitTick(50L);
    }

    private void submitTick(long delayMs)
    {
        ScheduledExecutorService exec = dispatchExecutor;
        if (exec == null || exec.isShutdown())
        {
            return;
        }
        try
        {
            if (delayMs > 0)
            {
                exec.schedule(this::runTick, delayMs, TimeUnit.MILLISECONDS);
            }
            else
            {
                exec.execute(this::runTick);
            }
        }
        catch (Exception e)
        {
            log.debug("调度拍投递失败(忽略): {}", e.getMessage());
        }
    }

    private void runTick()
    {
        if (!ticking.compareAndSet(false, true))
        {
            // 上一拍未结束，跳过（下次触发会再来）
            return;
        }
        try
        {
            dispatchTick();
        }
        catch (Exception e)
        {
            log.error("调度拍执行异常", e);
        }
        finally
        {
            ticking.set(false);
        }
    }
    /**
     * 提交 MQ 派发任务入队（已落库 PENDING + 已预冻结后调用），放行时发 RocketMQ 由 Consumer 消费。
     *
     * @return true=入队成功；false=CAS失败（任务已被推进/取消）
     */
    public boolean submitMqTask(Long taskId, Long projectId, Long episodeId, Long userId,
                                String modelCode, String taskType)
    {
        Long providerId = concurrencyConfig.resolveProviderId(modelCode);
        QueuedTaskContext ctx = QueuedTaskContext.builder()
                .taskId(taskId)
                .projectId(projectId)
                .episodeId(episodeId)
                .userId(userId)
                .modelCode(modelCode)
                .providerId(providerId)
                .taskType(taskType)
                .dispatchMode(MqTaskDispatchExecutor.MODE)
                .build();
        return enqueue(ctx);
    }

    /**
     * 提交 MQ 派发任务入队（强制立即入队），用于已处于事务提交后上下文（如 afterCommit 回调内）时直接入队。
     *
     * @return true=入队成功；false=CAS失败（任务已被推进/取消）
     */
    public boolean submitMqTaskNow(Long taskId, Long projectId, Long episodeId, Long userId,
                                   String modelCode, String taskType)
    {
        Long providerId = concurrencyConfig.resolveProviderId(modelCode);
        QueuedTaskContext ctx = QueuedTaskContext.builder()
                .taskId(taskId)
                .projectId(projectId)
                .episodeId(episodeId)
                .userId(userId)
                .modelCode(modelCode)
                .providerId(providerId)
                .taskType(taskType)
                .dispatchMode(MqTaskDispatchExecutor.MODE)
                .build();
        return doEnqueue(ctx);
    }

    /**
     * 提交本地派发任务入队（已落库 PENDING + 已预冻结后调用），先注册 job 再入队，放行时取出 job 在本地线程池执行。
     *
     * @return true=入队成功；false=CAS失败（任务已被推进/取消）
     */
    public boolean submitLocalTask(Long taskId, Long projectId, Long episodeId, Long userId,
                                   String modelCode, String taskType, Runnable job)
    {
        registerLocalJob(taskId, job);
        Long providerId = concurrencyConfig.resolveProviderId(modelCode);
        QueuedTaskContext ctx = QueuedTaskContext.builder()
                .taskId(taskId)
                .projectId(projectId)
                .episodeId(episodeId)
                .userId(userId)
                .modelCode(modelCode)
                .providerId(providerId)
                .taskType(taskType)
                .dispatchMode(LocalTaskDispatchExecutor.MODE)
                .ownerInstanceId(leaseManager.getInstanceId())
                .build();
        boolean ok = enqueue(ctx);
        if (!ok)
        {
            // 入队失败要清掉刚注册的 job，避免内存泄漏
            localJobRegistry.remove(taskId);
        }
        return ok;
    }

    /**
     * 提交本地派发任务入队（强制立即入队），用于已处于事务提交后上下文（afterCommit 回调内）时直接入队。
     *
     * @return true=入队成功；false=CAS失败（任务已被推进/取消）
     */
    public boolean submitLocalTaskNow(Long taskId, Long projectId, Long episodeId, Long userId,
                                      String modelCode, String taskType, Runnable job)
    {
        registerLocalJob(taskId, job);
        Long providerId = concurrencyConfig.resolveProviderId(modelCode);
        QueuedTaskContext ctx = QueuedTaskContext.builder()
                .taskId(taskId)
                .projectId(projectId)
                .episodeId(episodeId)
                .userId(userId)
                .modelCode(modelCode)
                .providerId(providerId)
                .taskType(taskType)
                .dispatchMode(LocalTaskDispatchExecutor.MODE)
                .ownerInstanceId(leaseManager.getInstanceId())
                .build();
        boolean ok = doEnqueue(ctx);
        if (!ok)
        {
            // 入队失败要清掉刚注册的 job，避免内存泄漏
            localJobRegistry.remove(taskId);
        }
        return ok;
    }

    /**
     * 注册本实例 LOCAL 任务的执行 Runnable（仅 dispatchMode=LOCAL 需要），须在入队前调用。
     */
    public void registerLocalJob(Long taskId, Runnable job)
    {
        localJobRegistry.register(taskId, job);
    }

    /**
     * 任务入队（已落库 PENDING + 已预冻结计费后调用），事务活跃时延迟到提交后执行，否则立即入队。
     *
     * @return true=入队成功或已登记 afterCommit 延迟入队
     */
    public boolean enqueue(QueuedTaskContext ctx)
    {
        if (ctx == null || ctx.getTaskId() == null)
        {
            return false;
        }
        // 事务活跃 → 延迟到 afterCommit（保证调度器能读到已提交的 QUEUED 行）
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
        {
            final QueuedTaskContext fctx = ctx;
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization()
                    {
                        @Override
                        public void afterCommit()
                        {
                            boolean ok;
                            try { ok = doEnqueue(fctx); }
                            catch (Exception e)
                            {
                                log.error("afterCommit 延迟入队失败, taskId={}", fctx.getTaskId(), e);
                                ok = false;
                            }
                            if (!ok)
                            {
                                // 延迟入队未成立：事务已提交、调用方已收到 true 误以为已入队，这里必须兜底——
                                // 置 FAILED + 清理队列痕迹，交由计费补偿(retryFrozenBatch 扫 FAILED)退回冻结款，
                                // 杜绝付费任务停在 PENDING/QUEUED + 冻结款无人退
                                failEnqueue(fctx.getTaskId());
                            }
                        }

                        @Override
                        public void afterCompletion(int status)
                        {
                            // 事务回滚 / 未知结局：afterCommit 不会执行、doEnqueue 不会发生，
                            // 清理调用方（如 submitLocalTask）可能已注册的本地执行 job，避免 Runnable 常驻内存泄漏。
                            // MQ 任务无本地 job，remove 为无害 no-op。
                            if (status != STATUS_COMMITTED)
                            {
                                try { localJobRegistry.remove(fctx.getTaskId()); }
                                catch (Exception ignore) { }
                            }
                        }
                    });
            return true;
        }
        return doEnqueue(ctx);
    }

    /**
     * 真正执行入队（事务已提交 / 无事务上下文）。
     */
    private boolean doEnqueue(QueuedTaskContext ctx)
    {
        Long taskId = ctx.getTaskId();
        long now = System.currentTimeMillis();
        ctx.setEnqueueMillis(now);

        LambdaUpdateWrapper<AidExtractTask> upd = Wrappers.lambdaUpdate();
        upd.eq(AidExtractTask::getId, taskId);
        upd.eq(AidExtractTask::getStatus, STATUS_PENDING);
        upd.set(AidExtractTask::getStatus, STATUS_QUEUED);
        upd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, upd);
        if (rows == 0)
        {
            log.warn("任务入队CAS失败(非PENDING), 跳过排队: taskId={}", taskId);
            return false;
        }

        // 2-3. 写 ctx + ZADD 等待集（关键步骤，缺一不可：ctx 缺失则放行无法构建派发消息、ZSET 缺失则永不被调度）。
        //      任一失败 → 清理已写痕迹 + 回滚 QUEUED→PENDING，返回 false，保证 doEnqueue 对调用方"原子"：
        //      true=已入队待派发；false=已回滚无痕迹。杜绝"半入队"任务被消费端执行而调用方已退款 → 免费生成。
        try
        {
            stringRedisTemplate.opsForValue().set(TaskQueueKeys.ctxKey(taskId),
                    OBJECT_MAPPER.writeValueAsString(ctx));
            double score = computeScore(now, 0);
            stringRedisTemplate.opsForZSet().add(TaskQueueKeys.WAIT_ZSET, String.valueOf(taskId), score);
        }
        catch (Exception e)
        {
            log.error("任务入队写Redis失败，回滚QUEUED→PENDING并清理痕迹, taskId={}", taskId, e);
            try { stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.WAIT_ZSET, String.valueOf(taskId)); }
            catch (Exception ignore) { }
            try { stringRedisTemplate.delete(TaskQueueKeys.ctxKey(taskId)); }
            catch (Exception ignore) { }
            LambdaUpdateWrapper<AidExtractTask> rb = Wrappers.lambdaUpdate();
            rb.eq(AidExtractTask::getId, taskId);
            rb.eq(AidExtractTask::getStatus, STATUS_QUEUED);
            rb.set(AidExtractTask::getStatus, STATUS_PENDING);
            rb.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            try { extractTaskService.getBaseMapper().update(null, rb); }
            catch (Exception ignore) { }
            return false;
        }

        // 4-5. 非关键后置：推送排队事件 + 唤醒调度。失败不影响"入队已成立"（下一调度拍会处理），仅记日志、不上抛
        try { pushQueuedEvent(taskId); }
        catch (Exception e) { log.warn("推送排队事件失败(不影响入队), taskId={}", taskId, e); }
        log.info("任务已入队排队: taskId={}, userId={}, modelCode={}", taskId, ctx.getUserId(), ctx.getModelCode());
        try { wakeup(); }
        catch (Exception e) { log.warn("唤醒调度失败(不影响入队), taskId={}", taskId, e); }
        return true;
    }

    /**
     * 入队兜底失败处理：CAS 置 FAILED（仅 PENDING/QUEUED 可置）并清理队列痕迹，让计费补偿退回冻结款。
     */
    private void failEnqueue(Long taskId)
    {
        // 视频续生任务（runNo>0）入队失败：回滚 PARTIAL_FAILED 保留续生入口，不按 FAILED 处理
        if (tryRollbackResumableVideoTask(taskId, "入队失败"))
        {
            return;
        }
        int rows;
        try
        {
            LambdaUpdateWrapper<AidExtractTask> upd = Wrappers.lambdaUpdate();
            upd.eq(AidExtractTask::getId, taskId);
            upd.in(AidExtractTask::getStatus, STATUS_PENDING, STATUS_QUEUED);
            upd.set(AidExtractTask::getStatus, "FAILED");
            upd.set(AidExtractTask::getErrorMessage, "入队失败");
            upd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            rows = extractTaskService.getBaseMapper().update(null, upd);
        }
        catch (Exception e)
        {
            // DB 置 FAILED 异常：终态是否落库不确定，绝不继续清队列 / 释放业务锁 / 推 error，
            // 避免"DB 未终态但外围资源已清"的不一致，交由对账 / 自愈补偿。（与 failTaskAndRefund 同一纪律）
            log.error("延迟入队失败兜底置FAILED异常，跳过外围清理(交由对账/自愈补偿), taskId={}", taskId, e);
            return;
        }
        if (rows == 0)
        {
            // 任务已被取消 / 其它线程推进到非 PENDING/QUEUED：不做终态收尾，避免与真实终态冲突
            log.info("入队失败兜底跳过，任务状态已变化: taskId={}", taskId);
            return;
        }
        // CAS 成功落 FAILED 后，再清外围资源
        try { stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.WAIT_ZSET, String.valueOf(taskId)); }
        catch (Exception ignore) { }
        try { stringRedisTemplate.delete(TaskQueueKeys.ctxKey(taskId)); }
        catch (Exception ignore) { }
        // 清理可能已注册的本地执行 job（submitLocalTask 在事务内走延迟入队时已注册），MQ 任务调用无害
        try { localJobRegistry.remove(taskId); }
        catch (Exception ignore) { }
        // 推 SSE 失败终态：SSE 轮询只读 Redis 快照，若不推则已连接前端只见 connected/心跳、看不到 FAILED
        try { sseManager.sendError(taskId, "入队失败"); }
        catch (Exception ignore) { }
        wechatNotifyService.notifyTaskTerminal(taskId);
        // 业务收尾：入队失败时提交防重锁通常已被 submit 持有，这里一并释放 + 清 worker cancel flag，避免用户等 TTL。幂等、不抛出。
        if (taskFinalizer != null)
        {
            try { taskFinalizer.onQueueTaskTerminated(taskId); }
            catch (Exception e) { log.warn("入队失败业务收尾回调异常(忽略): taskId={}", taskId, e); }
        }
    }

    private double computeScore(long enqueueMillis, int priority)
    {
        return (double) (enqueueMillis - (long) priority * PRIORITY_WEIGHT);
    }

    /** 派发锁 TTL（秒）：短 TTL，仅覆盖抢名额+CAS+发起派发的瞬时窗口，派发完成立即释放，崩溃则最多 {@value} 秒后可重派 */
    private static final long DISPATCH_LOCK_TTL_SECONDS = 10L;

    /**
     * 抢该任务的分布式派发锁（多实例防重派发），value 用本实例 ID，释放时 CAS 比对避免误删他人锁。
     */
    private boolean acquireDispatchLock(Long taskId)
    {
        try
        {
            Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(
                    TaskQueueKeys.dispatchLockKey(taskId), leaseManager.getInstanceId(),
                    DISPATCH_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(ok);
        }
        catch (Exception e)
        {
            // Redis 异常：偏保守不放行（返回 false），避免无锁并发派发
            log.warn("抢派发锁异常, 跳过本任务, taskId={}", taskId, e);
            return false;
        }
    }

    /** CAS 释放派发锁（仅当锁仍是本实例持有时删除）。 */
    private static final DefaultRedisScript<Long> DISPATCH_UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    private void releaseDispatchLock(Long taskId)
    {
        try
        {
            stringRedisTemplate.execute(DISPATCH_UNLOCK_SCRIPT,
                    java.util.Collections.singletonList(TaskQueueKeys.dispatchLockKey(taskId)),
                    leaseManager.getInstanceId());
        }
        catch (Exception e)
        {
            log.debug("释放派发锁异常(忽略, 将自然过期), taskId={}: {}", taskId, e.getMessage());
        }
    }
    /**
     * 调度器单拍：回收终态任务名额 + FIFO 排队放行，由定时器触发或名额释放后主动调用。
     */
    public synchronized void dispatchTick()
    {
        try
        {
            reconcileTerminalSlots();
        }
        catch (Exception e)
        {
            log.warn("回收终态名额异常", e);
        }
        try
        {
            reapDeadOwnerLocalTasks();
        }
        catch (Exception e)
        {
            log.warn("回收owner失活LOCAL任务异常", e);
        }
        try
        {
            drain();
        }
        catch (Exception e)
        {
            log.warn("排队放行异常", e);
        }
    }

    /**
     * 回收「owner 实例已失活」的排队中 LOCAL 任务（多实例容灾）。
     */
    private void reapDeadOwnerLocalTasks()
    {
        Set<String> waiting = stringRedisTemplate.opsForZSet()
                .range(TaskQueueKeys.WAIT_ZSET, 0, MAX_DRAIN_SCAN - 1);
        if (CollectionUtil.isEmpty(waiting))
        {
            return;
        }
        // 本拍内缓存实例存活判定：多个任务常共享同一 owner，避免重复查 Redis
        Map<String, Boolean> aliveCache = new HashMap<>();
        for (String member : waiting)
        {
            Long taskId;
            try { taskId = Long.parseLong(member); } catch (NumberFormatException e) { continue; }

            QueuedTaskContext ctx = loadCtx(taskId);
            // 仅回收 LOCAL + 有明确 owner 的任务
            if (ctx == null
                    || !LocalTaskDispatchExecutor.MODE.equals(ctx.getDispatchMode())
                    || StrUtil.isBlank(ctx.getOwnerInstanceId()))
            {
                continue;
            }
            String owner = ctx.getOwnerInstanceId();
            if (aliveCache.computeIfAbsent(owner, leaseManager::isInstanceAlive))
            {
                // owner 仍存活 → 由 owner 实例负责放行，本实例不动
                continue;
            }
            // owner 已失活：抢派发锁后再次确认，避免与 owner "心跳恢复" / 其它实例并发回收竞争
            if (!acquireDispatchLock(taskId))
            {
                continue;
            }
            try
            {
                if (leaseManager.isInstanceAlive(owner))
                {
                    // 抢锁期间 owner 心跳恢复 → 放弃回收
                    continue;
                }
                AidExtractTask task = extractTaskService.getOne(
                        Wrappers.<AidExtractTask>lambdaQuery()
                                .select(AidExtractTask::getId, AidExtractTask::getStatus)
                                .eq(AidExtractTask::getId, taskId).last("LIMIT 1"), false);
                if (task == null || !STATUS_QUEUED.equals(task.getStatus()))
                {
                    // 已被取消/推进 → 无需回收
                    continue;
                }
                log.warn("回收owner已失活的LOCAL排队任务: taskId={}, owner={}", taskId, owner);
                failTaskAndRefund(ctx, "执行节点失效");
            }
            finally
            {
                releaseDispatchLock(taskId);
            }
        }
    }

    /**
     * 回收终态任务的名额 + 给存活任务续期占用过期时刻：扫全局占用集，批量查状态。
     * 名额释放的"最终一致"保底：① 行已删 / 已终态 → 释放名额；② 非终态 + 租约存活 → 续期占用过期时刻
     * （{@link TaskSlotManager#renewOccupancy}），保证存活长任务的名额不被自过期误释放；③ 非终态 + 租约失活
     * （执行进程已死的孤儿）→ 不续期，交由 {@link TaskSlotManager#SLOT_OCCUPANCY_TTL_MS} 自过期窗口兜底回收
     * （正常更早由租约失活回收 / 扇入对账显式收尾）。每调度拍（~1.5s）执行。
     */
    private void reconcileTerminalSlots()
    {
        Set<String> occupants = slotManager.getGlobalOccupants();
        if (CollectionUtil.isEmpty(occupants))
        {
            return;
        }
        List<Long> ids = new ArrayList<>();
        for (String s : occupants)
        {
            try { ids.add(Long.parseLong(s)); } catch (NumberFormatException ignore) { }
        }
        if (ids.isEmpty())
        {
            return;
        }
        // 批量查状态 + 维度（id + status + userId + modelCode）：续期需要维度算占用集 key，避免逐条查 ctx
        List<AidExtractTask> tasks = extractTaskService.list(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getStatus,
                                AidExtractTask::getUserId, AidExtractTask::getModelCode)
                        .in(AidExtractTask::getId, ids));
        Map<Long, AidExtractTask> taskMap = new HashMap<>();
        for (AidExtractTask t : tasks)
        {
            taskMap.put(t.getId(), t);
        }
        for (Long taskId : ids)
        {
            AidExtractTask t = taskMap.get(taskId);
            String status = (t == null) ? null : t.getStatus();
            // 任务行已不存在（被物理删除）或已终态 → 释放名额
            if (status == null || TERMINAL_STATUS.contains(status))
            {
                releaseSlots(taskId);
                continue;
            }
            // 非终态：租约存活（执行进程在跑）→ 续期占用过期时刻，长任务不被自过期误释放；
            //         租约失活（孤儿）→ 不续期，留给自过期窗口 + 租约失活回收 / 扇入对账收尾。
            if (leaseManager.isAlive(taskId))
            {
                Long providerId = concurrencyConfig.resolveProviderId(t.getModelCode());
                slotManager.renewOccupancy(taskId, t.getUserId(), t.getModelCode(), providerId);
            }
        }
    }

    /**
     * FIFO 排队放行：取等待集稳定快照后按 score 升序逐个尝试抢四维名额。
     */
    private void drain()
    {
        int globalLimit = concurrencyConfig.getGlobalLimit();
        // 取稳定快照（一次性到扫描上限），后续放行的删除不再影响本拍遍历顺序
        Set<String> waiting = stringRedisTemplate.opsForZSet()
                .range(TaskQueueKeys.WAIT_ZSET, 0, MAX_DRAIN_SCAN - 1);
        if (CollectionUtil.isEmpty(waiting))
        {
            return;
        }

        for (String member : waiting)
        {
            // 全局已满 → 本拍无需再扫
            if (slotManager.getGlobalOccupied() >= globalLimit)
            {
                break;
            }
            Long taskId;
            try { taskId = Long.parseLong(member); } catch (NumberFormatException e) { continue; }

            // 多实例防重派发：尽早抢分布式派发锁，覆盖"ctx/状态检查 → 清理 → 抢名额 → 放行/回排"全过程。
            // 锁前移的关键作用（修复多实例竞态）：避免与<u>另一实例的 requeue（先 ZADD 后 CAS QUEUED）</u>窗口竞争——
            // 若不持锁就做"非QUEUED→清理 WAIT_ZSET/ctx"，可能误清掉对方正在回排、DB 尚未改回 QUEUED 的任务，
            // 造成"DB=QUEUED 但不在等待集"的孤儿。持锁后另一实例抢锁失败会跳过，不会误清。
            // （同时也避免两个实例对同一 taskId 同时 tryAcquire 导致名额被算少/上限被突破。）
            if (!acquireDispatchLock(taskId))
            {
                // 其它实例正在处理本任务，跳过（不动队列，下一拍/对方处理）
                continue;
            }
            try
            {
                QueuedTaskContext ctx = loadCtx(taskId);
                if (ctx == null)
                {
                    // ctx 丢失：尝试从 DB 重建；重建失败则清理出队
                    ctx = rebuildCtxFromDb(taskId);
                    if (ctx == null)
                    {
                        stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.WAIT_ZSET, member);
                        continue;
                    }
                }

                AidExtractTask task = extractTaskService.getOne(
                        Wrappers.<AidExtractTask>lambdaQuery()
                                .select(AidExtractTask::getId, AidExtractTask::getStatus)
                                .eq(AidExtractTask::getId, taskId).last("LIMIT 1"), false);
                String status = (task == null) ? null : task.getStatus();
                // PENDING 且仍在等待集：仅此态需要保护——它必然处于某实例派发锁的临界区
                //   （requeue 的 ZADD→CAS 之间，或 admit 的 CAS QUEUED→PENDING→ZREM 之间）。不清理、仅跳过本拍。
                //   即便那把锁因 DB 长卡顿/长 GC 超时过期、本实例抢到锁看到 PENDING，原实例恢复后仍会把它 CAS 回 QUEUED，无孤儿；
                //   极端情形（实例恰好死于 ZADD→CAS 微秒窗口）由重启自愈兜底。
                if (STATUS_PENDING.equals(status))
                {
                    continue;
                }
                // 其余非 QUEUED 态（null/已删除、终态、PROCESSING、历史脏态等）→ 移出等待集（不该再排队）。
                //   不能像 PENDING 那样只跳过：否则脏成员会长期滞留等待集，占用 rank / MAX_DRAIN_SCAN 窗口，
                //   并被 refreshQueuePositions（不查 DB）持续误推"排队中"快照。
                if (!STATUS_QUEUED.equals(status))
                {
                    stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.WAIT_ZSET, member);
                    // ctx 不只是排队展示：后续 releaseSlots() 会优先靠它释放 user/model/provider 维度名额。
                    //   仅 null/终态 才删 ctx（此时已无需释放或已释放）；PROCESSING/WAIT_POLL/INIT 等"仍在跑"的非终态脏成员
                    //   保留 ctx，留给其真正终态时按 ctx 精确释放维度名额，避免模型 provider 配置变更 / DB 回退异常时 provider 维度释放不全。
                    if (status == null || TERMINAL_STATUS.contains(status))
                    {
                        stringRedisTemplate.delete(TaskQueueKeys.ctxKey(taskId));
                    }
                    continue;
                }

                // 放行前命中「取消请求」：tryCancelQueuedOrPending 抢派发锁失败降级时会打此标记。此刻任务仍 QUEUED 未派发，
                // 在锁内直接队列层取消（CAS QUEUED→CANCELLED + 释放名额/出队 + 退款 + 推 cancelled），杜绝"已请求取消的排队任务仍被放行执行"。
                // 放在 owner-gate 之前：取消无需本地 job，任一实例的 drain 命中即可取消（不区分 owner）。
                if (isCancelRequested(taskId))
                {
                    LambdaUpdateWrapper<AidExtractTask> cx = Wrappers.lambdaUpdate();
                    cx.eq(AidExtractTask::getId, taskId);
                    cx.eq(AidExtractTask::getStatus, STATUS_QUEUED);
                    cx.set(AidExtractTask::getStatus, "CANCELLED");
                    cx.set(AidExtractTask::getErrorMessage, "用户取消");
                    cx.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
                    int cxRows = extractTaskService.getBaseMapper().update(null, cx);
                    if (cxRows > 0)
                    {
                        releaseSlots(taskId);
                        try
                        {
                            if (ctx.getUserId() != null)
                            {
                                extractBillingService.refundBilling(taskId, ctx.getUserId());
                            }
                        }
                        catch (Exception e)
                        {
                            log.error("取消请求退款异常, 需人工核对: taskId={}", taskId, e);
                        }
                        try { sseManager.sendCancelled(taskId, "用户取消"); } catch (Exception ignore) { }
                        // 业务收尾：释放 taskType 业务防重锁 + 清 worker cancel flag（队列层无法直接做，回调业务 Service）
                        if (taskFinalizer != null)
                        {
                            try { taskFinalizer.onQueueTaskTerminated(taskId); }
                            catch (Exception e) { log.warn("队列取消业务收尾回调异常(忽略): taskId={}", taskId, e); }
                        }
                        log.info("放行前命中取消请求，已在队列层取消: taskId={}", taskId);
                    }
                    clearCancelRequested(taskId);
                    continue;
                }

                // LOCAL 任务的执行 job 是创建实例的 JVM 内存态（LocalJobRegistry），只有 owner 实例能取到 job 放行。
                // 多实例下非 owner 实例若放行会取不到 job → 误判派发失败 → 退款。故非 owner 直接跳过，交给 owner 实例处理。
                // ctx 无 ownerInstanceId 时不做实例限制；owner 实例宕机的兜底：reaper 会把其遗留的 LOCAL 任务判失败退款。
                if (LocalTaskDispatchExecutor.MODE.equals(ctx.getDispatchMode())
                        && StrUtil.isNotBlank(ctx.getOwnerInstanceId())
                        && !ctx.getOwnerInstanceId().equals(leaseManager.getInstanceId()))
                {
                    continue;
                }

                int userLimit = concurrencyConfig.getUserLimit(ctx.getUserId());
                int modelLimit = concurrencyConfig.getModelLimit(ctx.getModelCode());
                int providerLimit = concurrencyConfig.getProviderLimit(ctx.getProviderId());

                // 派发执行器（如本地线程池）已饱和：本拍即使抢到名额也派发不出去。
                // 在抢名额 / CAS 之前就跳过，避免每拍对该任务做无谓的 QUEUED→PENDING→QUEUED 反复写、
                // 以及取消窗口里的瞬时 PENDING。任务留在等待集，由本拍收尾的 refreshQueuePositions
                // 推 LOCAL_EXECUTOR_LIMIT 文案；下一拍线程池有空位后再正常放行。
                TaskDispatchExecutor execProbe = executors().get(ctx.getDispatchMode());
                if (execProbe != null && execProbe.saturated())
                {
                    continue;
                }

                Long acq = slotManager.tryAcquire(taskId, ctx.getUserId(), ctx.getModelCode(), ctx.getProviderId(),
                        globalLimit, userLimit, modelLimit, providerLimit);
                if (acq == null)
                {
                    // Redis 异常，本拍放弃
                    break;
                }
                if (acq == 1L)
                {
                    boolean ok = admitAndDispatch(ctx, member);
                    if (!ok)
                    {
                        log.warn("任务放行派发失败: taskId={}", taskId);
                    }
                }
                else if (acq == -1L)
                {
                    // 全局满 → 停止
                    break;
                }
                // -2/-3/-4：用户/模型/服务商维度满 → 跳过，继续尝试后面任务
            }
            finally
            {
                releaseDispatchLock(taskId);
            }
        }

        // 放行后刷新仍在排队任务的位次
        refreshQueuePositions();
    }

    /**
     * 名额已抢到：CAS QUEUED→PENDING + 写租约 + 派发。
     *
     * @return true=派发成功
     */
    private boolean admitAndDispatch(QueuedTaskContext ctx, String member)
    {
        Long taskId = ctx.getTaskId();
        // CAS QUEUED→PENDING
        LambdaUpdateWrapper<AidExtractTask> upd = Wrappers.lambdaUpdate();
        upd.eq(AidExtractTask::getId, taskId);
        upd.eq(AidExtractTask::getStatus, STATUS_QUEUED);
        upd.set(AidExtractTask::getStatus, STATUS_PENDING);
        upd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, upd);
        if (rows == 0)
        {
            // 已被取消/推进：回滚名额 + 出队（均 best-effort，避免裸 Redis 异常打断回滚）
            try { slotManager.release(taskId, ctx.getUserId(), ctx.getModelCode(), ctx.getProviderId()); }
            catch (Exception e) { log.warn("放行CAS失败回滚名额异常(忽略), taskId={}", taskId, e); }
            try { stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.WAIT_ZSET, member); }
            catch (Exception e) { log.warn("放行CAS失败出等待集异常(忽略), taskId={}", taskId, e); }
            return false;
        }

        // LOCAL 派发成功后可能先在本地线程池排队，worker 尚未启动也要持续续租。
        if (LocalTaskDispatchExecutor.MODE.equals(ctx.getDispatchMode()))
        {
            leaseManager.markActive(taskId);
        }
        else
        {
            leaseManager.renew(taskId);
        }

        // 出等待集（已放行）。必须 best-effort：DB 已 CAS 成 PENDING、名额已占，若此处裸 ZREM 抛 Redis 异常导致方法
        // 在 dispatch 之前退出，任务会卡在"PENDING+占名额+未派发"，drain 下一拍又按 PENDING 保护性跳过，只能等僵尸回收兜底。
        // 故吞掉 ZREM 异常继续 sendAdmitted + dispatch；残留的等待集成员在任务进入非 QUEUED 后会被 drain 的脏成员清理移除。
        try { stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.WAIT_ZSET, member); }
        catch (Exception e) { log.warn("放行出等待集异常(忽略, 不阻断派发), taskId={}", taskId, e); }

        // 先取派发执行器（务必在推 admitted 之前）
        TaskDispatchExecutor exec = executors().get(ctx.getDispatchMode());
        if (exec == null)
        {
            log.error("无对应派发执行器, dispatchMode={}, taskId={}", ctx.getDispatchMode(), taskId);
            rollbackDispatchFailure(ctx);
            return false;
        }

        // 执行器已饱和（如本地线程池打满）：派发必被拒。直接走可重试回排，且不推 admitted——
        // 否则本地池满时每拍都会"已获得执行名额，准备开始"→瞬间又"排队中，等待执行名额"地闪跳。
        // 由 requeue 推一条 queued（blockedBy=LOCAL_EXECUTOR_LIMIT），用户停留在稳定的"等待执行名额"。
        if (exec.saturated())
        {
            log.warn("派发执行器已饱和，跳过本次放行直接回排(不推admitted): taskId={}, mode={}", taskId, ctx.getDispatchMode());
            requeueAfterRetryableReject(ctx, member);
            return false;
        }

        // 执行器有容量 → 在真正派发之前推一条「已获得执行名额，准备开始」(ADMITTED) 覆盖排队快照：
        // 这样无论 LOCAL worker 多快开始推真实进度，真实进度的 updateMillis 一定晚于 admitted，
        // 不会出现"业务已到具体阶段又被 admitted 倒退"的串写。派发失败/重排路径会再用 error/queued 覆盖本条。
        sseManager.sendAdmitted(taskId);

        try
        {
            boolean dispatched = exec.dispatch(ctx);
            if (!dispatched)
            {
                // 真正派发失败 → 置 FAILED + 退款 + SSE 通知（error 事件覆盖刚才的 admitted）
                rollbackDispatchFailure(ctx);
                return false;
            }
        }
        catch (TaskDispatchRetryableException retry)
        {
            // 临时性资源不足（如 saturated 判定后到 dispatch 之间线程池才被打满的竞态）：不判失败、不退款，
            // 撤销本次放行（回 QUEUED + 释放名额 + 重新入队），等下一调度拍重试。queued 事件覆盖刚才的 admitted。
            log.warn("任务派发暂不可用，撤销放行并重新入队等待下一拍: taskId={}, 原因={}", taskId, retry.getMessage());
            requeueAfterRetryableReject(ctx, member);
            return false;
        }
        catch (Exception e)
        {
            log.error("派发执行异常, taskId={}", taskId, e);
            rollbackDispatchFailure(ctx);
            return false;
        }

        log.info("任务放行成功: taskId={}, mode={}", taskId, ctx.getDispatchMode());
        return true;
    }

    /**
     * 派发失败回滚：释放名额 + 租约，标记任务 FAILED（CAS PENDING→FAILED），退回预冻结资金，推 SSE。
     */
    private void rollbackDispatchFailure(QueuedTaskContext ctx)
    {
        failTaskAndRefund(ctx, "任务派发失败");
    }

    /**
     * 任务失败收口（幂等）：先以 CAS 成功落 DB 终态为前提，再退回预冻结资金 + 释放名额 + 推 SSE error。
     *
     * @param errorMessage 失败原因（展示给用户，需简短）
     */
    private void failTaskAndRefund(QueuedTaskContext ctx, String errorMessage)
    {
        Long taskId = ctx.getTaskId();
        // 视频续生任务（runNo>0）派发失败：回滚 PARTIAL_FAILED 保留续生入口，不按 FAILED + 退款处理
        if (tryRollbackResumableVideoTask(taskId, errorMessage))
        {
            return;
        }
        int rows;
        try
        {
            LambdaUpdateWrapper<AidExtractTask> upd = Wrappers.lambdaUpdate();
            upd.eq(AidExtractTask::getId, taskId);
            upd.in(AidExtractTask::getStatus, STATUS_PENDING, STATUS_QUEUED);
            upd.set(AidExtractTask::getStatus, "FAILED");
            upd.set(AidExtractTask::getErrorMessage, errorMessage);
            upd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            rows = extractTaskService.getBaseMapper().update(null, upd);
        }
        catch (Exception e)
        {
            // DB 置 FAILED 异常：终态是否落库不确定，绝不继续退款 / 清队列 / 推 error，
            // 避免"DB 未终态但外围资源已清"的孤儿与误退款，交由对账 / 重启自愈补偿。
            log.error("任务置 FAILED 异常，跳过退款与清理(交由对账/自愈补偿), taskId={}", taskId, e);
            return;
        }
        if (rows == 0)
        {
            // 任务已被取消 / 其它线程推进到非 PENDING/QUEUED：本次失败收口作废，避免误退款、误推 error 与真实终态冲突
            log.info("失败收口跳过，任务状态已变化: taskId={}", taskId);
            return;
        }
        // CAS 成功落 FAILED 后，再清外围资源
        // 退回预冻结资金（幂等，避免资金挂账）
        try
        {
            if (ctx.getUserId() != null)
            {
                extractBillingService.refundBilling(taskId, ctx.getUserId());
            }
        }
        catch (Exception e)
        {
            log.error("失败退款异常, 需人工核对, taskId={}", taskId, e);
        }
        releaseSlots(taskId);
        try { sseManager.sendError(taskId, errorMessage); } catch (Exception ignore) { }
        wechatNotifyService.notifyTaskTerminal(taskId);
        // 业务收尾：释放 taskType 业务防重锁 + 清 worker cancel flag（队列层无法直接做，回调业务 Service）。
        // 与取消路径同一套收尾，避免失败/退款后业务锁仍占用到 TTL 才能重新提交。
        if (taskFinalizer != null)
        {
            try { taskFinalizer.onQueueTaskTerminated(taskId); }
            catch (Exception e) { log.warn("失败收口业务收尾回调异常(忽略): taskId={}", taskId, e); }
        }
    }

    /** 视频出片任务类型（与 StoryboardVideoGenerationServiceImpl 常量一致）。 */
    private static final String TASK_TYPE_STORYBOARD_VIDEO_GENERATE = "storyboard_video_generate";

    /** 图片出图任务类型（与 StoryboardImageGenerationServiceImpl 常量一致；同样支持续生回滚）。 */
    private static final String TASK_TYPE_STORYBOARD_IMAGE_GENERATE = "storyboard_image_generate";

    /**
     * 视频「续生」父任务（runNo&gt;0）入队/派发失败时的专用回滚：CAS PENDING/QUEUED → PARTIAL_FAILED，
     * 保留原 resultData 与续生入口，不退款（续生无父级冻结，历史成功已结算），并清队列痕迹 + 业务收尾（释放续生锁）。
     * 仅对 {@code task_type=storyboard_video_generate} 且 input_snapshot.runNo&gt;0 生效；命中并处理成功返回 true。
     *
     * @return true=已按续生回滚处理，调用方应直接 return；false=非续生场景，走默认 FAILED 处理
     */
    private boolean tryRollbackResumableVideoTask(Long taskId, String errorMessage)
    {
        try
        {
            AidExtractTask t = extractTaskService.getById(taskId);
            if (t == null
                    || (!TASK_TYPE_STORYBOARD_VIDEO_GENERATE.equals(t.getTaskType())
                            && !TASK_TYPE_STORYBOARD_IMAGE_GENERATE.equals(t.getTaskType())))
            {
                return false;
            }
            // 解析快照：runNo 判定是否续生；priorTotalCount 用于回滚时恢复 totalCount
            int runNo = 0;
            Integer priorTotalCount = null;
            try
            {
                com.fasterxml.jackson.databind.JsonNode node = OBJECT_MAPPER.readTree(
                        t.getInputSnapshot() == null ? "{}" : t.getInputSnapshot());
                runNo = node.path("runNo").asInt(0);
                if (node.hasNonNull("priorTotalCount")) { priorTotalCount = node.get("priorTotalCount").asInt(); }
            }
            catch (Exception ignore) { /* 解析失败按 runNo=0 处理 */ }
            if (runNo <= 0)
            {
                // fresh 提交（runNo=0）无历史成功，按正常 FAILED 处理
                return false;
            }
            LambdaUpdateWrapper<AidExtractTask> upd = Wrappers.lambdaUpdate();
            upd.eq(AidExtractTask::getId, taskId);
            upd.in(AidExtractTask::getStatus, STATUS_PENDING, STATUS_QUEUED);
            upd.set(AidExtractTask::getStatus, "PARTIAL_FAILED");
            // 恢复续生前的 totalCount，避免状态/结果与 total 不一致。
            // 注意：此处不恢复 inputSnapshot——onQueueTaskTerminated 需按当前快照里的新 token 释放本轮锁，
            // 若先把快照换回续生前版本会导致释放旧 token（no-op）而泄漏新 token。runNo 在队列派发失败时少量浪费可接受（需近千次派发失败才耗尽）。
            if (priorTotalCount != null) { upd.set(AidExtractTask::getTotalCount, priorTotalCount); }
            upd.set(AidExtractTask::getErrorMessage, "续生提交失败，可重试");
            upd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            int rows = extractTaskService.getBaseMapper().update(null, upd);
            if (rows == 0)
            {
                // 状态已被其它分支推进（取消/已 PROCESSING 等）→ 不接管，交默认逻辑
                return false;
            }
            // 清队列痕迹 + 释放名额 + 业务收尾（释放续生镜头锁）；不退款
            try { stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.WAIT_ZSET, String.valueOf(taskId)); }
            catch (Exception ignore) { }
            try { stringRedisTemplate.delete(TaskQueueKeys.ctxKey(taskId)); }
            catch (Exception ignore) { }
            try { localJobRegistry.remove(taskId); }
            catch (Exception ignore) { }
            releaseSlots(taskId);
            if (taskFinalizer != null)
            {
                try { taskFinalizer.onQueueTaskTerminated(taskId); }
                catch (Exception e) { log.warn("视频续生回滚业务收尾回调异常(忽略): taskId={}", taskId, e); }
            }
            // 推 SSE 终态：用旧 resultData 推一次 PARTIAL_FAILED，写终态快照，避免前端停在 queued/admitted 直到重连
            try
            {
                Object data = null;
                String rd = t.getResultData();
                if (rd != null && !rd.isEmpty())
                {
                    try { data = OBJECT_MAPPER.readValue(rd, Object.class); }
                    catch (Exception ignore) { data = null; }
                }
                sseManager.sendPartialFailed(taskId, data, "续生提交失败，可重试");
            }
            catch (Exception e) { log.warn("视频续生回滚 SSE 推送异常(忽略): taskId={}", taskId, e); }
            wechatNotifyService.notifyTaskTerminal(taskId);
            log.warn("视频续生任务入队/派发失败，回滚 PARTIAL_FAILED 保留续生入口: taskId={}, err={}", taskId, errorMessage);
            return true;
        }
        catch (Exception e)
        {
            // 回滚异常 → 不接管，回退默认 FAILED 处理（保证至少有终态，不悬挂）
            log.error("视频续生回滚 PARTIAL_FAILED 异常，回退默认失败处理: taskId={}", taskId, e);
            return false;
        }
    }

    /**
     * 派发「可重试」回退：撤销本次放行——释放刚抢到的名额 + 租约，重新加回等待集 + 回写 QUEUED。
     */
    private void requeueAfterRetryableReject(QueuedTaskContext ctx, String member)
    {
        Long taskId = ctx.getTaskId();
        slotManager.release(taskId, ctx.getUserId(), ctx.getModelCode(), ctx.getProviderId());
        leaseManager.release(taskId);

        long enqueueMillis = ctx.getEnqueueMillis() != null ? ctx.getEnqueueMillis() : System.currentTimeMillis();
        try
        {
            stringRedisTemplate.opsForZSet().add(TaskQueueKeys.WAIT_ZSET, member, computeScore(enqueueMillis, 0));
        }
        catch (Exception e)
        {
            // 加回等待集失败 → 无法重排，按真正派发失败收口，避免任务停在 PENDING 既不入队也不执行
            log.error("可重试回退-重新入队失败(Redis异常)，按派发失败收口: taskId={}", taskId, e);
            rollbackDispatchFailure(ctx);
            return;
        }

        int rows;
        try
        {
            LambdaUpdateWrapper<AidExtractTask> upd = Wrappers.lambdaUpdate();
            upd.eq(AidExtractTask::getId, taskId);
            upd.eq(AidExtractTask::getStatus, STATUS_PENDING);
            upd.set(AidExtractTask::getStatus, STATUS_QUEUED);
            upd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            rows = extractTaskService.getBaseMapper().update(null, upd);
        }
        catch (Exception e)
        {
            // DB 回写异常 → 撤回刚加入的等待集条目（避免 PENDING 残留在队列被反复扫），按派发失败收口
            log.error("可重试回退-回写QUEUED失败(DB异常)，撤回入队并按派发失败收口: taskId={}", taskId, e);
            try { stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.WAIT_ZSET, member); } catch (Exception ignore) { }
            rollbackDispatchFailure(ctx);
            return;
        }
        if (rows == 0)
        {
            // 已被取消/推进：撤回刚加入的等待集条目 + 清 ctx，放弃重排
            log.warn("可重试回退时任务已非PENDING, 撤回入队并放弃重排: taskId={}", taskId);
            try { stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.WAIT_ZSET, member); } catch (Exception ignore) { }
            try { stringRedisTemplate.delete(TaskQueueKeys.ctxKey(taskId)); } catch (Exception ignore) { }
            return;
        }

        //    故即使 emitter 推送失败，Redis 快照也已回到 QUEUED）。即便此处整体失败，本次 drain 收尾的
        //    refreshQueuePositions 仍会对窗口内（含本任务）重新推 queued、再次把快照纠正为 QUEUED，自愈不残留假 PROCESSING。
        // 用 pushQueuedEventLockHeld：requeue 当前持有本 taskId 派发锁，派发锁不可重入，不能走自抢锁的 pushQueuedEvent。
        try { pushQueuedEventLockHeld(taskId); }
        catch (Exception e) { log.warn("可重试回退推送排队事件失败(忽略, 由本拍 refresh 自愈), taskId={}", taskId, e); }
    }
    /**
     * 释放任务的四维名额 + 租约 + ctx + 出等待集（幂等）。
     * 终态、取消、僵尸回收、对账统一调用。维度信息优先取 ctx，缺失回退查 DB。
     */
    public void releaseSlots(Long taskId)
    {
        if (taskId == null)
        {
            return;
        }
        QueuedTaskContext ctx = loadCtx(taskId);
        Long userId = null;
        String modelCode = null;
        Long providerId = null;
        if (ctx != null)
        {
            userId = ctx.getUserId();
            modelCode = ctx.getModelCode();
            providerId = ctx.getProviderId();
        }
        else
        {
            // ctx 已清：从 DB 取维度（防止名额泄漏）
            try
            {
                AidExtractTask t = extractTaskService.getOne(
                        Wrappers.<AidExtractTask>lambdaQuery()
                                .select(AidExtractTask::getId, AidExtractTask::getUserId, AidExtractTask::getModelCode)
                                .eq(AidExtractTask::getId, taskId).last("LIMIT 1"), false);
                if (t != null)
                {
                    userId = t.getUserId();
                    modelCode = t.getModelCode();
                    providerId = concurrencyConfig.resolveProviderId(modelCode);
                }
            }
            catch (Exception e)
            {
                log.warn("释放名额时回退查DB维度失败, taskId={}", taskId, e);
            }
        }
        // 以下均为「外围资源清理」，做成 best-effort：任一步异常都不得阻断其它清理，更不得阻断调用方在 DB 终态后的退款/SSE/标记清理。
        // （slotManager.release / leaseManager.release 内部已各自 try/catch；这里再对 Redis/唤醒等裸操作兜底，保证 releaseSlots 永不抛出。）
        try { slotManager.release(taskId, userId, modelCode, providerId); }
        catch (Exception e) { log.warn("释放并发名额异常(忽略), taskId={}", taskId, e); }
        try { leaseManager.release(taskId); }
        catch (Exception e) { log.warn("释放执行租约异常(忽略), taskId={}", taskId, e); }
        try { localJobRegistry.remove(taskId); }
        catch (Exception ignore) { }
        try { stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.WAIT_ZSET, String.valueOf(taskId)); }
        catch (Exception e) { log.warn("出等待集异常(忽略), taskId={}", taskId, e); }
        try { stringRedisTemplate.delete(TaskQueueKeys.ctxKey(taskId)); }
        catch (Exception e) { log.warn("清排队上下文异常(忽略), taskId={}", taskId, e); }
        // 名额释放后唤醒调度，让排队任务尽快递补
        try { wakeup(); }
        catch (Exception ignore) { }
    }

    /**
     * 从等待队列移除（取消 QUEUED 任务时调用，未占名额，仅清队列 + ctx）。
     */
    public void removeFromQueue(Long taskId)
    {
        if (taskId == null)
        {
            return;
        }
        stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.WAIT_ZSET, String.valueOf(taskId));
        stringRedisTemplate.delete(TaskQueueKeys.ctxKey(taskId));
        localJobRegistry.remove(taskId);
    }

    /** 取消时抢派发锁的最大尝试次数：与 refresh/reaper 的瞬时持锁错开；真正在 dispatch 的任务会持锁较久 → 最终失败转 cancel flag */
    private static final int CANCEL_LOCK_TRY_TIMES = 5;
    /** 取消时抢派发锁的重试间隔（毫秒），总上限约 100ms，对用户取消接口可接受 */
    private static final long CANCEL_LOCK_TRY_INTERVAL_MS = 20L;

    /**
     * 在 taskId 派发锁保护下原子取消处于 QUEUED/PENDING 的任务：与 {@link #drain()} / {@code admitAndDispatch} /。
     *
     * @param taskId 任务ID
     * @return 是否已原子取消（落 CANCELLED）
     */
    public boolean tryCancelQueuedOrPending(Long taskId)
    {
        if (taskId == null)
        {
            return false;
        }
        boolean locked = false;
        for (int i = 0; i < CANCEL_LOCK_TRY_TIMES; i++)
        {
            if (acquireDispatchLock(taskId))
            {
                locked = true;
                break;
            }
            try
            {
                Thread.sleep(CANCEL_LOCK_TRY_INTERVAL_MS);
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!locked)
        {
            // 调度器正在放行本任务（持锁较久）→ 取消与执行二选一。打"取消请求"标记：drain() 放行前会在锁内命中并队列层取消，
            // 杜绝"已请求取消的 QUEUED 任务仍被放行"。同时调用方会再写 worker cancel flag，覆盖"已 dispatch 进入执行"的情形。
            markCancelRequested(taskId);
            log.info("取消抢派发锁失败，已打取消请求标记 + 交 cancel flag 兜底: taskId={}", taskId);
            return false;
        }
        try
        {
            LambdaUpdateWrapper<AidExtractTask> upd = Wrappers.lambdaUpdate();
            upd.eq(AidExtractTask::getId, taskId);
            upd.in(AidExtractTask::getStatus, STATUS_PENDING, STATUS_QUEUED);
            upd.set(AidExtractTask::getStatus, "CANCELLED");
            upd.set(AidExtractTask::getErrorMessage, "用户取消");
            upd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            int rows = extractTaskService.getBaseMapper().update(null, upd);
            if (rows == 0)
            {
                // 已 PROCESSING/终态 → 不在本方法可取消范围
                return false;
            }
            // 锁内释放名额 + 出队 + 清 ctx：此刻 drain 抢不到锁，绝不会再 dispatch 本任务
            releaseSlots(taskId);
            clearCancelRequested(taskId);
            log.info("队列任务已原子取消: taskId={}", taskId);
            return true;
        }
        finally
        {
            releaseDispatchLock(taskId);
        }
    }

    /** 打「取消请求」标记（抢锁失败降级用），TTL 与 ctx 同量级，足够覆盖任务在队列里的等待期。 */
    private void markCancelRequested(Long taskId)
    {
        try
        {
            stringRedisTemplate.opsForValue().set(
                    TaskQueueKeys.cancelReqKey(taskId), "1", 30, TimeUnit.MINUTES);
        }
        catch (Exception e)
        {
            log.warn("打取消请求标记失败, taskId={}", taskId, e);
        }
    }

    /** 是否存在「取消请求」标记。 */
    private boolean isCancelRequested(Long taskId)
    {
        try
        {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(TaskQueueKeys.cancelReqKey(taskId)));
        }
        catch (Exception e)
        {
            // Redis 异常偏保守：不阻断放行（返回 false），避免误取消正常任务
            return false;
        }
    }

    /** 清除「取消请求」标记。 */
    private void clearCancelRequested(Long taskId)
    {
        try { stringRedisTemplate.delete(TaskQueueKeys.cancelReqKey(taskId)); }
        catch (Exception ignore) { }
    }
    /**
     * 查询任务在等待队列中的位次（1-based）。
     *
     * @return 位次；不在队列中返回 null
     */
    public Integer getQueuePosition(Long taskId)
    {
        if (taskId == null)
        {
            return null;
        }
        Long rank = stringRedisTemplate.opsForZSet().rank(TaskQueueKeys.WAIT_ZSET, String.valueOf(taskId));
        return rank == null ? null : rank.intValue() + 1;
    }

    /** 当前等待队列总长度 */
    public long getQueueSize()
    {
        Long n = stringRedisTemplate.opsForZSet().zCard(TaskQueueKeys.WAIT_ZSET);
        return n == null ? 0L : n;
    }

    /**
     * 等待队列分维度统计（只读，后台监控用）。
     *
     * @param scanLimit 单次最多扫描的排队条数（建议 &le; 5000，防极长队列单次耗时过久）
     * @return 分维度统计快照；队列为空时各 Map 为空
     */
    public QueueWaitingBreakdown getWaitingBreakdown(int scanLimit)
    {
        long total = getQueueSize();
        Map<String, Integer> byModel = new HashMap<>();
        Map<String, Integer> byProvider = new HashMap<>();
        if (scanLimit <= 0)
        {
            return new QueueWaitingBreakdown(total, 0, 0, byModel, byProvider);
        }
        Set<String> members;
        try
        {
            members = stringRedisTemplate.opsForZSet().range(TaskQueueKeys.WAIT_ZSET, 0, scanLimit - 1L);
        }
        catch (Exception e)
        {
            log.warn("读取等待队列快照失败(监控降级)", e);
            return new QueueWaitingBreakdown(total, 0, 0, byModel, byProvider);
        }
        if (CollectionUtil.isEmpty(members))
        {
            return new QueueWaitingBreakdown(total, 0, 0, byModel, byProvider);
        }
        int membersSize = members.size();
        // 批量构造 ctx key，一次 multiGet 回来，避免逐条往返
        List<String> ctxKeys = new ArrayList<>(members.size());
        for (String member : members)
        {
            Long taskId;
            try { taskId = Long.parseLong(member); } catch (NumberFormatException e) { continue; }
            ctxKeys.add(TaskQueueKeys.ctxKey(taskId));
        }
        if (ctxKeys.isEmpty())
        {
            return new QueueWaitingBreakdown(total, membersSize, 0, byModel, byProvider);
        }
        List<String> ctxJsons;
        try
        {
            ctxJsons = stringRedisTemplate.opsForValue().multiGet(ctxKeys);
        }
        catch (Exception e)
        {
            log.warn("批量读取排队上下文失败(监控降级)", e);
            return new QueueWaitingBreakdown(total, membersSize, 0, byModel, byProvider);
        }
        int scanned = 0;
        if (ctxJsons != null)
        {
            for (String json : ctxJsons)
            {
                if (StrUtil.isBlank(json))
                {
                    continue;
                }
                try
                {
                    QueuedTaskContext ctx = OBJECT_MAPPER.readValue(json, QueuedTaskContext.class);
                    scanned++;
                    String modelCode = StrUtil.isBlank(ctx.getModelCode()) ? "unknown" : ctx.getModelCode();
                    byModel.merge(modelCode, 1, Integer::sum);
                    String providerKey = ctx.getProviderId() == null
                            ? TaskQueueKeys.PROVIDER_NONE : String.valueOf(ctx.getProviderId());
                    byProvider.merge(providerKey, 1, Integer::sum);
                }
                catch (Exception ignore)
                {
                    // 单条 ctx 解析失败不影响整体统计
                }
            }
        }
        return new QueueWaitingBreakdown(total, membersSize, scanned, byModel, byProvider);
    }

    /**
     * 标记任务进入执行态：登记本实例租约心跳（重启自愈据租约判活）。
     */
    public void markProcessing(Long taskId)
    {
        leaseManager.markActive(taskId);
    }

    /**
     * 标记「非阻塞扇入型」任务进入执行态：仅续租一次、不登记心跳常驻集合。
     * 用于出图 / 出视频父任务——非阻塞提交后无常驻线程，存活由 media 轮询续租表达；
     * 子任务全终态、轮询停止后租约自然过期，避免心跳按"进程存活"无限续租导致名额永久泄漏。
     */
    public void touchProcessing(Long taskId)
    {
        leaseManager.touchLease(taskId);
    }

    /**
     * 扇入型任务「同步提交阶段」结束：停止心跳续租但保留租约，转异步后由 media 轮询续租接管。
     * 与 {@link #markProcessing(Long)} 配对使用：同步提交期间用 markProcessing 登记心跳防租约过期误杀，
     * 提交结束（转异步 / 收尾）后调用本方法移出心跳集合，避免心跳无限续租导致名额泄漏。
     */
    public void deactivateProcessingHeartbeat(Long taskId)
    {
        leaseManager.deactivateHeartbeat(taskId);
    }

    /**
     * 任务执行租约是否存活（重启自愈判定：失活=执行进程已死）。
     */
    public boolean isLeaseAlive(Long taskId)
    {
        return leaseManager.isAlive(taskId);
    }

    /**
     * 清空指定任务的执行租约（单实例重启自愈时，对启动前快照内的 PROCESSING 任务调用，
     * 仅清这批，避免误清启动后新进程刚写的租约）。
     */
    public void clearLease(Long taskId)
    {
        leaseManager.release(taskId);
    }

    /**
     * 清空所有任务执行租约（保留：全量清理场景）。
     *
     * @return 清理的租约数
     */
    public long clearAllLeases()
    {
        try
        {
            Set<String> keys = stringRedisTemplate.keys(TaskQueueKeys.LEASE_PREFIX + "*");
            if (CollectionUtil.isEmpty(keys))
            {
                return 0L;
            }
            Long n = stringRedisTemplate.delete(keys);
            return n == null ? 0L : n;
        }
        catch (Exception e)
        {
            log.warn("清空执行租约异常", e);
            return 0L;
        }
    }
    private QueuedTaskContext loadCtx(Long taskId)
    {
        try
        {
            String json = stringRedisTemplate.opsForValue().get(TaskQueueKeys.ctxKey(taskId));
            if (StrUtil.isBlank(json))
            {
                return null;
            }
            return OBJECT_MAPPER.readValue(json, QueuedTaskContext.class);
        }
        catch (Exception e)
        {
            log.warn("读取排队上下文失败, taskId={}", taskId, e);
            return null;
        }
    }

    /**
     * ctx 丢失时从 DB 重建（用于 Redis 数据丢失/重启重排场景）。
     * 仅能重建 MQ 派发所需的最小上下文；LOCAL 任务的内存 job 已丢失，重建后派发会失败并被回滚。
     */
    private QueuedTaskContext rebuildCtxFromDb(Long taskId)
    {
        try
        {
            AidExtractTask t = extractTaskService.getOne(
                    Wrappers.<AidExtractTask>lambdaQuery()
                            .select(AidExtractTask::getId, AidExtractTask::getProjectId, AidExtractTask::getEpisodeId,
                                    AidExtractTask::getUserId, AidExtractTask::getModelCode, AidExtractTask::getTaskType)
                            .eq(AidExtractTask::getId, taskId).last("LIMIT 1"), false);
            if (t == null)
            {
                return null;
            }
            // ctx 丢失（Redis 数据丢失/重启）时只能重建 MQ 派发上下文；
            // 本地线程池类任务（分镜图/视频/编辑图等）的内存 job 已丢失，无法重建——返回 null，
            // 由 drain 出队后交由僵尸回收/重启自愈失败退款，避免误投到 MQ Consumer 用错误分支处理。
            String taskType = t.getTaskType();
            if (taskType == null || !MQ_CONSUMER_TASK_TYPES.contains(taskType))
            {
                log.warn("排队上下文丢失且非MQ可处理类型, 放弃重建(交由回收): taskId={}, taskType={}", taskId, taskType);
                return null;
            }
            Long providerId = concurrencyConfig.resolveProviderId(t.getModelCode());
            QueuedTaskContext ctx = QueuedTaskContext.builder()
                    .taskId(t.getId())
                    .projectId(t.getProjectId())
                    .episodeId(t.getEpisodeId())
                    .userId(t.getUserId())
                    .modelCode(t.getModelCode())
                    .providerId(providerId)
                    .taskType(taskType)
                    .dispatchMode("MQ")
                    .enqueueMillis(System.currentTimeMillis())
                    .build();
            // 回写 ctx 以便后续复用
            stringRedisTemplate.opsForValue().set(TaskQueueKeys.ctxKey(taskId),
                    OBJECT_MAPPER.writeValueAsString(ctx));
            return ctx;
        }
        catch (Exception e)
        {
            log.warn("重建排队上下文失败, taskId={}", taskId, e);
            return null;
        }
    }

    /**
     * 推送单个任务的排队事件（含位次 + 受限维度）——非持锁调用方（如入队 doEnqueue）使用。
     * 先抢 taskId 派发锁，再在锁内复查"仍在 WAIT_ZSET + DB 仍 QUEUED"后才推，杜绝与另一实例/调度拍 drain 放行
     * 交错把已放行任务的快照回写成"排队中"（admitted/progress 被 queued 覆盖、状态倒退）。抢不到锁说明有实例正在
     * 放行/处理本任务，直接跳过本次推送（其放行流程会推 admitted；后续 refresh 也会纠正）。
     */
    private void pushQueuedEvent(Long taskId)
    {
        if (!acquireDispatchLock(taskId))
        {
            // 调度器正在放行/处理本任务，跳过排队推送，避免回写"排队中"
            return;
        }
        try
        {
            sendQueuedIfStillQueued(taskId);
        }
        catch (Exception e)
        {
            log.warn("推送排队事件失败, taskId={}", taskId, e);
        }
        finally
        {
            releaseDispatchLock(taskId);
        }
    }

    /**
     * 推送排队事件——调用方已持有本 taskId 派发锁时使用（如 requeueAfterRetryableReject）。
     * 派发锁不可重入（Redis setIfAbsent），持锁方不能再调 {@link #pushQueuedEvent} 自抢锁（必失败而漏推），
     * 故单独提供本变体：仅做"仍在 WAIT_ZSET + DB 仍 QUEUED"复查后推送，不再抢锁。
     */
    private void pushQueuedEventLockHeld(Long taskId)
    {
        try
        {
            sendQueuedIfStillQueued(taskId);
        }
        catch (Exception e)
        {
            log.warn("推送排队事件(持锁)失败, taskId={}", taskId, e);
        }
    }

    /**
     * 复查"仍在等待集 + DB 仍 QUEUED"后推送 queued 快照；任一不成立则不推（避免回写已放行/已终态任务）。
     * 调用前提：调用方已持有本 taskId 派发锁（由 {@link #pushQueuedEvent} / {@link #pushQueuedEventLockHeld} 保证）。
     */
    private void sendQueuedIfStillQueued(Long taskId)
    {
        Integer position = getQueuePosition(taskId);
        if (position == null)
        {
            // 已被放行 ZREM 出等待集
            return;
        }
        AidExtractTask task = extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getStatus)
                        .eq(AidExtractTask::getId, taskId).last("LIMIT 1"), false);
        if (task == null || !STATUS_QUEUED.equals(task.getStatus()))
        {
            // 已放行(PENDING/PROCESSING)或终态：不推 queued，避免状态倒退
            return;
        }
        long total = getQueueSize();
        String blockedBy = computeBlockedBy(loadCtx(taskId));
        sseManager.sendQueued(taskId, position, position - 1, (int) total, blockedBy);
    }

    /**
     * 刷新仍在排队任务的位次（每拍放行后调用，给前端实时位次 + 受限维度）。
     * 刷新窗口与 {@link #drain()} 的扫描窗口对齐为 {@link #MAX_DRAIN_SCAN}，
     * 避免出现「drain 已扫到第 N(>200) 个、但其 SSE 位次/blockedBy 永远停在旧值」的契约割裂。
     * 超出该窗口的任务位次由后续调度拍随队列前移逐步补上。
     */
    private void refreshQueuePositions()
    {
        try
        {
            Set<String> waiting = stringRedisTemplate.opsForZSet()
                    .range(TaskQueueKeys.WAIT_ZSET, 0, MAX_DRAIN_SCAN - 1);
            if (CollectionUtil.isEmpty(waiting))
            {
                return;
            }
            int total = (int) getQueueSize();
            // 全局是否已满：满则所有排队任务统一受限于 GLOBAL_LIMIT，无需再逐任务查用户/模型维度，省 Redis 调用
            boolean globalFull = slotManager.getGlobalOccupied() >= concurrencyConfig.getGlobalLimit();
            String selfInstanceId = leaseManager.getInstanceId();
            int idx = 0;
            for (String member : waiting)
            {
                idx++;
                Long taskId;
                try { taskId = Long.parseLong(member); } catch (NumberFormatException e) { continue; }
                QueuedTaskContext ctx = loadCtx(taskId);
                // 非 owner 的 LOCAL 任务：本实例不负责其快照，直接跳过、不写 queued 快照——
                // 否则会用<u>本实例</u>的 LocalTaskDispatchExecutor.saturated() 误算 blockedBy，
                // 把 owner 实例写入的真实 LOCAL_EXECUTOR_LIMIT 覆盖成 null，前端又看到"当前第 N 位"。
                // 该任务的排队快照由其 owner 实例的 refresh 负责刷新。idx 仍照常自增，保证 owner 任务位次与全局 rank 一致。
                if (ctx != null
                        && LocalTaskDispatchExecutor.MODE.equals(ctx.getDispatchMode())
                        && StrUtil.isNotBlank(ctx.getOwnerInstanceId())
                        && !ctx.getOwnerInstanceId().equals(selfInstanceId))
                {
                    continue;
                }
                // 抢派发锁后再推 queued：避免与另一实例 drain 放行交错——
                //   B 实例 refresh 读到旧快照里 taskId 仍在等待集，A 实例随后拿锁 CAS QUEUED→PENDING+ZREM 并推 admitted，
                //   若 B 不持锁仍按旧快照 sendQueued()，会把已放行任务的 Redis/SSE 又写回"排队中"（文案乱飘）。
                //   持锁失败说明有实例正在放行/处理本任务，跳过本次刷新。
                if (!acquireDispatchLock(taskId))
                {
                    continue;
                }
                try
                {
                    // 锁内复查：仍在等待集（未被放行 ZREM）+ DB 仍为 QUEUED，二者皆成立才推 queued，杜绝回写已放行任务
                    Double score = stringRedisTemplate.opsForZSet().score(TaskQueueKeys.WAIT_ZSET, member);
                    if (score == null)
                    {
                        continue;
                    }
                    AidExtractTask task = extractTaskService.getOne(
                            Wrappers.<AidExtractTask>lambdaQuery()
                                    .select(AidExtractTask::getId, AidExtractTask::getStatus)
                                    .eq(AidExtractTask::getId, taskId).last("LIMIT 1"), false);
                    if (task == null || !STATUS_QUEUED.equals(task.getStatus()))
                    {
                        continue;
                    }
                    String blockedBy = globalFull ? "GLOBAL_LIMIT" : computeBlockedBy(ctx);
                    sseManager.sendQueued(taskId, idx, idx - 1, total, blockedBy);
                }
                finally
                {
                    releaseDispatchLock(taskId);
                }
            }
        }
        catch (Exception e)
        {
            log.warn("刷新排队位次异常", e);
        }
    }

    /**
     * 计算任务当前因哪一维并发受限而暂不能放行（用于排队文案如实展示，不再假报"即将开始"）。
     * 按调度放行的判定顺序：全局 → 用户 → 模型 → 服务商 → 派发执行器；命中即返回对应维度，全部有空位返回 null（可放行）。
     *
     * @return GLOBAL_LIMIT / USER_LIMIT / MODEL_LIMIT / PROVIDER_LIMIT / LOCAL_EXECUTOR_LIMIT；未受限返回 null
     */
    private String computeBlockedBy(QueuedTaskContext ctx)
    {
        if (ctx == null)
        {
            return null;
        }
        // 全局维度
        if (slotManager.getGlobalOccupied() >= concurrencyConfig.getGlobalLimit())
        {
            return "GLOBAL_LIMIT";
        }
        // 用户维度
        Long userId = ctx.getUserId();
        if (Objects.nonNull(userId)
                && slotManager.getUserOccupied(userId) >= concurrencyConfig.getUserLimit(userId))
        {
            return "USER_LIMIT";
        }
        // 模型维度（UNLIMITED 不拦截）
        String modelCode = ctx.getModelCode();
        int modelLimit = concurrencyConfig.getModelLimit(modelCode);
        if (StrUtil.isNotBlank(modelCode) && modelLimit != TaskConcurrencyConfig.UNLIMITED
                && slotManager.getModelOccupied(modelCode) >= modelLimit)
        {
            return "MODEL_LIMIT";
        }
        // 服务商维度（UNLIMITED 不拦截）
        Long providerId = ctx.getProviderId();
        int providerLimit = concurrencyConfig.getProviderLimit(providerId);
        if (Objects.nonNull(providerId) && providerLimit != TaskConcurrencyConfig.UNLIMITED
                && slotManager.getProviderOccupied(providerId) >= providerLimit)
        {
            return "PROVIDER_LIMIT";
        }
        // 执行器维度：四维名额都有空位，但派发执行器（如本地线程池）已饱和——任务仍跑不起来，如实标注
        TaskDispatchExecutor exec = executors().get(ctx.getDispatchMode());
        if (exec != null && exec.saturated())
        {
            return "LOCAL_EXECUTOR_LIMIT";
        }
        return null;
    }
}
