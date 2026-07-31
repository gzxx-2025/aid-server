package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI模型生成模式枚举。
 *
 * @author 视觉AID
 */
@Getter
@AllArgsConstructor
public enum GenerateModeEnum {

    /** 纯文本大模型（对应 model_type=text） */
    TEXT("text", "文本大模型"),

    /** 文生图（对应 model_type=image） */
    TEXT_TO_IMAGE("text_to_image", "文生图"),

    /** 图生图（对应 model_type=image） */
    IMAGE_TO_IMAGE("image_to_image", "图生图"),

    /** 图片编辑（对应 model_type=image） */
    IMAGE_EDIT("image_edit", "图片编辑"),

    /** 图片高清 / 超分辨率（对应 model_type=image） */
    IMAGE_UPSCALE("image_upscale", "图片高清"),

    /** 文生视频（对应 model_type=video） */
    TEXT_TO_VIDEO("text_to_video", "文生视频"),

    /** 图生视频（对应 model_type=video；含首尾帧） */
    IMAGE_TO_VIDEO("image_to_video", "图生视频"),

    /** 视频生视频（对应 model_type=video；多图视频 / 视频续写） */
    VIDEO_TO_VIDEO("video_to_video", "视频生视频"),

    /** 纯音频（对应 model_type=audio） */
    AUDIO("audio", "音频");

    @EnumValue
    private final String value;
    private final String desc;

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * 根据 value 获取枚举实例
     *
     * @param value 数据库存储值
     * @return 枚举实例；不存在时返回 null
     */
    public static GenerateModeEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (GenerateModeEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
