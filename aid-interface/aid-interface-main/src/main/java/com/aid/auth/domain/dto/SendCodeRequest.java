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
     * 目标地址 (手机号或邮箱，解绑场景不需要传)
     */
    private String target;

    /**
     * 验证码类型 (sms/email)
     */
    @NotBlank(message = "验证码类型不能为空")
    private String codeType;

    /**
     * 业务场景 (login/bind/unbind/reset)
     */
    @NotBlank(message = "业务场景不能为空")
    private String scene;
}
