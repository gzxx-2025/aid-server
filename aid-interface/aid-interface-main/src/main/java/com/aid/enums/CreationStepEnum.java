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
     * 全局设定
     */
    GLOBAL_SETTING(1, "全局设定"),

    /**
     * 故事剧本
     */
    SCRIPT(2, "故事剧本"),

    /**
     * 场景角色道具
     */
    ASSET(3, "场景角色道具"),

    /**
     * 分镜脚本
     */
    STORYBOARD(4, "分镜脚本"),

    /**
     * 分镜视频
     */
    VIDEO(5, "分镜视频"),

    /**
     * 配音对口型
     */
    AUDIO(6, "配音对口型"),

    /**
     * 视频预览
     */
    PREVIEW(7, "视频预览");

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
