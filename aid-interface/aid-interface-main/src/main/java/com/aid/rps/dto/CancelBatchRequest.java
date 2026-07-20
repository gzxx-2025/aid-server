package com.aid.rps.dto;

import java.util.List;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 批量任务取消请求（场景B：批量生图/高清/视频停止剩余）
 *
 * @author 视觉AID
 */
@Data
public class CancelBatchRequest
{
    /** 要取消的任务ID列表 */
    @NotEmpty(message = "任务列表不能为空")
    private List<Long> taskIds;
}
