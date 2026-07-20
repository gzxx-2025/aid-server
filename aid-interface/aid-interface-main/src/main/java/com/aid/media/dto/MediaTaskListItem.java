package com.aid.media.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 媒体任务列表项（精简字段，不含大体积快照）
 */
@Data
@Builder
public class MediaTaskListItem {

    private Long taskId;

    private Long projectId;

    private Long episodeId;

    // 媒体类型：IMAGE / VIDEO / TEXT。
    private String mediaType;

    // 协议标识。
    private String protocol;

    // 模型名称。
    private String modelName;

    // 任务提示词（截取前200字符）。
    private String prompt;

    // 任务状态。
    private String status;

    // OSS持久化URL（出参拼 CDN/localDomain）。
    @MediaUrl
    private String ossUrl;

    // 文本生成结果。
    private String textContent;

    // 错误信息。
    private String errorMessage;
    // 错误码（机器可读）。
    private String errorCode;

    // 错误大类：BALANCE / QUOTA / UPSTREAM_TECH / UPSTREAM_CONTENT / RESULT / INTERNAL / USER_INPUT。
    private String errorType;

    // 错误来源：USER / MERCHANT / PROVIDER / PLATFORM。
    private String errorSource;

    // 是否需要充值。
    private Boolean needRecharge;

    // 充值主体：USER / MERCHANT。
    private String rechargeOwner;

    // 是否可重试。
    private Boolean retryable;

    // 退款状态：NONE / PENDING / DONE / PARTIAL / NOT_REQUIRED。
    private String refundStatus;

    // 计费状态。
    private String billingStatus;

    // 实际扣费金额（元）。
    private BigDecimal actualCost;

    // 创建时间。
    private String createTime;

    // 更新时间。
    private String updateTime;
}
