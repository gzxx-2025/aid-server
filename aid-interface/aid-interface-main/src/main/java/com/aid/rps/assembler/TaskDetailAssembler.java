package com.aid.rps.assembler;

import com.aid.common.error.TaskErrorSnapshot;
import java.text.SimpleDateFormat;
import java.util.Objects;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidExtractTask;
import com.aid.common.error.ErrorNormalizer;
import com.aid.common.error.RefundStatusMapper;
import com.aid.common.error.TaskErrorResult;
import com.aid.rps.dto.TaskDetailVO;

/**
 * 通用任务 VO 转换器
 *
 * @author 视觉AID
 */
public class TaskDetailAssembler {

    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    public static TaskDetailVO toDetailVO(AidExtractTask task)
    {
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);

        // 优先读取终态快照，历史记录才从原文归类。
        String errorCode = null;
        String errorType = null;
        String errorSource = null;
        Boolean needRecharge = null;
        String rechargeOwner = null;
        Boolean retryable = null;
        String userMessage = task.getErrorMessage();

        if (StrUtil.isNotBlank(task.getErrorMessage()) || StrUtil.isNotBlank(task.getErrorDetailJson()))
        {
            TaskErrorResult normalized = TaskErrorSnapshot.fromTask(task);
            errorCode = normalized.getErrorCode();
            errorType = normalized.getErrorType();
            errorSource = normalized.getErrorSource();
            needRecharge = normalized.isNeedRecharge();
            rechargeOwner = normalized.getRechargeOwner();
            retryable = normalized.isRetryable();
            userMessage = normalized.getUserMessage();
        }

        // 从 billingStatus 派生 refundStatus（精确版本,区分"预冻结失败"和"已退款"）
        String refundStatus = RefundStatusMapper.resolveWithFrozen(
                task.getStatus(), task.getBillingStatus(),
                Objects.nonNull(task.getFrozenAmount()) && task.getFrozenAmount().signum() > 0);

        return TaskDetailVO.builder()
            .taskId(task.getId())
            .projectId(task.getProjectId())
            .episodeId(task.getEpisodeId())
            .taskType(task.getTaskType())
            .status(task.getStatus())
            .inputSnapshot(task.getInputSnapshot())
            .resultData(task.getResultData())
            .errorMessage(userMessage)
            .errorCode(errorCode)
            .errorType(errorType)
            .errorSource(errorSource)
            .needRecharge(needRecharge)
            .rechargeOwner(rechargeOwner)
            .retryable(retryable)
            .billingStatus(task.getBillingStatus())
            .refundStatus(refundStatus)
            .totalCount(task.getTotalCount())
            .modelCode(task.getModelCode())
            .createTime(Objects.nonNull(task.getCreateTime()) ? sdf.format(task.getCreateTime()) : null)
            .updateTime(Objects.nonNull(task.getUpdateTime()) ? sdf.format(task.getUpdateTime()) : null)
            .build();
    }
}
