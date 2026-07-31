package com.aid.quartz.task;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aid.rps.queue.TaskLeaseManager;
import com.aid.rps.queue.TaskQueueService;
import com.aid.rps.service.IAssetExtractService;

import lombok.extern.slf4j.Slf4j;

/**
 * 系统核心调度 - 定时任务（固定任务，禁止关闭）
 *
 * 原先散落在 interface-main 中的 @Scheduled 任务统一收口到 Quartz，
 * 全部纳入后台「定时任务」菜单管理（jobType=1 固定任务，禁止暂停/删除）。
 *
 * 调用示例（后台任务管理配置）：
 *   目标字符串：systemCoreTask.queueDispatchTick()        -- 任务队列调度拍（排队放行+名额回收）
 *   目标字符串：systemCoreTask.leaseHeartbeat()           -- 执行租约心跳（TTL 90秒，间隔不得超过30秒）
 *   目标字符串：systemCoreTask.reapLeaselessProcessing()  -- 租约失活 PROCESSING 僵尸任务回收
 *
 * 所有方法自带防重入保护，建议后台配置「并发执行=禁止」双重保障。
 *
 * @author AID
 */
@Slf4j
@Component("systemCoreTask")
public class SystemCoreTask {

    @Autowired
    private TaskQueueService taskQueueService;

    @Autowired
    private TaskLeaseManager taskLeaseManager;

    @Autowired
    private IAssetExtractService assetExtractService;

    /** 默认每轮僵尸回收批次上限 */
    private static final int DEFAULT_RECLAIM_BATCH = 200;

    /** 僵尸回收防重入标记 */
    private final AtomicBoolean reclaimRunning = new AtomicBoolean(false);

    /**
     * 任务队列调度拍：触发排队放行 + 终态并发名额回收。
     * 停用后用户的提取/生成任务将永久排队，属于系统必备任务。
     */
    public void queueDispatchTick() {
        try {
            // 只投递触发信号，重活在 QueueService 专用执行器里跑，自带防重入合并
            taskQueueService.triggerDispatch();
        } catch (Exception e) {
            log.warn("任务队列调度拍触发异常", e);
        }
    }

    /**
     * 执行租约心跳：为本实例所有执行中任务续 Redis 租约，并续实例存活心跳。
     * 租约 TTL 90 秒，本任务间隔不得超过 30 秒，否则长任务会被误判为僵尸并退款。
     */
    public void leaseHeartbeat() {
        try {
            taskLeaseManager.heartbeat();
        } catch (Exception e) {
            log.warn("执行租约心跳异常", e);
        }
    }

    /**
     * 租约失活僵尸任务回收 - 默认批次。
     * 回收进程崩溃后租约自然过期的 PROCESSING 任务：标记失败 + 退款 + 释放业务锁。
     */
    public void reapLeaselessProcessing() {
        reapLeaselessProcessing(DEFAULT_RECLAIM_BATCH);
    }

    /**
     * 租约失活僵尸任务回收 - 自定义批次大小。
     *
     * @param batchSize 每轮最大处理条数
     */
    public void reapLeaselessProcessing(Integer batchSize) {
        if (!reclaimRunning.compareAndSet(false, true)) {
            log.debug("租约失活僵尸回收上一轮仍在执行，跳过本次触发");
            return;
        }
        try {
            int limit = batchSize != null && batchSize > 0 ? batchSize : DEFAULT_RECLAIM_BATCH;
            // clearLeasesFirst=false：只回收租约已自然过期的任务，绝不强清活实例的租约
            int reset = assetExtractService.resetLeaselessProcessingTasks(limit, false);
            if (reset > 0) {
                log.warn("回收租约失活的 PROCESSING 僵尸任务: {} 条", reset);
            }
        } catch (Exception e) {
            log.error("租约失活僵尸任务回收异常", e);
        } finally {
            reclaimRunning.set(false);
        }
    }
}
