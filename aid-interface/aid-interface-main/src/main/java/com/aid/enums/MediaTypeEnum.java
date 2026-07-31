package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 媒体文件类型枚举
 * 用于区分生成产物是图片还是视频
 */
@Getter
@AllArgsConstructor
public enum MediaTypeEnum {

    /**
     * 图片
     */
    IMAGE("image", "图片"),

    /**
     * 视频
     */
    VIDEO("video", "视频");

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
    public static MediaTypeEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (MediaTypeEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
