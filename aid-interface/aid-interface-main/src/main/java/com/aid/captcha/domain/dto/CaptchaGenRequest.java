package com.aid.captcha.domain.dto;

import lombok.Data;

/**
 * 生成验证码请求参数。
 *
 * 一般无需传 type，由 aid_config 的 type 配置决定；如需强制指定类型可传入。
 *
 * @author 视觉AID
 */
@Data
public class CaptchaGenRequest {

    /**
     * 验证码类型（可选）：SLIDER/ROTATE/WORD_IMAGE_CLICK/CONCAT。
     * 不传则由服务端配置决定（含 RANDOM）。
     */
    private String type;
}
