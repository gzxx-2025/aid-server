package com.aid.billing.vo;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;

import lombok.Data;

/** 无副作用的用户侧权威报价。 */
@Data
public class BillingQuoteVO
{
    private String quoteType;
    private String modelCode;
    private Boolean isFree;
    private Boolean matched;
    private String skuCode;
    private String skuName;
    private String meterType;
    private String unit;
    private String unitName;
    private Map<String, Object> matchConditions;
    private Integer quantity;
    private BigDecimal amount;
    private BigDecimal preHoldAmount;
    private Boolean determined;
    private Boolean estimated;
    private String displayText;

    /** 多模型/多调用业务报价明细；直接媒体报价为空。 */
    private List<BillingQuoteVO> items;
}
