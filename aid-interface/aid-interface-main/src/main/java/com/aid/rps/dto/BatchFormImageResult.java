package com.aid.rps.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批量形态图生成返回结果，允许部分成功（tasks 成功 / failedForms 失败）。
 *
 * @author 视觉AID
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchFormImageResult
{
    /** 成功创建的任务ID列表，方便前端直接传给 cancel-batch */
    private List<Long> taskIds;

    /** 成功创建的每个 form 对应的任务详情 */
    private List<TaskItem> tasks;

    /** 创建失败的 form 列表（前置校验通过但任务创建阶段失败的） */
    private List<FailedFormItem> failedForms;

    /**
     * 单个 form 的任务信息（成功）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskItem
    {
        /** 从表形态ID */
        private Long formId;

        /** 创建的任务ID */
        private Long taskId;

        /** 任务状态（创建后为 PENDING） */
        private String status;
    }

    /**
     * 单个 form 的失败信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedFormItem
    {
        /** 从表形态ID */
        private Long formId;

        /** 失败原因（短文案） */
        private String message;
    }
}
