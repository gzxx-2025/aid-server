package com.aid.media.eta;

import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidMediaEtaStat;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaEtaStatMapper;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.media.dto.TaskEtaVO;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.service.MediaConcurrencyLimiter;
import com.aid.rps.queue.MediaGenFanInSupport;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图片、视频与音频任务统一 ETA 服务。统计查询带进程内缓存，SSE 只复用既有事件，不产生额外轮询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaEtaService {

    private static final String PREDICTION_VERSION = "histogram-v1";
    private static final List<String> TERMINAL_STATUSES = List.of(
        "SUCCEEDED", "FAILED", "CANCELLED", "PARTIAL_FAILED");
    private static final List<String> ELIGIBLE_MEDIA = List.of("IMAGE", "VIDEO", "AUDIO", "COMPOSE");

    private final AidMediaEtaStatMapper statMapper;
    private final AidMediaTaskMapper mediaTaskMapper;
    private final MediaEtaProfileResolver profileResolver;
    private final MediaEtaSettings settings;
    private final MediaConcurrencyLimiter concurrencyLimiter;

    private final Map<String, CachedStats> statCache = new ConcurrentHashMap<>();
    /** 批量子任务短缓存：吸收同一秒内多个子任务连续上报产生的查询突发。 */
    private final Map<Long, CachedChildren> childCache = new ConcurrentHashMap<>();

    public TaskEtaVO estimateMediaTask(AidMediaTask task, Integer queuePosition) {
        MediaEtaSettings.Snapshot cfg = settings.current();
        if (!cfg.enabled() || task == null || !ELIGIBLE_MEDIA.contains(upper(task.getMediaType()))) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (isTerminal(task.getStatus())) {
            return terminal(now, 1, 1);
        }

        MediaEtaProfileResolver.Profile profile = profileResolver.resolve(task);
        ResolvedStats processing = resolveStats(profile, MediaEtaRecorder.PHASE_PROCESSING, cfg);
        ResolvedStats queue = resolveStats(profile, MediaEtaRecorder.PHASE_QUEUE, cfg);
        int concurrency = concurrencyLimiter.getEffectiveConcurrencyLimit(task.getUserId(), task.getModelName());
        String status = upper(task.getStatus());

        if (MediaTaskStatus.QUEUED.name().equals(status)) {
            int position = Math.max(1, queuePosition == null ? 1 : queuePosition);
            int ahead = Math.max(0, position - 1);
            long startP50 = Math.max(queue.duration.p50Seconds(),
                MediaEtaCalculator.batchWaves(ahead, concurrency, processing.duration.p50Seconds()));
            long startP90 = Math.max(queue.duration.p90Seconds(),
                MediaEtaCalculator.batchWaves(ahead, concurrency, processing.duration.p90Seconds()));
            long remainingP50 = startP50 + processing.duration.p50Seconds();
            long remainingP90 = startP90 + processing.duration.p90Seconds();
            return build("QUEUED", 2, "ESTIMATED", remainingP50, remainingP90,
                now + startP50 * 1000L, now, confidence(processing.sampleCount, queue.sampleCount, cfg),
                Math.max(processing.sampleCount, queue.sampleCount), 1, 0, 0, 1, false);
        }

        Date accepted = task.getUpstreamAcceptTime();
        if (accepted == null) {
            return build("STARTING", 8, "ESTIMATED", processing.duration.p50Seconds(),
                processing.duration.p90Seconds(), now, now,
                confidence(processing.sampleCount, 0, cfg), processing.sampleCount,
                1, 0, 0, 1, false);
        }

        long elapsed = Math.max(0L, (now - accepted.getTime()) / 1000L);
        boolean delayed = elapsed >= processing.duration.p90Seconds();
        long p50Tail = delayed ? Math.max(15L, processing.duration.p50Seconds() / 5L)
            : Math.max(5L, processing.duration.p50Seconds() - elapsed);
        long p90Tail = delayed ? Math.max(30L, processing.duration.p90Seconds() / 5L)
            : Math.max(p50Tail, processing.duration.p90Seconds() - elapsed);
        int progress = MediaEtaCalculator.runningProgress(elapsed, processing.duration.p90Seconds());
        return build("GENERATING", progress, "ESTIMATED", p50Tail, p90Tail,
            accepted.getTime(), now, confidence(processing.sampleCount, 0, cfg), processing.sampleCount,
            1, 0, 1, 0, delayed);
    }

    /** 估算通用父任务；snapshot 可包含真实进度与实时排队位次。 */
    public TaskEtaVO estimateParentTask(AidExtractTask parent, Map<String, Object> snapshot) {
        MediaEtaSettings.Snapshot cfg = settings.current();
        if (!cfg.enabled() || parent == null || !isEligibleParent(parent.getTaskType())) {
            return null;
        }
        long now = System.currentTimeMillis();
        int expectedTotal = Math.max(1, parent.getTotalCount() == null ? 1 : parent.getTotalCount());
        if (isTerminal(parent.getStatus())) {
            return terminal(now, expectedTotal, expectedTotal);
        }

        List<AidMediaTask> children = loadChildren(parent.getId());
        String inferredMedia = inferMediaType(parent.getTaskType());
        Map<String, GroupEstimate> groups = new HashMap<>();
        int completed = 0;
        int running = 0;
        int queued = 0;
        double partialProgress = 0d;
        long strongestSampleCount = 0L;

        for (AidMediaTask child : children) {
            if (isTerminal(child.getStatus())) {
                completed++;
                continue;
            }
            MediaEtaProfileResolver.Profile profile = profileResolver.resolveSummary(child);
            ResolvedStats processing = resolveStats(profile, MediaEtaRecorder.PHASE_PROCESSING, cfg);
            GroupEstimate group = groups.computeIfAbsent(profile.profileKey(), ignored ->
                new GroupEstimate(child.getUserId(), child.getModelName(), processing));
            strongestSampleCount = Math.max(strongestSampleCount, processing.sampleCount);
            if (child.getUpstreamAcceptTime() != null && !MediaTaskStatus.QUEUED.name().equals(child.getStatus())) {
                running++;
                group.runningCount++;
                long elapsed = Math.max(0L, (now - child.getUpstreamAcceptTime().getTime()) / 1000L);
                long p50Tail = Math.max(5L, processing.duration.p50Seconds() - elapsed);
                long p90Tail = Math.max(p50Tail, processing.duration.p90Seconds() - elapsed);
                if (elapsed >= processing.duration.p90Seconds()) {
                    p50Tail = Math.max(15L, processing.duration.p50Seconds() / 5L);
                    p90Tail = Math.max(30L, processing.duration.p90Seconds() / 5L);
                    group.delayed = true;
                }
                group.runningP50.add(p50Tail);
                group.runningP90.add(p90Tail);
                partialProgress += Math.min(0.95d,
                    elapsed / (double) Math.max(1L, processing.duration.p90Seconds()));
            } else {
                queued++;
                group.queuedCount++;
            }
        }

        int known = completed + running + queued;
        int missing = Math.max(0, expectedTotal - known);
        if (missing > 0) {
            MediaEtaProfileResolver.Profile fallbackProfile = profileResolver.defaults(inferredMedia, parent.getModelCode());
            ResolvedStats fallback = resolveStats(fallbackProfile, MediaEtaRecorder.PHASE_PROCESSING, cfg);
            GroupEstimate group = groups.computeIfAbsent(fallbackProfile.profileKey(), ignored ->
                new GroupEstimate(parent.getUserId(), parent.getModelCode(), fallback));
            group.queuedCount += missing;
            queued += missing;
            strongestSampleCount = Math.max(strongestSampleCount, fallback.sampleCount);
        }

        long remainingP50 = 0L;
        long remainingP90 = 0L;
        boolean delayed = false;
        for (GroupEstimate group : groups.values()) {
            int concurrency = concurrencyLimiter.getEffectiveConcurrencyLimit(group.userId, group.modelCode);
            long groupP50 = MediaEtaCalculator.batchFinishSeconds(group.runningP50,
                group.queuedCount, concurrency, group.stats.duration.p50Seconds());
            long groupP90 = MediaEtaCalculator.batchFinishSeconds(group.runningP90,
                group.queuedCount, concurrency, group.stats.duration.p90Seconds());
            remainingP50 = Math.max(remainingP50, groupP50);
            remainingP90 = Math.max(remainingP90, groupP90);
            delayed |= group.delayed;
        }

        if (groups.isEmpty()) {
            MediaEtaProfileResolver.Profile profile = profileResolver.defaults(inferredMedia, parent.getModelCode());
            ResolvedStats fallback = resolveStats(profile, MediaEtaRecorder.PHASE_PROCESSING, cfg);
            int concurrency = concurrencyLimiter.getEffectiveConcurrencyLimit(parent.getUserId(), parent.getModelCode());
            remainingP50 = MediaEtaCalculator.batchWaves(expectedTotal, concurrency, fallback.duration.p50Seconds());
            remainingP90 = MediaEtaCalculator.batchWaves(expectedTotal, concurrency, fallback.duration.p90Seconds());
            queued = expectedTotal;
            strongestSampleCount = fallback.sampleCount;
        }

        int suppliedProgress = intFrom(snapshot, "progress", 0);
        int estimatedProgress = Math.min(95, 5 + (int) Math.floor(
            ((completed + partialProgress) / (double) expectedTotal) * 90d));
        int displayProgress = Math.min(95, Math.max(suppliedProgress, estimatedProgress));
        String source = suppliedProgress > 0 ? "MIXED" : "ESTIMATED";
        String parentStatus = upper(parent.getStatus());
        long startDelay = 0L;
        if ("QUEUED".equals(parentStatus)) {
            int ahead = Math.max(0, intFrom(snapshot, "ahead", intFrom(snapshot, "position", 1) - 1));
            int dispatchConcurrency = Math.max(1, concurrencyLimiter.getGlobalLimit());
            startDelay = Math.max(cfg.queueP50(), MediaEtaCalculator.batchWaves(
                ahead, dispatchConcurrency, cfg.queueP50()));
            long startDelayP90 = Math.max(cfg.queueP90(), MediaEtaCalculator.batchWaves(
                ahead, dispatchConcurrency, cfg.queueP90()));
            remainingP50 += startDelay;
            remainingP90 += Math.max(startDelay, startDelayP90);
            displayProgress = Math.min(displayProgress, 4);
        }

        return build("QUEUED".equals(parentStatus) ? "QUEUED" : "GENERATING",
            displayProgress, source, Math.max(1L, remainingP50), Math.max(1L, remainingP90),
            now + startDelay * 1000L, now, confidence(strongestSampleCount, 0, cfg), strongestSampleCount,
            expectedTotal, Math.min(completed, expectedTotal), running, queued, delayed);
    }

    private List<AidMediaTask> loadChildren(Long parentTaskId) {
        if (parentTaskId == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        CachedChildren cached = childCache.get(parentTaskId);
        if (cached != null && cached.expireAt > now) {
            return cached.children;
        }
        try {
            long lo = Math.multiplyExact(parentTaskId, MediaGenFanInSupport.BIZ_SEQ_PARENT_FACTOR);
            long hi = Math.addExact(lo, MediaGenFanInSupport.BIZ_SEQ_PARENT_FACTOR);
            LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.select(AidMediaTask::getId, AidMediaTask::getUserId, AidMediaTask::getMediaType,
                AidMediaTask::getProtocol, AidMediaTask::getModelName,
                AidMediaTask::getStatus, AidMediaTask::getCreateTime, AidMediaTask::getUpstreamAcceptTime,
                AidMediaTask::getTerminalTime, AidMediaTask::getBizTaskId, AidMediaTask::getBizTaskType,
                AidMediaTask::getParentTaskId);
            wrapper.in(AidMediaTask::getMediaType, ELIGIBLE_MEDIA);
            wrapper.and(q -> q.eq(AidMediaTask::getParentTaskId, parentTaskId)
                .or().eq(AidMediaTask::getBizTaskId, parentTaskId)
                .or(encoded -> encoded.ge(AidMediaTask::getBizTaskId, lo)
                    .lt(AidMediaTask::getBizTaskId, hi)
                    .in(AidMediaTask::getBizTaskType,
                        List.of("storyboard_image_generate", "storyboard_video_generate"))));
            wrapper.orderByAsc(AidMediaTask::getId);
            List<AidMediaTask> children = mediaTaskMapper.selectList(wrapper);
            List<AidMediaTask> result = children == null ? List.of() : children;
            childCache.put(parentTaskId, new CachedChildren(result, now + 750L));
            if (childCache.size() > 2000) {
                childCache.entrySet().removeIf(entry -> entry.getValue().expireAt <= now);
                if (childCache.size() > 2000) {
                    childCache.clear();
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("媒体 ETA 查询批量子任务失败(使用默认估算): parentTaskId={}, err={}",
                parentTaskId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private ResolvedStats resolveStats(MediaEtaProfileResolver.Profile profile, String phase,
                                       MediaEtaSettings.Snapshot cfg) {
        String cacheKey = phase + '|' + profile.profileKey() + '|' + cfg.windowDays() + '|' + cfg.minSamples();
        long now = System.currentTimeMillis();
        CachedStats cached = statCache.get(cacheKey);
        if (cached != null && cached.expireAt > now) {
            return cached.stats;
        }
        ResolvedStats resolved;
        try {
            Date since = daysAgo(cfg.windowDays() - 1);
            AidMediaEtaStat exact = statMapper.selectExactAggregate(since, phase, profile.profileKey());
            AidMediaEtaStat model = null;
            AidMediaEtaStat media = null;
            AidMediaEtaStat selected = exact;
            if (samples(selected) < cfg.minSamples()) {
                model = statMapper.selectModelAggregate(since, phase, profile.providerKey(),
                    profile.modelCode(), profile.mediaType());
                if (samples(model) >= cfg.minSamples()) {
                    selected = model;
                } else {
                    media = statMapper.selectMediaAggregate(since, phase, profile.mediaType());
                    if (samples(media) >= cfg.minSamples()) {
                        selected = media;
                    } else {
                        selected = mostSamples(exact, model, media);
                    }
                }
            }
            long defaultP50 = MediaEtaRecorder.PHASE_QUEUE.equals(phase)
                ? cfg.queueP50() : cfg.defaultP50(profile.mediaType());
            long defaultP90 = MediaEtaRecorder.PHASE_QUEUE.equals(phase)
                ? cfg.queueP90() : cfg.defaultP90(profile.mediaType());
            MediaEtaCalculator.DurationStats duration = MediaEtaCalculator.fromHistogram(
                selected, defaultP50, defaultP90);
            resolved = new ResolvedStats(duration, duration.sampleCount());
        } catch (Exception e) {
            long defaultP50 = MediaEtaRecorder.PHASE_QUEUE.equals(phase)
                ? cfg.queueP50() : cfg.defaultP50(profile.mediaType());
            long defaultP90 = MediaEtaRecorder.PHASE_QUEUE.equals(phase)
                ? cfg.queueP90() : cfg.defaultP90(profile.mediaType());
            resolved = new ResolvedStats(
                new MediaEtaCalculator.DurationStats(defaultP50, defaultP90, 0L), 0L);
            log.debug("媒体 ETA 统计读取失败，使用默认值: phase={}, mediaType={}", phase, profile.mediaType());
        }
        statCache.put(cacheKey, new CachedStats(resolved, now + cfg.cacheTtlSeconds() * 1000L));
        if (statCache.size() > 5000) {
            statCache.entrySet().removeIf(entry -> entry.getValue().expireAt <= now);
            if (statCache.size() > 5000) {
                statCache.clear();
            }
        }
        return resolved;
    }

    private TaskEtaVO build(String phase, int progress, String source, long p50, long p90,
                            long estimatedStartAt, long now, String confidence, long sampleCount,
                            int total, int completed, int running, int queued, boolean delayed) {
        long safeP50 = Math.max(0L, p50);
        long safeP90 = Math.max(safeP50, p90);
        return TaskEtaVO.builder()
            .phase(phase)
            .displayProgress(Math.max(0, Math.min(95, progress)))
            .progressSource(source)
            .remainingSecondsP50(safeP50)
            .remainingSecondsP90(safeP90)
            .estimatedStartAt(estimatedStartAt)
            .estimatedFinishAtP50(now + safeP50 * 1000L)
            .estimatedFinishAtP90(now + safeP90 * 1000L)
            .confidence(confidence)
            .sampleCount(sampleCount)
            .calculatedAt(now)
            .totalCount(total)
            .completedCount(completed)
            .runningCount(running)
            .queuedCount(queued)
            .delayed(delayed)
            .predictionVersion(PREDICTION_VERSION)
            .build();
    }

    private TaskEtaVO terminal(long now, int total, int completed) {
        return TaskEtaVO.builder()
            .phase("COMPLETED")
            .displayProgress(100)
            .progressSource("ACTUAL")
            .remainingSecondsP50(0L)
            .remainingSecondsP90(0L)
            .estimatedStartAt(null)
            .estimatedFinishAtP50(now)
            .estimatedFinishAtP90(now)
            .confidence("ACTUAL")
            .sampleCount(0L)
            .calculatedAt(now)
            .totalCount(total)
            .completedCount(completed)
            .runningCount(0)
            .queuedCount(0)
            .delayed(false)
            .predictionVersion(PREDICTION_VERSION)
            .build();
    }

    private static AidMediaEtaStat mostSamples(AidMediaEtaStat... candidates) {
        AidMediaEtaStat selected = null;
        for (AidMediaEtaStat candidate : candidates) {
            if (samples(candidate) > samples(selected)) {
                selected = candidate;
            }
        }
        return selected;
    }

    private static long samples(AidMediaEtaStat stat) {
        return stat == null || stat.getSampleCount() == null ? 0L : stat.getSampleCount();
    }

    private static Date daysAgo(int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.add(Calendar.DAY_OF_MONTH, -Math.max(0, days));
        return calendar.getTime();
    }

    private static String confidence(long primarySamples, long secondarySamples,
                                     MediaEtaSettings.Snapshot cfg) {
        long samples = Math.max(primarySamples, secondarySamples);
        if (samples <= 0) {
            return "DEFAULT";
        }
        if (samples < cfg.minSamples()) {
            return "LOW";
        }
        if (samples < cfg.minSamples() * 5L) {
            return "MEDIUM";
        }
        return "HIGH";
    }

    private static boolean isTerminal(String status) {
        return TERMINAL_STATUSES.contains(upper(status));
    }

    private static boolean isEligibleParent(String taskType) {
        String value = taskType == null ? "" : taskType.toLowerCase(Locale.ROOT);
        if (value.contains("prompt")) {
            return false;
        }
        return value.contains("image") || value.contains("video") || value.contains("audio")
            || value.contains("lip_sync") || value.contains("compose")
            || value.contains("form_multi_view") || value.contains("form_edit_chat")
            || value.contains("form_card");
    }

    private static String inferMediaType(String taskType) {
        String value = taskType == null ? "" : taskType.toLowerCase(Locale.ROOT);
        if (value.contains("video") || value.contains("lip_sync") || value.contains("compose")) {
            return "VIDEO";
        }
        if (value.contains("audio")) {
            return "AUDIO";
        }
        return "IMAGE";
    }

    private static int intFrom(Map<String, Object> values, String key, int fallback) {
        if (values == null) {
            return fallback;
        }
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private record ResolvedStats(MediaEtaCalculator.DurationStats duration, long sampleCount) {
    }

    private record CachedStats(ResolvedStats stats, long expireAt) {
    }

    private record CachedChildren(List<AidMediaTask> children, long expireAt) {
    }

    private static final class GroupEstimate {
        private final Long userId;
        private final String modelCode;
        private final ResolvedStats stats;
        private int runningCount;
        private int queuedCount;
        private final List<Long> runningP50 = new ArrayList<>();
        private final List<Long> runningP90 = new ArrayList<>();
        private boolean delayed;

        private GroupEstimate(Long userId, String modelCode, ResolvedStats stats) {
            this.userId = userId;
            this.modelCode = modelCode;
            this.stats = Objects.requireNonNull(stats);
        }
    }
}
