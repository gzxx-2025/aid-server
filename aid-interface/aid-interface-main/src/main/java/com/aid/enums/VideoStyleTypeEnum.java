package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 视频风格来源枚举
 * 对应数据库字段：video_style_type
 */
@Getter
@AllArgsConstructor
public enum VideoStyleTypeEnum {

    /**
     * 自定义
     */
    CUSTOM("custom", "自定义"),

    /**
     * AI生成
     */
    AI_GEN("ai_gen", "AI生成"),

    /**
     * 官方预设
     */
    OFFICIAL("official", "官方预设");

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
    public static VideoStyleTypeEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (VideoStyleTypeEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
