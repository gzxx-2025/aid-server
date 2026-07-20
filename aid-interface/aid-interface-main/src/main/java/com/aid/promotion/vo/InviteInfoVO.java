package com.aid.promotion.vo;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 我的邀请信息出参（邀请页主数据）
 *
 * @author 视觉AID
 */
@Data
public class InviteInfoVO
{
    /** 邀请活动是否开启（关闭时以下字段不返回） */
    private boolean enabled;

    /** 我的邀请码（活动开启时首次查询自动生成） */
    private String inviteCode;

    /** 当前返佣比例(%)，被邀请人充值到账积分按此比例返佣 */
    private BigDecimal rebateRatio;

    /** 单笔订单返佣积分上限（0为不限） */
    private BigDecimal rebateMaxPerOrder;

    /** 已邀请人数 */
    private Long invitedCount;

    /** 累计已获得返佣积分（不含已撤回） */
    private BigDecimal totalRebate;
}
