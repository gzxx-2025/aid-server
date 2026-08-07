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

    /** 测试验证码（可选），不传则随机生成六位数字 */
    private String code;
}
