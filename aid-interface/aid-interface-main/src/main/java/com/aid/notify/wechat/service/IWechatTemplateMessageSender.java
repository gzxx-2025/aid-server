package com.aid.notify.wechat.service;

import com.aid.notify.wechat.vo.WechatTemplatePayload;
import com.aid.notify.wechat.vo.WechatTemplateSendResult;

/**
 * 微信模板消息发送器。
 */
public interface IWechatTemplateMessageSender
{
    WechatTemplateSendResult send(WechatTemplatePayload payload);
}
