package com.aid.rps.helper;

/**
 * 提供最新剧本直驱分镜的写入与限额决策。
 *
 * @author 视觉AID
 */
public final class StoryboardDirectWritePolicy
{
    private StoryboardDirectWritePolicy()
    {
    }

    /**
     * 计算直驱任务首次写入策略。
     *
     * @param overwrite 是否覆盖
     * @param hasPersistedTaskShots 是否已有本任务镜头
     * @param selective 是否选择性生成
     * @return 写入策略
     */
    public static Decision decide(boolean overwrite, boolean hasPersistedTaskShots,
                                  boolean selective)
    {
        boolean firstActualWrite = !hasPersistedTaskShots;
        return new Decision(
                overwrite && firstActualWrite,
                firstActualWrite,
                overwrite && firstActualWrite && !selective,
                firstActualWrite && !selective);
    }

    /**
     * 判断批次是否仍可按执行前失败退款。
     *
     * @param status 批次状态
     * @param billingStatus 批次计费状态
     * @return 是否可退款
     */
    public static boolean isRefundableBeforeExecution(String status, String billingStatus)
    {
        boolean notStarted = "PENDING".equalsIgnoreCase(status)
                || "PROCESSING".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status);
        return notStarted && "FROZEN".equalsIgnoreCase(billingStatus);
    }

    /**
     * 判断新增镜头是否超过任务总量上限。
     *
     * @param persistedCount 已持久化数量
     * @param generatedCount 本轮已生成数量
     * @param incomingCount 待写入数量
     * @param maxCount 最大数量
     * @return 是否超限
     */
    public static boolean exceedsShotLimit(int persistedCount, int generatedCount,
                                           int incomingCount, int maxCount)
    {
        return (long) persistedCount + generatedCount + incomingCount > maxCount;
    }

    public record Decision(boolean replaceStoryboard,
                           boolean cleanupNonManualPlots,
                           boolean resetSortOrder,
                           boolean resetSceneSequence)
    {
    }
}
