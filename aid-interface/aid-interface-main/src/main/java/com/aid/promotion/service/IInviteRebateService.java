package com.aid.promotion.service;

import com.aid.aid.domain.AidPayOrder;

/**
 * 邀请充值返佣Service接口（支付链路营销钩子）
 *
 * @author 视觉AID
 */
public interface IInviteRebateService
{
    /**
     * 充值到账后给邀请人发返佣（静默处理，绝不抛异常阻断支付回调）。
     * 返佣 = 订单到账积分 × 配置比例(%)，可配置单笔上限；
     * 幂等：orderNo 唯一（返佣记录）+ traceId={orderNo}_INVITE（余额流水）双重保证，
     * 支付回调重试不会重复发放。
     *
     * @param order 已支付成功的充值订单
     */
    void grantRechargeRebate(AidPayOrder order);

    /**
     * 订单退款时扣回该订单产生的返佣（静默处理，绝不阻断退款主流程）。
     * 邀请人余额不足导致扣回失败时记录错误日志待人工处理，返佣记录保持 granted。
     *
     * @param order 已退款的充值订单
     */
    void revokeRebateOnRefund(AidPayOrder order);
}
