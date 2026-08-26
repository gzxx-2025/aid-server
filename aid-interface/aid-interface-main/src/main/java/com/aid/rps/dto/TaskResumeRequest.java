package com.aid.rps.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 统一「继续生成（续生）」请求 DTO，按父任务 task_type 自动路由到对应续生实现。
 *
 * @author 视觉AID
 */
@Data
public class TaskResumeRequest
{
    /** 父任务 ID（aid_extract_task.id，必填） */
    @NotNull(message = "任务ID不能为空")
    @Positive(message = "任务ID无效")
    private Long taskId;
}
