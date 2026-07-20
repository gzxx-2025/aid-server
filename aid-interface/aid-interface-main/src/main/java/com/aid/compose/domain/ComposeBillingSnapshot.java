package com.aid.compose.domain;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 合成计费快照（落 aid_media_task.billing_snapshot_json）。
 * 记录档单价构成与预冻结积分，防止后续改价影响历史任务；结算/退款依据本快照执行。
 *
 * @author 视觉AID
 */
@Data
public class ComposeBillingSnapshot {

    /** 分辨率档 */
    private String resolution;

    /** 编码(默认 H.264) */
    private String codec;

    /** 文档原价 元/分钟 */
    private BigDecimal unitPriceYuan;

    /** 元→积分 汇率(默认100) */
    private int creditRate;

    /** 利润倍率(默认1.1) */
    private BigDecimal profitMultiplier;

    /** 档单价(积分/分钟) = unitPriceYuan × creditRate × profitMultiplier */
    private BigDecimal creditPerMinute;

    /** 预估秒数 */
    private long estimatedSeconds;

    /** 预冻结积分 = ceil(estimatedSeconds/60) × creditPerMinute */
    private BigDecimal frozenCredits;
}
