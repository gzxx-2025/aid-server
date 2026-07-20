package com.aid.aid.domain.media;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.aid.common.aid.oss.annotation.MediaUrl;
import com.aid.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 媒体任务实体 aid_media_task
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_media_task")
public class AidMediaTask extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    // 发起用户 ID（匿名时可为空）。
    private Long userId;

    // 项目ID。
    @TableField("project_id")
    private Long projectId;

    // 剧集ID（电影模式为0）。
    @TableField("episode_id")
    private Long episodeId;

    // 媒体类型：IMAGE / VIDEO / TEXT。
    private String mediaType;

    // 协议名称：如 dashscope-image、vidu-video、ark-text。
    private String protocol;

    // 任务实际模型名。
    private String modelName;

    // 任务提示词。
    private String prompt;

    // 幂等哈希：用于避免重复任务/重复扣费。
    private String requestHash;

    // 请求原文 JSON 快照。
    private String requestJson;

    // 上游响应原文 JSON 快照。
    private String responseJson;

    // 上游 provider 任务ID。
    private String providerTaskId;

    // 任务状态。
    private String status;

    // 计费状态。
    private String billingStatus;

    // 计费链路追踪 ID。
    private String billingTraceId;

    // 预冻结积分：prepareBilling 时冻结，settle/refund 时清空。
    private java.math.BigDecimal frozenAmount;

    // 计费快照：命中SKU+价格+规则版本，防止后续改价影响历史任务。
    private String billingSnapshotJson;

    // 实际扣费积分。
    private java.math.BigDecimal actualCost;

    // 计费参数：参与匹配的参数值（如resolution、duration）。
    private String billingParamJson;

    // 文本差额结算状态：INIT=未开始, PROCESSING=处理中, DONE=已完成（CAS控制并发退款）。
    private String textSettleStatus;

    // 上游原始URL（外部 TOS/压缩可能是完整 URL，MediaUrlSerializer 会按 http/https 原样输出）。
    @MediaUrl
    private String originUrl;

    // OSS持久化URL（存相对路径，出参自动拼 CDN/localDomain）。
    @MediaUrl
    private String ossUrl;

    /**
     * OSS 补偿候选标记（数据库虚拟生成列）：
     * status=SUCCEEDED、origin_url 非空且 oss_url 为空时为 1。
     */
    @TableField(
        value = "oss_pending",
        insertStrategy = FieldStrategy.NEVER,
        updateStrategy = FieldStrategy.NEVER
    )
    private Integer ossPending;

    // 文本生成结果：同步 Chat 类接口返回的助手正文，非 URL 场景使用。
    private String resultText;

    // 错误信息。
    private String errorMessage;

    // 轮询次数。
    private Integer retryCount;

    /** 回填业务记录主键（aid_comic_asset.id 或 aid_gen_record.id），与 callback_category 成对使用。 */
    @TableField("callback_record_id")
    private Long callbackRecordId;

    /** 回填目标：asset / gen_record，与 IGenResultCallbackService#fillResultUrl 的 category 一致。 */
    @TableField("callback_category")
    private String callbackCategory;

    /**
     * 业务含义：批量媒体接口写入的批次号，同一 HTTP 批量请求内多行任务共用一个 UUID；
     * 单条图片/视频/文本接口创建的任务本字段为空，便于按 batch_id 做进度聚合查询。
     */
    @TableField("batch_id")
    private String batchId;

    /** 业务任务ID（如 aid_extract_task.id），用于关联触发本媒体任务的业务任务 */
    @TableField("biz_task_id")
    private Long bizTaskId;

    /** 业务任务类型（如 extract），与 biz_task_id 配合定位具体业务表 */
    @TableField("biz_task_type")
    private String bizTaskType;

    /** 调度模式: DIRECT/CALLBACK_FIRST/POLL_ONLY */
    @TableField("dispatch_mode")
    private String dispatchMode;

    /** 下次轮询时间（调度中心使用） */
    @TableField("next_poll_time")
    private java.util.Date nextPollTime;

    /** 回调等待截止时间（CALLBACK_FIRST模式） */
    @TableField("callback_deadline")
    private java.util.Date callbackDeadline;

    /** 调度策略快照JSON（任务创建时冻结） */
    @TableField("schedule_snapshot_json")
    private String scheduleSnapshotJson;

    /** 合成批次号 */
    @TableField("compose_batch_id")
    private String composeBatchId;

    /** 成片时长(秒) */
    @TableField("output_duration_seconds")
    private Long outputDurationSeconds;
}
