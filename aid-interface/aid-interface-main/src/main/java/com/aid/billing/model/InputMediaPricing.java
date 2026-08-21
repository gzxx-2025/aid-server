package com.aid.billing.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 输入媒体计费配置（billing_rule_json 的 inputPricing 段）。
 * 描述「参考图 / 输入视频」等输入侧素材的附加计费：
 * - 规则级（BillingRule.inputPricing）为模型默认；
 * - SKU 级（BillingSku.inputPricing）按档位覆盖（如视频输入单价随分辨率变化）。
 * 单价为 null 或 0 时表示该类输入不计费（免费）。
 * 官方阶梯价可通过图片计费项的 freeCount 表达前 N 张免费。
 */
@Data
public class InputMediaPricing {

    /** 图片输入计费（参考图，按张） */
    private ImagePricing image;

    /** 视频输入计费（参考视频，按秒） */
    private VideoPricing video;

    /**
     * 图片输入计费项。
     */
    @Data
    public static class ImagePricing {
        /** 官方原价（元/张）；null 或 0 = 不计费 */
        private BigDecimal unitPrice;
        /** 免费张数；null 或小于 0 按 0 处理 */
        private Integer freeCount;
        /** 计费张数上限（与 capability.maxReferenceImages 对齐）；null 或 <=0 = 不限 */
        private Integer maxCount;
    }

    /**
     * 视频输入计费项。
     */
    @Data
    public static class VideoPricing {
        /** 官方原价（元/秒）；null 或 0 = 不计费 */
        private BigDecimal unitPrice;
        /** 计费秒数上限（输入视频总时长上限）；时长未知但确有输入视频时按此值预扣（宁高勿低） */
        private Integer maxSeconds;
        /** 输入视频段数上限（仅配置展示，计费按秒） */
        private Integer maxCount;
    }
}
