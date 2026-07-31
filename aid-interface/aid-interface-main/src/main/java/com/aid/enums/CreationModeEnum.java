package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;

/**
 * 创作模式枚举
 * 对应数据库字段：creation_mode
 */
@Getter
@AllArgsConstructor
public enum CreationModeEnum {

    /**
     * 图生视频
     */
    I2V("i2v", "图生视频"),

    /**
     * 多参生视频
     */
    MULTI("multi", "多参生视频"),

    /**
     * 专业版
     */
    PRO("pro", "专业版"),

    /**
     * 自动宫格模式
     */
    AUTO_GRID("auto_grid", "自动宫格模式");

    @EnumValue
    private final String value;
    private final String desc;

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * 创作模式分组：。
     */
    public String getGroup() {
        switch (this) {
            case I2V:
            case MULTI:
                return "standard";
            case PRO:
            case AUTO_GRID:
                return "advanced";
            default:
                return null;
        }
    }

    /**
     * 判断两个创作模式的切换是否「跨组」（标准组 ↔ 进阶组）。
     * 任一为空 / 非法值 → 返回 {@code false}（不触发清空，由各自的参数校验处理）。
     *
     * @param oldMode 旧创作模式
     * @param newMode 新创作模式
     * @return true=跨组切换（需清空项目级生成配置）
     */
    public static boolean isCrossGroupSwitch(String oldMode, String newMode) {
        CreationModeEnum o = getByValue(oldMode);
        CreationModeEnum n = getByValue(newMode);
        if (o == null || n == null) {
            return false;
        }
        return !Objects.equals(o.getGroup(), n.getGroup());
    }

    /**
     * 根据value获取枚举实例
     *
     * @param value 数据库存储值
     * @return 对应的枚举实例，若不存在则返回null
     */
    public static CreationModeEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (CreationModeEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
