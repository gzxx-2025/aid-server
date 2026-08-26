package com.aid.billing.service;

import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.domain.vo.AiModelConfigVo;

/** 无账户副作用的权威预扣计算链。 */
public interface BillingPreHoldCalculationService
{
    BillingCalcResult calculate(AiModelConfigVo modelConfig, BillingInput billingInput);
}
