package com.aid.quartz.task;

import com.aid.billing.service.BillingFacadeService;
import com.aid.media.service.IMediaBillingService;
import com.aid.media.service.IMediaGenerationService;
import com.aid.media.service.TaskDispatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 媒体任务调度 - 定时任务
 *
 * 统一调度中心入口，周期扫描异步任务并按供应商/模型策略驱动轮询。
 * 同时保留旧补偿轮询逻辑作为降级手段。
 *
 * 调用示例（后台任务管理配置）：
 *   目标字符串：mediaTask.dispatch()         -- 默认批次50
 *   目标字符串：mediaTask.dispatch(100)       -- 自定义批次大小
 *   目标字符串：mediaTask.compensate()        -- 旧补偿轮询（降级）
 *   目标字符串：mediaTask.billingCompensate() -- 计费补偿（SETTLING/REFUNDING/FROZEN）
 *   目标字符串：mediaTask.ossCompensate()            -- OSS 持久化补偿（SUCCEEDED 但 oss_url 为空）
 *   目标字符串：mediaTask.extraChargeCompensate()     -- TOKEN追补（PARTIAL_DONE→DONE）
 *   目标字符串：mediaTask.extraChargeCompensate(100)  -- TOKEN追补 自定义批次
 *
 * 注意：建议在后台创建任务时将"并发执行"设为"禁止"，
 * 代码层面也做了防重入保护，双重保障避免堆积。
 *
 * @author AID
 */
@Slf4j
@Component("mediaTask")
public class MediaTask {

    @Autowired
    private IMediaGenerationService mediaGenerationService;

    @Autowired
    private TaskDispatchService taskDispatchService;

    @Autowired
    private IMediaBillingService mediaBillingService;

    @Autowired
    private BillingFacadeService billingFacadeService;

    /** 防重入标记，保证上一轮跑完才能进入下一轮 */
    private final AtomicBoolean dispatchRunning = new AtomicBoolean(false);
    private final AtomicBoolean compensateRunning = new AtomicBoolean(false);
    private final AtomicBoolean billingCompensateRunning = new AtomicBoolean(false);
    private final AtomicBoolean ossCompensateRunning = new AtomicBoolean(false);
    private final AtomicBoolean extraChargeRunning = new AtomicBoolean(false);

    /**
     * 统一调度中心 - 默认批次
     */
    public void dispatch() {
        dispatch(50);
    }

    /**
     * 统一调度中心 - 自定义批次大小
     * 包含三个阶段：到期轮询 → 回调超时转轮询 → 超时任务关闭
     *
     * @param batchSize 每轮最大处理条数
     */
    public void dispatch(Integer batchSize) {
        if (!dispatchRunning.compareAndSet(false, true)) {
            log.debug("调度中心上一轮仍在执行，跳过本次触发");
            return;
        }
        try {
            int limit = batchSize != null && batchSize > 0 ? batchSize : 50;

            // 1) 到期轮询：扫描 next_poll_time <= NOW() 的任务。
            int polled = taskDispatchService.dispatchDueTasks(limit);

            // 2) 回调超时转轮询：扫描 callback_deadline <= NOW() 的 WAIT_CALLBACK 任务。
            int expired = taskDispatchService.transitionExpiredCallbacks(limit);

            // 3) 超时任务关闭：扫描超过最大存活时间的任务。
            int timeout = taskDispatchService.closeTimeoutTasks(limit);

            // 4) 未提交上游的僵尸任务收口：PENDING/QUEUED 且无 providerTaskId 且超兜底时限（进程重启遗留）。
            int staleClosed = taskDispatchService.closeStaleUnsubmittedTasks(limit);

            // 5) 排队任务兜底拉起：完成事件丢失 / 重启后无在途任务时，QUEUED 任务无人拉起会滞留到被
            //    僵尸收口失败退款；此处按 FIFO 扫描 QUEUED 并尝试抢占并发坑位拉起（CAS 防重）。
            int queuedDrained = mediaGenerationService.drainQueuedCompensate(limit);

            if (polled > 0 || expired > 0 || timeout > 0 || staleClosed > 0 || queuedDrained > 0) {
                log.info("调度中心执行完成, polled={}, expired={}, timeout={}, staleClosed={}, queuedDrained={}",
                    polled, expired, timeout, staleClosed, queuedDrained);
            }
        } finally {
            dispatchRunning.set(false);
        }
    }

    /**
     * 补偿轮询 - 默认批次（旧逻辑，降级手段）
     */
    public void compensate() {
        compensate(50);
    }

    /**
     * 补偿轮询 - 自定义批次大小（旧逻辑，降级手段）
     *
     * @param batchSize 每轮最大处理条数
     */
    public void compensate(Integer batchSize) {
        if (!compensateRunning.compareAndSet(false, true)) {
            log.debug("媒体任务补偿上一轮仍在执行，跳过本次触发");
            return;
        }
        try {
            int handled = mediaGenerationService.compensateProcessingTasks(batchSize);
            if (handled > 0) {
                log.info("媒体任务补偿完成, handled={}", handled);
            }
        } finally {
            compensateRunning.set(false);
        }
    }

    /**
     * 计费补偿 - 默认批次
     * 扫描媒体任务中卡在 SETTLING / REFUNDING / FROZEN 状态超过2分钟的记录，推进到终态。
     *
     * 调用示例：mediaTask.billingCompensate()
     */
    public void billingCompensate() {
        billingCompensate(50);
    }

    /**
     * 计费补偿 - 自定义批次大小
     *
     * @param batchSize 每轮最大处理条数
     */
    public void billingCompensate(Integer batchSize) {
        if (!billingCompensateRunning.compareAndSet(false, true)) {
            log.debug("媒体计费补偿上一轮仍在执行，跳过本次触发");
            return;
        }
        try {
            int limit = batchSize != null && batchSize > 0 ? batchSize : 50;
            int handled = mediaBillingService.retryStaleBillings(limit);
            if (handled > 0) {
                log.info("媒体计费补偿完成, handled={}", handled);
            }
        } finally {
            billingCompensateRunning.set(false);
        }
    }

    /**
     * TOKEN 补扣追补 - 默认批次
     * 扫描 text_settle_status=PARTIAL_DONE 的媒体任务，从可用余额追补剩余差额。
     *
     * 调用示例：mediaTask.extraChargeCompensate()
     */
    public void extraChargeCompensate() {
        extraChargeCompensate(50);
    }

    /**
     * TOKEN 补扣追补 - 自定义批次大小
     *
     * @param batchSize 每轮最大处理条数
     */
    public void extraChargeCompensate(Integer batchSize) {
        if (!extraChargeRunning.compareAndSet(false, true)) {
            log.debug("媒体TOKEN追补上一轮仍在执行，跳过本次触发");
            return;
        }
        try {
            int limit = batchSize != null && batchSize > 0 ? batchSize : 50;
            int handled = billingFacadeService.retryPartialExtraCharges(limit);
            if (handled > 0) {
                log.info("媒体TOKEN追补完成, handled={}", handled);
            }
        } finally {
            extraChargeRunning.set(false);
        }
    }

    /**
     * OSS 持久化补偿 - 默认批次
     *
     * 扫描 status=SUCCEEDED 且 oss_url 为空的任务，重试下载上游产物并按 uploadMode 落地，
     * 成功后触发业务表回填。用于修复 persistOssIfNeeded 瞬时失败的记录。
     *
     * 建议 Quartz 触发频率：每 2-5 分钟一次（窗口需大于 OSS_COMPENSATION_READY_GAP_SECONDS=60s）。
     */
    public void ossCompensate() {
        ossCompensate(50);
    }

    /**
     * OSS 持久化补偿 - 自定义批次大小
     *
     * @param batchSize 每轮最大处理条数
     */
    public void ossCompensate(Integer batchSize) {
        if (!ossCompensateRunning.compareAndSet(false, true)) {
            log.debug("OSS 持久化补偿上一轮仍在执行，跳过本次触发");
            return;
        }
        try {
            int limit = batchSize != null && batchSize > 0 ? batchSize : 50;
            int handled = mediaGenerationService.compensateOssPersistence(limit);
            if (handled > 0) {
                log.info("OSS 持久化补偿完成, handled={}", handled);
            }
        } finally {
            ossCompensateRunning.set(false);
        }
    }
}
