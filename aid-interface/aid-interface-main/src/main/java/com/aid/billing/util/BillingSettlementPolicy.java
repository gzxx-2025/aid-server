package com.aid.billing.util;

import com.aid.billing.model.BillingRule;
import com.aid.billing.model.SettleRule;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.hutool.core.util.StrUtil;

/** 根据任务正式结算规则判断报价是否依赖上游真实用量。 */
public final class BillingSettlementPolicy
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private BillingSettlementPolicy()
    {
    }

    public static boolean isEstimated(String meterType, String billingMode, String billingRuleJson)
    {
        if ("TOKEN".equalsIgnoreCase(meterType))
        {
            return true;
        }
        if (!"PER_SECOND".equalsIgnoreCase(meterType)
                || "FIXED".equalsIgnoreCase(billingMode))
        {
            return false;
        }
        // 旧按秒规则无显式策略时保持现有“按上游实际时长只退不补”口径。
        SettleRule settleRule = parseSettleRule(billingRuleJson);
        if (settleRule == null)
        {
            return true;
        }
        boolean providerUsage = "PROVIDER_USAGE".equalsIgnoreCase(settleRule.getUsageSource());
        boolean refundOnly = "REFUND_ONLY".equalsIgnoreCase(settleRule.getSettleMode());
        return providerUsage || refundOnly || settleRule.isAllowRefund();
    }

    private static SettleRule parseSettleRule(String billingRuleJson)
    {
        if (StrUtil.isBlank(billingRuleJson))
        {
            return null;
        }
        try
        {
            BillingRule rule = OBJECT_MAPPER.readValue(billingRuleJson, BillingRule.class);
            return rule == null ? null : rule.getSettleRule();
        }
        catch (Exception ignored)
        {
            return null;
        }
    }
}
