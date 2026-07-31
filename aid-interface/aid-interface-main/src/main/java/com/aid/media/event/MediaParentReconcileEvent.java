package com.aid.media.event;

import org.springframework.context.ApplicationEvent;

/**
 * 媒体子任务全部离开在途状态后触发的父任务重启对账事件。
 * 事件只携带通用任务标识，具体业务编排由各自监听器恢复。
 */
public class MediaParentReconcileEvent extends ApplicationEvent {

    private final Long parentTaskId;
    private final String taskType;

    public MediaParentReconcileEvent(Object source, Long parentTaskId, String taskType) {
        super(source);
        this.parentTaskId = parentTaskId;
        this.taskType = taskType;
    }

    public Long getParentTaskId() {
        return parentTaskId;
    }

    public String getTaskType() {
        return taskType;
    }
}
