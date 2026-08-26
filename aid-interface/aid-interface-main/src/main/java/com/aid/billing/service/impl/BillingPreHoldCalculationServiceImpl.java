package com.aid.billing.service.impl;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.enums.BillingConstants;
import com.aid.billing.estimate.BillingEstimateResolver;
import com.aid.billing.service.BillingAmountCalculator;
import com.aid.billing.service.BillingPreHoldCalculationService;
import com.aid.billing.util.TextReasoningBillingResolver;
import com.aid.domain.vo.AiModelConfigVo;

import lombok.RequiredArgsConstructor;

/** 统一执行估算、SKU 命中、倍率及账户精度归一化，不写任务或账户。 */
@Service
@RequiredArgsConstructor
public class BillingPreHoldCalculationServiceImpl implements BillingPreHoldCalculationService
{
    private final BillingEstimateResolver billingEstimateResolver;
    private final BillingAmountCalculator billingAmountCalculator;

    @Override
    public BillingCalcResult calculate(AiModelConfigVo modelConfig, BillingInput billingInput)
    {
        TextReasoningBillingResolver.enrich(billingInput, modelConfig);
        billingEstimateResolver.enrichEstimate(billingInput, modelConfig);
        BillingCalcResult result = billingAmountCalculator.calculatePreHoldAmount(modelConfig, billingInput);
        if (result == null || !result.isMatched())
        {
            return result;
        }
        BigDecimal normalized = BillingConstants.normalizeAccountAmount(result.getAmount());
        result.setAmount(normalized);
        if (result.getSnapshot() != null)
        {
            result.getSnapshot().setPreHoldAmount(normalized);
        }
        return result;
    }
}
