package com.aid.runner;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.aid.rps.service.IAssetExtractService;

import lombok.extern.slf4j.Slf4j;

/**
 * 应用启动时自动清理提取/形态/图片任务的僵尸态（CX6 加固 3）。
 * <p>
 * 场景：上一次部署 / 重启时 consumer 正在处理的任务，进程退出后
 * aid_extract_task 表里 status=PENDING/PROCESSING 永远不会被推进到终态，
 * 对应业务锁也不会被释放——导致用户被"任务处理中"卡住。
 * </p>
 * <p>
 * 本 Runner 在 Spring 容器就绪后立即执行一次 {@code reclaimZombieExtractTasks}，
 * 把上一次遗留的僵尸任务全部清理掉（覆盖所有支持的 task_type）。
 * </p>
 * <p>
 * 入参传 0 表示完全按各 task_type 自带阈值判定，不附加额外的"启动激进窗口"。
 * 多实例部署时另一节点可能仍在跑长任务（asset_extract 上限 20 分钟），
 * 这里不能用过短的统一阈值，避免误杀活任务。
 * </p>
 *
 * @author AID
 */
@Slf4j
@Component
@Order(100) // 在业务 Bean 初始化完成后再跑
public class ExtractZombieStartupRunner implements ApplicationRunner
{
    @Autowired
    private IAssetExtractService assetExtractService;

    /**
     * 启动时调用方阈值（分钟）：传 0 → 完全按类型阈值扫描。
     * 多实例部署时另一节点可能仍在跑长任务，统一阈值过短会误杀活任务。
     */
    private static final int STARTUP_STALE_MINUTES = 0;
    private static final int STARTUP_BATCH_SIZE = 200;

    /**
     * 重启即时重置时是否清空启动前快照内任务的遗留租约。
     * <p>
     * 单实例部署 = true（默认）：本进程刚启动，PROCESSING 必属上次遗留，立即重置；<br>
     * 多实例部署 = false：仅按租约失效判定，避免误杀其它节点仍在跑的长任务。
     * 通过 {@code aid.taskq.restart-clear-leases} 配置。
     * </p>
     */
    @org.springframework.beans.factory.annotation.Value("${aid.taskq.restart-clear-leases:false}")
    private boolean restartClearLeases;

    @Override
    public void run(ApplicationArguments args)
    {
        // 先做"重启即时重置"：基于执行租约，只处理已确认失活的 PROCESSING 任务。
        // 单实例部署如需主动清除旧租约，可显式配置 aid.taskq.restart-clear-leases=true；
        // 默认关闭，避免滚动发布时清掉其它存活实例正在续期的租约。
        try
        {
            int resetCount = assetExtractService.resetLeaselessProcessingTasks(STARTUP_BATCH_SIZE, restartClearLeases);
            if (resetCount > 0)
            {
                log.warn("[启动自愈] 重启即时重置: 处理了 {} 条 PROCESSING 僵死任务", resetCount);
            }
            else
            {
                log.info("[启动自愈] 重启即时重置: 无 PROCESSING 僵死任务");
            }
        }
        catch (Exception e)
        {
            log.error("[启动自愈] 重启即时重置异常（不影响启动）", e);
        }

        try
        {
            int resetPendingQueued = assetExtractService.resetStartupPendingQueuedTasks(STARTUP_BATCH_SIZE);
            if (resetPendingQueued > 0)
            {
                log.warn("[启动自愈] 启动即时回收: 处理了 {} 条 PENDING/QUEUED 遗留任务", resetPendingQueued);
            }
            else
            {
                log.info("[启动自愈] 启动即时回收: 无 PENDING/QUEUED 遗留任务");
            }
        }
        catch (Exception e)
        {
            log.error("[启动自愈] 启动即时回收 PENDING/QUEUED 异常（不影响启动）", e);
        }

        // 兜底：原有按类型阈值的僵尸扫描（覆盖 PENDING/QUEUED 卡住等租约机制外的场景）
        try
        {
            int reclaimed = assetExtractService.reclaimZombieExtractTasks(STARTUP_STALE_MINUTES, STARTUP_BATCH_SIZE);
            if (reclaimed > 0)
            {
                log.warn("[启动自愈] 应用启动自愈: 清理了 {} 条僵尸提取任务", reclaimed);
            }
            else
            {
                log.info("[启动自愈] 应用启动自愈: 无僵尸提取任务");
            }
        }
        catch (Exception e)
        {
            // 启动自愈失败不阻断应用启动，由定时任务兜底
            log.error("[启动自愈] 应用启动自愈异常（不影响启动）", e);
        }
    }
}
