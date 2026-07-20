package com.aid.media.enums;

public enum MediaBillingStatus {
    // 初始状态：尚未执行计费。
    INIT,
    // 预冻结成功：费用已从余额冻结到冻结余额，等待任务完成后结算或退回。
    FROZEN,
    // 结算中：FROZEN → SETTLING，防止重复结算。
    SETTLING,
    // 退款中：FROZEN → REFUNDING，防止重复退款。
    REFUNDING,
    // 结算成功：任务成功，冻结金额已从 frozenBalance 扣减并累加到 totalConsumption。
    SUCCESS,
    // 计费失败：预冻结失败（余额不足），或任务失败后冻结金额已退回。
    FAILED
}
