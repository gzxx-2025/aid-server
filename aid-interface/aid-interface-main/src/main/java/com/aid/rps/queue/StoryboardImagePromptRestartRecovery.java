package com.aid.rps.queue;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.common.utils.DateUtils;
import com.aid.rps.service.IExtractBillingService;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜图脚本批量任务（{@code storyboard_image_prompt_batch}）「重启自愈」回收策略。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class StoryboardImagePromptRestartRecovery implements BatchTaskRestartRecovery
{
    private static final String TASK_TYPE = "storyboard_image_prompt_batch";
    /** 子任务 biz_task_type（写入 aid_media_task），与 StoryboardImagePromptServiceImpl 保持一致 */
    private static final String BIZ_TASK_TYPE = "storyboard_image_prompt";

    private static final String MEDIA_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
    private static final String BIZ_TYPE_CREATE = "create";

    private static final String RESUME_MARKER_PREFIX = "RESUME_TRACE:";
    private static final String RESUME_MARKER_FROZEN_SEP = "|FROZEN:";

    @Autowired
    private IAidExtractTaskService extractTaskService;

    @Autowired
    private AidMediaTaskMapper aidMediaTaskMapper;

    @Autowired
    private IExtractBillingService extractBillingService;

    @Autowired
    private IAccountUpdateService accountUpdateService;

    @Override
    public boolean supports(String taskType)
    {
        return TASK_TYPE.equals(taskType);
    }

    @Override
    public boolean recover(Long taskId)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (java.util.Objects.isNull(task) || !TASK_TYPE.equals(task.getTaskType()))
        {
            return false; // 非本类型，交回通用回收
        }
        Long userId = task.getUserId();

        // 产出判定：本任务是否已有成功的 LLM 子任务（已落库 image_prompt durable）
        boolean produced = hasSucceededSubTask(taskId);

        // 续生轮：父任务 remark 带 RESUME_TRACE 标记（resume 接口写入，未跑完则未清除）
        String marker = StrUtil.nullToEmpty(task.getRemark());
        boolean isResume = marker.startsWith(RESUME_MARKER_PREFIX);

        // 计费收尾：按对应冻结 trace 退回，禁止误用父任务首跑 trace 退续生款
        try
        {
            if (isResume)
            {
                refundResumeTrace(taskId, userId, marker);
            }
            else
            {
                // 首跑被中断：父任务一次冻结整笔退回
                extractBillingService.refundBilling(taskId, userId);
            }
        }
        catch (Exception billingEx)
        {
            log.error("[RESTART-RECOVER] 分镜图脚本任务回收计费异常: taskId={}", taskId, billingEx);
        }

        // 父任务终态：有成功子任务 → PARTIAL_FAILED（保留续生入口）；否则 FAILED
        LambdaUpdateWrapper<AidExtractTask> taskUpd = Wrappers.lambdaUpdate();
        taskUpd.eq(AidExtractTask::getId, taskId);
        if (produced)
        {
            taskUpd.set(AidExtractTask::getStatus, TASK_STATUS_PARTIAL_FAILED);
            taskUpd.set(AidExtractTask::getErrorMessage, "服务重启中断，部分已完成，可继续生成");
        }
        else
        {
            taskUpd.set(AidExtractTask::getStatus, TASK_STATUS_FAILED);
            taskUpd.set(AidExtractTask::getErrorMessage, "服务重启中断，已退回");
        }
        taskUpd.set(AidExtractTask::getRemark, null); // 清除续生标记，避免下轮误结算
        taskUpd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        extractTaskService.update(taskUpd);

        log.warn("[RESTART-RECOVER] 分镜图脚本任务重启回收完成: taskId={}, produced={}, 续生轮={}", taskId, produced, isResume);
        return true;
    }

    /** 整笔退回独立 resume trace 的冻结款（marker：RESUME_TRACE:{traceId}|FROZEN:{amount}） */
    private void refundResumeTrace(Long taskId, Long userId, String marker)
    {
        try
        {
            int p1 = marker.indexOf(RESUME_MARKER_PREFIX) + RESUME_MARKER_PREFIX.length();
            int p2 = marker.indexOf(RESUME_MARKER_FROZEN_SEP);
            if (p2 <= p1)
            {
                log.error("[RESTART-RECOVER] 分镜图脚本 RESUME_TRACE 解析失败，跳过续生退款（交统一补偿兜底）: taskId={}, remark={}",
                        taskId, marker);
                return;
            }
            String resumeTraceId = marker.substring(p1, p2);
            BigDecimal totalFrozen = new BigDecimal(marker.substring(p2 + RESUME_MARKER_FROZEN_SEP.length()).trim());
            if (StrUtil.isBlank(resumeTraceId) || totalFrozen.compareTo(BigDecimal.ZERO) <= 0)
            {
                return;
            }
            // 账户退款按 traceId 幂等：本轮未跑完结算前被中断，此处为首次退款，不会重复打钱
            accountUpdateService.refund(userId, totalFrozen, resumeTraceId, BIZ_TYPE_CREATE, "分镜图脚本续生退款");
            log.info("[RESTART-RECOVER] 分镜图脚本续生退款完成: taskId={}, traceId={}, refunded={}", taskId, resumeTraceId, totalFrozen);
        }
        catch (Exception e)
        {
            log.error("[RESTART-RECOVER] 分镜图脚本续生退款异常: taskId={}, remark={}", taskId, marker, e);
        }
    }

    /** 本任务是否已有成功 LLM 子任务（aid_media_task） */
    private boolean hasSucceededSubTask(Long taskId)
    {
        Long cnt = aidMediaTaskMapper.selectCount(
                Wrappers.<AidMediaTask>lambdaQuery()
                        .eq(AidMediaTask::getBizTaskId, taskId)
                        .eq(AidMediaTask::getBizTaskType, BIZ_TASK_TYPE)
                        .eq(AidMediaTask::getStatus, MEDIA_STATUS_SUCCEEDED));
        return cnt != null && cnt > 0;
    }
}
