package com.aid.common.error;

/**
 * 把内部 billingStatus 映射为对前端稳定的 refundStatus。
 * 前端只消费 refundStatus,不需要知道 billing 内部状态机。
 *
 * @author 视觉AID
 */
public final class RefundStatusMapper {

    /** 无需退款（任务成功或从未冻结） */
    public static final String NOT_REQUIRED = "NOT_REQUIRED";
    /** 退款处理中（FROZEN / REFUNDING） */
    public static final String PENDING = "PENDING";
    /** 退款已完成 */
    public static final String DONE = "DONE";
    /** 部分退款（TOKEN 补扣余额不足） */
    public static final String PARTIAL = "PARTIAL";
    /** 未知（兜底） */
    public static final String NONE = "NONE";

    private RefundStatusMapper() {
    }

    /**
     * 把任务 billingStatus + 任务 status 映射成 refundStatus。
     *
     * @param taskStatus   任务状态 SUCCEEDED / FAILED / CANCELLED 等
     * @param billingStatus 计费状态 INIT / FROZEN / SETTLING / REFUNDING / SUCCESS / FAILED / PARTIAL_SUCCESS
     */
    public static String resolve(String taskStatus, String billingStatus) {
        if (billingStatus == null) {
            return NONE;
        }
        // 任务成功 → 无需退款
        if ("SUCCEEDED".equals(taskStatus)) {
            return NOT_REQUIRED;
        }
        // 任务失败/取消 → 按 billingStatus 判断退款进度
        switch (billingStatus) {
            case "INIT":
                // 还没冻结,不需要退款
                return NOT_REQUIRED;
            case "FROZEN":
            case "REFUNDING":
                // 冻结未退 / 退款中
                return PENDING;
            case "FAILED":
                // FAILED 语义双重：预冻结失败（从未扣款）或失败后冻结金额已退回。
                // 此处拿不到 frozenAmount 无法区分，保守返回 NOT_REQUIRED；
                // 需精确区分时调用方应改用 resolveWithFrozen()。
                return NOT_REQUIRED;
            case "SUCCESS":
            case "SETTLING":
                // 任务失败但 billing 仍是 SUCCESS/SETTLING,说明扣款未退回,应视为异常
                return NONE;
            case "PARTIAL_SUCCESS":
                return PARTIAL;
            default:
                return NONE;
        }
    }

    /**
     * 带冻结金额的精确版本:能区分"预冻结失败(从未扣钱)"和"已退款"。
     *
     * @param taskStatus    任务状态
     * @param billingStatus 计费状态
     * @param hasFrozen     是否曾经冻结过(frozenAmount > 0)
     */
    public static String resolveWithFrozen(String taskStatus, String billingStatus, boolean hasFrozen) {
        if (billingStatus == null) {
            return NONE;
        }
        if ("SUCCEEDED".equals(taskStatus)) {
            return NOT_REQUIRED;
        }
        switch (billingStatus) {
            case "INIT":
                return NOT_REQUIRED;
            case "FROZEN":
            case "REFUNDING":
                return PENDING;
            case "FAILED":
                // 关键区分:曾冻结过 → 已退款;从未冻结 → 不需要退款
                return hasFrozen ? DONE : NOT_REQUIRED;
            case "SUCCESS":
            case "SETTLING":
                return NONE;
            case "PARTIAL_SUCCESS":
                return PARTIAL;
            default:
                return NONE;
        }
    }
}
