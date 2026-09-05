package com.aid.media.eta;

import com.aid.aid.domain.AidMediaEtaStat;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaEtaStatMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 成功任务 ETA 采样器。提交到有界单线程队列，队列满时丢弃观测样本，不反压生成主链路。
 */
@Slf4j
@Service
public class MediaEtaRecorder {

    public static final String PHASE_QUEUE = "QUEUE";
    public static final String PHASE_PROCESSING = "PROCESSING";
    private static final int QUEUE_CAPACITY = 2000;

    private final AidMediaEtaStatMapper statMapper;
    private final MediaEtaProfileResolver profileResolver;
    private final MediaEtaSettings settings;
    private final AtomicLong droppedSamples = new AtomicLong();
    private final AtomicLong nextCleanupAt = new AtomicLong();
    private final ThreadPoolExecutor executor;

    public MediaEtaRecorder(AidMediaEtaStatMapper statMapper,
                            MediaEtaProfileResolver profileResolver,
                            MediaEtaSettings settings) {
        this.statMapper = statMapper;
        this.profileResolver = profileResolver;
        this.settings = settings;
        this.executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "media-eta-recorder");
                thread.setDaemon(true);
                return thread;
            },
            (runnable, ignored) -> {
                long dropped = droppedSamples.incrementAndGet();
                if (dropped == 1L || dropped % 100L == 0L) {
                    log.warn("媒体 ETA 采样队列已满，本次样本丢弃, dropped={}", dropped);
                }
            });
    }

    /** 在任务终态事务提交后记录排队与执行两个独立样本。 */
    public void recordSuccess(AidMediaTask task) {
        Sample sample = Sample.from(task, profileResolver);
        if (sample == null) {
            return;
        }
        Runnable enqueue = () -> executor.execute(() -> persist(sample));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue.run();
                }
            });
        } else {
            enqueue.run();
        }
    }

    private void persist(Sample sample) {
        try {
            if (sample.queueDurationMs > 0L) {
                statMapper.upsertSample(buildStat(sample, PHASE_QUEUE, sample.queueDurationMs));
            }
            if (sample.processingDurationMs > 0L) {
                statMapper.upsertSample(buildStat(sample, PHASE_PROCESSING, sample.processingDurationMs));
            }
            cleanupExpiredIfDue();
        } catch (Exception e) {
            log.warn("媒体 ETA 样本记录失败(不影响业务): taskId={}, err={}", sample.taskId, e.getMessage());
        }
    }

    private AidMediaEtaStat buildStat(Sample sample, String phase, long durationMs) {
        AidMediaEtaStat stat = new AidMediaEtaStat();
        stat.setBucketDate(startOfDay(sample.terminalTime));
        stat.setPhase(phase);
        stat.setProfileKey(sample.profile.profileKey());
        stat.setProviderKey(sample.profile.providerKey());
        stat.setModelCode(sample.profile.modelCode());
        stat.setMediaType(sample.profile.mediaType());
        stat.setWorkloadKey(sample.profile.workloadKey());
        stat.setSampleCount(1L);
        stat.setTotalDurationMs(durationMs);
        stat.setMaxDurationMs(durationMs);
        stat.setBucket1s(durationMs <= 1_000L ? 1L : 0L);
        stat.setBucket5s(durationMs > 1_000L && durationMs <= 5_000L ? 1L : 0L);
        stat.setBucket15s(durationMs > 5_000L && durationMs <= 15_000L ? 1L : 0L);
        stat.setBucket30s(durationMs > 15_000L && durationMs <= 30_000L ? 1L : 0L);
        stat.setBucket60s(durationMs > 30_000L && durationMs <= 60_000L ? 1L : 0L);
        stat.setBucket120s(durationMs > 60_000L && durationMs <= 120_000L ? 1L : 0L);
        stat.setBucket300s(durationMs > 120_000L && durationMs <= 300_000L ? 1L : 0L);
        stat.setBucket600s(durationMs > 300_000L && durationMs <= 600_000L ? 1L : 0L);
        stat.setBucket1200s(durationMs > 600_000L && durationMs <= 1_200_000L ? 1L : 0L);
        stat.setBucket2400s(durationMs > 1_200_000L && durationMs <= 2_400_000L ? 1L : 0L);
        stat.setBucket4800s(durationMs > 2_400_000L && durationMs <= 4_800_000L ? 1L : 0L);
        stat.setBucketInf(durationMs > 4_800_000L ? 1L : 0L);
        stat.setCreateTime(sample.terminalTime);
        stat.setUpdateTime(sample.terminalTime);
        return stat;
    }

    private void cleanupExpiredIfDue() {
        long now = System.currentTimeMillis();
        long due = nextCleanupAt.get();
        if (now < due || !nextCleanupAt.compareAndSet(due, now + TimeUnit.HOURS.toMillis(6))) {
            return;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        calendar.add(Calendar.DAY_OF_MONTH, -settings.current().retentionDays());
        statMapper.deleteBefore(startOfDay(calendar.getTime()));
    }

    private static Date startOfDay(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private record Sample(Long taskId, MediaEtaProfileResolver.Profile profile, Date terminalTime,
                          long queueDurationMs, long processingDurationMs) {
        static Sample from(AidMediaTask task, MediaEtaProfileResolver resolver) {
            if (task == null || task.getId() == null || task.getCreateTime() == null) {
                return null;
            }
            String mediaType = task.getMediaType() == null ? ""
                : task.getMediaType().toUpperCase(Locale.ROOT);
            if (!("IMAGE".equals(mediaType) || "VIDEO".equals(mediaType)
                    || "AUDIO".equals(mediaType) || "COMPOSE".equals(mediaType))) {
                return null;
            }
            Date terminal = task.getTerminalTime() == null ? new Date() : task.getTerminalTime();
            Date accepted = task.getUpstreamAcceptTime();
            long queueMs = accepted == null ? 0L
                : Math.max(0L, accepted.getTime() - task.getCreateTime().getTime());
            long processingStart = accepted == null ? task.getCreateTime().getTime() : accepted.getTime();
            long processingMs = Math.max(1L, terminal.getTime() - processingStart);
            return new Sample(task.getId(), resolver.resolve(task), terminal, queueMs, processingMs);
        }
    }
}
