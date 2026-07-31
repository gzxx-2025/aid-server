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
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.monitor.vo.ModelQueueSnapshotVo;
import com.aid.aid.monitor.vo.ModelQueueStatVo;
import com.aid.aid.monitor.vo.ProviderQueueStatVo;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidMediaTaskService;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.service.MediaConcurrencyLimiter;
import com.aid.modelhealth.service.ModelHealthQueryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
/**
 * AI 模型上游请求并发 / 排队实时监控聚合服务（只读）。
 *
 * 口径统一为「上游请求」：running 取 {@link MediaConcurrencyLimiter} 的在途计数，
 * waiting 取 aid_media_task 中 QUEUED 的条数，上限取 schedule_strategy_json.maxConcurrency，
 * 与真正执行并发准入的那一套完全一致，避免监控数字与实际限流口径不一。
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

    /** 使用频繁度统计窗口（小时） */
    private static final int USAGE_WINDOW_HOURS = 24;

    private final IAidAiModelService aidAiModelService;
    private final IAidAiProviderService aidAiProviderService;
    private final IAidExtractTaskService extractTaskService;
    private final IAidMediaTaskService mediaTaskService;
    private final MediaConcurrencyLimiter concurrencyLimiter;
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

        // 排队条数：媒体层可精确统计（QUEUED 的 aid_media_task），无扫描窗口截断
        Map<String, Integer> waitingByModel = countQueuedMediaTaskByModel();
        Map<Long, Integer> waitingByProvider = new HashMap<>();
        // 总排队取原始聚合之和，覆盖不在 aid_ai_model 的 model_name（如 COMPOSE 合成任务）
        long totalWaiting = 0L;
        for (Integer c : waitingByModel.values())
        {
            totalWaiting += (c == null ? 0 : c);
        }
        // 在册模型承接的排队数，用于反推「未归属」部分
        long assignedWaiting = 0L;

        Map<String, Long> usageMap = getUsageMap();

        List<String> modelCodes = new ArrayList<>(models.size());
        for (AidAiModel m : models)
        {
            modelCodes.add(m.getModelCode());
        }
        Map<String, Integer> modelOccupied = concurrencyLimiter.getModelCountBatch(modelCodes);
        List<Long> providerIds = new ArrayList<>(providers.size());
        for (AidAiProvider p : providers)
        {
            providerIds.add(p.getId());
        }
        Map<Long, Integer> providerOccupied = concurrencyLimiter.getProviderCountBatch(providerIds);

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

            int limit = MediaConcurrencyLimiter.parseModelConcurrency(m.getScheduleStrategyJson());
            boolean limited = limit != MediaConcurrencyLimiter.UNLIMITED;
            row.setLimited(limited);
            row.setConcurrencyLimit(limited ? limit : null);

            long running = modelOccupied.getOrDefault(m.getModelCode(), 0);
            row.setRunning(running);
            long waiting = waitingByModel.getOrDefault(m.getModelCode(), 0);
            row.setWaiting(waiting);
            if (limited && limit > 0)
            {
                int pct = (int) Math.min(100L, Math.round(running * 100.0 / limit));
                row.setUsagePercent(pct);
                row.setSaturated(running >= limit);
            }
            row.setRecentUsage(usageMap.get(m.getModelCode()));

            modelRows.add(row);
            assignedWaiting += waiting;
            if (m.getProviderId() != null)
            {
                providerModelCount.merge(m.getProviderId(), 1, Integer::sum);
                // 供应商排队数 = 其下所有模型排队数之和（模型→供应商唯一归属，不会重复计）
                waitingByProvider.merge(m.getProviderId(), (int) waiting, Integer::sum);
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
            int limit = MediaConcurrencyLimiter.parseProviderConcurrency(p.getScheduleStrategyJson());
            boolean limited = limit != MediaConcurrencyLimiter.UNLIMITED;
            row.setLimited(limited);
            row.setConcurrencyLimit(limited ? limit : null);
            long running = providerOccupied.getOrDefault(p.getId(), 0);
            row.setRunning(running);
            long waiting = waitingByProvider.getOrDefault(p.getId(), 0);
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

        int globalLimit = concurrencyLimiter.getGlobalLimit();
        long globalRunning = concurrencyLimiter.getGlobalCount();

        ModelQueueSnapshotVo vo = new ModelQueueSnapshotVo();
        vo.setGeneratedAt(now);
        vo.setGlobalLimit(globalLimit);
        vo.setGlobalRunning(globalRunning);
        vo.setGlobalUsagePercent(globalLimit > 0
                ? (int) Math.min(100L, Math.round(globalRunning * 100.0 / globalLimit)) : 0);
        // 媒体层按 model_name 精确聚合，无扫描窗口，无抽样截断
        vo.setTotalWaiting(totalWaiting);
        // 未归属在册模型（model_name 不在 aid_ai_model）的排队条数，
        // 供前端解释「各模型排队之和 < 总排队」，如 COMPOSE 合成任务
        vo.setUnassignedProviderWaiting(Math.max(totalWaiting - assignedWaiting, 0L));
        vo.setUserDefaultLimit(concurrencyLimiter.getUserLimitValue());
        vo.setUsageWindowHours(USAGE_WINDOW_HOURS);
        vo.setModels(modelRows);
        vo.setProviders(providerRows);
        // 模型健康总览并入快照（内部30秒Redis缓存 + 异常时返回null，不阻断排队监控）
        vo.setHealth(modelHealthQueryService.queryAdminOverview());
        return vo;
    }

    /**
     * 统计当前在媒体层排队（QUEUED）的任务数，按模型编码分组。
     * QUEUED 表示已落库但因某一维并发已满尚未提交上游，正是「等待上游名额」的真实口径。
     *
     * @return modelName -&gt; 排队条数；查询异常时返回空 Map（监控降级，不阻断快照）
     */
    private Map<String, Integer> countQueuedMediaTaskByModel()
    {
        Map<String, Integer> result = new HashMap<>();
        try
        {
            // 特别标注：只做一次分组聚合 count(*) by model_name，不取明细行
            QueryWrapper<AidMediaTask> qw = new QueryWrapper<>();
            qw.select("model_name as modelName", "count(*) as cnt");
            qw.eq("status", MediaTaskStatus.QUEUED.name());
            qw.isNotNull("model_name");
            qw.groupBy("model_name");
            List<Map<String, Object>> rows = mediaTaskService.getBaseMapper().selectMaps(qw);
            if (rows == null)
            {
                return result;
            }
            for (Map<String, Object> r : rows)
            {
                Object name = r.get("modelName");
                Object cnt = r.get("cnt");
                if (name == null)
                {
                    continue;
                }
                result.put(String.valueOf(name), cnt == null ? 0 : Integer.parseInt(String.valueOf(cnt)));
            }
        }
        catch (Exception e)
        {
            log.warn("统计媒体任务排队数失败(监控降级，按空处理): {}", e.getMessage());
        }
        return result;
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
