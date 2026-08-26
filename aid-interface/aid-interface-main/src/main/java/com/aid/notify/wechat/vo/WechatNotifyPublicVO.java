package com.aid.notify.wechat.vo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Builder;
import lombok.Data;

/**
 * C端公开微信推送说明。
 */
@Data
@Builder
public class WechatNotifyPublicVO
{
    /** 后台总开关 */
    private Boolean enabled;

    /** 余额提醒资格阈值 */
    @Builder.Default
    private BigDecimal balanceReminderThreshold = BigDecimal.ZERO;

    /** 用户可见说明 */
    @Builder.Default
    private List<String> rules = new ArrayList<>();

    /** 当前登录用户个人推送开关；未登录时不返回。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean userEnabled;

    /** 当前登录用户是否已绑定微信公众号 OpenID；未登录时不返回。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean wechatBound;

    /** 平台推送配置就绪、个人开关和微信绑定均满足时为 true；未登录时不返回。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean pushAvailable;
}
