package com.aid.notify.wechat.service;

import java.math.BigDecimal;

import com.aid.notify.wechat.vo.WechatNotifyPreferenceVO;
import com.aid.notify.wechat.vo.WechatTemplateSendResult;

/**
 * 微信模板消息推送门面。
 */
public interface IWechatNotifyService
{
    void notifyTaskStarted(Long taskId);

    void notifyTaskTerminal(Long taskId);

    void notifyBalanceInsufficient(Long userId, String bizType, Long bizId, BigDecimal requiredAmount);

    /**
     * 支付订单退款成功通知。
     *
     * @param userId       退款用户ID
     * @param orderId      支付订单ID
     * @param orderName    订单名称
     * @param orderNo      商户订单号
     * @param refundReason 退款原因
     * @param refundAmount 实际退款金额（元）
     */
    void notifyOrderRefund(Long userId, Long orderId, String orderName, String orderNo,
                           String refundReason, BigDecimal refundAmount);

    WechatNotifyPreferenceVO getPreference(Long userId);

    WechatNotifyPreferenceVO enable(Long userId);

    WechatNotifyPreferenceVO disable(Long userId);

    WechatTemplateSendResult testSend(String openid, String eventType);
}
