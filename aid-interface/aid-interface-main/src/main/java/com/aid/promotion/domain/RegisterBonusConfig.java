package com.aid.promotion.domain;

import java.math.BigDecimal;
import java.util.Objects;

import com.aid.common.constant.AuthConstants;

import lombok.Data;

/**
 * 注册送积分配置（aid_config category=register_bonus 的类型化快照）
 *
 * @author 视觉AID
 */
@Data
public class RegisterBonusConfig
{
    /** 总开关 */
    private boolean enabled;

    /** 注册赠送积分数量 */
    private BigDecimal amount;

    /** 手机号注册是否参与 */
    private boolean smsEnabled;

    /** 邮箱注册是否参与（邮箱免费注册易被薅羊毛，默认关闭） */
    private boolean emailEnabled;

    /** 微信注册是否参与 */
    private boolean wechatEnabled;

    /**
     * 判断指定注册渠道是否参与注册送积分活动
     *
     * @param channel 注册渠道（sms/email/wechat）
     * @return 是否参与
     */
    public boolean isChannelEnabled(String channel)
    {
        if (Objects.equals(AuthConstants.BIND_TYPE_SMS, channel))
        {
            return smsEnabled;
        }
        if (Objects.equals(AuthConstants.BIND_TYPE_EMAIL, channel))
        {
            return emailEnabled;
        }
        if (Objects.equals(AuthConstants.BIND_TYPE_WECHAT, channel))
        {
            return wechatEnabled;
        }
        // 未知渠道一律不参与，防止后续新增登录方式时误发奖励
        return false;
    }
}
