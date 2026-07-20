package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通用是否状态枚举
 * 对应数据库字段（char(1)类型）
 */
@Getter
@AllArgsConstructor
public enum CommonYesNoEnum {

    /**
     * 否
     */
    NO("0", "否"),

    /**
     * 是
     */
    YES("1", "是");

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
    public static CommonYesNoEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (CommonYesNoEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
