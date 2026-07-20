package com.aid.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 分镜脚本生成 - 镜头密度档位枚举。
 *
 * @author 视觉AID
 */
@Getter
@AllArgsConstructor
public enum StoryboardShotDensityEnum {

    /** 精简模式：密度低、节奏紧凑（约为标准档位的 60%~70%） */
    LITE("精简模式", "节奏紧凑、信息密度高"),

    /** 标准模式：默认档位、基准拆分粒度 */
    STANDARD("标准模式", "默认拆分粒度"),

    /** 细拆模式：密度高、重点戏份 / 情绪高潮（约为标准档位的 130%~150%） */
    DETAILED("细拆模式", "重点戏份、情绪高潮、慢节奏");

    /** 中文 mode 值（同时是入参字符串、提示词关键字、字典 value） */
    private final String value;

    /** 字典 desc：补充说明（前端可用作 tooltip / 帮助文案） */
    private final String desc;

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * 根据中文 mode 值取枚举实例
     *
     * @param value 中文模式名（如 {@code 标准模式}）；为空 / 不在白名单返回 {@code null}
     */
    public static StoryboardShotDensityEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (StoryboardShotDensityEnum e : values()) {
            if (e.value.equals(value)) {
                return e;
            }
        }
        return null;
    }

    /** 默认模式：标准模式 */
    public static StoryboardShotDensityEnum defaultMode() {
        return STANDARD;
    }
}
