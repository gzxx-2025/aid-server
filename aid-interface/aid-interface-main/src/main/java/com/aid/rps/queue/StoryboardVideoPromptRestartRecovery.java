package com.aid.rps.queue;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.common.utils.DateUtils;
import com.aid.rps.service.IExtractBillingService;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 视频提示词批量任务（{@code storyboard_video_prompt_batch}）「重启自愈」回收策略。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class StoryboardVideoPromptRestartRecovery implements BatchTaskRestartRecovery
{
    private static final String TASK_TYPE = "storyboard_video_prompt_batch";
    /** 子任务 biz_task_type（写入 aid_media_task），与 StoryboardVideoPromptServiceImpl 保持一致 */
    private static final String BIZ_TASK_TYPE = "storyboard_video_prompt";

    private static final String MEDIA_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";

    @Autowired
    private IAidExtractTaskService extractTaskService;

    @Autowired
    private AidMediaTaskMapper aidMediaTaskMapper;

    @Autowired
    private IExtractBillingService extractBillingService;

    @Override
    public boolean supports(String taskType)
    {
        return TASK_TYPE.equals(taskType);
    }

    @Override
    public boolean recover(Long taskId)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || !TASK_TYPE.equals(task.getTaskType()))
        {
            return false; // 非本类型，交回通用回收
        }
        Long userId = task.getUserId();

        // 产出判定：本任务是否已有成功的 LLM 子任务（已落库 video_prompt durable）
        boolean produced = hasSucceededSubTask(taskId);
        // 计费快照在场才可安全地按 0 用量结算（否则 settleBilling 会降级为全额结算 → 误扣）
        boolean snapshotPresent = StrUtil.isNotBlank(
                extractBillingService.resolveBillingSnapshotJson(taskId, task.getBillingSnapshotJson()));

        boolean resumableBilling = false;
        try
        {
            if (produced && snapshotPresent)
            {
                // 0 用量结算：实际扣费=0 → 全额退回冻结 + 计费状态落 SUCCESS（满足续生 billing=SUCCESS 前置）
                Map<String, Object> zeroUsage = new HashMap<>();
                zeroUsage.put("input_tokens", 0);
                zeroUsage.put("output_tokens", 0);
                boolean settled = extractBillingService.settleBilling(taskId, userId, zeroUsage);
                resumableBilling = settled;
                if (!settled)
                {
                    // CAS 未抢到（已被其他线程推进）：billing 可能已是终态，保守按不可续生处理
                    log.warn("[RESTART-RECOVER] 视频提示词 0 用量结算 CAS 未命中，按不可续生处理: taskId={}", taskId);
                }
            }
            else
            {
                // 无产出 / 无快照：安全全额退回（billing → FAILED，不可续生，用户重新发起）
                extractBillingService.refundBilling(taskId, userId);
                resumableBilling = false;
            }
        }
        catch (Exception billingEx)
        {
            log.error("[RESTART-RECOVER] 视频提示词任务回收计费异常: taskId={}", taskId, billingEx);
            resumableBilling = false;
        }

        // 父任务终态：仅当确有产出且计费已收敛到 SUCCESS 才置可续生 PARTIAL_FAILED，否则 FAILED
        boolean partialFailed = produced && resumableBilling;
        LambdaUpdateWrapper<AidExtractTask> taskUpd = Wrappers.lambdaUpdate();
        taskUpd.eq(AidExtractTask::getId, taskId);
        if (partialFailed)
        {
            taskUpd.set(AidExtractTask::getStatus, TASK_STATUS_PARTIAL_FAILED);
            taskUpd.set(AidExtractTask::getErrorMessage, "服务重启中断，部分已完成，可继续生成");
        }
        else
        {
            taskUpd.set(AidExtractTask::getStatus, TASK_STATUS_FAILED);
            taskUpd.set(AidExtractTask::getErrorMessage, "服务重启中断，已退回");
        }
        taskUpd.set(AidExtractTask::getRemark, null);
        taskUpd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        extractTaskService.update(taskUpd);

        log.warn("[RESTART-RECOVER] 视频提示词任务重启回收完成: taskId={}, produced={}, snapshotPresent={}, 终态={}",
                taskId, produced, snapshotPresent, partialFailed ? TASK_STATUS_PARTIAL_FAILED : TASK_STATUS_FAILED);
        return true;
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
