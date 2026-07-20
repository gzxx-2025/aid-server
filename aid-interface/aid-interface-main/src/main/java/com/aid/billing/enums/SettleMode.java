package com.aid.billing.enums;

import lombok.Getter;

/**
 * 结算策略：描述任务完成后如何处理预扣与实际金额的差异。
 */
@Getter
public enum SettleMode {

    /** 直接结算：预扣金额即为最终金额（图片/视频适用，成功转消费、失败全退） */
    DIRECT_SETTLE("DIRECT_SETTLE", "直接结算"),

    /** 差额退款：预扣偏大，完成后按实际用量退差额，不补扣（文本模型适用） */
    REFUND_ONLY("REFUND_ONLY", "差额退款");

    private final String code;
    private final String desc;

    SettleMode(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
