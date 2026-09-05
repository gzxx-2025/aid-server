package com.aid.skill.task;

import com.aid.skill.service.ISkillInvocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Reconciles stale versioned Runtime runs. */
@Component("skillRuntimeTask")
@RequiredArgsConstructor
@Slf4j
public class SkillRuntimeTask {
    private final ISkillInvocationService invocationService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public void reconcileStaleRuns() {
        if (!running.compareAndSet(false, true)) {
            log.info("Skill Runtime reconciliation is already running; skipping this trigger");
            return;
        }
        try {
            invocationService.reconcileStaleRuns();
        } catch (Exception error) {
            log.error("Skill Runtime reconciliation failed, errorType={}",
                    error.getClass().getSimpleName(), error);
        } finally {
            running.set(false);
        }
    }
}
