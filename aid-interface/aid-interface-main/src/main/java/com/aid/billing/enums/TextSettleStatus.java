package com.aid.billing.enums;

/**
 * 文本差额结算状态：通过DB字段 text_settle_status 做CAS，防止并发重复退款。
 */
public final class TextSettleStatus {

    private TextSettleStatus() {
    }

    /** 未开始（初始状态，新建任务默认值） */
    public static final String INIT = "INIT";

    /** 处理中（CAS抢到的线程持有） */
    public static final String PROCESSING = "PROCESSING";

    /** 已完成（终态，幂等标记） */
    public static final String DONE = "DONE";

    /** 部分补扣完成（TOKEN 补扣余额不足，待后续追补） */
    public static final String PARTIAL_DONE = "PARTIAL_DONE";
}
