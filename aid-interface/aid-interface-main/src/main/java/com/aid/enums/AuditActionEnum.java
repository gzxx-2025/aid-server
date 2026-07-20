package com.aid.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审核动作枚举
 * 对应数据库字段：aid_comic_audit_record.action
 */
@Getter
@AllArgsConstructor
public enum AuditActionEnum {

    /**
     * 提交审核（C端用户发起）
     */
    SUBMIT(1, "提交审核"),

    /**
     * 审核通过（后台管理员）
     */
    PASS(2, "审核通过"),

    /**
     * 审核驳回（后台管理员）
     */
    REJECT(3, "审核驳回"),

    /**
     * 后台上架（发布管理，管理员将审核通过作品置为公开）
     */
    FORCE_ONLINE(4, "后台上架"),

    /**
     * 后台下架（发布管理，管理员关闭作品公开，审核状态保留）
     */
    FORCE_OFFLINE(5, "后台下架"),

    /**
     * 审核回撤（发布管理，管理员撤销审核通过并同步下架）
     */
    REVOKE(6, "审核回撤");

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
    public static AuditActionEnum getByValue(Integer value) {
        if (value == null) {
            return null;
        }
        for (AuditActionEnum e : values()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }
        return null;
    }
}
