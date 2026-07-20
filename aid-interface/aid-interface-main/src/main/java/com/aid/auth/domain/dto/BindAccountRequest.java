package com.aid.auth.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 绑定账号请求参数
 *
 * @author 视觉AID
 */
@Data
public class BindAccountRequest {

    /**
     * 绑定类型 (phone/email/wechat)
     */
    @NotBlank(message = "绑定类型不能为空")
    private String bindType;

    /**
     * 目标地址 (手机号或邮箱)
     */
    @NotBlank(message = "目标地址不能为空")
    private String target;

    /**
     * 验证码
     */
    @NotBlank(message = "验证码不能为空")
    private String code;

    /**
     * 第三方登录凭证 (绑定微信时使用)
     */
    private String accessToken;

    /**
     * 第三方登录openid
     */
    private String openid;
}
