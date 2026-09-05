package com.aid.rps.queue;

import com.aid.common.error.TaskErrorSnapshot;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.rps.dto.ExtractTaskMessage;
import com.aid.rps.sse.AssetExtractSseManager;
import com.aid.rps.service.IExtractBillingService.ResumeBillingContext;
import com.aid.rps.service.IExtractBillingService.ResumeBillingRecovery;

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
    /**
     * 队列 ctx / 任务快照的 JSON 编解码器。
     * 必须关闭 FAIL_ON_UNKNOWN_PROPERTIES：ctx 跨版本存活在 Redis 中，升级后旧版本写入的
     * 已废弃字段（如历史的 providerId）不能让反序列化整体失败——否则 {@link #loadCtx} 返回 null，
     * 排队中的任务会被误判为「ctx 丢失」而重建 / 清理出队。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Redis ctx 与等待集收据必须原子写入，避免只写成功其中一项。 */
    private static final DefaultRedisScript<Long> WRITE_QUEUE_RECEIPT_SCRIPT = new DefaultRedisScript<>(
            "redis.call('SET', KEYS[1], ARGV[1]); "
                    + "redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3]); return 1",
            Long.class);

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_FINALIZING = "FINALIZING";
    private static final String STATUS_RECOVERING = "RECOVERING";
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String RESUME_STATE_PREPARED = "PREPARED";
    private static final String RESUME_STATE_FUNDS_FROZEN = "FUNDS_FROZEN";
    private static final String RESUME_STATE_DISPATCH_INTENT = "DISPATCH_INTENT";
    private static final String RESUME_STATE_ROLLBACK_REQUIRED = "ROLLBACK_REQUIRED";
    private static final String LEGACY_MQ_RECEIPT_PREFIX = "taskq:legacy-mq-receipt:";
    private static final String DISPATCH_ACCEPTED_PREFIX = "taskq:dispatch-accepted:";
    /** 覆盖 RocketMQ 默认多级重试窗口；超时仍未领取则不再永久判活。 */
    private static final long MQ_DELIVERY_RECEIPT_MAX_AGE_MS = TimeUnit.HOURS.toMillis(6);
    /** MQ 首次投递可能早于同步发送方释放派发锁，Consumer 应在重投前等待该短暂临界区收敛。 */
    private static final long MQ_CONSUMER_DISPATCH_LOCK_WAIT_SECONDS = 10L;
    private static final long WORKER_CLAIM_LOCK_WAIT_SECONDS = 5L;

    /** 终态集合：处于这些状态的任务必须释放名额 */
    private static final Set<String> TERMINAL_STATUS = Set.of(
            "SUCCEEDED", "FAILED", "CANCELLED", "PARTIAL_FAILED");

    /** MQ Consumer 能处理的 taskType 白名单，仅用于 ctx 丢失后重建为 MQ 派发 */
    private static final Set<String> MQ_CONSUMER_TASK_TYPES = Set.of(
            "asset_extract", "image_upscale", "form_generate_batch", "form_image_batch",
            "form_card_image_batch",
            "storyboard_script_batch", "storyboard_image_prompt_batch", "storyboard_video_prompt_batch");

    /** 由队列包装层统一领取的 LOCAL 任务；其业务入口不得再次做 PENDING→PROCESSING。 */
    private static final Set<String> LOCAL_QUEUE_CLAIM_TASK_TYPES = Set.of(
            "asset_extract", "storyboard_image_prompt_batch", "storyboard_video_prompt_batch");

    /** 线程池线程可复用，必须同时记录 taskId 与 token，并在 finally 中恢复/清除。 */
    private static final ThreadLocal<LocalDispatchContext> LOCAL_DISPATCH_CONTEXT = new ThreadLocal<>();

    private record LocalDispatchContext(Long taskId, String dispatchToken) {}

    /** 单拍最多扫描的排队条数：按 score 升序取稳定快照，超出部分由后续调度拍接力处理 */
    private static final int MAX_DRAIN_SCAN = 1000;

    /**
     * 优先级换算权重：score = enqueueMillis - priority * 权重（越小越靠前）。
     */
    private static final long PRIORITY_WEIGHT = 86_400_000L;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

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

    /** Cluster 检测后改用旧 key 上的单 key 写入，避免每次触发 CROSSSLOT。 */
    private final AtomicBoolean singleKeyReceiptMode = new AtomicBoolean(false);

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
        QueuedTaskContext ctx = QueuedTaskContext.builder()
                .taskId(taskId)
                .projectId(projectId)
                .episodeId(episodeId)
                .userId(userId)
                .modelCode(modelCode)
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
        return submitMqTaskNow(taskId, projectId, episodeId, userId,
                modelCode, taskType, null);
    }

    public boolean submitMqTaskNow(Long taskId, Long projectId, Long episodeId, Long userId,
                                   String modelCode, String taskType, String dispatchToken)
    {
        QueuedTaskContext ctx = QueuedTaskContext.builder()
                .taskId(taskId)
                .projectId(projectId)
                .episodeId(episodeId)
                .userId(userId)
                .modelCode(modelCode)
                .taskType(taskType)
                .dispatchMode(MqTaskDispatchExecutor.MODE)
                .dispatchToken(dispatchToken)
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
        QueuedTaskContext ctx = QueuedTaskContext.builder()
                .taskId(taskId)
                .projectId(projectId)
                .episodeId(episodeId)
                .userId(userId)
                .modelCode(modelCode)
                .taskType(taskType)
                .dispatchMode(LocalTaskDispatchExecutor.MODE)
                .ownerInstanceId(leaseManager.getInstanceId())
                .build();
        return enqueueLocal(ctx, job);
    }

    /**
     * 提交本地派发任务入队（强制立即入队），用于已处于事务提交后上下文（afterCommit 回调内）时直接入队。
     *
     * @return true=入队成功；false=CAS失败（任务已被推进/取消）
     */
    public boolean submitLocalTaskNow(Long taskId, Long projectId, Long episodeId, Long userId,
                                      String modelCode, String taskType, Runnable job)
    {
        return submitLocalTaskNow(taskId, projectId, episodeId, userId,
                modelCode, taskType, job, null);
    }

    public boolean submitLocalTaskNow(Long taskId, Long projectId, Long episodeId, Long userId,
                                      String modelCode, String taskType, Runnable job,
                                      String dispatchToken)
    {
        QueuedTaskContext ctx = QueuedTaskContext.builder()
                .taskId(taskId)
                .projectId(projectId)
                .episodeId(episodeId)
                .userId(userId)
                .modelCode(modelCode)
                .taskType(taskType)
                .dispatchMode(LocalTaskDispatchExecutor.MODE)
                .dispatchToken(dispatchToken)
                .ownerInstanceId(leaseManager.getInstanceId())
                .build();
        return doEnqueueLocal(ctx, job);
    }

    /**
     * 返回当前 LOCAL worker 所属派发周期令牌。仅当线程上下文中的 taskId 与入参一致时返回，
     * 防止线程池复用或嵌套任务把上一任务令牌带入下一任务。
     */
    public String currentLocalDispatchToken(Long taskId)
    {
        LocalDispatchContext context = LOCAL_DISPATCH_CONTEXT.get();
        return context != null && Objects.equals(taskId, context.taskId())
                ? context.dispatchToken() : null;
    }

    /**
     * worker 按周期令牌领取任务，并在同一 task 派发锁临界区内登记租约。
     * 启动/僵尸扫描使用同一把锁，不会看到“DB 已 PROCESSING 但 lease 还未写”的中间态。
     */
    public boolean claimTaskForExecution(Long taskId, String dispatchToken)
    {
        if (taskId == null || StrUtil.isBlank(dispatchToken))
        {
            return false;
        }
        RLock lock = redissonClient.getLock(TaskQueueKeys.dispatchLockKey(taskId));
        try
        {
            if (!lock.tryLock(WORKER_CLAIM_LOCK_WAIT_SECONDS, TimeUnit.SECONDS))
            {
                log.info("worker领取未取得派发锁，等待重试: taskId={}", taskId);
                throw new ServiceException("系统繁忙");
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            log.info("worker领取等待派发锁被中断: taskId={}", taskId);
            throw new ServiceException("系统繁忙");
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("worker领取派发锁异常: taskId={}", taskId, e);
            throw new ServiceException("系统繁忙");
        }

        try
        {
            LambdaUpdateWrapper<AidExtractTask> claim = Wrappers.lambdaUpdate();
            claim.eq(AidExtractTask::getId, taskId);
            claim.eq(AidExtractTask::getStatus, STATUS_PENDING);
            claim.eq(AidExtractTask::getBillingTraceId, dispatchToken);
            claim.set(AidExtractTask::getStatus, STATUS_PROCESSING);
            claim.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            if (extractTaskService.getBaseMapper().update(null, claim) == 0)
            {
                return false;
            }
            leaseManager.markActive(taskId);
            return true;
        }
        catch (Exception e)
        {
            log.error("worker领取任务异常: taskId={}", taskId, e);
            throw new ServiceException("领取任务失败");
        }
        finally
        {
            releaseDispatchLock(taskId, lock);
        }
    }

    /**
     * MQ Consumer 领取前解析当前派发周期。新消息必须同时匹配 DB token 和 Redis ctx 收据；
     * 滚动发布期间旧消息没有 token 时，仅在收据仍明确属于旧 MQ 周期且已出等待集时，
     * 才在同一 task 派发锁内为 DB 和收据补全 token。返回 null 表示明确是过期/错轮消息。
     */
    public String resolveMqConsumerDispatchToken(ExtractTaskMessage message)
    {
        if (message == null || message.getTaskId() == null)
        {
            return null;
        }
        Long taskId = message.getTaskId();
        RLock lock = acquireMqConsumerDispatchLock(taskId);
        if (lock == null)
        {
            log.info("MQ周期校验暂未获取派发锁: taskId={}", taskId);
            throw new ServiceException("系统繁忙");
        }
        try
        {
            AidExtractTask task = extractTaskService.getOne(
                    Wrappers.<AidExtractTask>lambdaQuery()
                            .select(AidExtractTask::getId, AidExtractTask::getProjectId,
                                    AidExtractTask::getEpisodeId, AidExtractTask::getUserId,
                                    AidExtractTask::getModelCode, AidExtractTask::getTaskType,
                                    AidExtractTask::getStatus, AidExtractTask::getBillingTraceId)
                            .eq(AidExtractTask::getId, taskId).last("LIMIT 1"), false);
            if (task == null || !STATUS_PENDING.equals(task.getStatus()))
            {
                return null;
            }

            QueuedTaskContext receipt = loadMqReceiptStrict(taskId);
            if (!matchesMqReceipt(message, task, receipt))
            {
                log.info("MQ消息与当前派发收据不匹配: taskId={}", taskId);
                return null;
            }

            String messageToken = message.getDispatchToken();
            String persistedToken = task.getBillingTraceId();
            String receiptToken = receipt.getDispatchToken();
            if (StrUtil.isNotBlank(messageToken))
            {
                return Objects.equals(messageToken, persistedToken)
                        && Objects.equals(messageToken, receiptToken) ? messageToken : null;
            }

            // 新版 ctx 有 token 但旧消息无 token 时，只接受已由本兼容流程登记过的重投。
            String legacyReceiptKey = LEGACY_MQ_RECEIPT_PREFIX + taskId;
            String normalizedLegacyToken = stringRedisTemplate.opsForValue().get(legacyReceiptKey);
            if (StrUtil.isNotBlank(receiptToken))
            {
                return Objects.equals(receiptToken, persistedToken)
                        && Objects.equals(receiptToken, normalizedLegacyToken)
                        ? receiptToken : null;
            }

            Double waitingScore = stringRedisTemplate.opsForZSet().score(
                    TaskQueueKeys.WAIT_ZSET, String.valueOf(taskId));
            if (waitingScore != null)
            {
                // 旧消息无 token，只有“已出 WAIT”才能证明这是已成功派发的当前收据。
                log.info("MQ旧消息尚无已派发收据，等待重投: taskId={}", taskId);
                throw new ServiceException("派发未完成");
            }

            if (StrUtil.isBlank(persistedToken))
            {
                String generatedToken = UUID.randomUUID().toString().replace("-", "");
                LambdaUpdateWrapper<AidExtractTask> tokenUpdate = Wrappers.lambdaUpdate();
                tokenUpdate.eq(AidExtractTask::getId, taskId);
                tokenUpdate.eq(AidExtractTask::getStatus, STATUS_PENDING);
                if (persistedToken == null)
                {
                    tokenUpdate.isNull(AidExtractTask::getBillingTraceId);
                }
                else
                {
                    tokenUpdate.eq(AidExtractTask::getBillingTraceId, persistedToken);
                }
                tokenUpdate.set(AidExtractTask::getBillingTraceId, generatedToken);
                tokenUpdate.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
                if (extractTaskService.getBaseMapper().update(null, tokenUpdate) == 0)
                {
                    log.info("MQ旧消息周期令牌补全竞争失败: taskId={}", taskId);
                    throw new ServiceException("系统繁忙");
                }
                persistedToken = generatedToken;
            }

            receipt.setDispatchToken(persistedToken);
            // 先写兼容标记，再写 ctx；即使第二步返回结果不确定，重投仍能验证同一 token。
            stringRedisTemplate.opsForValue().set(legacyReceiptKey, persistedToken, 24, TimeUnit.HOURS);
            stringRedisTemplate.opsForValue().set(
                    TaskQueueKeys.ctxKey(taskId), OBJECT_MAPPER.writeValueAsString(receipt));
            log.info("MQ旧消息已补全派发周期令牌: taskId={}", taskId);
            return persistedToken;
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("MQ派发周期校验异常: taskId={}", taskId, e);
            throw new ServiceException("收据校验失败");
        }
        finally
        {
            releaseDispatchLock(taskId, lock);
        }
    }

    private QueuedTaskContext loadMqReceiptStrict(Long taskId) throws Exception
    {
        String json = stringRedisTemplate.opsForValue().get(TaskQueueKeys.ctxKey(taskId));
        if (StrUtil.isBlank(json))
        {
            log.error("MQ派发收据缺失: taskId={}", taskId);
            throw new ServiceException("派发收据缺失");
        }
        return OBJECT_MAPPER.readValue(json, QueuedTaskContext.class);
    }

    private boolean matchesMqReceipt(ExtractTaskMessage message, AidExtractTask task,
                                     QueuedTaskContext receipt)
    {
        return receipt != null
                && MqTaskDispatchExecutor.MODE.equals(receipt.getDispatchMode())
                && Objects.equals(task.getId(), message.getTaskId())
                && Objects.equals(task.getProjectId(), message.getProjectId())
                && Objects.equals(task.getEpisodeId(), message.getEpisodeId())
                && Objects.equals(task.getUserId(), message.getUserId())
                && Objects.equals(task.getModelCode(), message.getModelCode())
                && Objects.equals(task.getTaskType(), message.getTaskType())
                && Objects.equals(task.getId(), receipt.getTaskId())
                && Objects.equals(task.getProjectId(), receipt.getProjectId())
                && Objects.equals(task.getEpisodeId(), receipt.getEpisodeId())
                && Objects.equals(task.getUserId(), receipt.getUserId())
                && Objects.equals(task.getModelCode(), receipt.getModelCode())
                && Objects.equals(task.getTaskType(), receipt.getTaskType());
    }

    /**
     * 启动/滚动发布扫描使用的只读判活。方法可在 {@link #executeWithTaskDispatchLock}
     * 内调用（Redisson 可重入），也可直接调用；无法取得锁或 Redis 暂时异常时保守视为存活。
     */
    public boolean isCurrentCycleLive(Long taskId, String status, String dispatchToken)
    {
        if (taskId == null)
        {
            return false;
        }
        RLock lock = acquireDispatchLock(taskId);
        if (lock == null)
        {
            return true;
        }
        try
        {
            AidExtractTask task = extractTaskService.getOne(
                    Wrappers.<AidExtractTask>lambdaQuery()
                            .select(AidExtractTask::getId, AidExtractTask::getStatus,
                                    AidExtractTask::getBillingTraceId,
                                    AidExtractTask::getUpdateTime)
                            .eq(AidExtractTask::getId, taskId).last("LIMIT 1"), false);
            if (task == null || !Objects.equals(status, task.getStatus())
                    || !TaskCycleLivenessPolicy.matches(
                            status, dispatchToken, task.getBillingTraceId()))
            {
                return false;
            }

            // PROCESSING 已经用 DB token 原子领取；ctx 是辅助收据，不能因缓存淘汰误杀正在续租的 worker。
            if (STATUS_PROCESSING.equals(status) || STATUS_FINALIZING.equals(status))
            {
                return leaseManager.isAlive(taskId);
            }
            QueuedTaskContext ctx = loadCtx(taskId);
            if (ctx == null || !Objects.equals(taskId, ctx.getTaskId())
                    || !Objects.equals(dispatchToken, ctx.getDispatchToken()))
            {
                return false;
            }

            boolean waiting;
            try
            {
                waiting = stringRedisTemplate.opsForZSet().score(
                        TaskQueueKeys.WAIT_ZSET, String.valueOf(taskId)) != null;
            }
            catch (Exception e)
            {
                log.warn("任务周期判活读取WAIT失败，保守视为存活: taskId={}", taskId, e);
                return true;
            }

            boolean localMode = LocalTaskDispatchExecutor.MODE.equals(ctx.getDispatchMode());
            String localOwner = null;
            if (localMode)
            {
                localOwner = ctx.getOwnerInstanceId();
                if (StrUtil.isBlank(localOwner) || !leaseManager.isInstanceAlive(localOwner))
                {
                    return false;
                }
                if (Objects.equals(localOwner, leaseManager.getInstanceId())
                        && STATUS_QUEUED.equals(status)
                        && !localJobRegistry.contains(taskId, ctx.getDispatchToken()))
                {
                    return false;
                }
            }

            if (STATUS_QUEUED.equals(status))
            {
                return waiting;
            }
            if (STATUS_PENDING.equals(status))
            {
                // PENDING+WAIT 可由调度拍按同 token 重派；无 WAIT 的 MQ 收据表示 send 已返回成功。
                if (waiting)
                {
                    if (hasAcceptedDispatchReceipt(taskId, dispatchToken))
                    {
                        return true;
                    }
                    return !localMode
                            || !Objects.equals(localOwner, leaseManager.getInstanceId())
                            || localJobRegistry.contains(taskId, ctx.getDispatchToken());
                }
                if (localMode)
                {
                    return leaseManager.isAlive(taskId);
                }
                if (!MqTaskDispatchExecutor.MODE.equals(ctx.getDispatchMode()))
                {
                    return false;
                }

                // send 返回成功不等于 Consumer 必然领取；覆盖 MQ 重试窗口后放开僵尸回收。
                long deliveredAt = task.getUpdateTime() == null
                        ? 0L : task.getUpdateTime().getTime();
                try
                {
                    Long acceptedAt = acceptedDispatchMillis(taskId, dispatchToken);
                    if (acceptedAt != null)
                    {
                        deliveredAt = acceptedAt;
                    }
                }
                catch (Exception e)
                {
                    log.warn("MQ派发收据时间读取失败，保守判活: taskId={}", taskId, e);
                    return true;
                }
                return deliveredAt > 0L
                        && System.currentTimeMillis() - deliveredAt <= MQ_DELIVERY_RECEIPT_MAX_AGE_MS;
            }
            return false;
        }
        finally
        {
            releaseDispatchLock(taskId, lock);
        }
    }

    /** LOCAL job 与队列收据必须在同一 task 锁内登记，避免旧周期 finally 删除新周期 job。 */
    private boolean enqueueLocal(QueuedTaskContext ctx, Runnable job)
    {
        if (ctx == null || ctx.getTaskId() == null || job == null)
        {
            return false;
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
        {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization()
                    {
                        @Override
                        public void afterCommit()
                        {
                            boolean ok;
                            try
                            {
                                ok = doEnqueueLocal(ctx, job);
                            }
                            catch (Exception e)
                            {
                                log.error("afterCommit LOCAL延迟入队失败, taskId={}", ctx.getTaskId(), e);
                                ok = false;
                            }
                            if (!ok)
                            {
                                failEnqueue(ctx);
                            }
                        }
                    });
            return true;
        }
        return doEnqueueLocal(ctx, job);
    }

    private boolean doEnqueueLocal(QueuedTaskContext ctx, Runnable job)
    {
        Long taskId = ctx.getTaskId();
        RLock lock = acquireDispatchLock(taskId);
        if (lock == null)
        {
            return false;
        }
        try
        {
            if (!resolveAndValidateDispatchToken(ctx))
            {
                return false;
            }
            localJobRegistry.register(taskId, ctx.getDispatchToken(), wrapLocalJob(ctx, job));
            boolean queued = doEnqueueLockHeld(ctx, true);
            if (!queued)
            {
                localJobRegistry.remove(taskId, ctx.getDispatchToken());
            }
            return queued;
        }
        finally
        {
            releaseDispatchLock(taskId, lock);
        }
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
                                failEnqueue(fctx);
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
                                try { localJobRegistry.remove(fctx.getTaskId(), fctx.getDispatchToken()); }
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
        RLock lock = acquireDispatchLock(taskId);
        if (lock == null)
        {
            return false;
        }
        try
        {
            return doEnqueueLockHeld(ctx, false);
        }
        finally
        {
            releaseDispatchLock(taskId, lock);
        }
    }

    /** 调用方已持有 task 锁；tokenResolved=true 表示 LOCAL job 已按本 token 包装并登记。 */
    private boolean doEnqueueLockHeld(QueuedTaskContext ctx, boolean tokenResolved)
    {
        Long taskId = ctx.getTaskId();
        if (!tokenResolved && !resolveAndValidateDispatchToken(ctx))
        {
            return false;
        }
        long now = System.currentTimeMillis();
        ctx.setEnqueueMillis(now);

        LambdaUpdateWrapper<AidExtractTask> upd = Wrappers.lambdaUpdate();
        upd.eq(AidExtractTask::getId, taskId);
        upd.eq(AidExtractTask::getStatus, STATUS_PENDING);
        upd.eq(AidExtractTask::getBillingTraceId, ctx.getDispatchToken());
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
            double score = computeScore(now, 0);
            writeQueueReceipt(ctx, score);
        }
        catch (Exception e)
        {
            log.error("任务入队写Redis失败，回滚QUEUED→PENDING并清理痕迹, taskId={}", taskId, e);
            clearQueueReceiptLockHeld(taskId, ctx.getDispatchToken(), false);
            LambdaUpdateWrapper<AidExtractTask> rb = Wrappers.lambdaUpdate();
            rb.eq(AidExtractTask::getId, taskId);
            rb.eq(AidExtractTask::getStatus, STATUS_QUEUED);
            rb.eq(AidExtractTask::getBillingTraceId, ctx.getDispatchToken());
            rb.set(AidExtractTask::getStatus, STATUS_PENDING);
            rb.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            try { extractTaskService.getBaseMapper().update(null, rb); }
            catch (Exception ignore) { }
            return false;
        }

        // 4-5. 非关键后置：推送排队事件 + 唤醒调度。失败不影响"入队已成立"（下一调度拍会处理），仅记日志、不上抛
        try { pushQueuedEventLockHeld(taskId); }
        catch (Exception e) { log.warn("推送排队事件失败(不影响入队), taskId={}", taskId, e); }
        log.info("任务已入队排队: taskId={}, userId={}, modelCode={}", taskId, ctx.getUserId(), ctx.getModelCode());
        try { wakeup(); }
        catch (Exception e) { log.warn("唤醒调度失败(不影响入队), taskId={}", taskId, e); }
        return true;
    }

    /**
     * 每一轮派发都以主表 billingTraceId 作为不可复用的周期令牌。零金额/历史任务若尚无
     * traceId，则仅在 PENDING 状态下 CAS 生成，避免两个提交方各自持有不同令牌。
     */
    private boolean resolveAndValidateDispatchToken(QueuedTaskContext ctx)
    {
        Long taskId = ctx.getTaskId();
        AidExtractTask task = extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getStatus,
                                AidExtractTask::getBillingTraceId)
                        .eq(AidExtractTask::getId, taskId)
                        .last("LIMIT 1"), false);
        if (task == null || !STATUS_PENDING.equals(task.getStatus()))
        {
            log.warn("派发周期令牌解析失败(任务非PENDING): taskId={}", taskId);
            return false;
        }

        String requestedToken = ctx.getDispatchToken();
        String persistedToken = task.getBillingTraceId();
        if (StrUtil.isBlank(persistedToken))
        {
            String generatedToken = StrUtil.isNotBlank(requestedToken)
                    ? requestedToken : UUID.randomUUID().toString().replace("-", "");
            LambdaUpdateWrapper<AidExtractTask> tokenUpdate = Wrappers.lambdaUpdate();
            tokenUpdate.eq(AidExtractTask::getId, taskId);
            tokenUpdate.eq(AidExtractTask::getStatus, STATUS_PENDING);
            if (persistedToken == null)
            {
                tokenUpdate.isNull(AidExtractTask::getBillingTraceId);
            }
            else
            {
                tokenUpdate.eq(AidExtractTask::getBillingTraceId, persistedToken);
            }
            tokenUpdate.set(AidExtractTask::getBillingTraceId, generatedToken);
            tokenUpdate.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            int rows = extractTaskService.getBaseMapper().update(null, tokenUpdate);
            if (rows == 0)
            {
                AidExtractTask current = extractTaskService.getOne(
                        Wrappers.<AidExtractTask>lambdaQuery()
                                .select(AidExtractTask::getId, AidExtractTask::getStatus,
                                        AidExtractTask::getBillingTraceId)
                                .eq(AidExtractTask::getId, taskId)
                                .last("LIMIT 1"), false);
                if (current == null || !STATUS_PENDING.equals(current.getStatus())
                        || StrUtil.isBlank(current.getBillingTraceId()))
                {
                    return false;
                }
                persistedToken = current.getBillingTraceId();
            }
            else
            {
                persistedToken = generatedToken;
            }
        }

        if (StrUtil.isNotBlank(requestedToken)
                && !Objects.equals(requestedToken, persistedToken))
        {
            log.warn("派发周期令牌不匹配，拒绝入队: taskId={}", taskId);
            return false;
        }
        ctx.setDispatchToken(persistedToken);
        return true;
    }

    /** LOCAL 执行包装：限定任务先按 token+PENDING 原子领取，并为所有本地收尾提供线程周期上下文。 */
    private Runnable wrapLocalJob(QueuedTaskContext ctx, Runnable delegate)
    {
        return () -> {
            Long taskId = ctx.getTaskId();
            String dispatchToken = ctx.getDispatchToken();
            if (StrUtil.isBlank(dispatchToken))
            {
                log.error("LOCAL任务缺少派发周期令牌，拒绝执行: taskId={}", taskId);
                return;
            }

            if (LOCAL_QUEUE_CLAIM_TASK_TYPES.contains(ctx.getTaskType()))
            {
                if (!claimTaskForExecution(taskId, dispatchToken))
                {
                    log.info("LOCAL任务已取消、已领取或周期变化，跳过旧执行体: taskId={}", taskId);
                    return;
                }
            }
            else
            {
                Long valid = extractTaskService.count(
                        Wrappers.<AidExtractTask>lambdaQuery()
                                .eq(AidExtractTask::getId, taskId)
                                .eq(AidExtractTask::getStatus, STATUS_PENDING)
                                .eq(AidExtractTask::getBillingTraceId, dispatchToken));
                if (valid == null || valid == 0L)
                {
                    log.info("LOCAL任务周期已变化，跳过旧执行体: taskId={}", taskId);
                    return;
                }
            }

            LocalDispatchContext previous = LOCAL_DISPATCH_CONTEXT.get();
            LOCAL_DISPATCH_CONTEXT.set(new LocalDispatchContext(taskId, dispatchToken));
            try
            {
                delegate.run();
            }
            finally
            {
                if (previous == null)
                {
                    LOCAL_DISPATCH_CONTEXT.remove();
                }
                else
                {
                    LOCAL_DISPATCH_CONTEXT.set(previous);
                }
            }
        };
    }

    private void writeQueueReceipt(QueuedTaskContext ctx, double score) throws Exception
    {
        String serialized = OBJECT_MAPPER.writeValueAsString(ctx);
        if (!singleKeyReceiptMode.get())
        {
            try
            {
                Long written = stringRedisTemplate.execute(WRITE_QUEUE_RECEIPT_SCRIPT,
                        List.of(TaskQueueKeys.ctxKey(ctx.getTaskId()), TaskQueueKeys.WAIT_ZSET),
                        serialized, String.valueOf(score), String.valueOf(ctx.getTaskId()));
                if (!Objects.equals(written, 1L))
                {
                    throw new IllegalStateException("队列写入失败");
                }
                return;
            }
            catch (Exception e)
            {
                if (!isCrossSlotError(e))
                {
                    throw e;
                }
                // 保留旧 Redis key 以支持滚动发布；Cluster 上改用派发锁保护的单 key 命令。
                singleKeyReceiptMode.set(true);
                log.info("Redis Cluster 已启用单 key 队列收据写入模式");
            }
        }

        String ctxKey = TaskQueueKeys.ctxKey(ctx.getTaskId());
        String member = String.valueOf(ctx.getTaskId());
        try
        {
            // 调用方持有 task 派发锁；两条命令各自只访问一个 key，Cluster 不会跨 slot。
            stringRedisTemplate.opsForValue().set(ctxKey, serialized);
            stringRedisTemplate.opsForZSet().add(TaskQueueKeys.WAIT_ZSET, member, score);
        }
        catch (Exception e)
        {
            // 写入结果不确定时撤销两处收据，上层再按 DB CAS 回滚或下次对账重建。
            try { stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.WAIT_ZSET, member); }
            catch (Exception cleanupError) { log.warn("撤销等待集收据失败: taskId={}", ctx.getTaskId(), cleanupError); }
            try { stringRedisTemplate.delete(ctxKey); }
            catch (Exception cleanupError) { log.warn("撤销上下文收据失败: taskId={}", ctx.getTaskId(), cleanupError); }
            throw e;
        }
    }

    private boolean isCrossSlotError(Throwable error)
    {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 12; depth++)
        {
            String message = current.getMessage();
            if (message != null)
            {
                String upper = message.toUpperCase(java.util.Locale.ROOT);
                if (upper.contains("CROSSSLOT") || upper.contains("SAME SLOT"))
                {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /** 记录执行器已明确接受；用于区分 LOCAL job 已入线程池与内存 job 真丢失。 */
    private void markDispatchAccepted(QueuedTaskContext ctx)
    {
        try
        {
            stringRedisTemplate.opsForValue().set(
                    DISPATCH_ACCEPTED_PREFIX + ctx.getTaskId(),
                    ctx.getDispatchToken() + "|" + System.currentTimeMillis(),
                    24, TimeUnit.HOURS);
        }
        catch (Exception e)
        {
            // dispatch 已不可逆，收据写失败不得把已接受的任务改失败/退款。
            log.warn("写派发接受收据失败，保留WAIT对账: taskId={}", ctx.getTaskId(), e);
        }
    }

    private boolean hasAcceptedDispatchReceipt(Long taskId, String dispatchToken)
    {
        if (taskId == null || StrUtil.isBlank(dispatchToken))
        {
            return false;
        }
        try
        {
            String receipt = stringRedisTemplate.opsForValue().get(
                    DISPATCH_ACCEPTED_PREFIX + taskId);
            return matchesAcceptedDispatchReceipt(receipt, dispatchToken);
        }
        catch (Exception e)
        {
            // Redis 异常时不能据此判任务失活；让 WAIT 留待下次对账。
            log.warn("读派发接受收据失败: taskId={}", taskId, e);
            return false;
        }
    }

    private Long acceptedDispatchMillis(Long taskId, String dispatchToken)
    {
        String receipt = stringRedisTemplate.opsForValue().get(
                DISPATCH_ACCEPTED_PREFIX + taskId);
        if (!matchesAcceptedDispatchReceipt(receipt, dispatchToken))
        {
            return null;
        }
        int separator = receipt.lastIndexOf('|');
        if (separator < 0 || separator == receipt.length() - 1)
        {
            return null;
        }
        try
        {
            return Long.parseLong(receipt.substring(separator + 1));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private boolean matchesAcceptedDispatchReceipt(String receipt, String dispatchToken)
    {
        return Objects.equals(receipt, dispatchToken)
                || (receipt != null && dispatchToken != null
                        && receipt.startsWith(dispatchToken + "|"));
    }

    private boolean finishAcceptedDispatchReceipt(QueuedTaskContext ctx, String member)
    {
        if (!hasAcceptedDispatchReceipt(ctx.getTaskId(), ctx.getDispatchToken()))
        {
            return false;
        }
        try
        {
            stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.WAIT_ZSET, member);
            extractBillingService.confirmResumeBillingSubmission(
                    ctx.getTaskId(), ctx.getDispatchToken());
        }
        catch (Exception e)
        {
            // accepted 收据是不可回滚证据；收尾失败仅保留给下一拍重试。
            log.warn("派发接受收据收尾失败: taskId={}", ctx.getTaskId(), e);
        }
        return true;
    }

    /**
     * 入队兜底失败处理：CAS 置 FAILED（仅 PENDING/QUEUED 可置）并清理队列痕迹，让计费补偿退回冻结款。
     */
    private void failEnqueue(QueuedTaskContext ctx)
    {
        if (ctx == null || ctx.getTaskId() == null)
        {
            return;
        }
        Long taskId = ctx.getTaskId();
        RLock lock = acquireDispatchLock(taskId);
        if (lock == null)
        {
            log.error("入队失败收口未取得派发锁，交由对账: taskId={}", taskId);
            return;
        }
        try
        {
            failEnqueueLockHeld(ctx);
        }
        finally
        {
            releaseDispatchLock(taskId, lock);
        }
    }

    private void failEnqueueLockHeld(QueuedTaskContext ctx)
    {
        Long taskId = ctx.getTaskId();
        if (StrUtil.isBlank(ctx.getDispatchToken()))
        {
            AidExtractTask current = extractTaskService.getOne(
                    Wrappers.<AidExtractTask>lambdaQuery()
                            .select(AidExtractTask::getId, AidExtractTask::getBillingTraceId)
                            .eq(AidExtractTask::getId, taskId).last("LIMIT 1"), false);
            if (current != null)
            {
                ctx.setDispatchToken(current.getBillingTraceId());
            }
        }
        if (StrUtil.isBlank(ctx.getDispatchToken()))
        {
            log.error("入队失败收口缺少派发周期令牌，交由启动对账: taskId={}", taskId);
            return;
        }
        // 视频续生任务（runNo>0）入队失败：回滚 PARTIAL_FAILED 保留续生入口，不按 FAILED 处理
        if (tryRollbackResumableVideoTask(taskId, ctx.getDispatchToken(), "入队失败"))
        {
            return;
        }
        int rows;
        try
        {
            LambdaUpdateWrapper<AidExtractTask> upd = Wrappers.lambdaUpdate();
            upd.eq(AidExtractTask::getId, taskId);
            upd.in(AidExtractTask::getStatus, STATUS_PENDING, STATUS_QUEUED);
            upd.eq(AidExtractTask::getBillingTraceId, ctx.getDispatchToken());
            upd.set(AidExtractTask::getStatus, "FAILED");
            upd.set(AidExtractTask::getErrorMessage, "入队失败")
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage("入队失败"));
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
        // CAS 成功落 FAILED 后，再按本轮 token 清外围资源；禁止迟到回调清掉后继周期。
        releaseSlotsLockHeld(taskId, ctx.getDispatchToken());
        // 推 SSE 失败终态：SSE 轮询只读 Redis 快照，若不推则已连接前端只见 connected/心跳、看不到 FAILED
        try { sseManager.sendError(taskId, "入队失败"); }
        catch (Exception ignore) { }
        wechatNotifyService.notifyTaskTerminal(taskId);
        // 业务收尾：入队失败时提交防重锁通常已被 submit 持有，这里一并释放 + 清 worker cancel flag，避免用户等 TTL。幂等、不抛出。
        if (taskFinalizer != null)
        {
            try { taskFinalizer.onQueueTaskTerminated(taskId, ctx.getDispatchToken()); }
            catch (Exception e) { log.warn("入队失败业务收尾回调异常(忽略): taskId={}", taskId, e); }
        }
    }

    private double computeScore(long enqueueMillis, int priority)
    {
        return (double) (enqueueMillis - (long) priority * PRIORITY_WEIGHT);
    }

    /**
     * 抢该任务的 Redisson 可重入锁。未指定 leaseTime 时由 watchdog 自动续租，
     * 覆盖账户冻结、MQ 提交与失败退款等不可预测时长的临界区。
     */
    private RLock acquireDispatchLock(Long taskId)
    {
        RLock lock = redissonClient.getLock(TaskQueueKeys.dispatchLockKey(taskId));
        try
        {
            return lock.tryLock() ? lock : null;
        }
        catch (Exception e)
        {
            // Redis 异常：偏保守不放行，避免无锁并发派发。
            log.warn("抢派发锁异常, 跳过本任务, taskId={}", taskId, e);
            return null;
        }
    }

    /** MQ Consumer 有界等待同步发送方释放本任务派发锁，超时后交由 RocketMQ 重投。 */
    private RLock acquireMqConsumerDispatchLock(Long taskId)
    {
        RLock lock = redissonClient.getLock(TaskQueueKeys.dispatchLockKey(taskId));
        try
        {
            return lock.tryLock(MQ_CONSUMER_DISPATCH_LOCK_WAIT_SECONDS, TimeUnit.SECONDS)
                    ? lock : null;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            log.info("MQ周期校验等待派发锁被中断: taskId={}", taskId);
            throw new ServiceException("系统繁忙");
        }
        catch (Exception e)
        {
            log.warn("MQ周期校验获取派发锁异常: taskId={}, errorType={}",
                    taskId, e.getClass().getSimpleName());
            throw new ServiceException("系统繁忙");
        }
    }

    private void releaseDispatchLock(Long taskId, RLock lock)
    {
        if (lock == null)
        {
            return;
        }
        try
        {
            if (lock.isHeldByCurrentThread())
            {
                lock.unlock();
            }
        }
        catch (Exception e)
        {
            log.debug("释放派发锁异常(忽略, watchdog 将收敛), taskId={}: {}", taskId, e.getMessage());
        }
    }

    /** 不可逆步骤前校验当前线程仍持有派发锁，失锁时 fail-closed。 */
    public void assertTaskDispatchLockHeld(Long taskId)
    {
        RLock lock = redissonClient.getLock(TaskQueueKeys.dispatchLockKey(taskId));
        if (!lock.isHeldByCurrentThread())
        {
            log.error("任务派发锁已失效: taskId={}", taskId);
            throw new ServiceException("系统繁忙");
        }
    }

    /**
     * 在与排队放行、取消共用的 taskId 派发锁内执行续跑冻结和立即入队。
     * watchdog 每 10 秒续约，避免远程账户操作超过短租约后并发取消或重复派发。
     */
    public <T> T executeWithTaskDispatchLock(Long taskId, Supplier<T> action)
    {
        if (taskId == null || action == null)
        {
            throw new ServiceException("任务不可用");
        }
        RLock lock = null;
        for (int i = 0; i < CANCEL_LOCK_TRY_TIMES && lock == null; i++)
        {
            lock = acquireDispatchLock(taskId);
            if (lock == null)
            {
                try
                {
                    Thread.sleep(CANCEL_LOCK_TRY_INTERVAL_MS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (lock == null)
        {
            log.info("续跑获取派发锁失败: taskId={}", taskId);
            throw new ServiceException("系统繁忙");
        }

        try
        {
            T result = action.get();
            assertTaskDispatchLockHeld(taskId);
            return result;
        }
        finally
        {
            releaseDispatchLock(taskId, lock);
        }
    }
    /**
     * 调度器单拍：回收终态任务名额 + FIFO 排队放行，由定时器触发或名额释放后主动调用。
     */
    public synchronized void dispatchTick()
    {
        try
        {
            reconcileStaleResumeBillings();
        }
        catch (Exception e)
        {
            log.warn("恢复续跑派发异常", e);
        }
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
            RLock dispatchLockToken = acquireDispatchLock(taskId);
            if (dispatchLockToken == null)
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
                releaseDispatchLock(taskId, dispatchLockToken);
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
        for (String s : occupants)
        {
            Long taskId;
            try { taskId = Long.parseLong(s); }
            catch (NumberFormatException ignore) { continue; }

            RLock lock = acquireDispatchLock(taskId);
            if (lock == null)
            {
                continue;
            }
            try
            {
                // 使用候选 taskId 仅定位；所有判定在锁内重读，禁止旧快照释放/续期新周期。
                AidExtractTask current = extractTaskService.getOne(
                        Wrappers.<AidExtractTask>lambdaQuery()
                                .select(AidExtractTask::getId, AidExtractTask::getStatus,
                                        AidExtractTask::getUserId,
                                        AidExtractTask::getBillingTraceId)
                                .eq(AidExtractTask::getId, taskId).last("LIMIT 1"), false);
                String status = current == null ? null : current.getStatus();
                if (status == null || TERMINAL_STATUS.contains(status))
                {
                    QueuedTaskContext terminalCtx = current == null ? loadCtx(taskId) : null;
                    releaseSlotsLockHeld(taskId,
                            current != null ? current.getBillingTraceId()
                                    : (terminalCtx == null ? null : terminalCtx.getDispatchToken()));
                    continue;
                }
                // 非终态：租约存活才续期；租约失活留给自过期/僵尸对账。
                if (leaseManager.isAlive(taskId))
                {
                    slotManager.renewOccupancy(taskId, current.getUserId());
                }
            }
            finally
            {
                releaseDispatchLock(taskId, lock);
            }
        }
    }

    /**
     * FIFO 排队放行：取等待集稳定快照后按 score 升序逐个尝试抢全局、用户两维名额。
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
            RLock dispatchLockToken = acquireDispatchLock(taskId);
            if (dispatchLockToken == null)
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
                                .select(AidExtractTask::getId, AidExtractTask::getStatus,
                                        AidExtractTask::getBillingTraceId)
                                .eq(AidExtractTask::getId, taskId).last("LIMIT 1"), false);
                String status = (task == null) ? null : task.getStatus();
                if (task != null && !Objects.equals(ctx.getDispatchToken(), task.getBillingTraceId()))
                {
                    log.warn("清理跨周期队列收据: taskId={}", taskId);
                    clearQueueReceiptLockHeld(taskId, ctx.getDispatchToken(), true);
                    continue;
                }
                // 其余非 QUEUED 态（null/已删除、终态、PROCESSING、历史脏态等）→ 移出等待集（不该再排队）。
                //   不能像 PENDING 那样只跳过：否则脏成员会长期滞留等待集，占用 rank / MAX_DRAIN_SCAN 窗口，
                //   并被 refreshQueuePositions（不查 DB）持续误推"排队中"快照。
                if (!STATUS_QUEUED.equals(status) && !STATUS_PENDING.equals(status))
                {
                    // ctx 不只是排队展示：后续 releaseSlots() 会优先靠它释放 user/model/provider 维度名额。
                    //   仅 null/终态 才删 ctx（此时已无需释放或已释放）；PROCESSING/WAIT_POLL/INIT 等"仍在跑"的非终态脏成员
                    //   保留 ctx，留给其真正终态时按 ctx 精确释放维度名额，避免模型 provider 配置变更 / DB 回退异常时 provider 维度释放不全。
                    if (status == null || TERMINAL_STATUS.contains(status))
                    {
                        clearQueueReceiptLockHeld(taskId, ctx.getDispatchToken(), true);
                    }
                    else
                    {
                        stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.WAIT_ZSET, member);
                    }
                    continue;
                }

                // 放行前命中「取消请求」：tryCancelQueuedOrPending 抢派发锁失败降级时会打此标记。此刻任务仍 QUEUED 未派发，
                // 在锁内直接队列层取消（CAS QUEUED→CANCELLED + 释放名额/出队 + 退款 + 推 cancelled），杜绝"已请求取消的排队任务仍被放行执行"。
                // 放在 owner-gate 之前：取消无需本地 job，任一实例的 drain 命中即可取消（不区分 owner）。
                if (isCancelRequested(taskId, ctx.getDispatchToken()))
                {
                    LambdaUpdateWrapper<AidExtractTask> cx = Wrappers.lambdaUpdate();
                    cx.eq(AidExtractTask::getId, taskId);
                    cx.eq(AidExtractTask::getStatus, status);
                    cx.eq(AidExtractTask::getBillingTraceId, ctx.getDispatchToken());
                    cx.set(AidExtractTask::getStatus, "CANCELLED");
                    cx.set(AidExtractTask::getErrorMessage, "用户取消")
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage("用户取消"));
                    cx.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
                    int cxRows = extractTaskService.getBaseMapper().update(null, cx);
                    if (cxRows > 0)
                    {
                        releaseSlotsLockHeld(taskId, ctx.getDispatchToken());
                        try
                        {
                            if (ctx.getUserId() != null)
                            {
                                extractBillingService.refundBilling(
                                        taskId, ctx.getUserId(), ctx.getDispatchToken());
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
                            try { taskFinalizer.onQueueTaskTerminated(taskId, ctx.getDispatchToken()); }
                            catch (Exception e) { log.warn("队列取消业务收尾回调异常(忽略): taskId={}", taskId, e); }
                        }
                        log.info("放行前命中取消请求，已在队列层取消: taskId={}", taskId);
                    }
                    clearCancelRequested(taskId, ctx.getDispatchToken());
                    continue;
                }

                // PENDING+WAIT 表示已准入但尚未完成持久化派发收尾；取消请求已在上方优先处理。
                if (STATUS_PENDING.equals(status))
                {
                    boolean recovered = redispatchPending(ctx, member, null);
                    if (!recovered && LocalTaskDispatchExecutor.MODE.equals(ctx.getDispatchMode()))
                    {
                        String owner = ctx.getOwnerInstanceId();
                        boolean ownerDead = StrUtil.isBlank(owner) || !leaseManager.isInstanceAlive(owner);
                        boolean localJobLost = Objects.equals(owner, leaseManager.getInstanceId())
                                && !localJobRegistry.contains(taskId, ctx.getDispatchToken());
                        boolean acceptedOrRunning = hasAcceptedDispatchReceipt(
                                taskId, ctx.getDispatchToken()) || leaseManager.isAlive(taskId);
                        if (ownerDead || (localJobLost && !acceptedOrRunning))
                        {
                            failTaskAndRefund(ctx, "执行任务失效");
                        }
                    }
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

                // 派发执行器（如本地线程池）已饱和：本拍即使抢到名额也派发不出去。
                // 在抢名额 / CAS 之前就跳过，避免每拍对该任务做无谓的 QUEUED→PENDING→QUEUED 反复写、
                // 以及取消窗口里的瞬时 PENDING。任务留在等待集，由本拍收尾的 refreshQueuePositions
                // 推 LOCAL_EXECUTOR_LIMIT 文案；下一拍线程池有空位后再正常放行。
                TaskDispatchExecutor execProbe = executors().get(ctx.getDispatchMode());
                if (execProbe != null && execProbe.saturated())
                {
                    continue;
                }

                Long acq = slotManager.tryAcquire(taskId, ctx.getUserId(), globalLimit, userLimit);
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
                // -2：用户维度满 → 跳过，继续尝试后面任务
            }
            finally
            {
                releaseDispatchLock(taskId, dispatchLockToken);
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
        upd.eq(AidExtractTask::getBillingTraceId, ctx.getDispatchToken());
        upd.set(AidExtractTask::getStatus, STATUS_PENDING);
        upd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, upd);
        if (rows == 0)
        {
            // 已被取消/推进：回滚名额 + 出队（均 best-effort，避免裸 Redis 异常打断回滚）
            try { slotManager.release(taskId, ctx.getUserId()); }
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
            markDispatchAccepted(ctx);
            // WAIT 成员兼作持久化派发阶段标记：只有执行器明确接受后才移除。
            // dispatch 前崩溃会留下 PENDING+WAIT，INTENT 对账可按同 token 安全重派；
            // dispatch 后、ZREM 前崩溃最多重复投递，由 MQ/LOCAL 的 token claim 拦截。
            try
            {
                stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.WAIT_ZSET, member);
                extractBillingService.confirmResumeBillingSubmission(
                        taskId, ctx.getDispatchToken());
            }
            catch (Exception e)
            {
                log.warn("派发成功后出等待集异常(保留WAIT供幂等对账), taskId={}", taskId, e);
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
     * 恢复 PENDING+WAIT 的派发中间态。调用方持有 task 派发锁；同 token 重投由消费端或
     * LOCAL 包装层的 CAS 保证至多执行一次。
     */
    private boolean redispatchPending(QueuedTaskContext ctx, String member,
                                      ResumeBillingContext resumeContext)
    {
        Long taskId = ctx.getTaskId();
        AidExtractTask current = extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getStatus,
                                AidExtractTask::getBillingTraceId)
                        .eq(AidExtractTask::getId, taskId).last("LIMIT 1"), false);
        if (current == null || !STATUS_PENDING.equals(current.getStatus())
                || !Objects.equals(ctx.getDispatchToken(), current.getBillingTraceId()))
        {
            return false;
        }

        // 上一次 dispatch 已被执行器接受，仅补收尾，不重复投递 LOCAL job/MQ。
        if (finishAcceptedDispatchReceipt(ctx, member))
        {
            return true;
        }

        if (LocalTaskDispatchExecutor.MODE.equals(ctx.getDispatchMode()))
        {
            if (StrUtil.isBlank(ctx.getOwnerInstanceId())
                    || !leaseManager.isInstanceAlive(ctx.getOwnerInstanceId())
                    || !Objects.equals(ctx.getOwnerInstanceId(), leaseManager.getInstanceId())
                    || !localJobRegistry.contains(taskId, ctx.getDispatchToken()))
            {
                return false;
            }
        }

        Long acquired = slotManager.tryAcquire(taskId, ctx.getUserId(),
                concurrencyConfig.getGlobalLimit(), concurrencyConfig.getUserLimit(ctx.getUserId()));
        if (!Objects.equals(acquired, 1L))
        {
            return false;
        }
        if (LocalTaskDispatchExecutor.MODE.equals(ctx.getDispatchMode()))
        {
            leaseManager.markActive(taskId);
        }
        else
        {
            leaseManager.renew(taskId);
        }

        TaskDispatchExecutor executor = executors().get(ctx.getDispatchMode());
        if (executor == null || executor.saturated())
        {
            slotManager.release(taskId, ctx.getUserId());
            leaseManager.release(taskId);
            return false;
        }
        try
        {
            if (!executor.dispatch(ctx))
            {
                slotManager.release(taskId, ctx.getUserId());
                leaseManager.release(taskId);
                return false;
            }
            markDispatchAccepted(ctx);
            try
            {
                stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.WAIT_ZSET, member);
                extractBillingService.confirmResumeBillingSubmission(
                        taskId, ctx.getDispatchToken());
            }
            catch (Exception receiptEx)
            {
                // 执行器已经接受，绝不能再释放名额/退款；WAIT/INTENT 留给下一拍幂等收敛。
                log.warn("PENDING重派已接受但收据收尾失败: taskId={}", taskId, receiptEx);
            }
            log.info("PENDING派发中间态已按周期令牌恢复: taskId={}", taskId);
            return true;
        }
        catch (TaskDispatchRetryableException e)
        {
            slotManager.release(taskId, ctx.getUserId());
            leaseManager.release(taskId);
            log.info("PENDING重派暂不可用，保留WAIT重试: taskId={}", taskId);
            return false;
        }
        catch (Exception e)
        {
            slotManager.release(taskId, ctx.getUserId());
            leaseManager.release(taskId);
            log.warn("PENDING重派异常，保留WAIT重试: taskId={}", taskId, e);
            return false;
        }
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
        if (StrUtil.isBlank(ctx.getDispatchToken()))
        {
            log.error("任务失败收口缺少派发周期令牌，拒绝跨周期修改: taskId={}", taskId);
            return;
        }
        // 已确认的续跑周期派发明确失败：先恢复原业务终态和旧计费周期，再清队列资源。
        // dispatchToken 可阻止可能迟到的 MQ 消息领取已恢复的任务。
        if (StrUtil.isNotBlank(ctx.getDispatchToken())
                && extractBillingService.hasActiveResumeBilling(taskId, ctx.getDispatchToken())
                && extractBillingService.rollbackResumeAfterQueueFailure(taskId, ctx.getUserId()))
        {
            finishResumeRollbackResources(taskId, ctx.getDispatchToken());
            log.warn("续跑派发失败已恢复原任务: taskId={}", taskId);
            return;
        }
        // 视频续生任务（runNo>0）派发失败：回滚 PARTIAL_FAILED 保留续生入口，不按 FAILED + 退款处理
        if (tryRollbackResumableVideoTask(taskId, ctx.getDispatchToken(), errorMessage))
        {
            return;
        }
        int rows;
        try
        {
            LambdaUpdateWrapper<AidExtractTask> upd = Wrappers.lambdaUpdate();
            upd.eq(AidExtractTask::getId, taskId);
            upd.in(AidExtractTask::getStatus, STATUS_PENDING, STATUS_QUEUED);
            upd.eq(AidExtractTask::getBillingTraceId, ctx.getDispatchToken());
            upd.set(AidExtractTask::getStatus, "FAILED");
            upd.set(AidExtractTask::getErrorMessage, errorMessage)
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage(errorMessage));
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
                extractBillingService.refundBilling(
                        taskId, ctx.getUserId(), ctx.getDispatchToken());
            }
        }
        catch (Exception e)
        {
            log.error("失败退款异常, 需人工核对, taskId={}", taskId, e);
        }
        releaseSlots(taskId, ctx.getDispatchToken());
        try { sseManager.sendError(taskId, errorMessage); } catch (Exception ignore) { }
        wechatNotifyService.notifyTaskTerminal(taskId);
        // 业务收尾：释放 taskType 业务防重锁 + 清 worker cancel flag（队列层无法直接做，回调业务 Service）。
        // 与取消路径同一套收尾，避免失败/退款后业务锁仍占用到 TTL 才能重新提交。
        if (taskFinalizer != null)
        {
            try { taskFinalizer.onQueueTaskTerminated(taskId, ctx.getDispatchToken()); }
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
    private boolean tryRollbackResumableVideoTask(Long taskId, String dispatchToken,
                                                   String errorMessage)
    {
        try
        {
            AidExtractTask t = extractTaskService.getById(taskId);
            if (t == null
                    || !Objects.equals(dispatchToken, t.getBillingTraceId())
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
            upd.eq(AidExtractTask::getBillingTraceId, dispatchToken);
            upd.set(AidExtractTask::getStatus, "PARTIAL_FAILED");
            // 恢复续生前的 totalCount，避免状态/结果与 total 不一致。
            // 注意：此处不恢复 inputSnapshot——onQueueTaskTerminated 需按当前快照里的新 token 释放本轮锁，
            // 若先把快照换回续生前版本会导致释放旧 token（no-op）而泄漏新 token。runNo 在队列派发失败时少量浪费可接受（需近千次派发失败才耗尽）。
            if (priorTotalCount != null) { upd.set(AidExtractTask::getTotalCount, priorTotalCount); }
            upd.set(AidExtractTask::getErrorMessage, "续生提交失败，可重试")
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage("续生提交失败，可重试"));
            upd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            int rows = extractTaskService.getBaseMapper().update(null, upd);
            if (rows == 0)
            {
                // 状态已被其它分支推进（取消/已 PROCESSING 等）→ 不接管，交默认逻辑
                return false;
            }
            // 清队列痕迹 + 释放名额 + 业务收尾（释放续生镜头锁）；不退款。
            // 必须携带本轮 token，禁止迟到的旧收尾删除下一轮 ctx/local job。
            releaseSlots(taskId, dispatchToken);
            if (taskFinalizer != null)
            {
                try { taskFinalizer.onQueueTaskTerminated(taskId, dispatchToken); }
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
        slotManager.release(taskId, ctx.getUserId());
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
            upd.eq(AidExtractTask::getBillingTraceId, ctx.getDispatchToken());
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
            clearQueueReceiptLockHeld(taskId, ctx.getDispatchToken(), false);
            return;
        }

        //    故即使 emitter 推送失败，Redis 快照也已回到 QUEUED）。即便此处整体失败，本次 drain 收尾的
        //    refreshQueuePositions 仍会对窗口内（含本任务）重新推 queued、再次把快照纠正为 QUEUED，自愈不残留假 PROCESSING。
        // 用 pushQueuedEventLockHeld：requeue 当前持有本 taskId 派发锁，派发锁不可重入，不能走自抢锁的 pushQueuedEvent。
        try { pushQueuedEventLockHeld(taskId); }
        catch (Exception e) { log.warn("可重试回退推送排队事件失败(忽略, 由本拍 refresh 自愈), taskId={}", taskId, e); }
    }
    /**
     * 按派发周期释放外围资源。带令牌入口供 MQ/LOCAL worker 在 finally 中使用，
     * 后续会在同一 task 派发锁内核对 DB 与 ctx，避免旧周期误删新周期资源。
     */
    public void releaseSlots(Long taskId, String dispatchToken)
    {
        if (taskId == null || StrUtil.isBlank(dispatchToken))
        {
            log.warn("释放任务资源缺少派发周期，保守跳过: taskId={}", taskId);
            return;
        }
        RLock lock = null;
        for (int i = 0; i < CANCEL_LOCK_TRY_TIMES && lock == null; i++)
        {
            lock = acquireDispatchLock(taskId);
            if (lock == null)
            {
                try { Thread.sleep(CANCEL_LOCK_TRY_INTERVAL_MS); }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (lock == null)
        {
            log.warn("释放任务资源未取得派发锁，交由终态对账: taskId={}", taskId);
            return;
        }
        try
        {
            releaseSlotsLockHeld(taskId, dispatchToken);
        }
        finally
        {
            releaseDispatchLock(taskId, lock);
        }
    }

    /** 调用方已持有 task 派发锁。仅终态且周期仍匹配时清理，活动新周期一律 fail-closed。 */
    private void releaseSlotsLockHeld(Long taskId, String dispatchToken)
    {
        QueuedTaskContext ctx = loadCtx(taskId);
        AidExtractTask task = null;
        try
        {
            task = extractTaskService.getOne(
                    Wrappers.<AidExtractTask>lambdaQuery()
                            .select(AidExtractTask::getId, AidExtractTask::getUserId,
                                    AidExtractTask::getStatus, AidExtractTask::getBillingTraceId)
                            .eq(AidExtractTask::getId, taskId).last("LIMIT 1"), false);
        }
        catch (Exception e)
        {
            log.warn("释放名额时查询任务失败，保守跳过: taskId={}", taskId, e);
            return;
        }

        if (task != null && !TERMINAL_STATUS.contains(task.getStatus()))
        {
            log.info("跳过活动周期资源释放: taskId={}, status={}", taskId, task.getStatus());
            return;
        }

        String dbToken = task == null ? null : task.getBillingTraceId();
        String ctxToken = ctx == null ? null : ctx.getDispatchToken();
        String expectedToken = dispatchToken;
        if (StrUtil.isBlank(expectedToken))
        {
            log.warn("锁内释放任务资源缺少派发周期，保守跳过: taskId={}", taskId);
            return;
        }
        boolean dbMatches = expectedToken != null && Objects.equals(expectedToken, dbToken);
        boolean ctxMatches = expectedToken != null && Objects.equals(expectedToken, ctxToken);
        if (expectedToken != null && ctx != null && !ctxMatches)
        {
            log.info("跳过跨周期队列上下文释放: taskId={}", taskId);
            return;
        }
        if (expectedToken != null && !dbMatches && !ctxMatches)
        {
            // Saga 回滚会把主表 trace 恢复为旧周期，此时仍允许清理由当前新 token 留下的 ctx。
            // 其它不匹配均视为旧 worker 迟到，严禁删除当前周期资源。
            log.info("跳过跨周期资源释放: taskId={}", taskId);
            return;
        }

        Long userId = ctx != null && (expectedToken == null || Objects.equals(expectedToken, ctxToken))
                ? ctx.getUserId() : (task == null ? null : task.getUserId());
        // 以下均为「外围资源清理」，做成 best-effort：任一步异常都不得阻断其它清理，更不得阻断调用方在 DB 终态后的退款/SSE/标记清理。
        // （slotManager.release / leaseManager.release 内部已各自 try/catch；这里再对 Redis/唤醒等裸操作兜底，保证 releaseSlots 永不抛出。）
        try { slotManager.release(taskId, userId); }
        catch (Exception e) { log.warn("释放并发名额异常(忽略), taskId={}", taskId, e); }
        try { leaseManager.release(taskId); }
        catch (Exception e) { log.warn("释放执行租约异常(忽略), taskId={}", taskId, e); }
        clearQueueReceiptLockHeld(taskId, expectedToken, true);
        try { stringRedisTemplate.delete(LEGACY_MQ_RECEIPT_PREFIX + taskId); }
        catch (Exception e) { log.warn("清旧MQ收据标记异常(忽略), taskId={}", taskId, e); }
        try { stringRedisTemplate.delete(DISPATCH_ACCEPTED_PREFIX + taskId); }
        catch (Exception e) { log.warn("清派发接受收据异常(忽略), taskId={}", taskId, e); }
        // 名额释放后唤醒调度，让排队任务尽快递补
        try { wakeup(); }
        catch (Exception ignore) { }
    }

    /**
     * 调用方必须持有 task 派发锁。ctx 存在时按 dispatchToken 比较后再删除；
     * local job 自身也执行同 token 的 compare-and-remove，旧周期无法删除新执行体。
     */
    private boolean clearQueueReceiptLockHeld(Long taskId, String dispatchToken,
                                                boolean removeLocalJob)
    {
        if (taskId == null || StrUtil.isBlank(dispatchToken))
        {
            log.warn("清理队列收据缺少派发周期，保守跳过: taskId={}", taskId);
            return false;
        }
        QueuedTaskContext current = loadCtx(taskId);
        if (current != null && !Objects.equals(dispatchToken, current.getDispatchToken()))
        {
            log.info("跳过跨周期队列收据清理: taskId={}", taskId);
            return false;
        }
        try { stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.WAIT_ZSET, String.valueOf(taskId)); }
        catch (Exception e) { log.warn("出等待集异常(忽略), taskId={}", taskId, e); }
        try { stringRedisTemplate.delete(TaskQueueKeys.ctxKey(taskId)); }
        catch (Exception e) { log.warn("清排队上下文异常(忽略), taskId={}", taskId, e); }
        if (removeLocalJob)
        {
            try { localJobRegistry.remove(taskId, dispatchToken); }
            catch (Exception e) { log.warn("清本地执行体异常(忽略), taskId={}", taskId, e); }
        }
        return true;
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
        RLock dispatchLockToken = null;
        for (int i = 0; i < CANCEL_LOCK_TRY_TIMES; i++)
        {
            dispatchLockToken = acquireDispatchLock(taskId);
            if (dispatchLockToken != null)
            {
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
        if (dispatchLockToken == null)
        {
            // 调度器正在放行本任务（持锁较久）→ 取消与执行二选一。打"取消请求"标记：drain() 放行前会在锁内命中并队列层取消，
            // 杜绝"已请求取消的 QUEUED 任务仍被放行"。同时调用方会再写 worker cancel flag，覆盖"已 dispatch 进入执行"的情形。
            markCancelRequested(taskId);
            log.info("取消抢派发锁失败，已打取消请求标记 + 交 cancel flag 兜底: taskId={}", taskId);
            return false;
        }
        try
        {
            AidExtractTask current = extractTaskService.getOne(
                    Wrappers.<AidExtractTask>lambdaQuery()
                            .select(AidExtractTask::getId, AidExtractTask::getStatus,
                                    AidExtractTask::getBillingTraceId)
                            .eq(AidExtractTask::getId, taskId)
                            .last("LIMIT 1"), false);
            if (current == null
                    || (!STATUS_PENDING.equals(current.getStatus())
                            && !STATUS_QUEUED.equals(current.getStatus()))
                    || StrUtil.isBlank(current.getBillingTraceId()))
            {
                return false;
            }
            String expectedTraceId = current.getBillingTraceId();
            LambdaUpdateWrapper<AidExtractTask> upd = Wrappers.lambdaUpdate();
            upd.eq(AidExtractTask::getId, taskId);
            upd.in(AidExtractTask::getStatus, STATUS_PENDING, STATUS_QUEUED);
            upd.eq(AidExtractTask::getBillingTraceId, expectedTraceId);
            upd.set(AidExtractTask::getStatus, "CANCELLED");
            upd.set(AidExtractTask::getErrorMessage, "用户取消")
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage("用户取消"));
            upd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            int rows = extractTaskService.getBaseMapper().update(null, upd);
            if (rows == 0)
            {
                // 已 PROCESSING/终态 → 不在本方法可取消范围
                return false;
            }
            // 锁内释放名额 + 出队 + 清 ctx：此刻 drain 抢不到锁，绝不会再 dispatch 本任务
            releaseSlotsLockHeld(taskId, expectedTraceId);
            clearCancelRequested(taskId, expectedTraceId);
            log.info("队列任务已原子取消: taskId={}", taskId);
            return true;
        }
        finally
        {
            releaseDispatchLock(taskId, dispatchLockToken);
        }
    }

    /**
     * 续跑 outbox 对账：所有动作都在同一 task 派发锁内，按持久化 dispatchToken 幂等修复。
     */
    private void reconcileStaleResumeBillings()
    {
        List<ResumeBillingRecovery> recoveries =
                extractBillingService.listStaleResumeBillingRecoveries(100);
        for (ResumeBillingRecovery recovery : recoveries)
        {
            Long taskId = recovery.taskId();
            RLock lock = acquireDispatchLock(taskId);
            if (lock == null)
            {
                continue;
            }
            try
            {
                ResumeBillingContext context = recovery.context();
                if (!extractBillingService.hasActiveResumeBilling(taskId, context.resumeTraceId()))
                {
                    continue;
                }
                String state = context.rollbackState();
                if (RESUME_STATE_DISPATCH_INTENT.equals(state))
                {
                    reconcileResumeDispatchIntent(recovery);
                }
                else if (RESUME_STATE_PREPARED.equals(state)
                        || RESUME_STATE_FUNDS_FROZEN.equals(state)
                        || RESUME_STATE_ROLLBACK_REQUIRED.equals(state))
                {
                    extractBillingService.recoverResumeBillingIfNeeded(taskId, recovery.userId());
                    if (!extractBillingService.hasActiveResumeBilling(
                            taskId, context.resumeTraceId()))
                    {
                        finishResumeRollbackResources(taskId, context.resumeTraceId());
                    }
                }
            }
            catch (Exception e)
            {
                log.error("续跑持久化恢复失败: taskId={}", taskId, e);
            }
            finally
            {
                releaseDispatchLock(taskId, lock);
            }
        }
    }

    private void reconcileResumeDispatchIntent(ResumeBillingRecovery recovery)
    {
        ResumeBillingContext context = recovery.context();
        Long taskId = recovery.taskId();
        AidExtractTask task = extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getProjectId,
                                AidExtractTask::getEpisodeId, AidExtractTask::getUserId,
                                AidExtractTask::getModelCode, AidExtractTask::getTaskType,
                                AidExtractTask::getStatus, AidExtractTask::getBillingTraceId)
                        .eq(AidExtractTask::getId, taskId)
                        .last("LIMIT 1"), false);
        if (task == null || !Objects.equals(context.resumeTraceId(), task.getBillingTraceId()))
        {
            return;
        }

        String status = task.getStatus();
        if (!STATUS_PENDING.equals(status) && !STATUS_QUEUED.equals(status))
        {
            boolean workerClaimed = STATUS_PROCESSING.equals(status)
                    || STATUS_FINALIZING.equals(status)
                    || "SUCCEEDED".equals(status) || "PARTIAL_FAILED".equals(status);
            if (workerClaimed || hasAcceptedDispatchReceipt(taskId, context.resumeTraceId()))
            {
                // worker 按本 token 领取，或执行器已留下 accepted 收据，才能确认提交。
                extractBillingService.confirmResumeBillingSubmission(taskId, context);
            }
            else if ("FAILED".equals(status) || "CANCELLED".equals(status))
            {
                // 队列失败/放行前取消不能被误当成已提交，按 Saga 恢复原周期。
                rollbackUnrecoverableResumeDispatch(recovery);
            }
            return;
        }

        QueuedTaskContext queuedContext = loadCtx(taskId);
        boolean contextMatches = matchesResumeQueueContext(queuedContext, task, context);
        if (!contextMatches)
        {
            boolean localMode = LocalTaskDispatchExecutor.MODE.equals(context.dispatchMode());
            if (localMode)
            {
                // registry 只按 taskId 存储，ctx 缺失/错 token 时无法证明内存 job 属于本轮，禁止猜测执行。
                rollbackUnrecoverableResumeDispatch(recovery);
                return;
            }
            queuedContext = buildResumeQueueContext(task, context);
        }

        boolean waiting;
        try
        {
            waiting = stringRedisTemplate.opsForZSet().score(
                    TaskQueueKeys.WAIT_ZSET, String.valueOf(taskId)) != null;
        }
        catch (Exception e)
        {
            log.warn("续跑派发对账读取WAIT失败，保留INTENT重试: taskId={}", taskId, e);
            return;
        }

        if (STATUS_QUEUED.equals(status))
        {
            if (!validateLocalRecoveryOwner(queuedContext, false, recovery))
            {
                return;
            }
            // QUEUED 只说明内部排队成立，不能当作上游派发成功；修复收据后等待 drain。
            try
            {
                long enqueueMillis = queuedContext.getEnqueueMillis() == null
                        ? resolveResumeEnqueueMillis(context) : queuedContext.getEnqueueMillis();
                queuedContext.setEnqueueMillis(enqueueMillis);
                writeQueueReceipt(queuedContext, computeScore(enqueueMillis, 0));
                wakeup();
            }
            catch (Exception e)
            {
                log.warn("续跑队列收据修复失败，保留INTENT重试: taskId={}", taskId, e);
            }
            return;
        }

        if (waiting)
        {
            if (!validateLocalRecoveryOwner(queuedContext, true, recovery))
            {
                return;
            }
            // PENDING+WAIT 是 dispatch 前/后的不确定窗口；同 token 重派，成功后 ZREM 并 CONFIRMED。
            if (!redispatchPending(queuedContext, String.valueOf(taskId), context)
                    && LocalTaskDispatchExecutor.MODE.equals(queuedContext.getDispatchMode()))
            {
                rollbackUnrecoverableResumeDispatch(recovery);
            }
            return;
        }

        if (!contextMatches)
        {
            // PENDING 但本轮 ctx 也不存在时，“无 WAIT”不能证明曾成功 dispatch；
            // MQ 可按 token 重建并重投，LOCAL 已在上方按不可恢复回滚。
            try
            {
                long enqueueMillis = queuedContext.getEnqueueMillis() == null
                        ? resolveResumeEnqueueMillis(context) : queuedContext.getEnqueueMillis();
                queuedContext.setEnqueueMillis(enqueueMillis);
                writeQueueReceipt(queuedContext, computeScore(enqueueMillis, 0));
                redispatchPending(queuedContext, String.valueOf(taskId), context);
            }
            catch (Exception e)
            {
                log.warn("续跑MQ重建派发失败，保留INTENT: taskId={}", taskId, e);
            }
            return;
        }

        if (LocalTaskDispatchExecutor.MODE.equals(queuedContext.getDispatchMode()))
        {
            String owner = queuedContext.getOwnerInstanceId();
            if (StrUtil.isBlank(owner) || !leaseManager.isInstanceAlive(owner))
            {
                rollbackUnrecoverableResumeDispatch(recovery);
                return;
            }
            if (Objects.equals(owner, leaseManager.getInstanceId())
                    && localJobRegistry.contains(taskId, queuedContext.getDispatchToken()))
            {
                // job 尚在 registry 说明执行器并未接受；补回 WAIT 后按本 token 重派。
                try
                {
                    writeQueueReceipt(queuedContext, computeScore(
                            queuedContext.getEnqueueMillis() == null
                                    ? resolveResumeEnqueueMillis(context)
                                    : queuedContext.getEnqueueMillis(), 0));
                    if (!redispatchPending(queuedContext, String.valueOf(taskId), context))
                    {
                        rollbackUnrecoverableResumeDispatch(recovery);
                    }
                }
                catch (Exception e)
                {
                    log.warn("续跑LOCAL重派收据写入失败，保留INTENT: taskId={}", taskId, e);
                }
                return;
            }
            if (!leaseManager.isAlive(taskId))
            {
                rollbackUnrecoverableResumeDispatch(recovery);
                return;
            }
        }

        // PENDING+无WAIT 是“执行器已明确接受后才ZREM”的持久化证据。
        extractBillingService.confirmResumeBillingSubmission(taskId, context);
    }

    private boolean matchesResumeQueueContext(QueuedTaskContext queuedContext,
                                              AidExtractTask task,
                                              ResumeBillingContext context)
    {
        return queuedContext != null
                && Objects.equals(task.getId(), queuedContext.getTaskId())
                && Objects.equals(task.getProjectId(), queuedContext.getProjectId())
                && Objects.equals(task.getEpisodeId(), queuedContext.getEpisodeId())
                && Objects.equals(task.getUserId(), queuedContext.getUserId())
                && Objects.equals(task.getModelCode(), queuedContext.getModelCode())
                && Objects.equals(task.getTaskType(), queuedContext.getTaskType())
                && Objects.equals(context.dispatchMode(), queuedContext.getDispatchMode())
                && Objects.equals(context.resumeTraceId(), queuedContext.getDispatchToken());
    }

    /** LOCAL 对账只能由 owner 实例重派；owner/job 不可恢复时按 Saga 回滚。 */
    private boolean validateLocalRecoveryOwner(QueuedTaskContext ctx,
                                               boolean allowAcceptedReceipt,
                                               ResumeBillingRecovery recovery)
    {
        if (!LocalTaskDispatchExecutor.MODE.equals(ctx.getDispatchMode()))
        {
            return true;
        }
        String owner = ctx.getOwnerInstanceId();
        if (StrUtil.isBlank(owner) || !leaseManager.isInstanceAlive(owner))
        {
            rollbackUnrecoverableResumeDispatch(recovery);
            return false;
        }
        if (!Objects.equals(owner, leaseManager.getInstanceId()))
        {
            // owner 仍存活，由 owner 的调度拍处理；本实例不得读取其内存 registry。
            return false;
        }
        if (!localJobRegistry.contains(ctx.getTaskId(), ctx.getDispatchToken()))
        {
            if (allowAcceptedReceipt
                    && hasAcceptedDispatchReceipt(ctx.getTaskId(), ctx.getDispatchToken()))
            {
                return true;
            }
            if (allowAcceptedReceipt && leaseManager.isAlive(ctx.getTaskId()))
            {
                // dispatch 与 accepted 收据之间的瞬时窗口：租约存活时保留 INTENT 下拍复核。
                return false;
            }
            rollbackUnrecoverableResumeDispatch(recovery);
            return false;
        }
        return true;
    }

    private QueuedTaskContext buildResumeQueueContext(AidExtractTask task,
                                                       ResumeBillingContext context)
    {
        boolean localMode = LocalTaskDispatchExecutor.MODE.equals(context.dispatchMode());
        return QueuedTaskContext.builder()
                .taskId(task.getId())
                .projectId(task.getProjectId())
                .episodeId(task.getEpisodeId())
                .userId(task.getUserId())
                .modelCode(task.getModelCode())
                .taskType(task.getTaskType())
                .dispatchMode(localMode ? LocalTaskDispatchExecutor.MODE : MqTaskDispatchExecutor.MODE)
                .dispatchToken(context.resumeTraceId())
                .ownerInstanceId(localMode ? leaseManager.getInstanceId() : null)
                .enqueueMillis(resolveResumeEnqueueMillis(context))
                .build();
    }

    private long resolveResumeEnqueueMillis(ResumeBillingContext context)
    {
        return context.dispatchIntentMillis() == null
                ? System.currentTimeMillis() : context.dispatchIntentMillis();
    }

    private void rollbackUnrecoverableResumeDispatch(ResumeBillingRecovery recovery)
    {
        if (!extractBillingService.rollbackResumeAfterQueueFailure(
                recovery.taskId(), recovery.userId()))
        {
            return;
        }
        finishResumeRollbackResources(recovery.taskId(), recovery.context().resumeTraceId());
    }

    private void finishResumeRollbackResources(Long taskId, String resumeTraceId)
    {
        releaseSlots(taskId, resumeTraceId);
        clearCancelRequested(taskId, resumeTraceId);
        if (taskFinalizer != null)
        {
            try
            {
                taskFinalizer.onQueueTaskTerminated(taskId, resumeTraceId);
            }
            catch (Exception e)
            {
                log.warn("续跑恢复业务收尾异常(忽略): taskId={}", taskId, e);
            }
        }
    }

    /** 打「取消请求」标记（抢锁失败降级用），TTL 与 ctx 同量级，足够覆盖任务在队列里的等待期。 */
    private void markCancelRequested(Long taskId)
    {
        try
        {
            AidExtractTask task = extractTaskService.getOne(
                    Wrappers.<AidExtractTask>lambdaQuery()
                            .select(AidExtractTask::getId, AidExtractTask::getBillingTraceId)
                            .eq(AidExtractTask::getId, taskId).last("LIMIT 1"), false);
            String dispatchToken = task == null ? null : task.getBillingTraceId();
            if (StrUtil.isBlank(dispatchToken))
            {
                log.warn("取消请求缺少派发周期，保守跳过: taskId={}", taskId);
                return;
            }
            stringRedisTemplate.opsForValue().set(
                    TaskQueueKeys.cancelReqKey(taskId), dispatchToken, 30, TimeUnit.MINUTES);
        }
        catch (Exception e)
        {
            log.warn("打取消请求标记失败, taskId={}", taskId, e);
        }
    }

    /** 是否存在「取消请求」标记。 */
    private boolean isCancelRequested(Long taskId, String dispatchToken)
    {
        try
        {
            String storedToken = stringRedisTemplate.opsForValue().get(TaskQueueKeys.cancelReqKey(taskId));
            // "1" 兼容升级前已写入且仍在 TTL 内的取消标记。
            return "1".equals(storedToken) || Objects.equals(dispatchToken, storedToken);
        }
        catch (Exception e)
        {
            // Redis 异常偏保守：不阻断放行（返回 false），避免误取消正常任务
            return false;
        }
    }

    /** 清除「取消请求」标记。 */
    public void clearCancelRequested(Long taskId, String dispatchToken)
    {
        if (taskId == null || StrUtil.isBlank(dispatchToken))
        {
            return;
        }
        try
        {
            stringRedisTemplate.execute(new DefaultRedisScript<>(
                            "local v=redis.call('GET', KEYS[1]); "
                                    + "if v==ARGV[1] or v=='1' then return redis.call('DEL', KEYS[1]) end; return 0",
                            Long.class),
                    List.of(TaskQueueKeys.cancelReqKey(taskId)), dispatchToken);
        }
        catch (Exception e)
        {
            log.warn("按周期清除取消请求异常: taskId={}", taskId, e);
        }
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
    public void clearLease(Long taskId, String expectedDispatchToken)
    {
        if (taskId == null || StrUtil.isBlank(expectedDispatchToken))
        {
            log.warn("清理执行租约缺少派发周期，保守跳过: taskId={}", taskId);
            return;
        }
        executeWithTaskDispatchLock(taskId, () -> {
            AidExtractTask current = extractTaskService.getOne(
                    Wrappers.<AidExtractTask>lambdaQuery()
                            .select(AidExtractTask::getId, AidExtractTask::getStatus,
                                    AidExtractTask::getBillingTraceId)
                            .eq(AidExtractTask::getId, taskId)
                            .last("LIMIT 1"), false);
            if (current == null
                    || !Objects.equals(expectedDispatchToken, current.getBillingTraceId())
                    || !(STATUS_PROCESSING.equals(current.getStatus())
                    || STATUS_FINALIZING.equals(current.getStatus())
                    || STATUS_RECOVERING.equals(current.getStatus())))
            {
                log.info("清理执行租约跳过，任务周期/状态已变化: taskId={}", taskId);
                return Boolean.FALSE;
            }
            leaseManager.release(taskId);
            return Boolean.TRUE;
        });
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
                                    AidExtractTask::getUserId, AidExtractTask::getModelCode, AidExtractTask::getTaskType,
                                    AidExtractTask::getBillingTraceId)
                            .eq(AidExtractTask::getId, taskId).last("LIMIT 1"), false);
            if (t == null)
            {
                return null;
            }
            if (StrUtil.isBlank(t.getBillingTraceId()))
            {
                log.warn("排队上下文重建失败(缺少派发周期令牌): taskId={}", taskId);
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
            QueuedTaskContext ctx = QueuedTaskContext.builder()
                    .taskId(t.getId())
                    .projectId(t.getProjectId())
                    .episodeId(t.getEpisodeId())
                    .userId(t.getUserId())
                    .modelCode(t.getModelCode())
                    .taskType(taskType)
                    .dispatchMode("MQ")
                    .dispatchToken(t.getBillingTraceId())
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
        RLock dispatchLockToken = acquireDispatchLock(taskId);
        if (dispatchLockToken == null)
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
            releaseDispatchLock(taskId, dispatchLockToken);
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
                RLock dispatchLockToken = acquireDispatchLock(taskId);
                if (dispatchLockToken == null)
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
                    releaseDispatchLock(taskId, dispatchLockToken);
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
     * 按调度放行的判定顺序：全局 → 用户 → 派发执行器；命中即返回对应维度，全部有空位返回 null（可放行）。
     * 供应商 / 模型维度不在此列——它们只约束上游请求，由媒体层在任务放行后拦截，不影响队列放行。
     *
     * @return GLOBAL_LIMIT / USER_LIMIT / LOCAL_EXECUTOR_LIMIT；未受限返回 null
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
        // 执行器维度：两维名额都有空位，但派发执行器（如本地线程池）已饱和——任务仍跑不起来，如实标注
        TaskDispatchExecutor exec = executors().get(ctx.getDispatchMode());
        if (exec != null && exec.saturated())
        {
            return "LOCAL_EXECUTOR_LIMIT";
        }
        return null;
    }
}
