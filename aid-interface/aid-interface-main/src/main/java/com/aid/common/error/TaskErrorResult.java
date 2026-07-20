package com.aid.common.error;

import lombok.Builder;
import lombok.Data;

/**
 * 统一任务错误结果模型。
 * SSE error 事件、任务详情接口、媒体任务响应共用此结构。
 * 前端根据 errorCode / needRecharge / rechargeOwner / retryable 做机器判断。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class TaskErrorResult {
    /** 任务状态：FAILED / CANCELLED（便于 SSE 断线后前端一次拿完整终态） */
    private String taskStatus;
    /** 错误码（机器可读，前端 switch 判断用） */
    private String errorCode;

    /**
     * 错误大类（粗粒度）：BALANCE / QUOTA / UPSTREAM_TECH / UPSTREAM_CONTENT / RESULT / INTERNAL / USER_INPUT。
     * 前端做默认分支处理时用这个,不用枚举每个具体 errorCode。
     */
    private String errorType;

    /** 错误来源：USER / MERCHANT / PROVIDER / PLATFORM */
    private String errorSource;
    /** 面向用户的友好提示（前端可直接展示） */
    private String userMessage;

    /** 上游/内部原始错误信息（前端可选展示，主要用于排查） */
    private String rawMessage;
    /** 是否需要充值 */
    private boolean needRecharge;

    /** 充值主体：USER / MERCHANT / null */
    private String rechargeOwner;

    /** 是否可重试 */
    private boolean retryable;
    /** 计费终态：INIT / FROZEN / SUCCESS / FAILED / PARTIAL_SUCCESS */
    private String billingStatus;

    /** 退款状态：NONE / PENDING / DONE / PARTIAL / NOT_REQUIRED */
    private String refundStatus;
    /**
     * 兼容旧版 errorMessage 字段。
     * 旧前端只读 errorMessage，新前端读结构化字段。
     */
    public String getErrorMessage() {
        return userMessage;
    }
    /**
     * 从枚举 + 原始错误信息构建结果。
     * 默认 taskStatus=FAILED, refundStatus=NOT_REQUIRED, billingStatus=null（调用方可以 .toBuilder() 覆盖）
     */
    public static TaskErrorResult of(TaskErrorCode code, String rawMessage) {
        return TaskErrorResult.builder()
                .taskStatus("FAILED")
                .errorCode(code.name())
                .errorType(code.getErrorType())
                .errorSource(code.getErrorSource())
                .userMessage(code.getUserMessage())
                .rawMessage(rawMessage)
                .needRecharge(code.isNeedRecharge())
                .rechargeOwner(code.getRechargeOwner())
                .retryable(code.isRetryable())
                .refundStatus("NOT_REQUIRED")
                .build();
    }

    /**
     * 从枚举构建（无原始信息）
     */
    public static TaskErrorResult of(TaskErrorCode code) {
        return of(code, null);
    }

    /**
     * 兜底：只有文案，无法归类时使用
     */
    public static TaskErrorResult unknown(String rawMessage) {
        return of(TaskErrorCode.UNKNOWN, rawMessage);
    }
}
