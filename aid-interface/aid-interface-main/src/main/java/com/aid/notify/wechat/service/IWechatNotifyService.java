package com.aid.notify.wechat.service;

import java.math.BigDecimal;

import com.aid.notify.wechat.vo.WechatNotifyPreferenceVO;
import com.aid.notify.wechat.vo.WechatTemplateSendResult;

/**
 * 微信模板消息推送门面。
 */
public interface IWechatNotifyService
{
    /** 审核事件：提交审核 */
    String AUDIT_EVENT_SUBMITTED = "submitted";

    /** 审核事件：审核通过 */
    String AUDIT_EVENT_PASSED = "passed";

    /** 审核事件：审核不通过 */
    String AUDIT_EVENT_REJECTED = "rejected";

    /** 审核事件：发布（公开） */
    String AUDIT_EVENT_PUBLISHED = "published";

    /** 审核事件：审核回撤（后台撤销审核通过并下架） */
    String AUDIT_EVENT_REVOKED = "revoked";

    void notifyTaskStarted(Long taskId);

    void notifyTaskTerminal(Long taskId);

    void notifyBalanceInsufficient(Long userId, String bizType, Long bizId, BigDecimal requiredAmount);

    /**
     * 内容审核与发布状态变更推送。
     *
     * @param targetType 审核对象类型（project/episode，见 AuditTargetTypeEnum）
     * @param targetId   审核对象ID（项目ID或剧集ID）
     * @param auditEvent 审核事件（本接口 AUDIT_EVENT_* 常量）
     * @param reason     驳回原因（仅审核不通过时有值，可空）
     */
    void notifyContentAudit(String targetType, Long targetId, String auditEvent, String reason);

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
