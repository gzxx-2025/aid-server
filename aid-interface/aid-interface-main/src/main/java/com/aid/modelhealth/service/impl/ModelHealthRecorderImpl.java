package com.aid.modelhealth.service.impl;

import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.aid.service.IAidModelHealthStatService;
import com.aid.aid.domain.AidModelHealthStat;
import com.aid.modelhealth.service.ModelHealthRecorder;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 模型健康采集器实现：按「30分钟时间桶 + 模型」把成功/失败结果累加进 aid_model_health_stat。
 *
 * <p>写入方式为「先原子自增 UPDATE，未命中再 INSERT，插入撞唯一键则重试 UPDATE」，
 * 并发安全且每次调用只有 1~2 条轻量 SQL；全程吞异常，绝不影响主业务流程。</p>
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelHealthRecorderImpl implements ModelHealthRecorder {

    /** 时间桶大小：30分钟（与状态页时间轴粒度一致，24小时=48格） */
    public static final long BUCKET_MILLIS = 30L * 60 * 1000;

    /** 错误信息落库截断长度 */
    private static final int ERROR_MAX_LEN = 200;

    /** 模型→服务商编码解析缓存 TTL（毫秒），避免每次采集都查模型表 */
    private static final long PROVIDER_CACHE_TTL_MILLIS = 10L * 60 * 1000;

    private final IAidModelHealthStatService modelHealthStatService;
    private final IAidAiModelService aidAiModelService;
    private final IAidAiProviderService aidAiProviderService;

    /** 模型编码 → [服务商编码, 缓存过期时间戳] 的进程内缓存 */
    private final Map<String, ProviderCacheEntry> providerCache = new ConcurrentHashMap<>();

    @Override
    public void recordSuccess(String modelCode, String mediaType, Long latencyMs) {
        record(modelCode, mediaType, true, latencyMs, null);
    }

    @Override
    public void recordFailure(String modelCode, String mediaType, String errorMessage) {
        record(modelCode, mediaType, false, null, errorMessage);
    }

    /**
     * 统一累加入口：采集失败只打日志，绝不向调用方抛异常。
     */
    private void record(String modelCode, String mediaType, boolean success, Long latencyMs, String errorMessage) {
        try {
            if (StrUtil.isBlank(modelCode)) {
                return;
            }
            Date bucketTime = alignBucket(System.currentTimeMillis());
            String providerCode = resolveProviderCode(modelCode);
            // 先尝试原子自增（绝大多数情况命中已存在的桶）
            if (incrementBucket(bucketTime, modelCode, success, latencyMs, errorMessage) > 0) {
                return;
            }
            // 桶不存在：插入新桶；并发下撞唯一键则重试一次自增
            try {
                AidModelHealthStat stat = new AidModelHealthStat();
                stat.setBucketTime(bucketTime);
                stat.setProviderCode(StrUtil.nullToEmpty(providerCode));
                stat.setModelCode(modelCode);
                stat.setMediaType(mediaType);
                stat.setSuccessCount(success ? 1 : 0);
                stat.setFailCount(success ? 0 : 1);
                stat.setTotalLatencyMs(success && latencyMs != null && latencyMs > 0 ? latencyMs : 0L);
                if (!success) {
                    stat.setLastErrorMessage(cutError(errorMessage));
                    stat.setLastErrorTime(new Date());
                }
                stat.setCreateTime(new Date());
                stat.setUpdateTime(new Date());
                modelHealthStatService.save(stat);
            } catch (Exception dupEx) {
                // 并发插入撞唯一键：回退为自增
                incrementBucket(bucketTime, modelCode, success, latencyMs, errorMessage);
            }
        } catch (Exception e) {
            // 采集属于旁路观测，任何异常都不允许影响主流程
            log.warn("模型健康采集失败(不影响业务): modelCode={}, success={}, err={}",
                    modelCode, success, e.getMessage());
        }
    }

    /**
     * 对既有时间桶做原子自增。
     *
     * @return 命中行数（0=桶不存在）
     */
    private int incrementBucket(Date bucketTime, String modelCode, boolean success,
                                Long latencyMs, String errorMessage) {
        LambdaUpdateWrapper<AidModelHealthStat> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(AidModelHealthStat::getBucketTime, bucketTime);
        wrapper.eq(AidModelHealthStat::getModelCode, modelCode);
        if (success) {
            wrapper.setSql("success_count = success_count + 1");
            if (latencyMs != null && latencyMs > 0) {
                // latencyMs 为数值类型，不存在注入风险
                wrapper.setSql("total_latency_ms = total_latency_ms + " + latencyMs);
            }
        } else {
            wrapper.setSql("fail_count = fail_count + 1");
            wrapper.set(AidModelHealthStat::getLastErrorMessage, cutError(errorMessage));
            wrapper.set(AidModelHealthStat::getLastErrorTime, new Date());
        }
        wrapper.set(AidModelHealthStat::getUpdateTime, new Date());
        return modelHealthStatService.getBaseMapper().update(null, wrapper);
    }

    /** 时间戳按30分钟对齐到桶起点 */
    private Date alignBucket(long millis) {
        return new Date(millis - (millis % BUCKET_MILLIS));
    }

    /** 错误信息截断（存库仅留摘要，全文在 aid_media_task 里可查） */
    private String cutError(String errorMessage) {
        return StrUtil.sub(StrUtil.nullToEmpty(errorMessage), 0, ERROR_MAX_LEN);
    }

    /**
     * 解析模型所属服务商编码（带10分钟进程内缓存；不过滤模型启停状态，停用模型也能归到正确服务商）。
     */
    private String resolveProviderCode(String modelCode) {
        long now = System.currentTimeMillis();
        ProviderCacheEntry cached = providerCache.get(modelCode);
        if (Objects.nonNull(cached) && now < cached.expireAt) {
            return cached.providerCode;
        }
        String providerCode = "";
        try {
            // 查询字段精简：仅需服务商ID（新增使用字段时此处必须同步补充）
            AidAiModel model = aidAiModelService.getOne(Wrappers.<AidAiModel>lambdaQuery()
                    .select(AidAiModel::getId, AidAiModel::getProviderId)
                    .eq(AidAiModel::getModelCode, modelCode)
                    .eq(AidAiModel::getDelFlag, "0")
                    .last("LIMIT 1"), false);
            if (Objects.nonNull(model) && Objects.nonNull(model.getProviderId())) {
                // 查询字段精简：仅需服务商编码（新增使用字段时此处必须同步补充）
                AidAiProvider provider = aidAiProviderService.getOne(Wrappers.<AidAiProvider>lambdaQuery()
                        .select(AidAiProvider::getId, AidAiProvider::getProviderCode)
                        .eq(AidAiProvider::getId, model.getProviderId())
                        .last("LIMIT 1"), false);
                if (Objects.nonNull(provider)) {
                    providerCode = StrUtil.nullToEmpty(provider.getProviderCode());
                }
            }
        } catch (Exception e) {
            log.warn("模型健康采集解析服务商失败: modelCode={}, err={}", modelCode, e.getMessage());
        }
        providerCache.put(modelCode, new ProviderCacheEntry(providerCode, now + PROVIDER_CACHE_TTL_MILLIS));
        return providerCode;
    }

    /** 服务商解析缓存条目 */
    private record ProviderCacheEntry(String providerCode, long expireAt) {
    }
}
