package com.aid.media.service;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.common.exception.ServiceException;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 并发上限层级校验器：保存服务商 / 模型时强制 全局 &ge; 供应商 &ge; 模型 的包含关系。
 *
 * 上限统一取自 schedule_strategy_json 的 maxConcurrency（唯一键名，见
 * {@link MediaConcurrencyLimiter#parseMaxConcurrency}），缺失或 &le;0 表示该层不限，
 * 此时只受上层约束、不参与本校验。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConcurrencyConfigValidator
{
    /** 模型并发超出所属供应商上限 */
    private static final String ERR_MODEL_OVER_PROVIDER = "并发超供应商上限";

    /** 并发超出平台全局上限 */
    private static final String ERR_OVER_GLOBAL = "并发超全局上限";

    /** 供应商并发小于其下模型已配上限 */
    private static final String ERR_PROVIDER_UNDER_MODEL = "并发小于模型上限";

    private final MediaConcurrencyLimiter concurrencyLimiter;
    private final IAidAiModelService aidAiModelService;
    private final IAidAiProviderService aidAiProviderService;

    /**
     * 校验模型保存：模型上限不得超过所属供应商上限，也不得超过全局上限。
     *
     * @param model 待保存模型（新增 / 修改均适用）
     */
    public void validateModelSave(AidAiModel model)
    {
        if (Objects.isNull(model))
        {
            return;
        }
        int modelLimit = MediaConcurrencyLimiter.parseModelConcurrency(model.getScheduleStrategyJson());
        if (modelLimit == MediaConcurrencyLimiter.UNLIMITED)
        {
            // 未配 = 不限，仅受上层约束，无需校验
            return;
        }
        int globalLimit = concurrencyLimiter.getGlobalLimit();
        if (modelLimit > globalLimit)
        {
            log.error("模型并发超全局上限: modelCode={}, modelLimit={}, globalLimit={}",
                    model.getModelCode(), modelLimit, globalLimit);
            throw new ServiceException(ERR_OVER_GLOBAL);
        }
        int providerLimit = resolveProviderLimit(model.getProviderId());
        if (providerLimit != MediaConcurrencyLimiter.UNLIMITED && modelLimit > providerLimit)
        {
            log.error("模型并发超供应商上限: modelCode={}, providerId={}, modelLimit={}, providerLimit={}",
                    model.getModelCode(), model.getProviderId(), modelLimit, providerLimit);
            throw new ServiceException(ERR_MODEL_OVER_PROVIDER);
        }
    }

    /**
     * 校验供应商保存：供应商上限不得超过全局上限，也不得小于其下任一已配模型的上限
     * （否则模型维度配置将永远无法达到，属于自相矛盾的配置）。
     *
     * @param provider 待保存供应商（新增 / 修改均适用）
     */
    public void validateProviderSave(AidAiProvider provider)
    {
        if (Objects.isNull(provider))
        {
            return;
        }
        int providerLimit = MediaConcurrencyLimiter.parseProviderConcurrency(provider.getScheduleStrategyJson());
        if (providerLimit == MediaConcurrencyLimiter.UNLIMITED)
        {
            // 未配 = 不限，仅受全局约束，无需校验
            return;
        }
        int globalLimit = concurrencyLimiter.getGlobalLimit();
        if (providerLimit > globalLimit)
        {
            log.error("供应商并发超全局上限: providerId={}, providerLimit={}, globalLimit={}",
                    provider.getId(), providerLimit, globalLimit);
            throw new ServiceException(ERR_OVER_GLOBAL);
        }
        // 新增供应商尚无 ID，其下必然没有模型，跳过下钻校验
        if (Objects.isNull(provider.getId()))
        {
            return;
        }
        String conflictModelCode = findModelExceeding(provider.getId(), providerLimit);
        if (StrUtil.isNotBlank(conflictModelCode))
        {
            log.error("供应商并发小于其下模型上限: providerId={}, providerLimit={}, conflictModel={}",
                    provider.getId(), providerLimit, conflictModelCode);
            throw new ServiceException(ERR_PROVIDER_UNDER_MODEL);
        }
    }

    /**
     * 解析指定供应商当前已保存的并发上限。
     *
     * @param providerId 供应商 ID（可空）
     * @return 上限；供应商不存在 / 未配返回 {@link MediaConcurrencyLimiter#UNLIMITED}
     */
    private int resolveProviderLimit(Long providerId)
    {
        if (Objects.isNull(providerId))
        {
            return MediaConcurrencyLimiter.UNLIMITED;
        }
        // 特别标注：只查上限解析必要字段（id + schedule_strategy_json）
        AidAiProvider provider = aidAiProviderService.getOne(
                Wrappers.<AidAiProvider>lambdaQuery()
                        .select(AidAiProvider::getId, AidAiProvider::getScheduleStrategyJson)
                        .eq(AidAiProvider::getId, providerId)
                        .last("LIMIT 1"), false);
        return Objects.isNull(provider)
                ? MediaConcurrencyLimiter.UNLIMITED
                : MediaConcurrencyLimiter.parseProviderConcurrency(provider.getScheduleStrategyJson());
    }

    /**
     * 查找该供应商下第一个并发上限超过给定值的模型。
     *
     * @param providerId    供应商 ID
     * @param providerLimit 供应商上限
     * @return 冲突模型编码；无冲突返回 null
     */
    private String findModelExceeding(Long providerId, int providerLimit)
    {
        // 特别标注：只查上限解析必要字段（model_code + schedule_strategy_json）
        List<AidAiModel> models = aidAiModelService.list(
                Wrappers.<AidAiModel>lambdaQuery()
                        .select(AidAiModel::getModelCode, AidAiModel::getScheduleStrategyJson)
                        .eq(AidAiModel::getProviderId, providerId));
        if (CollectionUtil.isEmpty(models))
        {
            return null;
        }
        for (AidAiModel m : models)
        {
            int modelLimit = MediaConcurrencyLimiter.parseModelConcurrency(m.getScheduleStrategyJson());
            if (modelLimit != MediaConcurrencyLimiter.UNLIMITED && modelLimit > providerLimit)
            {
                return m.getModelCode();
            }
        }
        return null;
    }
}
