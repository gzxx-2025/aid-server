package com.aid.billing.service;

import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.domain.vo.AiModelConfigVo;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 计费金额计算器：根据模型配置和计费参数计算预扣金额和结算金额。
 * 核心分发依据：meterType（TOKEN / PER_IMAGE / PER_SECOND / SKU_PACKAGE）。
 */
public interface BillingAmountCalculator {

    /**
     * 计算预扣金额（统一入口，按 meterType 分发公式）。
     *
     * @param modelConfig  模型配置
     * @param billingInput 统一计费输入
     * @return 计算结果（含金额和快照）
     */
    BillingCalcResult calculatePreHoldAmount(AiModelConfigVo modelConfig, BillingInput billingInput);

    /**
     * 计算结算金额（统一入口，按快照中 meterType 分发）。
     *
     * @param preHoldAmount 预扣金额
     * @param snapshotJson  原始快照JSON（须含 meterType + billingRuleJson）
     * @param usageData     上游 usage 数据
     * @return 结算后的计算结果（含实际金额和退款金额）
     */
    BillingCalcResult calculateSettleAmount(BigDecimal preHoldAmount, String snapshotJson, Map<String, Object> usageData);
}
