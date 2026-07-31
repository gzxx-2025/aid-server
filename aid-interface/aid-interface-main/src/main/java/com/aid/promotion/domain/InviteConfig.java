package com.aid.promotion.domain;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 邀请激励配置（aid_config category=invite 的类型化快照）
 *
 * @author 视觉AID
 */
@Data
public class InviteConfig
{
    /** 总开关（关闭后不再绑定新关系，也不再返佣） */
    private boolean enabled;

    /** 被邀请人充值返佣比例(%)，支持小数，0 为不返佣 */
    private BigDecimal rebateRatio;

    /** 单笔订单返佣积分上限（null 或 <=0 为不限） */
    private BigDecimal rebateMaxPerOrder;
}
