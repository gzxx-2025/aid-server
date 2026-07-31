package com.aid.media.dto;

import lombok.Data;

/**
 * 上游回调请求体（通用结构，各供应商适配器负责将原生回调映射到此类）。
 */
@Data
public class CallbackRequest {

    /** 服务商任务ID（上游返回的 providerTaskId） */
    private String providerTaskId;

    /** 任务状态：SUCCEEDED / FAILED / PROCESSING */
    private String status;

    /** 结果 URL（成功时） */
    private String resultUrl;

    /** 错误信息（失败时） */
    private String errorMessage;

    /** 供应商原始回调报文（供审计） */
    private String rawPayload;
}
