package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 提示词分类枚举
 * 对应数据库字段：prompt_type
 */
@Getter
@AllArgsConstructor
public enum PromptTypeEnum {

    /**
     * 视频风格
     */
    STYLE("style", "视频风格"),

    /**
     * 镜头语言
     */
    CAMERA("camera", "镜头语言"),

    /**
     * 主体描述
     */
    SUBJECT("subject", "主体描述");

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
    public static PromptTypeEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (PromptTypeEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
