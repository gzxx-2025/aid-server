package com.aid.auth.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 首次设置密码请求。
 *
 * @author 视觉AID
 */
@Data
public class SetPasswordRequest {

    /** 验证渠道：sms / email */
    @NotBlank(message = "验证方式不能为空")
    private String verifyType;

    /** set_password 场景验证码 */
    @NotBlank(message = "验证码不能为空")
    private String code;

    /** 新密码 */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 5, max = 20, message = "密码长度5至20位")
    private String newPassword;

    /** 确认密码 */
    @NotBlank(message = "确认密码不能为空")
    @Size(min = 5, max = 20, message = "密码长度5至20位")
    private String confirmPassword;
}
