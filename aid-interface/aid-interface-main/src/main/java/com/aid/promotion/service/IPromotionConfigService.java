package com.aid.promotion.service;

import com.aid.promotion.domain.InviteConfig;
import com.aid.promotion.domain.RegisterBonusConfig;

/**
 * 营销配置读取Service接口
 * 统一从 aid_config 读取注册送积分 / 邀请激励配置并类型化，配置缺失时返回安全默认值（活动关闭）。
 *
 * @author 视觉AID
 */
public interface IPromotionConfigService
{
    /**
     * 读取注册送积分配置（category=register_bonus）
     *
     * @return 类型化配置（配置缺失或异常时返回关闭状态，绝不抛异常）
     */
    RegisterBonusConfig getRegisterBonusConfig();

    /**
     * 读取邀请激励配置（category=invite）
     *
     * @return 类型化配置（配置缺失或异常时返回关闭状态，绝不抛异常）
     */
    InviteConfig getInviteConfig();
}
