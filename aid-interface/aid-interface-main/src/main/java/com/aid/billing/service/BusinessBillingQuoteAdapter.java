package com.aid.billing.service;

import com.aid.billing.vo.BillingQuoteVO;
import com.fasterxml.jackson.databind.JsonNode;

/** 将复杂业务提交参数转换为与正式链一致的只读计费计划。 */
public interface BusinessBillingQuoteAdapter
{
    boolean supports(String quoteType);

    BillingQuoteVO quote(String quoteType, JsonNode payload, Long userId);
}
