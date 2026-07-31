package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 生成模式枚举
 * 对应数据库字段：default_gen_mode
 */
@Getter
@AllArgsConstructor
public enum GenModeEnum {

    /**
     * 经济模式
     */
    ECONOMY("economy", "经济模式"),

    /**
     * 性能模式
     */
    PERFORMANCE("performance", "性能模式");

    @EnumValue
    private final String value;
    private final String desc;

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * 根据value获取枚举实例
     *
     * @param value 数据库存储值
     * @return 对应的枚举实例，若不存在则返回null
     */
    public static GenModeEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (GenModeEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
