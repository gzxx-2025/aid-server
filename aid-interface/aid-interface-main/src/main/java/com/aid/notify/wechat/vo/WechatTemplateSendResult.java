package com.aid.notify.wechat.vo;

import lombok.Data;

/**
 * 微信模板消息发送结果。
 */
@Data
public class WechatTemplateSendResult
{
    private Integer errcode;

    private String errmsg;

    private Long msgid;

    private String rawResponse;

    public boolean success()
    {
        return Integer.valueOf(0).equals(errcode);
    }
}
