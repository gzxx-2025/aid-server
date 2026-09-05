package com.aid.providerbalance.model;

import lombok.Data;

/** 供应商余额监控全局配置，序列化到 aid_config/provider_balance/settings。 */
@Data
public class ProviderBalanceSettings {
    /** 模块总开关，默认关闭。 */
    private boolean enabled;
    /** 每日邮件余额汇总开关。 */
    private boolean dailyReportEnabled;
    /** 日报触发时间，HH:mm。 */
    private String dailyReportTime = "09:00";
    /** 全渠道默认重复提醒间隔。 */
    private int defaultRepeatIntervalMinutes = 360;
    /** 所有渠道均失败后的重试间隔。 */
    private int failureRetryMinutes = 10;
    /** 余额采样快照保留天数。 */
    private int snapshotRetentionDays = 90;
    /** 通知发送记录保留天数。 */
    private int deliveryRetentionDays = 180;
    /** 短信模板 ID；短信宝渠道下可填写本地内容模板。 */
    private String smsTemplateId;
    /** 微信模板消息 ID。 */
    private String wechatTemplateId;
    private String wechatJumpUrl;
    /** 微信模板字段名可适配不同模板。 */
    private String wechatProviderField = "thing1";
    private String wechatBalanceField = "amount2";
    private String wechatStatusField = "thing3";
    private String wechatTimeField = "time4";
}
