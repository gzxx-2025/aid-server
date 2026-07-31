package com.aid.media.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.aid.media.model.ScheduleStrategy;
import com.aid.media.util.TaskLivenessDecider.Verdict;

class TaskLivenessDeciderTest
{
    private static final long ACCEPTED_AT = 1_000_000L;

    @Test
    void shouldKeepTaskAliveInsideBothWindows()
    {
        Verdict verdict = TaskLivenessDecider.decide(
                ACCEPTED_AT + 299_000L, ACCEPTED_AT, ACCEPTED_AT, 3600, 300);

        assertEquals(Verdict.ALIVE, verdict);
    }

    @Test
    void shouldMarkTaskStalledAfterProgressTimeout()
    {
        Verdict verdict = TaskLivenessDecider.decide(
                ACCEPTED_AT + 301_000L, ACCEPTED_AT, ACCEPTED_AT, 3600, 300);

        assertEquals(Verdict.STALLED, verdict);
    }

    @Test
    void shouldPreferAbsoluteExpiryWhenBothWindowsAreExceeded()
    {
        Verdict verdict = TaskLivenessDecider.decide(
                ACCEPTED_AT + 3_601_000L, ACCEPTED_AT, ACCEPTED_AT, 3600, 300);

        assertEquals(Verdict.EXPIRED, verdict);
    }

    @Test
    void shouldRemainAliveBeyondLifeThresholdWhenOfficialProgressIsRecent()
    {
        Verdict verdict = TaskLivenessDecider.decide(
                ACCEPTED_AT + 3_601_000L,
                ACCEPTED_AT,
                ACCEPTED_AT + 3_590_000L,
                3600,
                300);

        assertEquals(Verdict.ALIVE, verdict);
    }

    @Test
    void shouldIgnoreProgressTimestampEarlierThanAcceptance()
    {
        Verdict verdict = TaskLivenessDecider.decide(
                ACCEPTED_AT + 299_000L, ACCEPTED_AT, ACCEPTED_AT - 600_000L, 3600, 300);

        assertEquals(Verdict.ALIVE, verdict);
    }

    @Test
    void shouldRemainAliveExactlyAtBoundary()
    {
        Verdict verdict = TaskLivenessDecider.decide(
                ACCEPTED_AT + 300_000L, ACCEPTED_AT, ACCEPTED_AT, 3600, 300);

        assertEquals(Verdict.ALIVE, verdict);
    }

    @Test
    void shouldUseSafeDefaultsForMissingSnapshotValues()
    {
        ScheduleStrategy strategy = new ScheduleStrategy();

        assertEquals(3600, strategy.effectiveMaxLifeSeconds());
        assertEquals(3600, strategy.effectiveProgressTimeoutSeconds());
    }
}
