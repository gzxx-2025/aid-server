package com.aid.billing.estimate;

import com.aid.billing.dto.BillingInput;
import com.aid.domain.vo.AiModelConfigVo;

/**
 * 预冻结参数估算策略接口：按 meterType 对 BillingInput 进行估算参数增补。
 * BillingInputExtractor 只提取原始业务参数（字符数、张数、时长等），
 * 估算层按 meterType 增补 inputTokens / outputTokens 等预估值。
 */
public interface BillingEstimateStrategy {

    /**
     * 对 BillingInput 进行预冻结估算参数增补。
     * 策略实现可以根据 modelConfig 中的模型信息做模型族级别的子分发。
     *
     * @param billingInput 已提取原始业务参数的计费输入
     * @param modelConfig  模型配置（含 modelCode、billingRuleJson 等）
     */
    void enrichEstimate(BillingInput billingInput, AiModelConfigVo modelConfig);
}
