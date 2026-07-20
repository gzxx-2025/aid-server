package com.aid.quartz.task;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aid.rps.service.IAssetExtractService;

import lombok.extern.slf4j.Slf4j;

/**
 * 资产提取/形态/图片僵尸任务清理 - 定时任务（CX6）
 *
 * <p>
 * 扫描 aid_extract_task 中 status=PENDING/PROCESSING 但已超过类型阈值的任务，
 * 自动标记 FAILED + 退款 + 释放对应业务锁，避免 consumer 进程被 kill / OOM 后用户被
 * "任务处理中"卡住。
 * </p>
 *
 * <p>覆盖任务类型：asset_extract / form_generate(_batch) / form_image(_batch) /
 * form_card_image / form_multi_view / form_edit_chat / image_upscale，每类有独立阈值，
 * 详见 {@link com.aid.rps.service.impl.AssetExtractServiceImpl} 中
 * {@code ZOMBIE_STALE_MINUTES_BY_TYPE}。</p>
 *
 * <p>账户操作自带幂等守卫，重复调用安全。</p>
 *
 * <p>调用示例（后台任务管理配置）：</p>
 * <pre>
 *   目标字符串：extractZombieReclaimTask.reclaim()                 -- 仅按类型阈值扫描, batchSize=50
 *   目标字符串：extractZombieReclaimTask.reclaim(30)               -- 调用方阈值取 max(30, 类型阈值)
 *   目标字符串：extractZombieReclaimTask.reclaim(20, 100)          -- 自定义阈值 + 批次
 * </pre>
 *
 * <p>建议在后台创建任务时将"并发执行"设为"禁止"，代码层面也做了防重入保护。</p>
 *
 * @author AID
 */
@Slf4j
@Component("extractZombieReclaimTask")
public class ExtractZombieReclaimTask {

    @Autowired
    private IAssetExtractService assetExtractService;

    /** 防重入标记 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 默认调用方阈值（分钟）：传 0 表示"完全按类型阈值扫描"，
     * 不再附加额外约束，由 Service 内部按 task_type 自带阈值判定。
     */
    private static final int DEFAULT_STALE_MINUTES = 0;
    /** 默认每轮扫描批次 */
    private static final int DEFAULT_BATCH_SIZE = 50;

    /**
     * 默认参数：纯按类型阈值扫描, batchSize=50
     */
    public void reclaim() {
        reclaim(DEFAULT_STALE_MINUTES, DEFAULT_BATCH_SIZE);
    }

    /**
     * 自定义调用方阈值，批次走默认值
     */
    public void reclaim(Integer staleMinutes) {
        reclaim(staleMinutes, DEFAULT_BATCH_SIZE);
    }

    /**
     * 自定义调用方阈值 + 批次
     *
     * @param staleMinutes 调用方最低判定时长（分钟）。Service 内部会取 max(此值, 类型阈值)，
     *                     ≤0 表示完全按类型阈值扫描，建议传 0 或不传
     * @param batchSize    每轮最大处理条数，≤0 用默认 50
     */
    public void reclaim(Integer staleMinutes, Integer batchSize) {
        if (!running.compareAndSet(false, true)) {
            log.debug("提取僵尸任务清理上一轮仍在执行，跳过本次触发");
            return;
        }
        try {
            int stale = staleMinutes != null && staleMinutes > 0 ? staleMinutes : 0;
            int limit = batchSize != null && batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
            int reclaimed = assetExtractService.reclaimZombieExtractTasks(stale, limit);
            if (reclaimed > 0) {
                log.info("提取僵尸任务清理完成, reclaimed={}, callerStaleMinutes={}", reclaimed, stale);
            }
        } catch (Exception e) {
            log.error("提取僵尸任务清理异常", e);
        } finally {
            running.set(false);
        }
    }
}
