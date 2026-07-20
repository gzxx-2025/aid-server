package com.aid.rps.queue;

import java.util.List;

import org.springframework.context.ApplicationEvent;

/**
 * 批量「提示词」任务终态事件，供合并接口链式触发器据此自动发起下一步出图/出片。
 *
 * @author 视觉AID
 */
public class BatchPromptTerminalEvent extends ApplicationEvent
{
    /** 父任务 ID（aid_extract_task.id） */
    private final Long taskId;
    /** 任务类型 */
    private final String taskType;
    /** 终态状态（SUCCEEDED / PARTIAL_FAILED） */
    private final String status;

    /**
     * 链式子任务 ID（回填字段）：Spring 同步事件下，监听器（StoryboardStepChainService）触发出图/出片后
     * 把子任务 ID 写回本字段，发布方（本地编排器）在 publishEvent 返回后读取，塞进终态事件 payload。
     */
    private Long chainChildTaskId;
    /** 链式子任务ID列表（回填字段），按提交顺序排列。 */
    private List<Long> chainChildTaskIds;
    /** 链式子任务类型（回填字段）：storyboard_image_generate / storyboard_video_generate */
    private String chainChildTaskType;

    /** Whether this terminal event carried chainNext and expected a child task. */
    private boolean chainRequested;

    /** Whether chainNext existed but the child task was not created. */
    private boolean chainFailed;

    /** Short user-facing reason for chain creation failure. */
    private String chainMessage;

    public BatchPromptTerminalEvent(Object source, Long taskId, String taskType, String status)
    {
        super(source);
        this.taskId = taskId;
        this.taskType = taskType;
        this.status = status;
    }

    public Long getTaskId() { return taskId; }

    public String getTaskType() { return taskType; }

    public String getStatus() { return status; }

    public Long getChainChildTaskId() { return chainChildTaskId; }

    public void setChainChildTaskId(Long chainChildTaskId) { this.chainChildTaskId = chainChildTaskId; }

    public List<Long> getChainChildTaskIds() { return chainChildTaskIds; }

    public void setChainChildTaskIds(List<Long> chainChildTaskIds) { this.chainChildTaskIds = chainChildTaskIds; }

    public String getChainChildTaskType() { return chainChildTaskType; }

    public void setChainChildTaskType(String chainChildTaskType) { this.chainChildTaskType = chainChildTaskType; }

    public boolean isChainRequested() { return chainRequested; }

    public void setChainRequested(boolean chainRequested) { this.chainRequested = chainRequested; }

    public boolean isChainFailed() { return chainFailed; }

    public void setChainFailed(boolean chainFailed) { this.chainFailed = chainFailed; }

    public String getChainMessage() { return chainMessage; }

    public void setChainMessage(String chainMessage) { this.chainMessage = chainMessage; }
}
