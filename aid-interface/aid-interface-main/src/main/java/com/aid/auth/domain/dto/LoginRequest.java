package com.aid.auth.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 统一登录请求参数
 *
 * @author 视觉AID
 */
@Data
public class LoginRequest {

    /**
     * 登录类型 (password/sms/email/wechat)
     */
    @NotBlank(message = "登录类型不能为空")
    private String loginType;

    /**
     * 账号 (用户名/手机号/邮箱)
     */
    private String account;

    /**
     * 密码 (账号密码登录时必填)
     */
    private String password;

    /**
     * 验证码 (短信/邮箱验证码，或图形验证码)
     */
    private String code;

    /**
     * 第三方登录凭证 (微信扫码登录sceneStr等)
     */
    private String accessToken;

    /**
     * 第三方登录openid
     */
    private String openid;

    /**
     * 设备信息
     */
    private String deviceInfo;

    /**
     * 邀请码 (可选；仅首次注册瞬间生效，用于绑定邀请关系，老用户登录时忽略)
     */
    private String inviteCode;
}
