package com.aid.notify.wechat.vo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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
}
