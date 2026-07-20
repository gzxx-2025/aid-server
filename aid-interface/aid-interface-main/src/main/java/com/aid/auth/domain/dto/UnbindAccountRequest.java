package com.aid.auth.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 解绑账号请求参数
 *
 * @author 视觉AID
 */
@Data
public class UnbindAccountRequest {

    /**
     * 解绑类型 (sms/email/wechat)
     */
    @NotBlank(message = "解绑类型不能为空")
    private String unbindType;

    /**
     * 验证码（解绑手机/邮箱时需要）
     */
    private String code;
}
