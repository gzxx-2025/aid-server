package com.aid.rps.helper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

class StoryboardDirectRefundExecutorTest
{
    @Test
    void falseRefundResultIsNotReportedAsSucceeded()
    {
        BooleanSupplier refundAction = mock(BooleanSupplier.class);
        when(refundAction.getAsBoolean()).thenReturn(false);

        StoryboardDirectRefundExecutor.RefundResult result =
                StoryboardDirectRefundExecutor.execute(refundAction);

        assertFalse(result.succeeded());
        assertNull(result.error());
    }

    @Test
    void refundExceptionIsReturnedForCompensationHandling()
    {
        BooleanSupplier refundAction = mock(BooleanSupplier.class);
        when(refundAction.getAsBoolean()).thenThrow(new IllegalStateException("refund failed"));

        StoryboardDirectRefundExecutor.RefundResult result =
                StoryboardDirectRefundExecutor.execute(refundAction);

        assertFalse(result.succeeded());
        assertNotNull(result.error());
    }
}
