package com.aid.media.util;

/**
 * 根据上游受理时钟与最近进展时钟判定媒体任务是否仍应等待。
 *
 * @author 视觉AID
 */
public final class TaskLivenessDecider
{
    private TaskLivenessDecider()
    {
    }

    /** 存活判定结论。 */
    public enum Verdict
    {
        /** 仍在合理等待窗口内，继续调度 */
        ALIVE,
        /** 上游长时间无进展，需要强制对账 */
        STALLED,
        /** 任务总时长已超过警戒线且无进展，需要升级告警并强制对账 */
        EXPIRED
    }

    /**
     * 判定任务是否还该继续等待。
     *
     * @param nowMs                  当前时刻（毫秒）
     * @param acceptedAtMs           上游受理时刻（毫秒）；仅用于判断是否升级为严重告警
     * @param lastProgressAtMs       最近一次观测到上游推进的时刻（毫秒）；从未观测到时由调用方回落到受理时刻
     * @param maxLifeSeconds         最大存活（秒），须为正数，取 {@code ScheduleStrategy#effectiveMaxLifeSeconds()}
     * @param progressTimeoutSeconds 无进展超时（秒），须为正数，取 {@code ScheduleStrategy#effectiveProgressTimeoutSeconds()}
     * @return 存活判定结论
     */
    public static Verdict decide(long nowMs, long acceptedAtMs, long lastProgressAtMs,
                                 int maxLifeSeconds, int progressTimeoutSeconds)
    {
        // 进展时刻晚于受理时刻属正常（每次观测都会前移），早于受理时刻则按受理时刻算，避免脏数据把任务提前判死
        long progressAt = Math.max(lastProgressAtMs, acceptedAtMs);
        if (nowMs - progressAt > (long) progressTimeoutSeconds * 1000L)
        {
            return nowMs - acceptedAtMs > (long) maxLifeSeconds * 1000L
                    ? Verdict.EXPIRED : Verdict.STALLED;
        }
        return Verdict.ALIVE;
    }
}
