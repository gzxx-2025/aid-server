package com.aid.modelhealth.service.impl;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.AidModelHealthStat;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.aid.service.IAidModelHealthStatService;
import com.aid.common.core.redis.RedisCache;
import com.aid.modelhealth.dto.ModelHealthBoardRequest;
import com.aid.modelhealth.service.ModelHealthQueryService;
import com.aid.modelhealth.vo.ModelHealthBoardVO;
import com.aid.modelhealth.vo.ModelHealthBucketVO;
import com.aid.modelhealth.vo.ModelHealthSummaryVO;
import com.aid.modelhealth.vo.ModelHealthTimelineVO;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.aid.modelhealth.service.impl.ModelHealthRecorderImpl.BUCKET_MILLIS;

/**
 * 模型运行状态看板查询实现。
 *
 * <p>查询效率设计：统计表按「时间桶+模型」聚合，24小时窗口全量数据 = 模型数×48 行（百级），
 * 一条 bucket_time 范围索引查询即可取完，再在内存组装时间线；C端结果按查询条件做 60 秒 Redis
 * 缓存，状态页高频刷新不会反复打到数据库。</p>
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelHealthQueryServiceImpl implements ModelHealthQueryService {

    /** 统计窗口：24小时 */
    private static final long WINDOW_MILLIS = 24L * 60 * 60 * 1000;
    /** 一天毫秒数（多日可用率窗口计算） */
    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;
    /** 多日可用率窗口（天）：7/15/30 */
    private static final int WINDOW_7_DAYS = 7;
    private static final int WINDOW_15_DAYS = 15;
    private static final int WINDOW_30_DAYS = 30;
    /** 时间轴格数（24小时 / 30分钟桶） */
    private static final int BUCKET_COUNT = (int) (WINDOW_MILLIS / BUCKET_MILLIS);
    /** C端缓存键前缀 */
    private static final String CACHE_KEY_PREFIX = "model_health:board:";
    /** C端缓存时长（秒） */
    private static final int CACHE_SECONDS = 60;
    /** 后台监控页健康总览缓存键（并入排队监控快照，轮询频繁需短缓存保护DB） */
    private static final String ADMIN_OVERVIEW_CACHE_KEY = "model_health:admin_overview";
    /** 后台健康总览缓存时长（秒） */
    private static final int ADMIN_OVERVIEW_CACHE_SECONDS = 30;
    /** 后台健康总览单次最大时间线条数（全量返回，防御性上限） */
    private static final int ADMIN_OVERVIEW_MAX_LINES = 1000;
    /** 默认/上限分页参数 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    /** 启用状态与未删除标记 */
    private static final String STATUS_NORMAL = "0";
    private static final String DEL_FLAG_NORMAL = "0";
    /** 时间格状态 */
    private static final String STATUS_OPERATIONAL = "operational";
    private static final String STATUS_DEGRADED = "degraded";
    private static final String STATUS_ERROR = "error";
    private static final String STATUS_NONE = "none";

    private final IAidAiProviderService aidAiProviderService;
    private final IAidAiModelService aidAiModelService;
    private final IAidModelHealthStatService modelHealthStatService;
    private final RedisCache redisCache;

    @Override
    public ModelHealthBoardVO queryBoard(ModelHealthBoardRequest request, boolean adminView) {
        ModelHealthBoardRequest query = Objects.isNull(request) ? new ModelHealthBoardRequest() : request;
        int pageNum = Objects.isNull(query.getPageNum()) || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = Objects.isNull(query.getPageSize()) || query.getPageSize() < 1
                ? DEFAULT_PAGE_SIZE : Math.min(query.getPageSize(), MAX_PAGE_SIZE);
        String providerCode = StrUtil.trimToEmpty(query.getProviderCode());
        String modelType = StrUtil.trimToEmpty(query.getModelType()).toLowerCase();

        // C端走 60 秒短缓存：状态页高频刷新不反复打库
        String cacheKey = null;
        if (!adminView) {
            cacheKey = CACHE_KEY_PREFIX + providerCode + ":" + modelType + ":" + pageNum + ":" + pageSize;
            try {
                ModelHealthBoardVO cached = redisCache.getCacheObject(cacheKey);
                if (Objects.nonNull(cached)) {
                    return cached;
                }
            } catch (Exception e) {
                log.warn("模型状态看板读缓存失败(降级直查): err={}", e.getMessage());
            }
        }

        ModelHealthBoardVO board = buildBoard(providerCode, modelType, pageNum, pageSize, adminView);

        if (!adminView && Objects.nonNull(cacheKey)) {
            try {
                redisCache.setCacheObject(cacheKey, board, CACHE_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("模型状态看板写缓存失败(不影响返回): err={}", e.getMessage());
            }
        }
        return board;
    }

    @Override
    public ModelHealthBoardVO queryAdminOverview() {
        try {
            // 30秒短缓存：监控页 3~10 秒轮询一次，健康数据桶粒度为30分钟，无需每次实时计算
            try {
                ModelHealthBoardVO cached = redisCache.getCacheObject(ADMIN_OVERVIEW_CACHE_KEY);
                if (Objects.nonNull(cached)) {
                    return cached;
                }
            } catch (Exception e) {
                log.warn("后台健康总览读缓存失败(降级直查): err={}", e.getMessage());
            }

            // 全量模型（含停用）不分页 + 每格错误摘要
            ModelHealthBoardVO board = buildBoard("", "", 1, ADMIN_OVERVIEW_MAX_LINES, true);
            try {
                redisCache.setCacheObject(ADMIN_OVERVIEW_CACHE_KEY, board,
                        ADMIN_OVERVIEW_CACHE_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("后台健康总览写缓存失败(不影响返回): err={}", e.getMessage());
            }
            return board;
        } catch (Exception e) {
            // 健康总览仅为监控页增强数据：异常时返回 null，不阻断排队监控主数据
            log.error("查询后台健康总览失败: err={}", e.getMessage());
            return null;
        }
    }

    /**
     * 组装看板：服务商/模型基础信息 + 24小时统计行 → 每模型48格时间线。
     */
    private ModelHealthBoardVO buildBoard(String providerCode, String modelType,
                                          int pageNum, int pageSize, boolean adminView) {
        // 1) 服务商（C端仅启用；查询字段精简：新增使用字段时此处必须同步补充）
        List<AidAiProvider> providers = aidAiProviderService.list(Wrappers.<AidAiProvider>lambdaQuery()
                .select(AidAiProvider::getId, AidAiProvider::getProviderCode,
                        AidAiProvider::getProviderName, AidAiProvider::getStatus)
                .eq(AidAiProvider::getDelFlag, DEL_FLAG_NORMAL)
                .eq(!adminView, AidAiProvider::getStatus, STATUS_NORMAL)
                .eq(StrUtil.isNotBlank(providerCode), AidAiProvider::getProviderCode, providerCode));
        Map<Long, AidAiProvider> providerById = new LinkedHashMap<>();
        for (AidAiProvider provider : providers) {
            providerById.put(provider.getId(), provider);
        }

        // 2) 模型（C端仅启用「运行中」；查询字段精简：新增使用字段时此处必须同步补充）
        List<AidAiModel> models = CollectionUtil.isEmpty(providerById)
                ? new ArrayList<>()
                : aidAiModelService.list(Wrappers.<AidAiModel>lambdaQuery()
                        .select(AidAiModel::getId, AidAiModel::getProviderId, AidAiModel::getModelCode,
                                AidAiModel::getModelName, AidAiModel::getModelType, AidAiModel::getStatus,
                                AidAiModel::getPriority)
                        .eq(AidAiModel::getDelFlag, DEL_FLAG_NORMAL)
                        .eq(!adminView, AidAiModel::getStatus, STATUS_NORMAL)
                        .eq(StrUtil.isNotBlank(modelType), AidAiModel::getModelType, modelType)
                        .in(AidAiModel::getProviderId, providerById.keySet())
                        .orderByAsc(AidAiModel::getProviderId)
                        .orderByDesc(AidAiModel::getPriority)
                        .orderByAsc(AidAiModel::getId));

        // 3) 24小时统计行（bucket_time 范围索引一次取完，行数=模型数×48 上限，体量恒小）
        long nowMillis = System.currentTimeMillis();
        Date windowStart = new Date(alignBucket(nowMillis) - WINDOW_MILLIS + BUCKET_MILLIS);
        List<AidModelHealthStat> stats = modelHealthStatService.list(
                Wrappers.<AidModelHealthStat>lambdaQuery()
                        .select(AidModelHealthStat::getBucketTime, AidModelHealthStat::getModelCode,
                                AidModelHealthStat::getSuccessCount, AidModelHealthStat::getFailCount,
                                AidModelHealthStat::getTotalLatencyMs, AidModelHealthStat::getLastErrorMessage)
                        .ge(AidModelHealthStat::getBucketTime, windowStart));
        // modelCode → (bucketMillis → stat)
        Map<String, Map<Long, AidModelHealthStat>> statByModel = new LinkedHashMap<>();
        for (AidModelHealthStat stat : stats) {
            statByModel.computeIfAbsent(stat.getModelCode(), k -> new LinkedHashMap<>())
                    .put(stat.getBucketTime().getTime(), stat);
        }

        // 4) 后台监控附带多日聚合（7/15/30天可用率 + 7天平均延迟），SQL 分组求和一次取完
        Map<String, long[]> agg7d = adminView ? aggregateSince(new Date(nowMillis - WINDOW_7_DAYS * DAY_MILLIS)) : null;
        Map<String, long[]> agg15d = adminView ? aggregateSince(new Date(nowMillis - WINDOW_15_DAYS * DAY_MILLIS)) : null;
        Map<String, long[]> agg30d = adminView ? aggregateSince(new Date(nowMillis - WINDOW_30_DAYS * DAY_MILLIS)) : null;

        // 5) 组装每个模型的时间线
        List<ModelHealthTimelineVO> timelines = new ArrayList<>();
        for (AidAiModel model : models) {
            AidAiProvider provider = providerById.get(model.getProviderId());
            if (Objects.isNull(provider)) {
                continue;
            }
            ModelHealthTimelineVO timeline = buildTimeline(model, provider,
                    statByModel.get(model.getModelCode()), nowMillis, adminView);
            if (adminView) {
                fillMultiDayMetrics(timeline, agg7d, agg15d, agg30d);
            }
            timelines.add(timeline);
        }

        // 6) 全量汇总（不受分页影响）
        int operational = 0;
        int degraded = 0;
        int error = 0;
        int noData = 0;
        for (ModelHealthTimelineVO timeline : timelines) {
            if (STATUS_ERROR.equals(timeline.getLatestStatus())) {
                error++;
            } else if (STATUS_DEGRADED.equals(timeline.getLatestStatus())) {
                degraded++;
            } else if (STATUS_OPERATIONAL.equals(timeline.getLatestStatus())) {
                operational++;
            } else {
                noData++;
            }
        }

        // 7) 内存分页（数据集为百级，无需二次查库）
        int total = timelines.size();
        int fromIndex = Math.min((pageNum - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<ModelHealthTimelineVO> page = new ArrayList<>(timelines.subList(fromIndex, toIndex));

        ModelHealthBoardVO board = new ModelHealthBoardVO();
        board.setTotal(total);
        board.setPageNum(pageNum);
        board.setPageSize(pageSize);
        board.setTrendPeriod("24h");
        board.setLastUpdated(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        String overallStatus = error > 0
                ? STATUS_ERROR
                : (degraded > 0 ? STATUS_DEGRADED : (operational > 0 ? STATUS_OPERATIONAL : STATUS_NONE));
        board.setOverallStatus(overallStatus);
        // 横幅文案：前端「所有服务运行正常」概览图直接展示
        board.setOverallStatusText(overallStatusText(overallStatus));
        board.setOperationalCount(operational);
        board.setDegradedCount(degraded);
        board.setErrorCount(error);
        board.setNoDataCount(noData);
        board.setProviderTimelines(page);
        return board;
    }

    /** 整体状态 → 横幅文案（所有服务运行正常 / 部分服务降级 / 部分服务异常） */
    private String overallStatusText(String overallStatus) {
        if (STATUS_ERROR.equals(overallStatus)) {
            return "部分服务异常";
        }
        if (STATUS_DEGRADED.equals(overallStatus)) {
            return "部分服务降级";
        }
        if (STATUS_NONE.equals(overallStatus)) {
            return "暂无调用数据";
        }
        return "所有服务运行正常";
    }

    /**
     * 组装单个模型的48格时间线与汇总指标（桶聚合逻辑复用 {@link #buildSummary}）。
     */
    private ModelHealthTimelineVO buildTimeline(AidAiModel model, AidAiProvider provider,
                                                Map<Long, AidModelHealthStat> bucketStats,
                                                long nowMillis, boolean adminView) {
        ModelHealthSummaryVO summary = buildSummary(bucketStats, nowMillis, adminView);

        ModelHealthTimelineVO timeline = new ModelHealthTimelineVO();
        timeline.setProviderId(provider.getId());
        timeline.setProviderCode(provider.getProviderCode());
        timeline.setProviderName(provider.getProviderName());
        timeline.setModelCode(model.getModelCode());
        timeline.setModelName(model.getModelName());
        timeline.setModelType(model.getModelType());
        timeline.setEnabled(Objects.equals(STATUS_NORMAL, model.getStatus()));
        timeline.setLatestStatus(summary.getLatestStatus());
        timeline.setTotalChecks(summary.getTotalChecks());
        timeline.setSuccessCount(summary.getSuccessCount());
        timeline.setFailCount(summary.getFailCount());
        timeline.setAvailabilityPct(summary.getAvailabilityPct());
        timeline.setAvgLatencyMs(summary.getAvgLatencyMs());
        timeline.setLatestLatencyMs(summary.getLatestLatencyMs());
        timeline.setItems(summary.getItems());
        return timeline;
    }

    /**
     * 填充多日聚合指标（7/15/30天可用率 + 7天平均延迟），无调用窗口保持 null。
     */
    private void fillMultiDayMetrics(ModelHealthTimelineVO timeline, Map<String, long[]> agg7d,
                                     Map<String, long[]> agg15d, Map<String, long[]> agg30d) {
        String modelCode = timeline.getModelCode();
        long[] sum7d = agg7d == null ? null : agg7d.get(modelCode);
        long[] sum15d = agg15d == null ? null : agg15d.get(modelCode);
        long[] sum30d = agg30d == null ? null : agg30d.get(modelCode);
        timeline.setAvailability7dPct(availabilityPct(sum7d));
        timeline.setAvailability15dPct(availabilityPct(sum15d));
        timeline.setAvailability30dPct(availabilityPct(sum30d));
        // 7天平均延迟 = 7天成功任务总耗时 / 成功次数
        if (Objects.nonNull(sum7d) && sum7d[0] > 0) {
            timeline.setAvgLatency7dMs(sum7d[2] / sum7d[0]);
        }
    }

    /** 聚合数组 → 可用率百分比（两位小数），无调用返回 null */
    private Double availabilityPct(long[] sums) {
        if (Objects.isNull(sums)) {
            return null;
        }
        long total = sums[0] + sums[1];
        if (total <= 0) {
            return null;
        }
        return Math.round(sums[0] * 10000.0 / total) / 100.0;
    }

    /**
     * 按模型分组求和指定时间之后的健康统计（SQL 聚合，返回行数=模型数，体量恒小）。
     *
     * @param since 统计起点
     * @return modelCode → [成功数, 失败数, 成功总耗时ms]
     */
    private Map<String, long[]> aggregateSince(Date since) {
        // 聚合查询：只取模型编码与三项求和值
        QueryWrapper<AidModelHealthStat> wrapper = new QueryWrapper<>();
        wrapper.select("model_code AS modelCode",
                        "IFNULL(SUM(success_count),0) AS successSum",
                        "IFNULL(SUM(fail_count),0) AS failSum",
                        "IFNULL(SUM(total_latency_ms),0) AS latencySum")
                .ge("bucket_time", since)
                .groupBy("model_code");
        Map<String, long[]> result = new LinkedHashMap<>();
        for (Map<String, Object> row : modelHealthStatService.listMaps(wrapper)) {
            String modelCode = Objects.isNull(row.get("modelCode")) ? null : String.valueOf(row.get("modelCode"));
            if (StrUtil.isBlank(modelCode)) {
                continue;
            }
            result.put(modelCode, new long[] {
                    numberValue(row.get("successSum")),
                    numberValue(row.get("failSum")),
                    numberValue(row.get("latencySum"))
            });
        }
        return result;
    }

    /** 聚合值安全转 long（SUM 在不同驱动下可能返回 BigDecimal/Long） */
    private long numberValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    /**
     * 桶聚合核心：24小时统计行 → 48格时间轴 + 汇总指标（可用率/计数/平均耗时/最新状态）。
     *
     * @param bucketStats 该模型的桶统计（bucketMillis → stat），可为 null（窗口内无调用）
     * @param nowMillis   当前时间戳（同一次请求内保持一致，保证各模型时间轴对齐）
     * @param adminView   true=附带每格错误摘要（仅后台管理看板使用）
     * @return 健康摘要（永不为 null，时间轴固定48格）
     */
    private ModelHealthSummaryVO buildSummary(Map<Long, AidModelHealthStat> bucketStats,
                                              long nowMillis, boolean adminView) {
        SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        long currentBucket = alignBucket(nowMillis);
        long firstBucket = currentBucket - (long) (BUCKET_COUNT - 1) * BUCKET_MILLIS;

        List<ModelHealthBucketVO> items = new ArrayList<>(BUCKET_COUNT);
        int totalSuccess = 0;
        int totalFail = 0;
        long totalLatency = 0;
        String latestStatus = null;
        Long latestLatency = null;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            long bucketMillis = firstBucket + (long) i * BUCKET_MILLIS;
            AidModelHealthStat stat = bucketStats == null ? null : bucketStats.get(bucketMillis);
            ModelHealthBucketVO item = new ModelHealthBucketVO();
            item.setBucketTime(timeFormat.format(new Date(bucketMillis)));
            if (Objects.isNull(stat)) {
                item.setStatus(STATUS_NONE);
                item.setSuccessCount(0);
                item.setFailCount(0);
            } else {
                int success = stat.getSuccessCount() == null ? 0 : stat.getSuccessCount();
                int fail = stat.getFailCount() == null ? 0 : stat.getFailCount();
                long latency = stat.getTotalLatencyMs() == null ? 0 : stat.getTotalLatencyMs();
                item.setStatus(bucketStatus(success, fail));
                item.setSuccessCount(success);
                item.setFailCount(fail);
                if (success > 0) {
                    item.setAvgLatencyMs(latency / success);
                }
                if (adminView && fail > 0) {
                    item.setErrorMessage(stat.getLastErrorMessage());
                }
                totalSuccess += success;
                totalFail += fail;
                totalLatency += latency;
                if (success + fail > 0) {
                    latestStatus = item.getStatus();
                    // 最新延迟跟随最近一个有成功调用的时间格
                    if (Objects.nonNull(item.getAvgLatencyMs())) {
                        latestLatency = item.getAvgLatencyMs();
                    }
                }
            }
            items.add(item);
        }

        ModelHealthSummaryVO summary = new ModelHealthSummaryVO();
        // 窗口内无任何调用时保持“无数据”，不得计入正常模型。
        summary.setLatestStatus(StrUtil.blankToDefault(latestStatus, STATUS_NONE));
        int totalChecks = totalSuccess + totalFail;
        summary.setTotalChecks(totalChecks);
        summary.setSuccessCount(totalSuccess);
        summary.setFailCount(totalFail);
        if (totalChecks > 0) {
            summary.setAvailabilityPct(Math.round(totalSuccess * 10000.0 / totalChecks) / 100.0);
        }
        if (totalSuccess > 0) {
            summary.setAvgLatencyMs(totalLatency / totalSuccess);
        }
        summary.setLatestLatencyMs(latestLatency);
        summary.setItems(items);
        return summary;
    }

    /** 单格状态判定：无失败=正常；有成功有失败=降级；全失败=异常 */
    private String bucketStatus(int success, int fail) {
        if (fail <= 0) {
            return STATUS_OPERATIONAL;
        }
        return success > 0 ? STATUS_DEGRADED : STATUS_ERROR;
    }

    /** 时间戳按30分钟对齐到桶起点 */
    private long alignBucket(long millis) {
        return millis - (millis % BUCKET_MILLIS);
    }
}
