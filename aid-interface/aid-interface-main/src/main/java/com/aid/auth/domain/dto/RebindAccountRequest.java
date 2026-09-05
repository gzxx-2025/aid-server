package com.aid.auth.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 手机号或邮箱换绑请求。
 *
 * @author 视觉AID
 */
@Data
public class RebindAccountRequest {

    /** 换绑类型：sms / email */
    @NotBlank(message = "换绑类型不能为空")
    private String bindType;

    /** 新手机号或邮箱 */
    @NotBlank(message = "新地址不能为空")
    @Size(max = 100, message = "新地址长度不能超过100位")
    private String newTarget;

    /** rebind_old 场景验证码 */
    @NotBlank(message = "旧验证码不能为空")
    @Size(max = 10, message = "旧验证码格式错误")
    private String oldCode;

    /** rebind_new 场景验证码 */
    @NotBlank(message = "新验证码不能为空")
    @Size(max = 10, message = "新验证码格式错误")
    private String newCode;
}
