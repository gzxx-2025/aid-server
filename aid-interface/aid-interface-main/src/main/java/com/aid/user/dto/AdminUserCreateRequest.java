package com.aid.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.aid.common.constant.UserConstants;

/**
 * 后台创建 C 端用户请求。
 *
 * @author 视觉AID
 */
@Data
public class AdminUserCreateRequest {

    /** 用户邮箱。 */
    @Email(message = "邮箱格式错误")
    @Size(max = UserConstants.LOGIN_ACCOUNT_MAX_LENGTH, message = "邮箱长度超限")
    private String email;

    /** 用户手机号。 */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式错误")
    private String phonenumber;
}
