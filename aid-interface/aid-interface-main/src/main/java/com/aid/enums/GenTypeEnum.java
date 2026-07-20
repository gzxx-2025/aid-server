package com.aid.enums;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 生成抽卡记录类型枚举
 * 对应数据库字段：gen_type
 */
@Getter
@AllArgsConstructor
public enum GenTypeEnum {

    /**
     * 单图
     */
    IMAGE("image", "单图"),

    /**
     * 九宫格
     */
    GRID("grid", "九宫格"),

    /**
     * 图生视频
     */
    I2V("i2v", "图生视频"),

    /**
     * 多参视频
     */
    MULTI("multi", "多参视频"),

    /**
     * 首尾视频
     */
    EDGE("edge", "首尾视频"),

    /**
     * 用户上传视频（区别于 AI 生成视频，归入视频大类，可被设为分镜主视频）
     */
    UPLOAD_VIDEO("upload_video", "上传视频"),

    /**
     * 配音合成视频（配音贴回分镜视频 / 成片合成产物，归入视频大类，可被设为分镜主视频）
     */
    COMPOSE("compose", "配音合成视频");

    @EnumValue
    private final String value;
    private final String desc;

    /** 原视频轨 genType 集合（配音前的分镜视频：i2v/multi/edge/upload_video） */
    private static final List<String> ORIGINAL_VIDEO_VALUES = Collections.unmodifiableList(Arrays.asList(
            I2V.getValue(), MULTI.getValue(), EDGE.getValue(), UPLOAD_VIDEO.getValue()));

    /** 配音轨 genType 集合（配音合成视频 / 对口型视频，统一归为配音类型视频） */
    private static final List<String> COMPOSE_VIDEO_VALUES = Collections.singletonList(COMPOSE.getValue());

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * 原视频轨 genType 集合（分镜视频大类，不含配音合成视频）。
     *
     * @return 不可变列表
     */
    public static List<String> originalVideoValues() {
        return ORIGINAL_VIDEO_VALUES;
    }

    /**
     * 配音轨 genType 集合（配音合成视频 / 对口型视频）。
     *
     * @return 不可变列表
     */
    public static List<String> composeVideoValues() {
        return COMPOSE_VIDEO_VALUES;
    }

    /**
     * 根据value获取枚举实例
     *
     * @param value 数据库存储值
     * @return 对应的枚举实例，若不存在则返回null
     */
    public static GenTypeEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (GenTypeEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
