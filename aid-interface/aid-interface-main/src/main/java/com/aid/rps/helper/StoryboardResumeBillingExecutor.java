package com.aid.rps.helper;

import java.math.BigDecimal;
import java.util.Objects;

import cn.hutool.core.util.StrUtil;

/**
 * 执行分镜续生结算与退款并返回统一资金处理结果。
 *
 * @author 视觉AID
 */
public final class StoryboardResumeBillingExecutor
{
    private static final String RESUME_MARKER_PREFIX = "RESUME_TRACE:";

    private StoryboardResumeBillingExecutor()
    {
    }

    /**
     * 顺序执行续生结算和退款。
     *
     * @param settleAction 结算动作
     * @param refundAction 退款动作
     * @return 资金处理结果
     */
    public static BillingResult execute(Runnable settleAction, Runnable refundAction)
    {
        Objects.requireNonNull(settleAction, "settleAction");
        Objects.requireNonNull(refundAction, "refundAction");
        try
        {
            settleAction.run();
            refundAction.run();
            return new BillingResult(true, null);
        }
        catch (RuntimeException e)
        {
            return new BillingResult(false, e);
        }
    }

    /**
     * 判断首轮计费是否仍在处理中。
     *
     * @param billingStatus 计费状态
     * @return 是否处理中
     */
    public static boolean isPrimaryBillingPending(String billingStatus)
    {
        return "FROZEN".equalsIgnoreCase(billingStatus)
                || "SETTLING".equalsIgnoreCase(billingStatus)
                || "REFUNDING".equalsIgnoreCase(billingStatus)
                || "PARTIAL_SUCCESS".equalsIgnoreCase(billingStatus);
    }

    /**
     * 判断当前执行是否属于续生轮次。
     *
     * @param taskRemark 父任务备注
     * @param hasSucceededBatch 是否已有成功批次
     * @return 是否按续生计费收口
     */
    public static boolean isResumeExecution(String taskRemark, boolean hasSucceededBatch)
    {
        return hasSucceededBatch || StrUtil.startWith(taskRemark, RESUME_MARKER_PREFIX);
    }

    /**
     * 解析续生计费标记。
     *
     * @param marker 续生计费标记
     * @return 标记内容
     */
    public static ResumeMarker parseMarker(String marker)
    {
        String frozenSeparatorValue = "|FROZEN:";
        if (!StrUtil.startWith(marker, RESUME_MARKER_PREFIX))
        {
            throw new IllegalArgumentException("invalid resume marker");
        }
        int frozenSeparator = marker.indexOf(frozenSeparatorValue);
        if (frozenSeparator <= RESUME_MARKER_PREFIX.length())
        {
            throw new IllegalArgumentException("invalid resume marker");
        }
        String traceId = marker.substring(RESUME_MARKER_PREFIX.length(), frozenSeparator);
        BigDecimal frozenAmount = new BigDecimal(
                marker.substring(frozenSeparator + frozenSeparatorValue.length()));
        int roundSeparator = traceId.lastIndexOf("_r");
        if (roundSeparator <= 0 || roundSeparator + 2 >= traceId.length())
        {
            throw new IllegalArgumentException("invalid resume marker");
        }
        int retryRound = Integer.parseInt(traceId.substring(roundSeparator + 2));
        if (StrUtil.isBlank(traceId) || frozenAmount.signum() < 0
                || retryRound < 1)
        {
            throw new IllegalArgumentException("invalid resume marker");
        }
        return new ResumeMarker(traceId, frozenAmount, retryRound);
    }

    /**
     * 计算批次最终计费状态。
     *
     * @param wholeTaskRefunded 是否整单退款
     * @param batchSucceeded 批次是否成功
     * @return 最终计费状态
     */
    public static String terminalBatchBillingStatus(boolean wholeTaskRefunded,
                                                     boolean batchSucceeded)
    {
        if (wholeTaskRefunded || !batchSucceeded)
        {
            return "REFUNDED";
        }
        return "SETTLED";
    }

    public record BillingResult(boolean succeeded, RuntimeException error)
    {
    }

    public record ResumeMarker(String traceId, BigDecimal frozenAmount, int retryRound)
    {
    }
}
