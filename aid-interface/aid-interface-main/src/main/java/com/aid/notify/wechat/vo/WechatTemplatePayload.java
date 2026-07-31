package com.aid.notify.wechat.vo;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;

/**
 * 微信模板消息发送载荷。
 */
@Data
public class WechatTemplatePayload
{
    private String openid;

    private String templateId;

    private String url;

    private String clientMsgId;

    private Map<String, String> data = new LinkedHashMap<>();
}
