package com.aid.billing.enums;

import lombok.Getter;

/**
 * 计费模式枚举
 */
@Getter
public enum BillingMode {

    FIXED("FIXED", "固定价格"),
    SKU("SKU", "SKU规则计费");

    private final String code;
    private final String desc;

    BillingMode(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据字符串解析计费模式，空白默认 FIXED
     */
    public static BillingMode of(String value) {
        if (value == null || value.isBlank()) {
            return FIXED;
        }
        for (BillingMode mode : values()) {
            if (mode.code.equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return FIXED;
    }
}
