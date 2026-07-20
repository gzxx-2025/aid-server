package com.aid.rps.enums;

/**
 * 资产提取任务计费状态枚举
 *
 * @author 视觉AID
 */
public enum ExtractBillingStatus {

    /** 初始 */
    INIT,

    /** 已冻结 */
    FROZEN,

    /** 结算中（FROZEN → SETTLING，防止重复结算） */
    SETTLING,

    /** 退款中（FROZEN → REFUNDING，防止重复退款） */
    REFUNDING,

    /** 已结算 */
    SUCCESS,

    /** 部分补扣完成（TOKEN 补扣余额不足，待后续追补） */
    PARTIAL_SUCCESS,

    /** 已退回 */
    FAILED
}
