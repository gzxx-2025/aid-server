package com.aid.quartz.task;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aid.rps.service.IExtractBillingService;

import lombok.extern.slf4j.Slf4j;

/**
 * 资产提取计费补偿 - 定时任务
 *
 * 扫描提取任务中卡在 SETTLING / REFUNDING / FROZEN 状态超过2分钟的记录，推进到终态。
 * 账户操作自带幂等守卫（balance_log traceId + changeType），重复调用安全。
 *
 * 调用示例（后台任务管理配置）：
 *   目标字符串：extractBillingTask.compensate()              -- 默认批次50
 *   目标字符串：extractBillingTask.compensate(100)            -- 自定义批次大小
 *   目标字符串：extractBillingTask.extraChargeCompensate()    -- TOKEN追补（PARTIAL_SUCCESS→SUCCESS）
 *   目标字符串：extractBillingTask.extraChargeCompensate(100) -- TOKEN追补 自定义批次
 *
 * 建议在后台创建任务时将"并发执行"设为"禁止"，
 * 代码层面也做了防重入保护，双重保障避免堆积。
 *
 * @author AID
 */
@Slf4j
@Component("extractBillingTask")
public class ExtractBillingTask {

    @Autowired
    private IExtractBillingService extractBillingService;

    /** 防重入标记 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean extraChargeRunning = new AtomicBoolean(false);

    /**
     * 计费补偿 - 默认批次
     */
    public void compensate() {
        compensate(50);
    }

    /**
     * 计费补偿 - 自定义批次大小
     *
     * @param batchSize 每轮最大处理条数
     */
    public void compensate(Integer batchSize) {
        if (!running.compareAndSet(false, true)) {
            log.debug("提取计费补偿上一轮仍在执行，跳过本次触发");
            return;
        }
        try {
            int limit = batchSize != null && batchSize > 0 ? batchSize : 50;
            int handled = extractBillingService.retryStaleFrozenBillings(limit);
            if (handled > 0) {
                log.info("提取计费补偿完成, handled={}", handled);
            }
        } finally {
            running.set(false);
        }
    }

    /**
     * TOKEN 补扣追补 - 默认批次
     * 扫描 billing_status=PARTIAL_SUCCESS 的提取任务，从可用余额追补剩余差额。
     *
     * 调用示例：extractBillingTask.extraChargeCompensate()
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
            log.debug("提取TOKEN追补上一轮仍在执行，跳过本次触发");
            return;
        }
        try {
            int limit = batchSize != null && batchSize > 0 ? batchSize : 50;
            int handled = extractBillingService.retryPartialExtraCharges(limit);
            if (handled > 0) {
                log.info("提取TOKEN追补完成, handled={}", handled);
            }
        } finally {
            extraChargeRunning.set(false);
        }
    }
}
