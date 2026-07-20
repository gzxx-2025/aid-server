package com.aid.billing.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * SKU定义：描述一个具体的计费规则条目。
 */
@Data
public class BillingSku {

    /** SKU编码，唯一标识 */
    private String skuCode;

    /** SKU名称，可读 */
    private String skuName;

    /** 是否启用 */
    private boolean enabled;

    /**
     * 优先级，数值越小越先匹配。
     */
    private int priority;

    /** 命中条件：支持等值匹配和范围匹配（如 resolution=720P, durationMin=1, durationMax=5） */
    private Map<String, Object> match;

    /** 官方原价（元；图片单张价 / 视频整包价等） */
    private BigDecimal price;

    /** 官方每秒原价（元/秒），仅 PER_SECOND 使用；null时降级到 price / maxDuration */
    private BigDecimal pricePerSecond;

    /** 官方每字符原价（元/字符），仅 PER_CHAR 使用；null时降级到 price */
    private BigDecimal pricePerChar;

    /** 输入官方原价（元/百万Token），仅 TEXT SKU 使用；null时降级到 price */
    private BigDecimal inputPricePerMillion;

    /** 输出官方原价（元/百万Token），仅 TEXT SKU 使用；null时降级到 price */
    private BigDecimal outputPricePerMillion;

    /** 输入媒体计费（参考图/输入视频附加费）SKU 级覆盖；null 时回退规则级 inputPricing */
    private InputMediaPricing inputPricing;

    /** 备注 */
    private String remark;
}
