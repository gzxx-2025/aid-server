package com.aid.auth.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 重置密码请求参数
 *
 * @author 视觉AID
 */
@Data
public class ResetPasswordRequest {

    /**
     * 目标地址 (手机号或邮箱)
     */
    @NotBlank(message = "目标地址不能为空")
    private String target;

    /**
     * 重置方式 (phone/email)
     */
    @NotBlank(message = "重置方式不能为空")
    private String resetType;

    /**
     * 验证码
     */
    @NotBlank(message = "验证码不能为空")
    private String code;

    /**
     * 新密码
     */
    @NotBlank(message = "新密码不能为空")
    private String newPassword;

    /**
     * 确认密码
     */
    @NotBlank(message = "确认密码不能为空")
    private String confirmPassword;
}
