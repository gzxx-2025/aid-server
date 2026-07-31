package com.aid.rps.queue;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 排队任务上下文（持久化到 Redis taskq:ctx:{taskId}），承载放行时重新派发所需参数与并发维度键。
 *
 * 本对象跨版本存活在 Redis 中，增删字段必须保持前后向兼容：反序列化方
 * （{@code TaskQueueService.OBJECT_MAPPER}）已关闭 FAIL_ON_UNKNOWN_PROPERTIES，
 * 升级后仍能读旧版本写入的多余字段。
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

    /** AI模型编码（放行后随派发消息下发给消费端，不参与并发准入） */
    private String modelCode;

    /** 任务类型（决定调度放行后的派发分支） */
    private String taskType;

    /** 派发模式：MQ=放行后发 RocketMQ 消费；LOCAL=放行后走本地线程池异步（MQ 关闭时降级） */
    private String dispatchMode;

    /** 派发周期令牌；续跑时等于本轮 billingTraceId，用于拒绝上一轮延迟消息和重复执行。 */
    private String dispatchToken;

    /** 归属实例 ID（仅 dispatchMode=LOCAL 有意义，调度器只放行 owner 实例的 LOCAL 任务） */
    private String ownerInstanceId;

    /** 入队时间戳（毫秒），用于 FIFO 排序与排队时长老化 */
    private Long enqueueMillis;
}
