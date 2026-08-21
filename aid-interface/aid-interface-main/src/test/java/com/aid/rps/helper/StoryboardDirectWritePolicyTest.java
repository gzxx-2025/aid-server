package com.aid.rps.helper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StoryboardDirectWritePolicyTest
{
    @Test
    void fullOverwriteReplacesOnlyAtFirstActualWrite()
    {
        StoryboardDirectWritePolicy.Decision first =
                StoryboardDirectWritePolicy.decide(true, false, false);
        assertTrue(first.replaceStoryboard());
        assertTrue(first.cleanupNonManualPlots());
        assertTrue(first.resetSortOrder());
        assertTrue(first.resetSceneSequence());

        StoryboardDirectWritePolicy.Decision resume =
                StoryboardDirectWritePolicy.decide(true, true, false);
        assertFalse(resume.replaceStoryboard());
        assertFalse(resume.cleanupNonManualPlots());
        assertFalse(resume.resetSortOrder());
        assertFalse(resume.resetSceneSequence());
    }

    @Test
    void selectiveWriteKeepsTimelineAndSequenceWhileCleaningItsScope()
    {
        StoryboardDirectWritePolicy.Decision decision =
                StoryboardDirectWritePolicy.decide(true, false, true);
        assertTrue(decision.replaceStoryboard());
        assertTrue(decision.cleanupNonManualPlots());
        assertFalse(decision.resetSortOrder());
        assertFalse(decision.resetSceneSequence());
    }

    @Test
    void freshNonOverwriteGenerationStillRebuildsDerivedPlots()
    {
        StoryboardDirectWritePolicy.Decision decision =
                StoryboardDirectWritePolicy.decide(false, false, false);
        assertFalse(decision.replaceStoryboard());
        assertTrue(decision.cleanupNonManualPlots());
        assertFalse(decision.resetSortOrder());
        assertTrue(decision.resetSceneSequence());
    }

    @Test
    void preExecutionRefundOnlyTargetsUnsettledFrozenBatches()
    {
        assertTrue(StoryboardDirectWritePolicy.isRefundableBeforeExecution("PENDING", "FROZEN"));
        assertTrue(StoryboardDirectWritePolicy.isRefundableBeforeExecution("PROCESSING", "FROZEN"));
        assertTrue(StoryboardDirectWritePolicy.isRefundableBeforeExecution("FAILED", "FROZEN"));
        assertFalse(StoryboardDirectWritePolicy.isRefundableBeforeExecution("SUCCEEDED", "SETTLED"));
        assertFalse(StoryboardDirectWritePolicy.isRefundableBeforeExecution("FAILED", "REFUNDED"));
    }

    @Test
    void resumeShotLimitIncludesAlreadyPersistedTaskShots()
    {
        assertFalse(StoryboardDirectWritePolicy.exceedsShotLimit(4_900, 50, 50, 5_000));
        assertTrue(StoryboardDirectWritePolicy.exceedsShotLimit(4_900, 50, 51, 5_000));
    }
}
