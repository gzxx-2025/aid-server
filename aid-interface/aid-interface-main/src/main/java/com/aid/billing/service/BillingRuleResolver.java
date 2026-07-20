package com.aid.billing.service;

import com.aid.billing.model.BillingRule;
import com.aid.billing.model.BillingSku;
import com.aid.domain.vo.AiModelConfigVo;

import java.util.Map;

/**
 * 计费规则解析器：解析 billing_rule_json 并根据参数命中SKU。
 */
public interface BillingRuleResolver {

    /**
     * 解析模型的计费规则JSON为BillingRule对象。
     * FIXED模式或规则为空时返回null。
     *
     * @param modelConfig 模型配置（含 billingRuleJson）
     * @return 解析后的规则对象，FIXED模式返回null
     */
    BillingRule parseRule(AiModelConfigVo modelConfig);

    /**
     * 根据实际参数从规则中命中一个SKU。
     * 按 priority 升序、enabled=true 过滤，返回第一个匹配的SKU。
     *
     * @param rule   计费规则
     * @param params 实际计费参数
     * @return 命中的SKU，无命中返回null
     */
    BillingSku resolve(BillingRule rule, Map<String, Object> params);
}
