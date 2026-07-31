package com.aid.billing.dto;

import com.aid.billing.model.BillingSnapshot;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 计费计算结果：BillingAmountCalculator 的返回值。
 */
@Data
public class BillingCalcResult {

    /** 是否命中SKU（FIXED模式默认true） */
    private boolean matched;

    /** 命中的SKU编码 */
    private String skuCode;

    /** 命中的SKU名称 */
    private String skuName;

    /** 计算出的金额 */
    private BigDecimal amount;

    /** 计费快照（写入 aid_media_task.billing_snapshot_json） */
    private BillingSnapshot snapshot;

    /** 错误信息（未命中时） */
    private String errorMessage;

    /**
     * FIXED模式快速构建
     */
    public static BillingCalcResult fixed(BigDecimal amount, BillingSnapshot snapshot) {
        BillingCalcResult r = new BillingCalcResult();
        r.setMatched(true);
        r.setAmount(amount);
        r.setSnapshot(snapshot);
        return r;
    }

    /**
     * SKU模式命中构建
     */
    public static BillingCalcResult sku(String skuCode, String skuName, BigDecimal amount, BillingSnapshot snapshot) {
        BillingCalcResult r = new BillingCalcResult();
        r.setMatched(true);
        r.setSkuCode(skuCode);
        r.setSkuName(skuName);
        r.setAmount(amount);
        r.setSnapshot(snapshot);
        return r;
    }

    /**
     * 未命中构建
     */
    public static BillingCalcResult notMatched(String errorMessage) {
        BillingCalcResult r = new BillingCalcResult();
        r.setMatched(false);
        r.setErrorMessage(errorMessage);
        return r;
    }
}
