package com.aid.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审核对象类型枚举
 * 对应数据库字段：aid_comic_audit_record.target_type
 */
@Getter
@AllArgsConstructor
public enum AuditTargetTypeEnum {

    /**
     * 项目（电影/剧集主体）
     */
    PROJECT("project", "项目"),

    /**
     * 剧集（单集）
     */
    EPISODE("episode", "剧集");

    @JsonValue
    private final String value;
    private final String desc;

    /**
     * 根据value获取枚举实例
     *
     * @param value 数据库存储值
     * @return 对应的枚举实例，若不存在则返回null
     */
    public static AuditTargetTypeEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (AuditTargetTypeEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
