package com.aid.billing.model;

import lombok.Data;

import java.util.List;

/**
 * billing_rule_json 顶层结构：统一覆盖 TOKEN / PER_IMAGE / PER_SECOND / SKU_PACKAGE 四类计费口径。
 */
@Data
public class BillingRule {

    /** 计费模式：FIXED / SKU */
    private String mode;

    /** 统一计费口径：TOKEN / PER_IMAGE / PER_SECOND / SKU_PACKAGE */
    private String meterType;

    /** 业务类型（保留兼容）：TEXT / IMAGE / VIDEO */
    private String chargeType;

    /** 是否启用预扣冻结 */
    private boolean preHold;

    /** SKU匹配策略，当前仅支持 FIRST_HIT */
    private String matchStrategy;

    /** 本模型计费依赖的参数定义列表 */
    private List<BillingParamDef> params;

    /** SKU列表 */
    private List<BillingSku> skus;

    /** 结算规则，仅文本模型使用 */
    private SettleRule settleRule;

    /** 输入媒体计费（参考图/输入视频附加费）规则级默认；SKU 级同名配置按档位覆盖 */
    private InputMediaPricing inputPricing;
}
