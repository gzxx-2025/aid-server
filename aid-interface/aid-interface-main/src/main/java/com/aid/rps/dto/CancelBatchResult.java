package com.aid.rps.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批量取消任务返回结果
 *
 * @author 视觉AID
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancelBatchResult
{
    /** 已受理停止/暂停的任务数量 */
    private int cancelCount;
}
