package com.aid.notify.wechat.vo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * 用户微信推送偏好。
 */
@Data
@Builder
public class WechatNotifyPreferenceVO
{
    private Boolean systemEnabled;

    private Boolean userEnabled;

    private Boolean wechatBound;

    /** 余额提醒资格阈值 */
    @Builder.Default
    private BigDecimal balanceReminderThreshold = BigDecimal.ZERO;

    @Builder.Default
    private List<String> rules = new ArrayList<>();
}
