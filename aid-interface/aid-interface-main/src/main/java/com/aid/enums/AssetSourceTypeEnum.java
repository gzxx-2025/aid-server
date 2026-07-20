package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 资产来源枚举
 * 对应数据库字段：source_type
 */
@Getter
@AllArgsConstructor
public enum AssetSourceTypeEnum {

    /**
     * 手动填写
     */
    MANUAL(1, "手动填写"),

    /**
     * 单集提取
     */
    SINGLE_EXTRACT(2, "单集提取"),

    /**
     * 全集全局提取
     */
    GLOBAL_EXTRACT(3, "全集全局提取");

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
    public static AssetSourceTypeEnum getByValue(Integer value) {
        if (value == null) {
            return null;
        }
        for (AssetSourceTypeEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
