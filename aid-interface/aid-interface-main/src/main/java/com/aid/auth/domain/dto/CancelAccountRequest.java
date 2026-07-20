package com.aid.auth.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 注销账号请求参数。账号注销需二次验证码确认，防止会话被劫持后被一键注销。
 *
 * @author 视觉AID
 */
@Data
public class CancelAccountRequest {

    /**
     * 验证方式：sms / email
     * - sms：通过用户已绑定手机号发送验证码；
     * - email：通过用户已绑定邮箱发送验证码；
     * 用户无任何绑定时禁止注销。
     */
    @NotBlank(message = "验证方式不能为空")
    private String verifyType;

    /**
     * 验证码。通过 /auth/sendCode（scene=cancel）发送，校验按 cancel 场景隔离。
     */
    @NotBlank(message = "验证码不能为空")
    private String code;
}
