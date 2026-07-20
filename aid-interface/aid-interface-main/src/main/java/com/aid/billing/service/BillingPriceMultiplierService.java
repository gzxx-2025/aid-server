package com.aid.billing.service;

import java.math.BigDecimal;

/**
 * 模型价格倍率服务：统一处理官方原价到用户积分价的换算。
 */
public interface BillingPriceMultiplierService {

    /**
     * 读取 aid_config 中的模型基础倍率（积分/元）。
     *
     * @return 正数倍率，配置缺失时返回 1
     */
    BigDecimal getGlobalMultiplier();

    /**
     * 解析单模型倍率，空值或非正数按 1 处理。
     *
     * @param modelMultiplier aid_ai_model.billing_multiplier
     * @return 有效倍率
     */
    BigDecimal resolveModelMultiplier(BigDecimal modelMultiplier);

    /**
     * 将官方原价换算为最终积分价。
     *
     * @param rawPrice        官方原价（元）
     * @param modelMultiplier 单模型倍率
     * @return 原价 × 模型基础倍率 × 单模型倍率
     */
    BigDecimal apply(BigDecimal rawPrice, BigDecimal modelMultiplier);
}
