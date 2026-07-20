package com.aid.rps.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 提取任务状态查询请求DTO
 *
 * @author 视觉AID
 */
@Data
public class TaskStatusQueryRequest
{
    /** 任务ID */
    @NotNull(message = "任务ID不能为空")
    private Long taskId;
}
