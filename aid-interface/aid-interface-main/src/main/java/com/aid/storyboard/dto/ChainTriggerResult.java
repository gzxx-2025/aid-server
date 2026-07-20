package com.aid.storyboard.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lombok.Data;

/**
 * 合并接口链式触发结果：提示词批量任务终态后触发出图/出片子任务的返回信息。
 * 由 {@code StoryboardStepChainService.onPromptBatchTerminal} 返回，供上游（MQ Consumer / 本地编排器）
 * 在推送提示词任务终态事件（complete / partial_failed）时，把子任务 ID 直接塞进事件 payload，
 * 前端一个事件即可拿到出图/出片子任务 ID，无需额外的 chain_started 事件与断线重连兜底。
 *
 * @author 视觉AID
 */
@Data
public class ChainTriggerResult
{
    /** 触发的子出图/出片父任务 ID（aid_extract_task.id）；未触发（无待处理分镜/非合并任务/触发失败）时为 null */
    private Long childTaskId;

    /** 链式触发产生的全部子任务ID，按提交顺序排列。 */
    private List<Long> childTaskIds;

    /** 子任务类型：storyboard_image_generate（出图）/ storyboard_video_generate（出片）；未触发时为 null */
    private String childTaskType;

    /** 父任务是否携带 chainNext（即期望触发子任务） */
    private boolean chainRequested;

    /** 携带 chainNext 但子任务未创建成功时为 true */
    private boolean chainFailed;

    /** 链式触发失败的简短用户可见原因 */
    private String message;

    public ChainTriggerResult() {}

    public ChainTriggerResult(Long childTaskId, String childTaskType)
    {
        this(childTaskId, childTaskType, childTaskId != null, false, null);
    }

    public ChainTriggerResult(Long childTaskId, String childTaskType, boolean chainRequested,
                              boolean chainFailed, String message)
    {
        this(childTaskId, childTaskType,
                childTaskId == null ? Collections.emptyList() : Collections.singletonList(childTaskId),
                chainRequested, chainFailed, message);
    }

    public ChainTriggerResult(Long childTaskId, String childTaskType, List<Long> childTaskIds,
                              boolean chainRequested, boolean chainFailed, String message)
    {
        List<Long> safeTaskIds = normalizeChildTaskIds(childTaskIds);
        this.childTaskId = childTaskId != null ? childTaskId : firstChildTaskId(safeTaskIds);
        this.childTaskIds = safeTaskIds;
        this.childTaskType = childTaskType;
        this.chainRequested = chainRequested;
        this.chainFailed = chainFailed;
        this.message = message;
    }

    /** 空结果：非合并任务或无子任务触发 */
    public static ChainTriggerResult empty()
    {
        return new ChainTriggerResult(null, null, false, false, null);
    }

    public static ChainTriggerResult success(Long childTaskId, String childTaskType)
    {
        return new ChainTriggerResult(childTaskId, childTaskType, true, false, null);
    }

    public static ChainTriggerResult success(List<Long> childTaskIds, String childTaskType)
    {
        return new ChainTriggerResult(null, childTaskType, childTaskIds, true, false, null);
    }

    public static ChainTriggerResult failed(String message)
    {
        return new ChainTriggerResult(null, null, true, true, message);
    }

    public static ChainTriggerResult failed(String childTaskType, String message)
    {
        return new ChainTriggerResult(null, childTaskType, true, true, message);
    }

    public static ChainTriggerResult failed(String childTaskType, List<Long> childTaskIds, String message)
    {
        return new ChainTriggerResult(null, childTaskType, childTaskIds, true, true, message);
    }

    private static List<Long> normalizeChildTaskIds(List<Long> taskIds)
    {
        if (taskIds == null || taskIds.isEmpty())
        {
            return new ArrayList<>();
        }
        List<Long> result = new ArrayList<>();
        for (Long taskId : taskIds)
        {
            if (taskId != null && taskId > 0 && !result.contains(taskId))
            {
                result.add(taskId);
            }
        }
        return result;
    }

    private static Long firstChildTaskId(List<Long> taskIds)
    {
        return taskIds == null || taskIds.isEmpty() ? null : taskIds.get(0);
    }
}
