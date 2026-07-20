package com.aid.rps.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 单任务取消请求（场景A：提取任务中途停止）
 *
 * @author 视觉AID
 */
@Data
public class CancelTaskRequest
{
    /** 要取消的任务ID */
    @NotNull(message = "任务ID不能为空")
    private Long taskId;
}
