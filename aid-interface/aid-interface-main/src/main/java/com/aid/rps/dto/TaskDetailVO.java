package com.aid.rps.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 通用任务详情响应VO
 *
 * @author 视觉AID
 */
@Data
@Builder
public class TaskDetailVO {

    // 任务ID。
    private Long taskId;

    // 项目ID。
    private Long projectId;

    // 剧集ID。
    private Long episodeId;

    // 任务类型：asset_extract / form_generate 等。
    private String taskType;

    // 任务状态：PENDING / PROCESSING / SUCCEEDED / FAILED。
    private String status;

    // 输入快照JSON。
    private String inputSnapshot;

    // 结果数据JSON（SUCCEEDED 时有值）。
    private String resultData;

    // 错误信息（FAILED 时有值）。
    private String errorMessage;
    // 错误码（机器可读）。
    private String errorCode;

    // 错误大类（粗粒度）：BALANCE / QUOTA / UPSTREAM_TECH / UPSTREAM_CONTENT / RESULT / INTERNAL / USER_INPUT。
    private String errorType;

    // 错误来源：USER / MERCHANT / PROVIDER / PLATFORM。
    private String errorSource;

    // 是否需要充值。
    private Boolean needRecharge;

    // 充值主体：USER / MERCHANT。
    private String rechargeOwner;

    // 是否可重试。
    private Boolean retryable;

    // 上游原始错误信息（排查用）。
    private String rawErrorMessage;

    // 计费状态：INIT / FROZEN / SUCCESS / FAILED / PARTIAL_SUCCESS。
    private String billingStatus;

    // 退款状态：NONE / PENDING / DONE / PARTIAL / NOT_REQUIRED。
    private String refundStatus;

    // 处理总数。
    private Integer totalCount;

    // 模型编码。
    private String modelCode;

    // 创建时间。
    private String createTime;

    // 更新时间。
    private String updateTime;

    // 排队位次（1-based，仅 QUEUED 状态有值）。
    private Integer queuePosition;
}
