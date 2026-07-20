package com.aid.rps.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MQ提取任务消息体
 *
 * @author 视觉AID
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractTaskMessage
{
    /** 提取任务ID */
    private Long taskId;

    /** 项目ID */
    private Long projectId;

    /** 剧集ID */
    private Long episodeId;

    /** 用户ID */
    private Long userId;

    /** AI模型编码 */
    private String modelCode;

    /** 任务类型（extract / image_upscale 等），用于 Consumer 分发 */
    private String taskType;
}
