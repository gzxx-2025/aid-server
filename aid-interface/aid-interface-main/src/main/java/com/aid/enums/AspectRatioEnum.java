package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 画面比例枚举
 * 对应数据库字段：aspect_ratio
 */
@Getter
@AllArgsConstructor
public enum AspectRatioEnum {

    /** 16:9 横屏 */
    RATIO_16_9("16:9", "横屏"),

    /** 9:16 竖屏 */
    RATIO_9_16("9:16", "竖屏"),

    /** 1:1 方形 */
    RATIO_1_1("1:1", "方形"),

    /** 4:3 传统屏 */
    RATIO_4_3("4:3", "传统屏"),

    /** 3:4 竖版传统 */
    RATIO_3_4("3:4", "竖版传统"),

    /** 3:2 经典横屏 */
    RATIO_3_2("3:2", "经典横屏"),

    /** 2:3 经典竖屏 */
    RATIO_2_3("2:3", "经典竖屏"),

    /** 21:9 超宽屏 */
    RATIO_21_9("21:9", "超宽屏"),

    /** 9:21 超长竖屏 */
    RATIO_9_21("9:21", "超长竖屏"),

    /** 2:1 宽银幕 */
    RATIO_2_1("2:1", "宽银幕"),

    /** 1:2 长竖屏 */
    RATIO_1_2("1:2", "长竖屏"),

    /** 4:5 社交媒体竖版 */
    RATIO_4_5("4:5", "社交媒体竖版"),

    /** 5:4 社交媒体横版 */
    RATIO_5_4("5:4", "社交媒体横版");

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
    public static AspectRatioEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (AspectRatioEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
