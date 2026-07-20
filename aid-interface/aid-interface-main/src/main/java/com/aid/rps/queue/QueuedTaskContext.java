package com.aid.rps.queue;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 排队任务上下文（持久化到 Redis taskq:ctx:{taskId}），承载放行时重新派发所需参数与并发维度键。
 *
 * @author 视觉AID
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueuedTaskContext
{
    /** 任务ID（aid_extract_task.id） */
    private Long taskId;

    /** 项目ID */
    private Long projectId;

    /** 剧集ID */
    private Long episodeId;

    /** 用户ID（用户维度并发） */
    private Long userId;

    /** AI模型编码（模型维度并发） */
    private String modelCode;

    /** 模型所属服务商ID（服务商维度并发，可空） */
    private Long providerId;

    /** 任务类型（决定调度放行后的派发分支） */
    private String taskType;

    /** 派发模式：MQ=放行后发 RocketMQ 消费；LOCAL=放行后走本地线程池异步（MQ 关闭时降级） */
    private String dispatchMode;

    /** 归属实例 ID（仅 dispatchMode=LOCAL 有意义，调度器只放行 owner 实例的 LOCAL 任务） */
    private String ownerInstanceId;

    /** 入队时间戳（毫秒），用于 FIFO 排序与排队时长老化 */
    private Long enqueueMillis;
}
