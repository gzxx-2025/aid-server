package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 配音来源枚举
 * 对应数据库字段：audio_source
 */
@Getter
@AllArgsConstructor
public enum AudioSourceEnum {

    /**
     * AI文字配音
     */
    AI_TTS(1, "AI文字配音"),

    /**
     * 用户上传
     */
    USER_UPLOAD(2, "用户上传");

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
    public static AudioSourceEnum getByValue(Integer value) {
        if (value == null) {
            return null;
        }
        for (AudioSourceEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
