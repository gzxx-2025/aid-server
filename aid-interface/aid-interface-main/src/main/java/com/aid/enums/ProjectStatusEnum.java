package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 项目状态枚举
 * 对应数据库字段：status
 */
@Getter
@AllArgsConstructor
public enum ProjectStatusEnum {

    /**
     * 草稿
     */
    DRAFT(0, "草稿"),

    /**
     * 制作中
     */
    PROCESSING(1, "制作中"),

    /**
     * 完成未提交
     */
    FINISHED_UNSUBMITTED(2, "完成未提交"),

    /**
     * 审核中
     */
    AUDITING(3, "审核中"),

    /**
     * 审核通过
     */
    AUDIT_PASSED(4, "审核通过"),

    /**
     * 审核失败
     */
    AUDIT_FAILED(5, "审核失败");

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
    public static ProjectStatusEnum getByValue(Integer value) {
        if (value == null) {
            return null;
        }
        for (ProjectStatusEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
