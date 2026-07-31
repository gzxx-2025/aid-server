package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通用启用/停用状态枚举
 * 对应数据库字段：status（char(1)类型）
 */
@Getter
@AllArgsConstructor
public enum CommonEnableStatusEnum {

    /**
     * 正常/启用
     */
    ENABLE("0", "正常/启用"),

    /**
     * 停用/禁用
     */
    DISABLE("1", "停用/禁用");

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
    public static CommonEnableStatusEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (CommonEnableStatusEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
