package com.aid.auth.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发送验证码请求参数
 *
 * @author 视觉AID
 */
@Data
public class SendCodeRequest {

    /**
     * 目标地址（手机号或邮箱；解绑、注销、首次设置密码和换绑旧地址场景不需要传）
     */
    private String target;

    /**
     * 验证码类型 (sms/email)
     */
    @NotBlank(message = "验证码类型不能为空")
    private String codeType;

    /**
     * 业务场景 (login/bind/unbind/reset/cancel/set_password/rebind_old/rebind_new)
     */
    @NotBlank(message = "业务场景不能为空")
    private String scene;

    /**
     * 邀请码（可选，仅登录场景的新用户发送验证码前校验）
     */
    private String inviteCode;
}
