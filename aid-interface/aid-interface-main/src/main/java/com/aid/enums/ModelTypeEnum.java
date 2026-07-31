package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI模型分类枚举
 * 对应数据库字段：model_type
 */
@Getter
@AllArgsConstructor
public enum ModelTypeEnum {

    /**
     * 生图
     */
    IMAGE("image", "生图"),

    /**
     * 生视频
     */
    VIDEO("video", "生视频"),

    /**
     * 配音
     */
    AUDIO("audio", "配音");

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
    public static ModelTypeEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (ModelTypeEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
