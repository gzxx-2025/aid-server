package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 创作流水线步骤枚举。
 */
@Getter
@AllArgsConstructor
public enum CreationStepEnum {

    /**
     * 剧集模式下项目主表固定值
     */
    SERIES_DEFAULT(-1, "剧集默认"),

    /**
     * 项目配置
     */
    GLOBAL_SETTING(1, "项目配置"),

    /**
     * 剧本创作
     */
    SCRIPT(2, "剧本创作"),

    /**
     * 素材准备
     */
    ASSET(3, "素材准备"),

    /**
     * 分镜设计
     */
    STORYBOARD(4, "分镜设计"),

    /**
     * 视频生成
     */
    VIDEO(5, "视频生成"),

    /**
     * 音画同步
     */
    AUDIO(6, "音画同步"),

    /**
     * 成品预览
     */
    PREVIEW(7, "成品预览");

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
    public static CreationStepEnum getByValue(Integer value) {
        if (value == null) {
            return null;
        }
        for (CreationStepEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }

    /**
     * 判断是否为有效的流水线步骤(1~7)。
     *
     * @param value 步骤值
     * @return 是否有效
     */
    public static boolean isValidStep(Integer value) {
        return value != null && value >= 1 && value <= 7;
    }
}
