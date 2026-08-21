package com.aid.rps.helper;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 执行直驱分镜退款动作并保留可判断的资金处理结果。
 *
 * @author 视觉AID
 */
public final class StoryboardDirectRefundExecutor
{
    private StoryboardDirectRefundExecutor()
    {
    }

    /**
     * 执行退款动作。
     *
     * @param refundAction 退款动作
     * @return 退款结果
     */
    public static RefundResult execute(BooleanSupplier refundAction)
    {
        Objects.requireNonNull(refundAction, "refundAction");
        try
        {
            return new RefundResult(refundAction.getAsBoolean(), null);
        }
        catch (RuntimeException e)
        {
            return new RefundResult(false, e);
        }
    }

    public record RefundResult(boolean succeeded, RuntimeException error)
    {
    }
}
