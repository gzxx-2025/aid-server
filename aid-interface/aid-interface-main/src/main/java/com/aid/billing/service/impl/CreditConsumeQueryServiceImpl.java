package com.aid.billing.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.dto.BalanceConsumeAggDTO;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidBalanceLogService;
import com.aid.billing.dto.CreditConsumeQueryRequest;
import com.aid.billing.service.ICreditConsumeQueryService;
import com.aid.billing.vo.CreditConsumeRecordVO;
import com.aid.common.core.domain.AjaxResult;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 积分消耗明细查询 Service 实现（C 端）：按业务聚合余额流水，把同一任务的多笔变动合并为一条分页返回。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditConsumeQueryServiceImpl implements ICreditConsumeQueryService
{
    private final IAidBalanceLogService aidBalanceLogService;
    private final IAidAiModelService aidAiModelService;

    /** 业务类型 → 中文名映射 */
    private static final Map<String, String> BIZ_TYPE_NAME = Map.of(
            "create", "媒体创作",
            "extract", "资产提取",
            "chat", "AI对话",
            "admin_adjust", "管理员调整"
    );

    @Override
    public AjaxResult queryConsumeDetail(CreditConsumeQueryRequest request, Long userId)
    {
        int pageNum = Objects.isNull(request) ? 1 : request.resolvePageNum();
        int pageSize = Objects.isNull(request) ? 10 : request.resolvePageSize();

        // 开启分页（紧邻聚合查询，确保 PageHelper 仅拦截这一条）
        PageHelper.startPage(pageNum, pageSize);
        List<BalanceConsumeAggDTO> aggList = aidBalanceLogService.selectUserConsumeAgg(userId);
        // 先取总数（基于代理后的分页对象），再转换为 VO，避免转换后丢失分页信息
        long total = new PageInfo<>(aggList).getTotal();
        Map<String, String> modelNameMap = loadModelNames(aggList);

        List<CreditConsumeRecordVO> voList = new ArrayList<>();
        for (BalanceConsumeAggDTO agg : aggList)
        {
            voList.add(toVO(agg, modelNameMap));
        }

        log.info("查询积分消耗明细: userId={}, pageNum={}, pageSize={}, total={}", userId, pageNum, pageSize, total);
        return AjaxResult.success()
                .put("total", total)
                .put("data", voList);
    }

    /**
     * 聚合 DTO → C 端 VO，并计算实际消耗、退款标记、友好业务名与金额规整。
     */
    private CreditConsumeRecordVO toVO(BalanceConsumeAggDTO agg, Map<String, String> modelNameMap)
    {
        CreditConsumeRecordVO vo = new CreditConsumeRecordVO();
        vo.setBizTraceId(agg.getRelatedId());
        vo.setBizType(agg.getBizType());
        vo.setBizTypeName(resolveBizTypeName(agg.getBizType(), agg.getBizName()));
        vo.setBizName(agg.getBizName());
        vo.setModelName(resolveModelName(agg.getModelCode(), modelNameMap));
        vo.setCreateTime(agg.getCreateTime());

        // 净增减（带符号）：负数=净消耗
        BigDecimal change = scale2(agg.getChangeAmount());
        vo.setChangeAmount(change);
        // 实际消耗 = -净额（净额为正时按 0）
        BigDecimal consumed = change.signum() < 0 ? change.negate() : BigDecimal.ZERO;
        vo.setConsumedAmount(scale2(consumed));
        // 最初冻结/预扣
        vo.setFrozenAmount(scale2(agg.getFrozenAmount()));
        // 退款
        BigDecimal refund = scale2(agg.getRefundAmount());
        vo.setRefundAmount(refund);
        vo.setHasRefund(refund.signum() > 0);
        // 超额补扣
        vo.setExtraAmount(scale2(agg.getExtraAmount()));
        return vo;
    }

    /**
     * 一次性加载当前页涉及的模型展示名称，避免逐条查询。
     */
    private Map<String, String> loadModelNames(List<BalanceConsumeAggDTO> aggList)
    {
        Set<String> modelCodes = new LinkedHashSet<>();
        for (BalanceConsumeAggDTO agg : aggList)
        {
            modelCodes.addAll(splitModelCodes(agg.getModelCode()));
        }
        if (modelCodes.isEmpty())
        {
            return Map.of();
        }
        List<AidAiModel> models = aidAiModelService.list(
                Wrappers.<AidAiModel>lambdaQuery()
                        .in(AidAiModel::getModelCode, modelCodes)
                        .select(AidAiModel::getModelCode, AidAiModel::getModelName));
        Map<String, String> modelNameMap = new HashMap<>();
        for (AidAiModel model : models)
        {
            if (StrUtil.isNotBlank(model.getModelCode()))
            {
                modelNameMap.put(model.getModelCode(),
                        StrUtil.blankToDefault(model.getModelName(), model.getModelCode()));
            }
        }
        return modelNameMap;
    }

    private String resolveModelName(String modelCodeValue, Map<String, String> modelNameMap)
    {
        Set<String> modelCodes = splitModelCodes(modelCodeValue);
        if (modelCodes.isEmpty())
        {
            return null;
        }
        List<String> modelNames = new ArrayList<>();
        for (String modelCode : modelCodes)
        {
            modelNames.add(modelNameMap.getOrDefault(modelCode, modelCode));
        }
        return String.join("、", modelNames);
    }

    private Set<String> splitModelCodes(String modelCodeValue)
    {
        Set<String> modelCodes = new LinkedHashSet<>();
        if (StrUtil.isBlank(modelCodeValue))
        {
            return modelCodes;
        }
        for (String modelCode : modelCodeValue.split(","))
        {
            if (StrUtil.isNotBlank(modelCode))
            {
                modelCodes.add(modelCode.trim());
            }
        }
        return modelCodes;
    }

    /** 业务类型中文名：优先映射表，未命中回退 bizName，再回退原始 bizType */
    private String resolveBizTypeName(String bizType, String bizName)
    {
        if (StrUtil.isNotBlank(bizType) && BIZ_TYPE_NAME.containsKey(bizType))
        {
            return BIZ_TYPE_NAME.get(bizType);
        }
        if (StrUtil.isNotBlank(bizName))
        {
            return bizName;
        }
        return bizType;
    }

    /** 金额规整为 2 位小数；null 视为 0 */
    private BigDecimal scale2(BigDecimal v)
    {
        if (Objects.isNull(v))
        {
            return BigDecimal.ZERO;
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
