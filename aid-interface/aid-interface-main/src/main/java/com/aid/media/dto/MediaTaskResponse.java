package com.aid.media.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class MediaTaskResponse {

    // 平台任务ID：前端轮询主键。
    private Long taskId;

    // 媒体类型：IMAGE / VIDEO / TEXT。
    private String mediaType;

    // 协议标识：用于定位具体 provider。
    private String protocol;

    // 模型名称：本次任务实际使用的模型。
    private String modelName;

    // 任务状态：QUEUED/PENDING/PROCESSING/SUCCEEDED/FAILED。
    private String status;

    // 排队位置：仅在 status=QUEUED 时有值，表示前面还有几个任务等待。
    private Integer queuePosition;

    // 上游厂商任务ID：用于排查上游任务。
    private String providerTaskId;

    // 厂商原始产物 URL（外部完整 URL）。
    @MediaUrl
    private String originUrl;

    // 平台 OSS 持久化 URL（出参拼 CDN/localDomain）。
    @MediaUrl
    private String ossUrl;

    // 文本生成结果：TEXT 类型任务成功时返回助手输出全文。
    private String textContent;

    // 错误信息：任务失败时返回。
    private String errorMessage;
    // 错误码（机器可读，对应 TaskErrorCode 枚举）。
    private String errorCode;

    // 错误大类（粗粒度）：BALANCE / QUOTA / UPSTREAM_TECH / UPSTREAM_CONTENT / RESULT / INTERNAL / USER_INPUT。
    private String errorType;

    // 错误来源：USER / MERCHANT / PROVIDER / PLATFORM。
    private String errorSource;

    // 面向用户的友好提示。
    private String userMessage;

    // 上游原始错误信息（排查用）。
    private String rawMessage;

    // 是否需要充值。
    private boolean needRecharge;

    // 充值主体：USER / MERCHANT / null。
    private String rechargeOwner;

    // 是否可重试。
    private boolean retryable;

    // 退款状态：NONE / PENDING / DONE / PARTIAL / NOT_REQUIRED（从 billingStatus 派生）。
    private String refundStatus;

    // 计费状态：INIT/SUCCESS/FAILED。
    private String billingStatus;

    // 预扣金额（元）。
    private BigDecimal preHoldAmount;

    // 实际扣费金额（元）。
    private BigDecimal actualCost;

    // 计费模式：FIXED / SKU。
    private String billingMode;

    /**
     * 产物时长（秒，向上取整）：透传 aid_media_task.output_duration_seconds。
     * TTS 同步合成 / 合成任务成功时有值，其余场景为 null。
     */
    private Long outputDurationSeconds;
}
