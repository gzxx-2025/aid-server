package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 剧本状态枚举
 * 对应数据库字段：status
 */
@Getter
@AllArgsConstructor
public enum ScriptStatusEnum {

    /**
     * 草稿
     */
    DRAFT(0, "草稿"),

    /**
     * 使用
     */
    IN_USE(1, "使用"),

    /**
     * 历史版本
     */
    HISTORY(2, "历史版本");

    @EnumValue
    private final Integer value;
    private final String desc;

    @JsonValue
    public Integer getValue() {
        return value;
    }

    /**
     * 根据value获取枚举实例
     *
     * @param value 数据库存储值
     * @return 对应的枚举实例，若不存在则返回null
     */
    public static ScriptStatusEnum getByValue(Integer value) {
        if (value == null) {
            return null;
        }
        for (ScriptStatusEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
