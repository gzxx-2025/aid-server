package com.aid.billing.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 管理端计费规则预览/试算请求
 */
@Data
public class BillingRulePreviewRequest {

    /** 计费规则JSON */
    private String billingRuleJson;

    /** 模拟输入的参数 */
    private Map<String, Object> testParams;

    /** 单模型倍率；为空时按 1 */
    private BigDecimal billingMultiplier;
}
