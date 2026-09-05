package com.aid.skill.listener;

import com.aid.media.event.MediaTaskCompletedEvent;
import com.aid.skill.service.ISkillInvocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 将统一媒体队列的终态收敛回 Skill Run。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillMediaTaskCompletedListener {

    private final ISkillInvocationService skillInvocationService;

    @EventListener
    public void onCompleted(MediaTaskCompletedEvent event) {
        if (event == null || event.getTaskId() == null) {
            return;
        }
        try {
            skillInvocationService.reconcileMediaTask(event.getTaskId());
        } catch (Exception e) {
            log.error("Skill Runtime任务终态回写失败, taskId={}, errorType={}", event.getTaskId(),
                    e.getClass().getSimpleName(), e);
        }
    }
}
