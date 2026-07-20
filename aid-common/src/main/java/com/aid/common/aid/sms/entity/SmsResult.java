package com.aid.common.aid.sms.entity;

import lombok.Builder;
import lombok.Data;

/**
 * 上传返回体
 *
 * @author 视觉AID
 */
@Data
@Builder
public class SmsResult {

    /**
     * 是否成功
     */
    private boolean isSuccess;

    /**
     * 响应消息
     */
    private String message;

    /**
     * 实际响应体
     * 可自行转换为 SDK 对应的 SendSmsResponse
     */
    private String response;
}
