package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 资产类型枚举
 * 对应数据库字段：asset_type
 */
@Getter
@AllArgsConstructor
public enum AssetTypeEnum {

    /**
     * 风格
     */
    STYLE("style", "风格"),

    /**
     * 场景
     */
    SCENE("scene", "场景"),

    /**
     * 角色
     */
    CHARACTER("character", "角色"),

    /**
     * 道具
     */
    PROP("prop", "道具"),

    /**
     * 文件
     */
    FILE("file", "文件"),

    /**
     * 姿势
     */
    POSE("pose", "姿势"),

    /**
     * 特效
     */
    EFFECT("effect", "特效"),

    /**
     * 表情
     */
    EXPRESSION("expression", "表情");

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
    public static AssetTypeEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (AssetTypeEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
