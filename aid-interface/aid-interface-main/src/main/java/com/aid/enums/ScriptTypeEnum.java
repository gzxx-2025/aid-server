package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 剧本类型枚举
 * 对应数据库字段：script_type
 */
@Getter
@AllArgsConstructor
public enum ScriptTypeEnum {

    /**
     * 剧情演绎
     */
    PLOT("plot", "剧情演绎"),

    /**
     * 真人解说漫
     */
    MONOLOGUE("monologue", "真人解说漫");

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
    public static ScriptTypeEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (ScriptTypeEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
