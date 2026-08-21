package com.aid.rps.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class StoryboardResumeBillingExecutorTest
{
    @Test
    void reportsSuccessOnlyAfterBothMoneyActionsComplete()
    {
        Runnable settleAction = mock(Runnable.class);
        Runnable refundAction = mock(Runnable.class);

        StoryboardResumeBillingExecutor.BillingResult result =
                StoryboardResumeBillingExecutor.execute(settleAction, refundAction);

        assertTrue(result.succeeded());
        InOrder ordered = inOrder(settleAction, refundAction);
        ordered.verify(settleAction).run();
        ordered.verify(refundAction).run();
    }

    @Test
    void partialSuccessKeepsFailureVisibleForIdempotentRetry()
    {
        Runnable settleAction = mock(Runnable.class);
        Runnable refundAction = mock(Runnable.class);
        doThrow(new IllegalStateException("refund failed")).when(refundAction).run();

        StoryboardResumeBillingExecutor.BillingResult result =
                StoryboardResumeBillingExecutor.execute(settleAction, refundAction);

        assertFalse(result.succeeded());
        assertNotNull(result.error());
        verify(settleAction, times(1)).run();
        verify(refundAction, times(1)).run();
    }

    @Test
    void rejectsNewResumeWhilePrimaryBillingIsNotTerminal()
    {
        assertTrue(StoryboardResumeBillingExecutor.isPrimaryBillingPending("FROZEN"));
        assertTrue(StoryboardResumeBillingExecutor.isPrimaryBillingPending("SETTLING"));
        assertTrue(StoryboardResumeBillingExecutor.isPrimaryBillingPending("REFUNDING"));
        assertTrue(StoryboardResumeBillingExecutor.isPrimaryBillingPending("PARTIAL_SUCCESS"));
        assertFalse(StoryboardResumeBillingExecutor.isPrimaryBillingPending("SUCCESS"));
        assertFalse(StoryboardResumeBillingExecutor.isPrimaryBillingPending("FAILED"));
    }

    @Test
    void resumeMarkerIdentifiesRetryWithoutSucceededBatch()
    {
        assertTrue(StoryboardResumeBillingExecutor.isResumeExecution(
                "RESUME_TRACE:sb_resume_88_r2|FROZEN:1.20", false));
        assertTrue(StoryboardResumeBillingExecutor.isResumeExecution(null, true));
        assertFalse(StoryboardResumeBillingExecutor.isResumeExecution(null, false));
    }

    @Test
    void parsesRoundBoundMarkerAndRejectsLegacyMarkerWithoutRound()
    {
        StoryboardResumeBillingExecutor.ResumeMarker marker =
                StoryboardResumeBillingExecutor.parseMarker(
                        "RESUME_TRACE:sb_resume_88_r3|FROZEN:1.25");

        assertEquals("sb_resume_88_r3", marker.traceId());
        assertEquals(3, marker.retryRound());
        assertThrows(IllegalArgumentException.class,
                () -> StoryboardResumeBillingExecutor.parseMarker(
                        "RESUME_TRACE:legacy_trace|FROZEN:1.25"));
        assertThrows(IllegalArgumentException.class,
                () -> StoryboardResumeBillingExecutor.parseMarker("RESUME_TRACE:broken"));
    }

    @Test
    void wholeTaskRefundMarksSuccessfulAndFailedBatchesRefunded()
    {
        assertEquals("REFUNDED",
                StoryboardResumeBillingExecutor.terminalBatchBillingStatus(true, true));
        assertEquals("REFUNDED",
                StoryboardResumeBillingExecutor.terminalBatchBillingStatus(true, false));
        assertEquals("SETTLED",
                StoryboardResumeBillingExecutor.terminalBatchBillingStatus(false, true));
    }
}
