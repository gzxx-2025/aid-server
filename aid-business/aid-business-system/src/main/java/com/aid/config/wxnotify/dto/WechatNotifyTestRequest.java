package com.aid.config.wxnotify.dto;

import lombok.Data;

/**
 * 微信模板消息测试发送请求。
 */
@Data
public class WechatNotifyTestRequest
{
    /** 测试接收人的公众号 OpenID */
    private String openid;

    /** 事件类型：余额提醒、批量任务或订单退款 */
    private String eventType;
}
