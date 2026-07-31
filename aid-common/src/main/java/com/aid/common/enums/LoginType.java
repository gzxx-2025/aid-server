package com.aid.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 登录类型枚举
 *
 * @author 视觉AID
 */
@Getter
@AllArgsConstructor
public enum LoginType {

    /**
     * 账号密码登录
     */
    PASSWORD("password", "账号密码登录"),

    /**
     * 短信验证码登录
     */
    SMS("sms", "短信验证码登录"),

    /**
     * 邮箱验证码登录
     */
    EMAIL("email", "邮箱验证码登录"),

    /**
     * 微信公众号登录
     */
    WECHAT("wechat", "微信公众号登录");

    /**
     * 登录类型编码
     */
    private final String code;

    /**
     * 登录类型描述
     */
    private final String desc;

    /**
     * 根据编码获取登录类型
     *
     * @param code 登录类型编码
     * @return 登录类型枚举
     */
    public static LoginType getByCode(String code) {
        for (LoginType loginType : values()) {
            if (loginType.getCode().equals(code)) {
                return loginType;
            }
        }
        return null;
    }
}
