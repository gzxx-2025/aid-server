package com.aid.media.event;

import org.springframework.context.ApplicationEvent;

/**
 * 媒体任务 OSS 持久化完成事件：。
 *
 * @author 视觉AID
 */
public class MediaTaskOssPersistedEvent extends ApplicationEvent {

    private final Long taskId;
    private final Long userId;

    public MediaTaskOssPersistedEvent(Object source, Long taskId, Long userId) {
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
