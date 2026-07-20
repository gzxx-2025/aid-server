package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 生成结果存储目标枚举
 * 用于区分回调结果写入哪张表
 */
@Getter
@AllArgsConstructor
public enum GenResultTargetEnum {

    /**
     * 资产表 aid_comic_asset
     */
    ASSET("asset", "资产表"),

    /**
     * 抽卡记录表 aid_gen_record
     */
    GEN_RECORD("gen_record", "抽卡记录表");

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
    public static GenResultTargetEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (GenResultTargetEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
