package com.aid.aid.monitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.monitor.vo.ModelQueueSnapshotVo;
import com.aid.aid.monitor.vo.ModelQueueStatVo;
import com.aid.aid.monitor.vo.ProviderQueueStatVo;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.modelhealth.service.ModelHealthQueryService;
import com.aid.rps.queue.QueueWaitingBreakdown;
import com.aid.rps.queue.TaskConcurrencyConfig;
import com.aid.rps.queue.TaskQueueService;
import com.aid.rps.queue.TaskSlotManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
/**
 * AI 模型排队 / 并发实时监控聚合服务（只读）。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelQueueMonitorService
{
    /** 实时快照缓存 TTL（毫秒）：后台多页面轮询合并到一次真实计算 */
    private static final long SNAPSHOT_TTL_MS = 2000L;

    /** 使用频繁度聚合缓存 TTL（毫秒）：独立长缓存，降低对业务表的扫描频率 */
    private static final long USAGE_TTL_MS = 60_000L;

    /** 等待队列单次扫描上限：极长队列时只统计队首这么多条，保护监控自身性能 */
    private static final int WAIT_SCAN_LIMIT = 2000;

    /** 使用频繁度统计窗口（小时） */
    private static final int USAGE_WINDOW_HOURS = 24;

    private final IAidAiModelService aidAiModelService;
    private final IAidAiProviderService aidAiProviderService;
    private final IAidExtractTaskService extractTaskService;
    private final TaskSlotManager slotManager;
    private final TaskQueueService taskQueueService;
    private final TaskConcurrencyConfig concurrencyConfig;
    /** 模型健康总览（并入快照返回，内部带30秒Redis缓存） */
    private final ModelHealthQueryService modelHealthQueryService;
    private volatile ModelQueueSnapshotVo cachedSnapshot;
    private volatile long snapshotCacheTime = 0L;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private volatile Map<String, Long> cachedUsage = Collections.emptyMap();
    private volatile long usageCacheTime = 0L;
    private final AtomicBoolean usageRefreshing = new AtomicBoolean(false);

    /**
     * 获取实时监控快照（短 TTL 缓存）。
     * 缓存有效则直接返回；过期则尝试单飞刷新——抢到刷新权的线程计算新快照，
     * 其余线程返回上一份（或在首次无缓存时同步等待一次计算）。
     */
    public ModelQueueSnapshotVo getSnapshot()
    {
        long now = System.currentTimeMillis();
        ModelQueueSnapshotVo snapshot = cachedSnapshot;
        if (snapshot != null && now - snapshotCacheTime < SNAPSHOT_TTL_MS)
        {
            return snapshot;
        }
        // 过期：单飞刷新。抢到的线程刷新；没抢到的直接返回旧快照（避免惊群）
        if (refreshing.compareAndSet(false, true))
        {
            try
            {
                ModelQueueSnapshotVo fresh = buildSnapshot();
                cachedSnapshot = fresh;
                snapshotCacheTime = System.currentTimeMillis();
                return fresh;
            }
            catch (Exception e)
            {
                log.error("构建模型排队监控快照失败", e);
                // 失败时回退旧快照；首次失败则返回一个空壳，避免前端拿到 null
                return snapshot != null ? snapshot : emptySnapshot();
            }
            finally
            {
                refreshing.set(false);
            }
        }
        // 没抢到刷新权
        if (snapshot != null)
        {
            return snapshot;
        }
        // 首次且并发：同步等待抢到锁的线程算完（最多自旋很短时间）
        for (int i = 0; i < 50 && cachedSnapshot == null; i++)
        {
            try { Thread.sleep(20L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        return cachedSnapshot != null ? cachedSnapshot : emptySnapshot();
    }

    private ModelQueueSnapshotVo emptySnapshot()
    {
        ModelQueueSnapshotVo vo = new ModelQueueSnapshotVo();
        vo.setGeneratedAt(System.currentTimeMillis());
        vo.setModels(Collections.emptyList());
        vo.setProviders(Collections.emptyList());
        vo.setUsageWindowHours(USAGE_WINDOW_HOURS);
        return vo;
    }

    /**
     * 真正构建一份快照（仅在缓存过期且抢到单飞权时调用）。
     */
    private ModelQueueSnapshotVo buildSnapshot()
    {
        long now = System.currentTimeMillis();

        List<AidAiModel> models = aidAiModelService.list(
                Wrappers.<AidAiModel>lambdaQuery()
                        .select(AidAiModel::getId, AidAiModel::getProviderId, AidAiModel::getModelCode,
                                AidAiModel::getRealModelCode, AidAiModel::getModelName, AidAiModel::getModelType,
                                AidAiModel::getGenerateMode, AidAiModel::getStatus,
                                AidAiModel::getScheduleStrategyJson));

        List<AidAiProvider> providers = aidAiProviderService.list(
                Wrappers.<AidAiProvider>lambdaQuery()
                        .select(AidAiProvider::getId, AidAiProvider::getProviderName,
                                AidAiProvider::getStatus, AidAiProvider::getScheduleStrategyJson));
        Map<Long, AidAiProvider> providerMap = new HashMap<>();
        for (AidAiProvider p : providers)
        {
            providerMap.put(p.getId(), p);
        }

        QueueWaitingBreakdown breakdown = taskQueueService.getWaitingBreakdown(WAIT_SCAN_LIMIT);
        Map<String, Integer> waitingByModel = breakdown.getWaitingByModel();
        Map<String, Integer> waitingByProvider = breakdown.getWaitingByProvider();

        Map<String, Long> usageMap = getUsageMap();

        List<String> modelCodes = new ArrayList<>(models.size());
        for (AidAiModel m : models)
        {
            modelCodes.add(m.getModelCode());
        }
        Map<String, Long> modelOccupied = slotManager.getModelOccupiedBatch(modelCodes);
        List<Long> providerIds = new ArrayList<>(providers.size());
        for (AidAiProvider p : providers)
        {
            providerIds.add(p.getId());
        }
        Map<Long, Long> providerOccupied = slotManager.getProviderOccupiedBatch(providerIds);

        List<ModelQueueStatVo> modelRows = new ArrayList<>(models.size());
        Map<Long, Integer> providerModelCount = new HashMap<>();
        for (AidAiModel m : models)
        {
            ModelQueueStatVo row = new ModelQueueStatVo();
            row.setId(m.getId());
            row.setModelCode(m.getModelCode());
            row.setModelName(m.getModelName());
            row.setRealModelCode(m.getRealModelCode());
            row.setModelType(m.getModelType());
            row.setGenerateMode(m.getGenerateMode());
            row.setProviderId(m.getProviderId());
            row.setStatus(m.getStatus());
            AidAiProvider p = m.getProviderId() == null ? null : providerMap.get(m.getProviderId());
            row.setProviderName(p != null ? p.getProviderName() : null);

            int limit = TaskConcurrencyConfig.parseMaxConcurrencyFromJson(m.getScheduleStrategyJson());
            boolean limited = limit != TaskConcurrencyConfig.UNLIMITED;
            row.setLimited(limited);
            row.setConcurrencyLimit(limited ? limit : null);

            long running = modelOccupied.getOrDefault(m.getModelCode(), 0L);
            row.setRunning(running);
            long waiting = waitingByModel == null ? 0 : waitingByModel.getOrDefault(m.getModelCode(), 0);
            row.setWaiting(waiting);
            if (limited && limit > 0)
            {
                int pct = (int) Math.min(100L, Math.round(running * 100.0 / limit));
                row.setUsagePercent(pct);
                row.setSaturated(running >= limit);
            }
            row.setRecentUsage(usageMap.get(m.getModelCode()));

            modelRows.add(row);
            if (m.getProviderId() != null)
            {
                providerModelCount.merge(m.getProviderId(), 1, Integer::sum);
            }
        }
        // 排序：先饱和的、再按排队多、再按运行多
        modelRows.sort(Comparator
                .comparing(ModelQueueStatVo::isSaturated).reversed()
                .thenComparing(Comparator.comparingLong(ModelQueueStatVo::getWaiting).reversed())
                .thenComparing(Comparator.comparingLong(ModelQueueStatVo::getRunning).reversed()));

        List<ProviderQueueStatVo> providerRows = new ArrayList<>(providers.size());
        for (AidAiProvider p : providers)
        {
            ProviderQueueStatVo row = new ProviderQueueStatVo();
            row.setProviderId(p.getId());
            row.setProviderName(p.getProviderName());
            row.setStatus(p.getStatus());
            int limit = TaskConcurrencyConfig.parseMaxConcurrencyFromJson(p.getScheduleStrategyJson());
            boolean limited = limit != TaskConcurrencyConfig.UNLIMITED;
            row.setLimited(limited);
            row.setConcurrencyLimit(limited ? limit : null);
            long running = providerOccupied.getOrDefault(p.getId(), 0L);
            row.setRunning(running);
            long waiting = waitingByProvider == null ? 0
                    : waitingByProvider.getOrDefault(String.valueOf(p.getId()), 0);
            row.setWaiting(waiting);
            if (limited && limit > 0)
            {
                int pct = (int) Math.min(100L, Math.round(running * 100.0 / limit));
                row.setUsagePercent(pct);
                row.setSaturated(running >= limit);
            }
            row.setModelCount(providerModelCount.getOrDefault(p.getId(), 0));
            providerRows.add(row);
        }
        providerRows.sort(Comparator
                .comparing(ProviderQueueStatVo::isSaturated).reversed()
                .thenComparing(Comparator.comparingLong(ProviderQueueStatVo::getWaiting).reversed())
                .thenComparing(Comparator.comparingLong(ProviderQueueStatVo::getRunning).reversed()));

        int globalLimit = concurrencyConfig.getGlobalLimit();
        long globalRunning = slotManager.getGlobalOccupied();

        ModelQueueSnapshotVo vo = new ModelQueueSnapshotVo();
        vo.setGeneratedAt(now);
        vo.setGlobalLimit(globalLimit);
        vo.setGlobalRunning(globalRunning);
        vo.setGlobalUsagePercent(globalLimit > 0
                ? (int) Math.min(100L, Math.round(globalRunning * 100.0 / globalLimit)) : 0);
        vo.setTotalWaiting(breakdown.getTotalWaiting());
        vo.setScannedWaiting(breakdown.getScanned());
        // 截断判定：扫描窗口确实被打满（membersSize 达上限）且仍有更多排队，才算「分模型为抽样」。
        // 不能用 scanned < totalWaiting 判断——二者是两次独立 Redis 调用，且 ctx 缺失会让 scanned 偏小，
        // 队列远未到上限时也会误报「采样统计」。
        vo.setWaitingTruncated(breakdown.getMembersSize() >= WAIT_SCAN_LIMIT
                && breakdown.getTotalWaiting() > breakdown.getMembersSize());
        // 未归属服务商（providerId 为空）的排队条数，供前端解释「各服务商排队之和 < 总排队」
        long unassigned = 0L;
        if (waitingByProvider != null)
        {
            unassigned = waitingByProvider.getOrDefault(com.aid.rps.queue.TaskQueueKeys.PROVIDER_NONE, 0);
        }
        vo.setUnassignedProviderWaiting(unassigned);
        vo.setUserDefaultLimit(concurrencyConfig.getUserLimit(null));
        vo.setUsageWindowHours(USAGE_WINDOW_HOURS);
        vo.setModels(modelRows);
        vo.setProviders(providerRows);
        // 模型健康总览并入快照（内部30秒Redis缓存 + 异常时返回null，不阻断排队监控）
        vo.setHealth(modelHealthQueryService.queryAdminOverview());
        return vo;
    }

    /**
     * 获取「近窗口期模型使用次数」映射（长缓存 + 单飞 + 失败容忍）。
     */
    private Map<String, Long> getUsageMap()
    {
        long now = System.currentTimeMillis();
        if (now - usageCacheTime < USAGE_TTL_MS)
        {
            return cachedUsage;
        }
        if (!usageRefreshing.compareAndSet(false, true))
        {
            // 别的线程在刷，直接用旧值（即便为空也无妨，下次再补）
            return cachedUsage;
        }
        try
        {
            Date windowStart = new Date(now - USAGE_WINDOW_HOURS * 3600_000L);
            // 仅做一次分组聚合，count(*) by model_code；window 走 create_time
            QueryWrapper<AidExtractTask> qw = new QueryWrapper<>();
            qw.select("model_code as modelCode", "count(*) as cnt");
            qw.ge("create_time", windowStart);
            qw.eq("del_flag", "0");
            qw.isNotNull("model_code");
            qw.groupBy("model_code");
            List<Map<String, Object>> rows = extractTaskService.getBaseMapper().selectMaps(qw);
            Map<String, Long> usage = new HashMap<>();
            if (rows != null)
            {
                for (Map<String, Object> r : rows)
                {
                    Object code = r.get("modelCode");
                    Object cnt = r.get("cnt");
                    if (code == null)
                    {
                        continue;
                    }
                    usage.put(String.valueOf(code), cnt == null ? 0L : Long.parseLong(String.valueOf(cnt)));
                }
            }
            cachedUsage = usage;
            usageCacheTime = System.currentTimeMillis();
        }
        catch (Exception e)
        {
            log.warn("聚合模型使用频繁度失败(监控降级，本次沿用旧值): {}", e.getMessage());
            // 失败也推进时间戳，避免短时间内反复重试拖累业务表
            usageCacheTime = System.currentTimeMillis();
        }
        finally
        {
            usageRefreshing.set(false);
        }
        return cachedUsage;
    }
}
