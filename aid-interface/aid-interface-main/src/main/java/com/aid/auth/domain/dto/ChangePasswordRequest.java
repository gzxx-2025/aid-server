package com.aid.auth.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求。
 *
 * @author 视觉AID
 */
@Data
public class ChangePasswordRequest {

    /** 当前密码 */
    @NotBlank(message = "旧密码不能为空")
    @Size(max = 20, message = "旧密码长度不能超过20位")
    private String oldPassword;

    /** 新密码 */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 5, max = 20, message = "密码长度5至20位")
    private String newPassword;

    /** 确认密码 */
    @NotBlank(message = "确认密码不能为空")
    @Size(min = 5, max = 20, message = "密码长度5至20位")
    private String confirmPassword;
}
