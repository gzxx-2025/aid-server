package com.aid.media.eta;

import com.aid.aid.domain.AidMediaEtaStat;

import java.util.Collection;
import java.util.PriorityQueue;

/** ETA 纯计算函数，便于在不启动 Spring 的情况下做回归测试。 */
public final class MediaEtaCalculator {

    private static final long[] UPPER_BOUNDS_SECONDS = {
        1, 5, 15, 30, 60, 120, 300, 600, 1200, 2400, 4800
    };

    private MediaEtaCalculator() {
    }

    public static DurationStats fromHistogram(AidMediaEtaStat stat, long defaultP50, long defaultP90) {
        long samples = value(stat == null ? null : stat.getSampleCount());
        if (samples <= 0) {
            return new DurationStats(defaultP50, Math.max(defaultP90, defaultP50), 0);
        }
        long[] buckets = {
            value(stat.getBucket1s()), value(stat.getBucket5s()), value(stat.getBucket15s()),
            value(stat.getBucket30s()), value(stat.getBucket60s()), value(stat.getBucket120s()),
            value(stat.getBucket300s()), value(stat.getBucket600s()), value(stat.getBucket1200s()),
            value(stat.getBucket2400s()), value(stat.getBucket4800s()), value(stat.getBucketInf())
        };
        long p50 = percentile(samples, buckets, 0.50d, value(stat.getMaxDurationMs()), defaultP50);
        long p90 = percentile(samples, buckets, 0.90d, value(stat.getMaxDurationMs()), defaultP90);
        return new DurationStats(Math.max(1, p50), Math.max(p50, p90), samples);
    }

    public static long batchWaves(int taskCount, int concurrency, long durationSeconds) {
        if (taskCount <= 0) {
            return 0;
        }
        int safeConcurrency = Math.max(1, concurrency);
        long waves = (taskCount + (long) safeConcurrency - 1L) / safeConcurrency;
        return waves * Math.max(1L, durationSeconds);
    }

    /**
     * 按并发槽最早可用时间估算一组任务的最晚完成时刻。
     * runningRemainingSeconds 表示已占用槽位的剩余时间，queuedCount 个待执行任务按相同时长依次进入最早空闲槽。
     */
    public static long batchFinishSeconds(Collection<Long> runningRemainingSeconds,
                                          int queuedCount,
                                          int concurrency,
                                          long durationSeconds) {
        int runningCount = runningRemainingSeconds == null ? 0 : runningRemainingSeconds.size();
        int safeConcurrency = Math.max(Math.max(1, concurrency), runningCount);
        long safeDuration = Math.max(1L, durationSeconds);
        PriorityQueue<Long> slots = new PriorityQueue<>();
        if (runningRemainingSeconds != null) {
            for (Long remaining : runningRemainingSeconds) {
                slots.offer(Math.max(0L, remaining == null ? 0L : remaining));
            }
        }
        while (slots.size() < safeConcurrency) {
            slots.offer(0L);
        }
        for (int i = 0; i < Math.max(0, queuedCount); i++) {
            long availableAt = slots.remove();
            slots.offer(availableAt + safeDuration);
        }
        long finishAt = 0L;
        for (Long slot : slots) {
            finishAt = Math.max(finishAt, slot);
        }
        return finishAt;
    }

    public static int runningProgress(long elapsedSeconds, long p90Seconds) {
        double ratio = Math.min(1d, Math.max(0d, elapsedSeconds) / (double) Math.max(1L, p90Seconds));
        return Math.min(95, 15 + (int) Math.floor(ratio * 80d));
    }

    private static long percentile(long samples, long[] buckets, double quantile,
                                   long maxDurationMs, long fallback) {
        long target = Math.max(1L, (long) Math.ceil(samples * quantile));
        long cumulative = 0L;
        for (int i = 0; i < buckets.length; i++) {
            cumulative += buckets[i];
            if (cumulative >= target) {
                if (i < UPPER_BOUNDS_SECONDS.length) {
                    return UPPER_BOUNDS_SECONDS[i];
                }
                return maxDurationMs > 0 ? Math.max(1L, (maxDurationMs + 999L) / 1000L) : fallback;
            }
        }
        return fallback;
    }

    private static long value(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    public record DurationStats(long p50Seconds, long p90Seconds, long sampleCount) {
    }
}
