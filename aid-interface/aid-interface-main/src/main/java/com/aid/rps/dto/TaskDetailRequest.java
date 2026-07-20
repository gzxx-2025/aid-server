package com.aid.rps.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 通用任务详情查询请求DTO
 *
 * @author 视觉AID
 */
@Data
public class TaskDetailRequest {

    // 任务ID（必填）。
    @NotNull(message = "任务ID不能为空")
    private Long taskId;
}
