package com.aid.notify.wechat.config;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * 微信模板消息推送配置，整体存储在 aid_config(wx_notify/templateConfig)。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WechatNotifyConfig
{
    /** 后台总开关；关闭时所有任务推送都跳过 */
    private Boolean enabled = Boolean.FALSE;

    /** 模板消息点击跳转基础地址 */
    private String jumpUrlBase;

    /** 单用户每日推送上限 */
    private Integer dailyUserLimit = 20;

    /** 单用户每分钟推送上限 */
    private Integer minuteUserLimit = 3;

    /** 余额提醒资格阈值：单次充值金额大于该值，才允许后续余额不足提醒 */
    private BigDecimal balanceReminderThreshold = BigDecimal.ZERO;

    /** 模板集合 */
    private Map<String, WechatNotifyTemplateConfig> templates = new LinkedHashMap<>();
}
