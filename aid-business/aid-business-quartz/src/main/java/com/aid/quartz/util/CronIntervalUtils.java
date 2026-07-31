package com.aid.quartz.util;

import java.util.Date;

import org.quartz.CronExpression;

/**
 * cron 频率间隔工具：估算 cron 的触发间隔、按目标间隔生成规整 cron。
 * 用于固定任务的频率安全范围校验与推荐频率生成。
 *
 * @author AID
 */
public final class CronIntervalUtils
{
    /** 估算触发间隔时采样的触发次数 */
    private static final int SAMPLE_FIRE_TIMES = 5;

    /** 60 的因数集合：秒级 cron 只能使用能整除 60 的步长，保证间隔均匀 */
    private static final int[] DIVISORS_OF_60 = {1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30, 60};

    private CronIntervalUtils()
    {
    }

    /**
     * 估算 cron 表达式的平均触发间隔（秒）。
     * 取未来若干次触发时间的相邻间隔平均值；表达式无效或无法触发时返回 -1。
     *
     * @param cron cron 表达式
     * @return 平均触发间隔秒数，-1 表示无法估算
     */
    public static long estimateIntervalSeconds(String cron)
    {
        try
        {
            CronExpression expression = new CronExpression(cron);
            Date cursor = new Date();
            Date first = expression.getNextValidTimeAfter(cursor);
            if (first == null)
            {
                return -1;
            }
            Date prev = first;
            long totalGapMs = 0;
            int gapCount = 0;
            for (int i = 0; i < SAMPLE_FIRE_TIMES; i++)
            {
                Date next = expression.getNextValidTimeAfter(prev);
                if (next == null)
                {
                    break;
                }
                totalGapMs += next.getTime() - prev.getTime();
                gapCount++;
                prev = next;
            }
            if (gapCount == 0)
            {
                return -1;
            }
            return Math.round(totalGapMs / (double) gapCount / 1000d);
        }
        catch (Exception e)
        {
            return -1;
        }
    }

    /**
     * 校验 cron 触发间隔是否落在 [minSeconds, maxSeconds] 安全范围内（含边界）。
     * min/max 为 0 表示该侧不限制。
     *
     * @param cron       cron 表达式
     * @param minSeconds 频率安全下限（秒）
     * @param maxSeconds 频率安全上限（秒）
     * @return true=在安全范围内
     */
    public static boolean isWithinRange(String cron, int minSeconds, int maxSeconds)
    {
        long interval = estimateIntervalSeconds(cron);
        if (interval <= 0)
        {
            return false;
        }
        if (minSeconds > 0 && interval < minSeconds)
        {
            return false;
        }
        if (maxSeconds > 0 && interval > maxSeconds)
        {
            return false;
        }
        return true;
    }

    /**
     * 按目标间隔秒数生成规整的 cron 表达式。
     * 小于 60 秒：对齐到 60 的因数走秒级步长；60 秒~1 小时：按分钟步长；更大：按小时步长。
     *
     * @param intervalSeconds 目标间隔秒数（&gt;0）
     * @return cron 表达式
     */
    public static String buildCron(int intervalSeconds)
    {
        if (intervalSeconds < 60)
        {
            int step = nearestDivisorOf60(Math.max(1, intervalSeconds));
            if (step >= 60)
            {
                return "0 * * * * ?";
            }
            return "0/" + step + " * * * * ?";
        }
        int minutes = Math.round(intervalSeconds / 60f);
        if (minutes < 60)
        {
            if (minutes <= 1)
            {
                return "0 * * * * ?";
            }
            return "0 0/" + minutes + " * * * ?";
        }
        int hours = Math.min(23, Math.round(minutes / 60f));
        if (hours <= 1)
        {
            return "0 0 * * * ?";
        }
        return "0 0 0/" + hours + " * * ?";
    }

    /**
     * 取距离目标值最近的 60 的因数（保证秒级 cron 间隔均匀）
     */
    private static int nearestDivisorOf60(int seconds)
    {
        int best = DIVISORS_OF_60[0];
        int bestDiff = Integer.MAX_VALUE;
        for (int divisor : DIVISORS_OF_60)
        {
            int diff = Math.abs(divisor - seconds);
            if (diff < bestDiff)
            {
                bestDiff = diff;
                best = divisor;
            }
        }
        return best;
    }
}
