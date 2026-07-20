package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 产物类型枚举
 * 用于区分生成的产物属于哪种业务类型
 */
@Getter
@AllArgsConstructor
public enum ProductTypeEnum {

    /**
     * 资产（角色/场景/道具等）
     */
    ASSET("asset", "资产"),

    /**
     * 分镜内容
     */
    STORYBOARD("storyboard", "分镜"),

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
    public static ProductTypeEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (ProductTypeEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
