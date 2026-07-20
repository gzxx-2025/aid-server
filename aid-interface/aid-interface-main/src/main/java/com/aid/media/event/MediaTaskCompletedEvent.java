package com.aid.media.event;

import org.springframework.context.ApplicationEvent;

/**
 * 异步任务终态完成事件：事务提交后发布，触发排队任务消费。
 */
public class MediaTaskCompletedEvent extends ApplicationEvent {

    private final Long taskId;
    private final Long userId;

    public MediaTaskCompletedEvent(Object source, Long taskId, Long userId) {
        super(source);
        this.taskId = taskId;
        this.userId = userId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public Long getUserId() {
        return userId;
    }
}
