package com.aid.common.aid.sms.dto;

import lombok.Data;

/**
 * 短信测试发送请求
 *
 * @author 视觉AID
 */
@Data
public class SmsTestRequest {

    /** 接收手机号 */
    private String phone;

    /** 测试验证码（可选），不传默认使用 1234 */
    private String code;
}
