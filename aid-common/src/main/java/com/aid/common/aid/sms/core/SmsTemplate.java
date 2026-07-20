package com.aid.common.aid.sms.core;

import com.aid.common.aid.sms.entity.SmsResult;

import java.util.Map;

/**
 * 短信模板
 *
 * @author 视觉AID
 *
 */
public interface SmsTemplate {

    /**
     * 发送短信。
     *
     * @param phones     电话号(多个逗号分割)
     * @param templateId 模板id
     * @param param      模板对应参数
     */
    SmsResult send(String phones, String templateId, Map<String, String> param);

}
