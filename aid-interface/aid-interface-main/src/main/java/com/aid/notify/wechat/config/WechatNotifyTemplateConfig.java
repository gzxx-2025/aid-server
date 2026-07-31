package com.aid.notify.wechat.config;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;

/**
 * 微信模板消息单模板配置。
 */
@Data
public class WechatNotifyTemplateConfig
{
    /** 模板是否启用 */
    private Boolean enabled = Boolean.TRUE;

    /** 后台展示标题 */
    private String title;

    /** 微信模板ID */
    private String templateId;

    /** 业务字段到微信官方关键词的映射 */
    private Map<String, String> fields = new LinkedHashMap<>();
}
