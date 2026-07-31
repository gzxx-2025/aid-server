package com.aid.rps.queue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TaskCycleLivenessPolicyTest {

    @Test
    void shouldAllowTokenlessProcessingCycleWhenDatabaseIsAlsoTokenless() {
        assertTrue(TaskCycleLivenessPolicy.matches("PROCESSING", null, null));
        assertTrue(TaskCycleLivenessPolicy.matches("FINALIZING", "", null));
    }

    @Test
    void shouldRejectTokenlessCycleWhenDatabaseHasAnotherToken() {
        assertFalse(TaskCycleLivenessPolicy.matches("PROCESSING", null, "cycle-2"));
    }

    @Test
    void shouldRequireExactTokenForBilledProcessingCycle() {
        assertTrue(TaskCycleLivenessPolicy.matches("PROCESSING", "cycle-1", "cycle-1"));
        assertFalse(TaskCycleLivenessPolicy.matches("PROCESSING", "cycle-1", "cycle-2"));
    }

    @Test
    void shouldNeverTreatTokenlessQueuedCycleAsLive() {
        assertFalse(TaskCycleLivenessPolicy.matches("PENDING", null, null));
        assertFalse(TaskCycleLivenessPolicy.matches("QUEUED", "", null));
    }
}
