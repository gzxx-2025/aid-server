package com.aid.rps.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidExtractTaskBillingSnapshot;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidExtractTaskBillingSnapshotService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.enums.BillingConstants;
import com.aid.billing.enums.BillingMode;
import com.aid.billing.enums.MeterType;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.BillingAmountCalculator;
import com.aid.billing.service.BillingRecordMetadataService;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.rps.enums.ExtractBillingStatus;
import com.aid.rps.service.IExtractBillingService;
import com.aid.rps.service.IExtractBillingService.ResumeBillingContext;
import com.aid.rps.service.IExtractBillingService.ResumeBillingRecovery;
import com.aid.rps.service.IExtractBillingService.ResumeTaskMutation;
import com.aid.rps.service.IExtractBillingService.ResumeTaskState;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 资产提取任务级计费实现：预冻结 → 结算/退回
 * 账户变更统一委托 IAccountUpdateService（按 userId 串行化），
 * 本类只负责任务计费状态管理 + 委托账户操作。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class ExtractBillingServiceImpl implements IExtractBillingService
{
    private static final String SNAPSHOT_STAGE_FROZEN = "FROZEN";
    private static final String SNAPSHOT_STAGE_SETTLED = "SETTLED";
    private static final String SNAPSHOT_STAGE_SETTLE_PLAN = "SETTLE_PLAN";
    private static final String RESUME_ROLLBACK_STATE_PREPARED = "PREPARED";
    private static final String RESUME_ROLLBACK_STATE_FUNDS_FROZEN = "FUNDS_FROZEN";
    private static final String RESUME_ROLLBACK_STATE_DISPATCH_INTENT = "DISPATCH_INTENT";
    private static final String RESUME_ROLLBACK_STATE_DISPATCH_CONFIRMED = "DISPATCH_CONFIRMED";
    private static final String RESUME_ROLLBACK_STATE_REQUIRED = "ROLLBACK_REQUIRED";
    private static final String SNAPSHOT_STAGE_RESUME_PREPARED = "RESUME_PREPARED";
    private static final String SNAPSHOT_STAGE_RESUME_FUNDS_FROZEN = "RESUME_FUNDS_FROZEN";
    private static final String SNAPSHOT_STAGE_RESUME_DISPATCH_INTENT = "RESUME_DISPATCH_INTENT";
    private static final String SNAPSHOT_STAGE_RESUME_DISPATCH_CONFIRMED = "RESUME_DISPATCH_CONFIRMED";
    private static final String SNAPSHOT_STAGE_RESUME_ROLLBACK_REQUIRED = "RESUME_ROLLBACK_REQUIRED";
    private static final List<String> RESUME_SNAPSHOT_STAGES = List.of(
            SNAPSHOT_STAGE_RESUME_PREPARED,
            SNAPSHOT_STAGE_RESUME_FUNDS_FROZEN,
            SNAPSHOT_STAGE_RESUME_DISPATCH_INTENT,
            SNAPSHOT_STAGE_RESUME_DISPATCH_CONFIRMED,
            SNAPSHOT_STAGE_RESUME_ROLLBACK_REQUIRED);
    private static final List<String> STALE_RESUME_RECOVERY_STAGES = List.of(
            SNAPSHOT_STAGE_RESUME_PREPARED,
            SNAPSHOT_STAGE_RESUME_FUNDS_FROZEN,
            SNAPSHOT_STAGE_RESUME_DISPATCH_INTENT,
            SNAPSHOT_STAGE_RESUME_ROLLBACK_REQUIRED);
    private static final long RESUME_RECOVERY_STALE_MILLIS = 60_000L;
    private static final String BILLING_BATCH_TYPE_MULTI_MODEL_EXTRACT = "asset_extract_multi_model";
    private static final String TASK_TYPE_ASSET_EXTRACT = "asset_extract";
    private static final String TASK_TYPE_STORYBOARD_IMAGE_PROMPT = "storyboard_image_prompt_batch";
    private static final String TASK_TYPE_STORYBOARD_VIDEO_PROMPT = "storyboard_video_prompt_batch";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    private static final String TASK_STATUS_FINALIZING = "FINALIZING";
    private static final String TASK_STATUS_RECOVERING = "RECOVERING";
    private static final String MEDIA_BIZ_TYPE_EXTRACT = "extract";
    private static final String MEDIA_BIZ_TYPE_IMAGE_PROMPT = "storyboard_image_prompt";
    private static final String MEDIA_BIZ_TYPE_VIDEO_PROMPT = "storyboard_video_prompt";
    private static final String USAGE_KEY_MODEL_USAGES = "model_usages";
    private static final String USAGE_KEY_AGGREGATION_COMPLETE = "aggregation_complete";
    private static final String USAGE_KEY_SUCCESSFUL_CALL_COUNT = "successful_call_count";
    private static final String USAGE_KEY_USAGE_CALL_COUNT = "usage_call_count";
    private static final String USAGE_KEY_SUCCESSFUL_USAGE_CALL_COUNT = "successful_usage_call_count";
    private static final String USAGE_KEY_INPUT_TOKENS = "input_tokens";
    private static final String USAGE_KEY_OUTPUT_TOKENS = "output_tokens";
    private static final String USAGE_KEY_CALL_USAGES = "call_usages";
    private static final String CALL_USAGE_KEY_MEDIA_TASK_ID = "media_task_id";
    private static final String CALL_USAGE_KEY_MODEL_CODE = "model_code";
    private static final String CALL_USAGE_KEY_SUCCESSFUL = "successful";
    private static final String CALL_USAGE_KEY_HAS_USAGE = "has_usage";
    private static final String SNAPSHOT_KEY_USAGE_START_MEDIA_TASK_ID = "usageStartMediaTaskId";
    private static final String SETTLE_PLAN_KEY_TRACE_ID = "traceId";
    private static final String SETTLE_PLAN_KEY_USAGE_DATA = "usageData";
    private static final String SNAPSHOT_KEY_EXPECTED_CALL_COUNT = "expectedCallCount";
    private static final String SNAPSHOT_KEY_UNIT_PRE_HOLD_AMOUNT = "unitPreHoldAmount";
    private static final String SNAPSHOT_KEY_CALL_ESTIMATES = "callEstimates";
    private static final String SNAPSHOT_KEY_SETTLED_CALLS = "settledCalls";
    private static final String MEDIA_TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String MEDIA_TASK_STATUS_FAILED = "FAILED";

    @Autowired
    private IAidExtractTaskService extractTaskService;

    @Autowired
    private IAidComicProjectService comicProjectService;

    @Autowired
    private AidMediaTaskMapper aidMediaTaskMapper;

    @Autowired
    private IAidExtractTaskBillingSnapshotService billingSnapshotService;

    @Autowired
    private IAccountUpdateService accountUpdateService;

    @Autowired
    private BillingAmountCalculator billingAmountCalculator;

    @Autowired
    private BillingRecordMetadataService billingRecordMetadataService;

    @Autowired
    private IWechatNotifyService wechatNotifyService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Override
    public void prepareBilling(Long taskId, Long userId, BigDecimal frozenAmount, String billingSnapshotJson)
    {
        frozenAmount = Objects.isNull(frozenAmount)
                ? null : BillingConstants.normalizeAccountAmount(frozenAmount);
        // 幂等检查
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (task == null)
        {
            throw new ServiceException("任务不存在");
        }
        if (task.getBillingTraceId() != null)
        {
            return;
        }

        String traceId = IdUtil.fastSimpleUUID();
        String priorSnapshotRef = task.getBillingSnapshotJson();
        String priorSnapshotJson = resolveBillingSnapshotJson(taskId, priorSnapshotRef);

        // 冻结金额为0直接标记成功
        if (frozenAmount == null || frozenAmount.compareTo(BigDecimal.ZERO) <= 0)
        {
            int reserved = casReserveBillingTrace(taskId, traceId, BigDecimal.ZERO, billingSnapshotJson);
            if (reserved == 0)
            {
                AidExtractTask reloaded = extractTaskService.selectAidExtractTaskById(taskId);
                if (reloaded != null && reloaded.getBillingTraceId() != null)
                {
                    log.info("提取任务已由其他请求预冻结, taskId={}, existingTraceId={}", taskId, reloaded.getBillingTraceId());
                    return;
                }
                log.warn("提取任务零金额预冻结 CAS 失败且未发现现有 traceId, taskId={}", taskId);
                throw new ServiceException("系统繁忙");
            }
            try
            {
                saveBillingSnapshot(taskId, SNAPSHOT_STAGE_FROZEN, billingSnapshotJson);
            }
            catch (RuntimeException snapshotEx)
            {
                rollbackBillingTraceOnFreezeFail(taskId, traceId, priorSnapshotJson, priorSnapshotRef);
                throw snapshotEx;
            }
            return;
        }

        // CAS 抢占唯一计费周期，防止并发提交重复冻结。
        int reserved = casReserveBillingTrace(taskId, traceId, frozenAmount, billingSnapshotJson);
        if (reserved == 0)
        {
            // 并发场景：另一线程已占用 traceId；重新加载一次确认状态
            AidExtractTask reloaded = extractTaskService.selectAidExtractTaskById(taskId);
            if (reloaded != null && reloaded.getBillingTraceId() != null)
            {
                log.info("提取任务已由其他请求预冻结, taskId={}, existingTraceId={}", taskId, reloaded.getBillingTraceId());
                return;
            }
            log.warn("提取任务预冻结 CAS 失败且未发现现有 traceId, taskId={}", taskId);
            throw new ServiceException("系统繁忙");
        }

        try
        {
            saveBillingSnapshot(taskId, SNAPSHOT_STAGE_FROZEN, billingSnapshotJson);
        }
        catch (RuntimeException snapshotEx)
        {
            rollbackBillingTraceOnFreezeFail(taskId, traceId, priorSnapshotJson, priorSnapshotRef);
            throw snapshotEx;
        }

        try
        {
            // 委托统一账户执行器冻结，账户侧负责余额条件扣减。
            String bizName = billingRecordMetadataService.buildExtractBizName(task, false);
            String modelCodes = billingRecordMetadataService.resolveExtractModelCodes(task);
            accountUpdateService.freeze(userId, frozenAmount, traceId, "extract", bizName, modelCodes);

            log.info("提取任务预冻结成功, taskId={}, userId={}, traceId={}, frozenAmount={}",
                    taskId, userId, traceId, frozenAmount);
        }
        catch (RuntimeException freezeEx)
        {
            if (isBalanceInsufficient(freezeEx))
            {
                wechatNotifyService.notifyBalanceInsufficient(userId, task.getTaskType(), taskId, frozenAmount);
            }
            // 先核对幂等冻结流水。若账户事务其实已提交，只保留 FROZEN 意图交补偿退款，
            // 不能清掉 task trace 后留下无法关联的冻结资金。
            if (!hasFreezeRecordFailClosed(traceId))
            {
                try
                {
                    rollbackBillingTraceOnFreezeFail(taskId, traceId, priorSnapshotJson, priorSnapshotRef);
                }
                catch (Exception rollbackEx)
                {
                    log.error("冻结失败后回滚计费意图异常, taskId={}, traceId={}", taskId, traceId, rollbackEx);
                }
            }
            else
            {
                log.error("冻结调用异常但流水已存在，保留计费意图等待补偿: taskId={}, traceId={}",
                        taskId, traceId);
            }
            throw freezeEx;
        }
    }

    /**
     * CAS 抢占 billing_trace_id 唯一执行权并置为 FROZEN 状态。
     */
    private int casReserveBillingTrace(Long taskId, String traceId, BigDecimal frozenAmount, String billingSnapshotJson)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.isNull(AidExtractTask::getBillingTraceId);
        update.set(AidExtractTask::getBillingTraceId, traceId);
        update.set(AidExtractTask::getBillingStatus, ExtractBillingStatus.FROZEN.name());
        if (frozenAmount != null)
        {
            update.set(AidExtractTask::getFrozenAmount, frozenAmount);
        }
        update.set(AidExtractTask::getBillingSnapshotJson,
                StrUtil.isBlank(billingSnapshotJson) ? null : buildSnapshotRefJson(SNAPSHOT_STAGE_FROZEN));
        return extractTaskService.getBaseMapper().update(null, update);
    }

    private boolean isBalanceInsufficient(Throwable throwable)
    {
        Throwable current = throwable;
        while (current != null)
        {
            if (current instanceof ServiceException && "余额不足".equals(current.getMessage()))
            {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * freeze 失败时回滚预占：仅当 traceId 仍等于当前抢占值 + billingStatus=FROZEN 时清空，
     * 避免已经进入 SETTLING / REFUNDING 的任务被误清。
     */
    private void rollbackBillingTraceOnFreezeFail(Long taskId, String traceId,
                                                  String priorSnapshotJson, String priorSnapshotRef)
    {
        String priorSnapshotStage = resolveSnapshotStage(priorSnapshotRef);
        if (StrUtil.isBlank(priorSnapshotStage) && StrUtil.isNotBlank(priorSnapshotJson))
        {
            priorSnapshotStage = SNAPSHOT_STAGE_FROZEN;
        }
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.eq(AidExtractTask::getBillingTraceId, traceId);
        update.eq(AidExtractTask::getBillingStatus, ExtractBillingStatus.FROZEN.name());
        update.set(AidExtractTask::getBillingTraceId, null);
        update.set(AidExtractTask::getBillingStatus, (Object) null);
        update.set(AidExtractTask::getFrozenAmount, null);
        update.set(AidExtractTask::getBillingSnapshotJson,
                buildRestoredSnapshotRefJson(priorSnapshotStage, priorSnapshotJson));
        int rows = extractTaskService.getBaseMapper().update(null, update);
        if (rows > 0)
        {
            restoreSnapshotAfterRollback(taskId, priorSnapshotStage, priorSnapshotJson);
        }
    }

    /**
     * 续生重置计费周期：把已完整结算（SUCCESS）或已退款（FAILED）的任务
     * 重置为新一轮 FROZEN（新 traceId/金额/快照）并冻结余额。
     * 仅允许旧 billing_status=SUCCESS/FAILED：若旧周期是 PARTIAL_SUCCESS（首跑欠扣未追补完），不允许重置——
     * 否则旧周期的 extraChargeRequired 追补入口会被新快照覆盖丢失，需待 {@link #retryPartialExtraCharges(int)}
     * 把旧周期追平为 SUCCESS 后再续生。CAS 防并发重复重置；冻结失败原样恢复旧值。
     */
    @Override
    public ResumeBillingContext rearmBillingForResume(Long taskId, Long userId, BigDecimal frozenAmount,
                                                      String billingSnapshotJson,
                                                      String expectedTerminalStatus,
                                                      ResumeTaskMutation taskMutation,
                                                      String dispatchMode)
    {
        if (StrUtil.isBlank(expectedTerminalStatus)
                || !("MQ".equals(dispatchMode) || "LOCAL".equals(dispatchMode)))
        {
            log.error("续生参数异常: taskId={}, expectedStatus={}, dispatchMode={}",
                    taskId, expectedTerminalStatus, dispatchMode);
            throw new ServiceException("状态不支持");
        }
        BigDecimal frozen = frozenAmount == null
                ? BillingConstants.normalizeAccountAmount(BigDecimal.ZERO)
                : BillingConstants.normalizeAccountAmount(frozenAmount);
        String traceId = IdUtil.fastSimpleUUID();
        AidExtractTask preflightTask = extractTaskService.selectAidExtractTaskById(taskId);
        Long assetExtractProjectId = Objects.nonNull(preflightTask)
                && Objects.equals(TASK_TYPE_ASSET_EXTRACT, preflightTask.getTaskType())
                ? preflightTask.getProjectId() : null;
        AidExtractTask[] metadataTask = new AidExtractTask[1];
        ResumeBillingContext preparedContext;
        try
        {
            preparedContext = transactionTemplate.execute(status -> {
                if (Objects.nonNull(assetExtractProjectId))
                {
                    // 续跑置活前先锁项目，再锁任务；与风格切换保持统一锁顺序且不覆盖后续资金冻结/派发。
                    AidComicProject lockedProject = comicProjectService.getOne(
                            Wrappers.<AidComicProject>lambdaQuery()
                                    .select(AidComicProject::getId)
                                    .eq(AidComicProject::getId, assetExtractProjectId)
                                    .eq(AidComicProject::getUserId, userId)
                                    .eq(AidComicProject::getDelFlag, "0")
                                    .last("FOR UPDATE"));
                    if (Objects.isNull(lockedProject))
                    {
                        throw new ServiceException("项目不存在");
                    }
                }
                AidExtractTask lockedTask = selectTaskForUpdate(taskId);
                if (lockedTask == null || !Objects.equals(userId, lockedTask.getUserId())
                        || !"0".equals(lockedTask.getDelFlag()))
                {
                    throw new ServiceException("任务不存在");
                }
                if (!Objects.equals(expectedTerminalStatus, lockedTask.getStatus()))
                {
                    log.info("续生重置计费被拒绝（任务状态已变化）, taskId={}, expected={}, actual={}",
                            taskId, expectedTerminalStatus, lockedTask.getStatus());
                    throw new ServiceException("状态不支持");
                }
                assertResumeContextReadyForNewCycle(lockedTask);

                String oldBillingStatus = lockedTask.getBillingStatus();
                String oldTraceId = lockedTask.getBillingTraceId();
                BigDecimal oldFrozen = lockedTask.getFrozenAmount();
                String oldSnapshotRef = lockedTask.getBillingSnapshotJson();
                String oldSnapshotJson = resolveBillingSnapshotJson(taskId, oldSnapshotRef);
                String oldSnapshotStage = resolvePriorSnapshotStage(
                        oldBillingStatus, oldSnapshotRef, oldSnapshotJson);
                boolean settledCycle = ExtractBillingStatus.SUCCESS.name().equals(oldBillingStatus)
                        || ExtractBillingStatus.FAILED.name().equals(oldBillingStatus);
                boolean legacyUnbilledCycle = (StrUtil.isBlank(oldBillingStatus)
                        || "INIT".equals(oldBillingStatus))
                        && StrUtil.isBlank(oldTraceId)
                        && (oldFrozen == null || oldFrozen.compareTo(BigDecimal.ZERO) <= 0);
                if (!settledCycle && !legacyUnbilledCycle)
                {
                    log.info("续生重置计费被拒绝（旧周期尚未收敛）, taskId={}, billingStatus={}",
                            taskId, oldBillingStatus);
                    throw new ServiceException("结算未完成");
                }

                ResumeTaskState actualTaskState = new ResumeTaskState(
                        lockedTask.getStatus(), lockedTask.getErrorMessage(), lockedTask.getRemark(),
                        lockedTask.getInputSnapshot(), lockedTask.getTotalCount());
                ResumeBillingContext context = new ResumeBillingContext(
                        oldBillingStatus, oldTraceId, oldFrozen, oldSnapshotJson, oldSnapshotRef,
                        oldSnapshotStage, traceId, actualTaskState, dispatchMode, null,
                        RESUME_ROLLBACK_STATE_PREPARED);
                saveResumeRollbackContext(lockedTask, context);

                LambdaUpdateWrapper<AidExtractTask> reset = Wrappers.lambdaUpdate();
                reset.eq(AidExtractTask::getId, taskId);
                reset.eq(AidExtractTask::getUserId, userId);
                reset.eq(AidExtractTask::getStatus, expectedTerminalStatus);
                appendNullableEquals(reset, AidExtractTask::getBillingStatus, oldBillingStatus);
                appendNullableEquals(reset, AidExtractTask::getBillingTraceId, oldTraceId);
                appendNullableEquals(reset, AidExtractTask::getFrozenAmount, oldFrozen);
                appendNullableEquals(reset, AidExtractTask::getBillingSnapshotJson, oldSnapshotRef);
                reset.set(AidExtractTask::getStatus, "PENDING");
                reset.set(AidExtractTask::getErrorMessage, null);
                if (taskMutation != null && taskMutation.replaceRemark())
                {
                    reset.set(AidExtractTask::getRemark, taskMutation.remark());
                }
                if (taskMutation != null && taskMutation.replaceInputSnapshot())
                {
                    reset.set(AidExtractTask::getInputSnapshot, taskMutation.inputSnapshot());
                }
                if (taskMutation != null && taskMutation.replaceTotalCount())
                {
                    reset.set(AidExtractTask::getTotalCount, taskMutation.totalCount());
                }
                reset.set(AidExtractTask::getBillingTraceId, traceId);
                reset.set(AidExtractTask::getFrozenAmount, frozen);
                reset.set(AidExtractTask::getBillingSnapshotJson,
                        StrUtil.isBlank(billingSnapshotJson)
                                ? null : buildSnapshotRefJson(SNAPSHOT_STAGE_FROZEN));
                reset.set(AidExtractTask::getBillingStatus, ExtractBillingStatus.FROZEN.name());
                reset.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
                reset.set(AidExtractTask::getUpdateBy, String.valueOf(userId));
                if (extractTaskService.getBaseMapper().update(null, reset) == 0)
                {
                    throw new ServiceException("结算未完成");
                }
                saveBillingSnapshot(taskId, SNAPSHOT_STAGE_FROZEN, billingSnapshotJson);
                metadataTask[0] = lockedTask;
                return context;
            });
        }
        catch (RuntimeException reserveEx)
        {
            log.info("续生重置计费事务失败（可能被并发推进）, taskId={}", taskId);
            throw reserveEx;
        }

        if (preparedContext == null || metadataTask[0] == null)
        {
            log.error("续生重置计费未生成上下文: taskId={}", taskId);
            throw new ServiceException("计费状态异常");
        }

        try
        {
            if (frozen.compareTo(BigDecimal.ZERO) > 0)
            {
                String bizName = billingRecordMetadataService.buildExtractBizName(metadataTask[0], true);
                String modelCodes = billingRecordMetadataService.resolveExtractModelCodes(metadataTask[0]);
                accountUpdateService.freeze(userId, frozen, traceId, "extract", bizName, modelCodes);
            }
            ResumeBillingContext frozenContext = transitionResumeRollbackState(taskId, preparedContext,
                    RESUME_ROLLBACK_STATE_PREPARED, RESUME_ROLLBACK_STATE_FUNDS_FROZEN);
            log.info("续生重置计费并冻结成功, taskId={}, userId={}, traceId={}, frozen={}",
                    taskId, userId, traceId, frozen);
            return frozenContext;
        }
        catch (RuntimeException rearmEx)
        {
            if (requestResumeBillingRollback(taskId, userId, preparedContext))
            {
                rollbackResumeBilling(taskId, userId, preparedContext);
            }
            log.error("续生冻结未完成，已进入安全回滚: taskId={}, traceId={}",
                    taskId, traceId, rearmEx);
            throw rearmEx;
        }
    }

    @Override
    public boolean requestResumeBillingRollback(Long taskId, Long userId, ResumeBillingContext context)
    {
        return requestResumeBillingRollback(taskId, userId, context, false, false);
    }

    private boolean requestResumeBillingRollback(Long taskId, Long userId,
                                                 ResumeBillingContext context,
                                                 boolean allowConfirmed,
                                                 boolean allowClaimed)
    {
        if (!isValidResumeContext(context) || context.resumeTaskState() == null)
        {
            return false;
        }
        Boolean requested = transactionTemplate.execute(status -> {
            AidExtractTask lockedTask = selectTaskForUpdate(taskId);
            if (!matchesActiveResumeCycle(lockedTask, userId, context))
            {
                return false;
            }
            ResumeBillingContext persisted = loadResumeRollbackContext(taskId);
            if (!isSameResumeCycle(persisted, context))
            {
                return false;
            }
            ResumeTaskState priorTask = context.resumeTaskState();

            // REQUIRED 与业务旧终态在同一事务内落库。只有已经持久化为 REQUIRED 后，
            // 才允许把 priorStatus 视为上一次请求已成功的幂等证据。
            if (RESUME_ROLLBACK_STATE_REQUIRED.equals(persisted.rollbackState()))
            {
                return isIdempotentResumeRollbackRequest(
                        persisted.rollbackState(), lockedTask.getStatus(), priorTask.status());
            }

            // 首次抢回必须仍处于未领取的 PENDING/QUEUED。即使 worker 执行后又回到同名
            // PARTIAL_FAILED/CANCELLED，也绝不能据此回滚并退款真实发生的本轮调用。
            if (!canTransitionResumeRollbackToRequired(
                    persisted.rollbackState(), lockedTask.getStatus(), allowConfirmed, allowClaimed))
            {
                return false;
            }
            LambdaUpdateWrapper<AidExtractTask> restoreTask = Wrappers.lambdaUpdate();
            restoreTask.eq(AidExtractTask::getId, taskId);
            restoreTask.eq(AidExtractTask::getUserId, userId);
            if (allowClaimed)
            {
                restoreTask.in(AidExtractTask::getStatus, "PENDING", "QUEUED", "PROCESSING");
            }
            else
            {
                restoreTask.in(AidExtractTask::getStatus, "PENDING", "QUEUED");
            }
            restoreTask.eq(AidExtractTask::getBillingTraceId, context.resumeTraceId());
            restoreTask.in(AidExtractTask::getBillingStatus,
                    ExtractBillingStatus.FROZEN.name(), ExtractBillingStatus.REFUNDING.name(),
                    ExtractBillingStatus.FAILED.name());
            restoreTask.set(AidExtractTask::getStatus, priorTask.status());
            restoreTask.set(AidExtractTask::getErrorMessage, priorTask.errorMessage());
            restoreTask.set(AidExtractTask::getRemark, priorTask.remark());
            restoreTask.set(AidExtractTask::getInputSnapshot, priorTask.inputSnapshot());
            restoreTask.set(AidExtractTask::getTotalCount, priorTask.totalCount());
            restoreTask.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            restoreTask.set(AidExtractTask::getUpdateBy, String.valueOf(userId));
            int updated = extractTaskService.getBaseMapper().update(null, restoreTask);
            if (updated == 0)
            {
                return false;
            }
            saveResumeRollbackContext(lockedTask, withRollbackState(
                    persisted, RESUME_ROLLBACK_STATE_REQUIRED));
            return true;
        });
        return Boolean.TRUE.equals(requested);
    }

    /**
     * 首次把续跑上下文推进到 REQUIRED 的状态门禁。
     * PREPARED/FUNDS_FROZEN 用于冻结阶段异常补偿；DISPATCH_CONFIRMED 仅允许明确的队列失败收口调用。
     */
    boolean canTransitionResumeRollbackToRequired(String rollbackState, String taskStatus,
                                                   boolean allowConfirmed)
    {
        return canTransitionResumeRollbackToRequired(
                rollbackState, taskStatus, allowConfirmed, false);
    }

    private boolean canTransitionResumeRollbackToRequired(String rollbackState, String taskStatus,
                                                           boolean allowConfirmed,
                                                           boolean allowClaimed)
    {
        boolean sourceStateAllowed = RESUME_ROLLBACK_STATE_PREPARED.equals(rollbackState)
                || RESUME_ROLLBACK_STATE_FUNDS_FROZEN.equals(rollbackState)
                || RESUME_ROLLBACK_STATE_DISPATCH_INTENT.equals(rollbackState)
                || (allowConfirmed
                && RESUME_ROLLBACK_STATE_DISPATCH_CONFIRMED.equals(rollbackState));
        return sourceStateAllowed && ("PENDING".equals(taskStatus) || "QUEUED".equals(taskStatus)
                || (allowClaimed && "PROCESSING".equals(taskStatus)));
    }

    /** 已进入 REQUIRED 后，旧业务终态仅作为幂等重试证据，不再触发第二次状态迁移。 */
    boolean isIdempotentResumeRollbackRequest(String rollbackState, String taskStatus,
                                               String priorStatus)
    {
        return RESUME_ROLLBACK_STATE_REQUIRED.equals(rollbackState)
                && Objects.equals(priorStatus, taskStatus);
    }

    @Override
    public ResumeBillingContext markResumeBillingDispatchIntent(Long taskId,
                                                                ResumeBillingContext context)
    {
        ResumeBillingContext intentContext = new ResumeBillingContext(
                context.priorBillingStatus(), context.priorTraceId(), context.priorFrozenAmount(),
                context.priorBillingSnapshotJson(), context.priorBillingSnapshotRefJson(),
                context.priorBillingSnapshotStage(), context.resumeTraceId(),
                context.resumeTaskState(), context.dispatchMode(), System.currentTimeMillis(),
                RESUME_ROLLBACK_STATE_DISPATCH_INTENT);
        return transitionResumeRollbackContext(taskId, context, intentContext,
                RESUME_ROLLBACK_STATE_FUNDS_FROZEN);
    }

    @Override
    public void confirmResumeBillingSubmission(Long taskId, ResumeBillingContext context)
    {
        if (!isValidResumeContext(context))
        {
            throw new ServiceException("计费状态异常");
        }
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        ResumeBillingContext persisted = loadResumeRollbackContext(taskId);
        if (task != null
                && Objects.equals(context.resumeTraceId(), task.getBillingTraceId())
                && persisted == null)
        {
            // Worker 可能已快速完成终态并清理临时上下文，确认操作保持幂等。
            return;
        }
        if (task == null || !Objects.equals(context.resumeTraceId(), task.getBillingTraceId())
                || !isSameResumeCycle(persisted, context))
        {
            log.error("续生入队确认失败: taskId={}", taskId);
            throw new ServiceException("计费状态异常");
        }
        if (RESUME_ROLLBACK_STATE_DISPATCH_CONFIRMED.equals(persisted.rollbackState()))
        {
            return;
        }
        if (!RESUME_ROLLBACK_STATE_DISPATCH_INTENT.equals(persisted.rollbackState()))
        {
            log.error("续生入队确认状态异常: taskId={}, state={}", taskId, persisted.rollbackState());
            throw new ServiceException("计费状态异常");
        }
        transitionResumeRollbackState(taskId, persisted,
                RESUME_ROLLBACK_STATE_DISPATCH_INTENT,
                RESUME_ROLLBACK_STATE_DISPATCH_CONFIRMED);
    }

    @Override
    public void confirmResumeBillingSubmission(Long taskId, String dispatchToken)
    {
        if (Objects.isNull(taskId) || StrUtil.isBlank(dispatchToken))
        {
            return;
        }
        ResumeBillingContext context = loadResumeRollbackContext(taskId);
        if (context == null)
        {
            // 首跑没有续跑上下文；续跑若已快速终态，也会由结算/退款清掉上下文。
            return;
        }
        if (!Objects.equals(dispatchToken, context.resumeTraceId()))
        {
            log.info("忽略过期派发确认: taskId={}", taskId);
            return;
        }
        confirmResumeBillingSubmission(taskId, context);
    }

    @Override
    public boolean rollbackResumeBilling(Long taskId, Long userId, ResumeBillingContext context)
    {
        if (!isValidResumeContext(context) || context.resumeTaskState() == null)
        {
            return false;
        }
        AidExtractTask currentTask = extractTaskService.selectAidExtractTaskById(taskId);
        ResumeBillingContext currentContext = loadResumeRollbackContext(taskId);
        if (!matchesActiveResumeCycle(currentTask, userId, context)
                || !Objects.equals(context.resumeTaskState().status(), currentTask.getStatus())
                || !isSameResumeCycle(currentContext, context)
                || !RESUME_ROLLBACK_STATE_REQUIRED.equals(currentContext.rollbackState()))
        {
            return false;
        }

        BigDecimal resumeFrozen = currentTask.getFrozenAmount() == null
                ? BigDecimal.ZERO : currentTask.getFrozenAmount();
        boolean freezeRecorded = resumeFrozen.compareTo(BigDecimal.ZERO) <= 0
                || hasFreezeRecordFailClosed(context.resumeTraceId());
        if (freezeRecorded)
        {
            try
            {
                if (!refundBilling(taskId, userId))
                {
                    return false;
                }
            }
            catch (RuntimeException refundEx)
            {
                log.error("续生回滚退款失败: taskId={}", taskId, refundEx);
                return false;
            }
        }

        // 账户动作不占用 task 行锁；退款幂等完成后，再用短事务原子恢复主表和快照。
        Boolean restored = transactionTemplate.execute(status -> {
            AidExtractTask lockedTask = selectTaskForUpdate(taskId);
            ResumeBillingContext persisted = loadResumeRollbackContext(taskId);
            if (!matchesActiveResumeCycle(lockedTask, userId, context)
                    || !Objects.equals(context.resumeTaskState().status(), lockedTask.getStatus())
                    || !isSameResumeCycle(persisted, context)
                    || !RESUME_ROLLBACK_STATE_REQUIRED.equals(persisted.rollbackState()))
            {
                return false;
            }
            if (freezeRecorded
                    && !ExtractBillingStatus.FAILED.name().equals(lockedTask.getBillingStatus()))
            {
                return false;
            }
            if (!freezeRecorded
                    && !(ExtractBillingStatus.FROZEN.name().equals(lockedTask.getBillingStatus())
                    || ExtractBillingStatus.REFUNDING.name().equals(lockedTask.getBillingStatus())
                    || ExtractBillingStatus.FAILED.name().equals(lockedTask.getBillingStatus())))
            {
                return false;
            }

            LambdaUpdateWrapper<AidExtractTask> restoreBilling = Wrappers.lambdaUpdate();
            restoreBilling.eq(AidExtractTask::getId, taskId);
            restoreBilling.eq(AidExtractTask::getStatus, context.resumeTaskState().status());
            restoreBilling.eq(AidExtractTask::getBillingTraceId, context.resumeTraceId());
            if (freezeRecorded)
            {
                restoreBilling.eq(AidExtractTask::getBillingStatus, ExtractBillingStatus.FAILED.name());
            }
            else
            {
                restoreBilling.in(AidExtractTask::getBillingStatus,
                        ExtractBillingStatus.FROZEN.name(), ExtractBillingStatus.REFUNDING.name(),
                        ExtractBillingStatus.FAILED.name());
            }
            restoreBilling.set(AidExtractTask::getBillingStatus, context.priorBillingStatus());
            restoreBilling.set(AidExtractTask::getBillingTraceId, context.priorTraceId());
            restoreBilling.set(AidExtractTask::getFrozenAmount, context.priorFrozenAmount());
            restoreBilling.set(AidExtractTask::getBillingSnapshotJson,
                    buildRestoredSnapshotRefJson(context.priorBillingSnapshotStage(),
                            context.priorBillingSnapshotJson()));
            restoreBilling.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            int updated = extractTaskService.getBaseMapper().update(null, restoreBilling);
            if (updated == 0)
            {
                return false;
            }
            // 主表指针、旧快照内容和回滚上下文在同一数据库事务内完成，避免 split-brain。
            restoreSnapshotAfterRollback(taskId, context.priorBillingSnapshotStage(),
                    context.priorBillingSnapshotJson());
            deleteAllResumeRollbackContexts(taskId);
            return true;
        });
        if (Boolean.TRUE.equals(restored))
        {
            log.info("续生入队失败已回滚本轮冻结并恢复上一轮计费周期, taskId={}, priorStatus={}",
                    taskId, context.priorBillingStatus());
            return true;
        }
        log.warn("续生回滚尚未完成，保留持久化上下文等待补偿: taskId={}", taskId);
        return false;
    }

    @Override
    public List<ResumeBillingRecovery> listStaleResumeBillingRecoveries(int batchSize)
    {
        int safeBatchSize = Math.max(1, Math.min(batchSize, 500));
        List<AidExtractTaskBillingSnapshot> snapshots = billingSnapshotService.list(
                Wrappers.<AidExtractTaskBillingSnapshot>lambdaQuery()
                        .select(AidExtractTaskBillingSnapshot::getId,
                                AidExtractTaskBillingSnapshot::getTaskId,
                                AidExtractTaskBillingSnapshot::getUserId,
                                AidExtractTaskBillingSnapshot::getSnapshotStage,
                                AidExtractTaskBillingSnapshot::getSnapshotJson,
                                AidExtractTaskBillingSnapshot::getUpdateTime)
                        .in(AidExtractTaskBillingSnapshot::getSnapshotStage,
                                STALE_RESUME_RECOVERY_STAGES)
                        .eq(AidExtractTaskBillingSnapshot::getDelFlag, "0")
                        .lt(AidExtractTaskBillingSnapshot::getUpdateTime,
                                new Date(System.currentTimeMillis() - RESUME_RECOVERY_STALE_MILLIS))
                        .orderByAsc(AidExtractTaskBillingSnapshot::getUpdateTime,
                                AidExtractTaskBillingSnapshot::getId)
                        .last("LIMIT " + safeBatchSize));
        List<ResumeBillingRecovery> recoveries = new java.util.ArrayList<>(snapshots.size());
        for (AidExtractTaskBillingSnapshot snapshot : snapshots)
        {
            ResumeBillingContext context = deserializeResumeBillingContext(
                    snapshot.getTaskId(), snapshot.getSnapshotJson(), snapshot.getSnapshotStage());
            if (context != null)
            {
                recoveries.add(new ResumeBillingRecovery(
                        snapshot.getTaskId(), snapshot.getUserId(), context));
            }
        }
        return recoveries;
    }

    @Override
    public boolean recoverResumeBillingIfNeeded(Long taskId, Long userId)
    {
        ResumeBillingContext context = loadResumeRollbackContext(taskId);
        if (context == null)
        {
            return false;
        }
        if (RESUME_ROLLBACK_STATE_DISPATCH_INTENT.equals(context.rollbackState())
                || RESUME_ROLLBACK_STATE_DISPATCH_CONFIRMED.equals(context.rollbackState()))
        {
            return true;
        }
        ResumeBillingContext effective = context;
        if (!RESUME_ROLLBACK_STATE_REQUIRED.equals(effective.rollbackState()))
        {
            if (!requestResumeBillingRollback(taskId, userId, effective))
            {
                return true;
            }
            effective = loadResumeRollbackContext(taskId);
        }
        rollbackResumeBilling(taskId, userId, effective);
        return true;
    }

    @Override
    public boolean hasActiveResumeBilling(Long taskId, String billingTraceId)
    {
        ResumeBillingContext context = loadResumeRollbackContext(taskId);
        return context != null && Objects.equals(billingTraceId, context.resumeTraceId());
    }

    @Override
    public boolean rollbackResumeAfterQueueFailure(Long taskId, Long userId)
    {
        ResumeBillingContext context = loadResumeRollbackContext(taskId);
        if (context == null
                || !(RESUME_ROLLBACK_STATE_DISPATCH_INTENT.equals(context.rollbackState())
                || RESUME_ROLLBACK_STATE_DISPATCH_CONFIRMED.equals(context.rollbackState())))
        {
            return false;
        }
        if (!requestResumeBillingRollback(taskId, userId, context, true, false))
        {
            return false;
        }
        return rollbackResumeBilling(taskId, userId, loadResumeRollbackContext(taskId));
    }

    @Override
    public boolean rollbackDeadResumeExecution(Long taskId, Long userId, String dispatchToken)
    {
        ResumeBillingContext context = loadResumeRollbackContext(taskId);
        if (context == null || StrUtil.isBlank(dispatchToken)
                || !Objects.equals(dispatchToken, context.resumeTraceId()))
        {
            return false;
        }
        if (!requestResumeBillingRollback(taskId, userId, context, true, true))
        {
            return false;
        }
        return rollbackResumeBilling(taskId, userId, loadResumeRollbackContext(taskId));
    }

    @Override
    public String resolveBillingSnapshotJson(Long taskId, String billingSnapshotJson)
    {
        String snapshotStage = resolveSnapshotStage(billingSnapshotJson);
        if (StrUtil.isBlank(snapshotStage))
        {
            return billingSnapshotJson;
        }
        return billingSnapshotService.getSnapshotJson(taskId, snapshotStage);
    }

    @Override
    public void restoreBillingSnapshotJson(Long taskId, String billingSnapshotJson, String billingSnapshotRefJson)
    {
        String snapshotStage = resolveSnapshotStage(billingSnapshotRefJson);
        if (StrUtil.isBlank(snapshotStage) && StrUtil.isNotBlank(billingSnapshotJson))
        {
            snapshotStage = SNAPSHOT_STAGE_FROZEN;
        }
        restoreSnapshotAfterRollback(taskId, snapshotStage, billingSnapshotJson);
    }

    private void restoreSnapshotAfterRollback(Long taskId, String snapshotStage, String snapshotJson)
    {
        if (StrUtil.isBlank(snapshotStage))
        {
            billingSnapshotService.deleteSnapshot(taskId, SNAPSHOT_STAGE_FROZEN);
            billingSnapshotService.deleteSnapshot(taskId, SNAPSHOT_STAGE_SETTLED);
            return;
        }
        if (!SNAPSHOT_STAGE_FROZEN.equals(snapshotStage))
        {
            billingSnapshotService.deleteSnapshot(taskId, SNAPSHOT_STAGE_FROZEN);
        }
        if (StrUtil.isBlank(snapshotJson))
        {
            if (SNAPSHOT_STAGE_FROZEN.equals(snapshotStage))
            {
                billingSnapshotService.deleteSnapshot(taskId, SNAPSHOT_STAGE_FROZEN);
            }
            else if (SNAPSHOT_STAGE_SETTLED.equals(snapshotStage))
            {
                billingSnapshotService.deleteSnapshot(taskId, SNAPSHOT_STAGE_SETTLED);
            }
            return;
        }
        saveBillingSnapshot(taskId, snapshotStage, snapshotJson);
    }

    @Override
    public long findLatestExtractMediaTaskId(Long taskId)
    {
        return findLatestMediaTaskId(taskId, MEDIA_BIZ_TYPE_EXTRACT);
    }

    @Override
    public long findLatestBillingMediaTaskId(Long taskId)
    {
        if (taskId == null)
        {
            return 0L;
        }
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        return task == null ? 0L : findLatestMediaTaskId(taskId, resolveMediaBizTaskType(task.getTaskType()));
    }

    private long findLatestMediaTaskId(Long taskId, String mediaBizTaskType)
    {
        if (taskId == null || StrUtil.isBlank(mediaBizTaskType))
        {
            return 0L;
        }
        List<AidMediaTask> mediaTasks = aidMediaTaskMapper.selectList(
                Wrappers.<AidMediaTask>lambdaQuery()
                        .select(AidMediaTask::getId)
                        .eq(AidMediaTask::getBizTaskId, taskId)
                        .eq(AidMediaTask::getBizTaskType, mediaBizTaskType)
                        .orderByDesc(AidMediaTask::getId)
                        .last("LIMIT 1"));
        if (mediaTasks == null || mediaTasks.isEmpty() || mediaTasks.get(0).getId() == null)
        {
            return 0L;
        }
        return mediaTasks.get(0).getId();
    }

    private String resolveMediaBizTaskType(String taskType)
    {
        if (Objects.equals(TASK_TYPE_STORYBOARD_IMAGE_PROMPT, taskType))
        {
            return MEDIA_BIZ_TYPE_IMAGE_PROMPT;
        }
        if (Objects.equals(TASK_TYPE_STORYBOARD_VIDEO_PROMPT, taskType))
        {
            return MEDIA_BIZ_TYPE_VIDEO_PROMPT;
        }
        return MEDIA_BIZ_TYPE_EXTRACT;
    }

    @Override
    public Map<String, Object> aggregateTokenUsage(Long taskId)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        String mediaBizTaskType = task == null ? null : resolveMediaBizTaskType(task.getTaskType());
        long usageStartMediaTaskId = resolveUsageStartMediaTaskId(taskId);
        LambdaQueryWrapper<AidMediaTask> query = Wrappers.<AidMediaTask>lambdaQuery()
                // 聚合只读取关联、状态、模型和计费快照字段，禁止扫描媒体任务无关大字段。
                .select(AidMediaTask::getId, AidMediaTask::getModelName,
                        AidMediaTask::getStatus, AidMediaTask::getBillingSnapshotJson)
                .eq(AidMediaTask::getBizTaskId, taskId)
                .eq(AidMediaTask::getBizTaskType,
                        StrUtil.blankToDefault(mediaBizTaskType, MEDIA_BIZ_TYPE_EXTRACT))
                .orderByAsc(AidMediaTask::getId);
        if (usageStartMediaTaskId > 0L)
        {
            query.gt(AidMediaTask::getId, usageStartMediaTaskId);
        }
        List<AidMediaTask> mediaTasks = aidMediaTaskMapper.selectList(query);

        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int totalSuccessfulCalls = 0;
        int totalUsageCalls = 0;
        boolean aggregationComplete = true;
        // 数组字段：input、output、successful、usage、successfulUsage。
        Map<String, int[]> usageByModel = new LinkedHashMap<>();
        // 每次调用保留独立用量，供按单次真实 Token 重新命中 SKU；仅保存紧凑计费字段。
        List<Map<String, Object>> callUsages = new ArrayList<>();

        if (Objects.nonNull(mediaTasks))
        {
            for (AidMediaTask mediaTask : mediaTasks)
            {
                String modelCode = StrUtil.trim(mediaTask.getModelName());
                boolean succeeded = Objects.equals(MEDIA_TASK_STATUS_SUCCEEDED, mediaTask.getStatus());
                boolean terminal = succeeded || Objects.equals(MEDIA_TASK_STATUS_FAILED, mediaTask.getStatus());
                if (!terminal)
                {
                    aggregationComplete = false;
                }

                int inputTokens = 0;
                int outputTokens = 0;
                boolean hasUsage = false;
                if (StrUtil.isNotBlank(mediaTask.getBillingSnapshotJson()))
                {
                    try
                    {
                        BillingSnapshot snapshot = JSONUtil.toBean(
                                mediaTask.getBillingSnapshotJson(), BillingSnapshot.class);
                        Integer actualInputTokens = snapshot.getActualInputTokens();
                        Integer actualOutputTokens = snapshot.getActualOutputTokens();
                        hasUsage = actualInputTokens != null || actualOutputTokens != null;
                        inputTokens = actualInputTokens == null ? 0 : Math.max(0, actualInputTokens);
                        outputTokens = actualOutputTokens == null ? 0 : Math.max(0, actualOutputTokens);
                    }
                    catch (Exception e)
                    {
                        log.warn("解析提取媒体任务计费快照失败: taskId={}, mediaTaskId={}",
                                taskId, mediaTask.getId(), e);
                    }
                }

                Map<String, Object> callUsage = new LinkedHashMap<>();
                callUsage.put(CALL_USAGE_KEY_MEDIA_TASK_ID, mediaTask.getId());
                callUsage.put(CALL_USAGE_KEY_MODEL_CODE, modelCode);
                callUsage.put(CALL_USAGE_KEY_SUCCESSFUL, succeeded);
                callUsage.put(CALL_USAGE_KEY_HAS_USAGE, hasUsage);
                callUsage.put(USAGE_KEY_INPUT_TOKENS, inputTokens);
                callUsage.put(USAGE_KEY_OUTPUT_TOKENS, outputTokens);
                callUsages.add(callUsage);

                totalInputTokens += inputTokens;
                totalOutputTokens += outputTokens;
                if (succeeded)
                {
                    totalSuccessfulCalls++;
                }
                if (hasUsage)
                {
                    totalUsageCalls++;
                }

                if (StrUtil.isBlank(modelCode))
                {
                    // 无法归属模型时禁止把空键猜到任意分项；标记聚合不完整让结算保守兜底。
                    aggregationComplete = false;
                    log.warn("提取媒体任务模型为空: taskId={}, mediaTaskId={}", taskId, mediaTask.getId());
                    continue;
                }
                int[] modelUsage = usageByModel.computeIfAbsent(modelCode, key -> new int[5]);
                modelUsage[0] += inputTokens;
                modelUsage[1] += outputTokens;
                if (succeeded)
                {
                    modelUsage[2]++;
                }
                if (hasUsage)
                {
                    modelUsage[3]++;
                    if (succeeded)
                    {
                        modelUsage[4]++;
                    }
                }
            }
        }

        Map<String, Object> modelUsages = new LinkedHashMap<>();
        usageByModel.forEach((modelCode, values) -> {
            Map<String, Object> modelUsage = new LinkedHashMap<>();
            modelUsage.put(USAGE_KEY_INPUT_TOKENS, values[0]);
            modelUsage.put(USAGE_KEY_OUTPUT_TOKENS, values[1]);
            modelUsage.put(USAGE_KEY_SUCCESSFUL_CALL_COUNT, values[2]);
            modelUsage.put(USAGE_KEY_USAGE_CALL_COUNT, values[3]);
            modelUsage.put(USAGE_KEY_SUCCESSFUL_USAGE_CALL_COUNT, values[4]);
            modelUsages.put(modelCode, modelUsage);
        });

        Map<String, Object> usageData = new LinkedHashMap<>();
        usageData.put(USAGE_KEY_AGGREGATION_COMPLETE, aggregationComplete);
        usageData.put(USAGE_KEY_INPUT_TOKENS, totalInputTokens);
        usageData.put(USAGE_KEY_OUTPUT_TOKENS, totalOutputTokens);
        usageData.put(USAGE_KEY_SUCCESSFUL_CALL_COUNT, totalSuccessfulCalls);
        usageData.put(USAGE_KEY_USAGE_CALL_COUNT, totalUsageCalls);
        usageData.put(USAGE_KEY_MODEL_USAGES, modelUsages);
        usageData.put(USAGE_KEY_CALL_USAGES, callUsages);
        log.info("提取用量聚合完成: taskId={}, watermark={}, mediaTaskCount={}, complete={}, modelUsages={}",
                taskId, usageStartMediaTaskId, Objects.isNull(mediaTasks) ? 0 : mediaTasks.size(),
                aggregationComplete, modelUsages);
        return usageData;
    }

    private long resolveUsageStartMediaTaskId(Long taskId)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (task == null)
        {
            return 0L;
        }
        String snapshotJson = getBillingSnapshotJson(task, SNAPSHOT_STAGE_FROZEN);
        if (StrUtil.isBlank(snapshotJson))
        {
            return 0L;
        }
        try
        {
            JSONObject root = JSONUtil.parseObj(snapshotJson);
            long rootWatermark = Math.max(0L,
                    resolveLong(root.get(SNAPSHOT_KEY_USAGE_START_MEDIA_TASK_ID), 0L));
            if (rootWatermark > 0L || root.containsKey(SNAPSHOT_KEY_USAGE_START_MEDIA_TASK_ID))
            {
                return rootWatermark;
            }
            if (Objects.equals(BILLING_BATCH_TYPE_MULTI_MODEL_EXTRACT, root.getStr("batchType")))
            {
                return Math.max(0L, resolveLong(root.get(SNAPSHOT_KEY_USAGE_START_MEDIA_TASK_ID), 0L));
            }
            BillingSnapshot snapshot = JSONUtil.toBean(snapshotJson, BillingSnapshot.class);
            if (snapshot != null && snapshot.getRequestParams() != null)
            {
                return Math.max(0L, resolveLong(
                        snapshot.getRequestParams().get(SNAPSHOT_KEY_USAGE_START_MEDIA_TASK_ID), 0L));
            }
        }
        catch (Exception e)
        {
            log.warn("解析提取用量水位线失败: taskId={}", taskId, e);
        }
        return 0L;
    }

    /**
     * 在任务行锁内固化当前 trace 的用量计划并推进到 SETTLING。
     * 同一 trace 一旦已有计划便只读重放，避免迟到线程覆盖已开始执行的资金指令。
     */
    private Map<String, Object> reserveOrLoadSettlePlan(Long taskId, Long userId,
                                                        Map<String, Object> usageData,
                                                        String expectedTraceId)
    {
        return transactionTemplate.execute(status -> {
            AidExtractTask lockedTask = selectTaskForUpdate(taskId);
            if (lockedTask == null || !Objects.equals(userId, lockedTask.getUserId()))
            {
                return null;
            }
            if (StrUtil.isNotBlank(expectedTraceId)
                    && !Objects.equals(expectedTraceId, lockedTask.getBillingTraceId()))
            {
                return null;
            }
            if (StrUtil.isNotBlank(expectedTraceId)
                    && !isWorkerSettlementStatus(lockedTask.getStatus()))
            {
                log.info("结算跳过，任务执行权已回收: taskId={}, status={}",
                        taskId, lockedTask.getStatus());
                return null;
            }
            String billingStatus = lockedTask.getBillingStatus();
            boolean canSettle = ExtractBillingStatus.FROZEN.name().equals(billingStatus)
                    || ExtractBillingStatus.PARTIAL_SUCCESS.name().equals(billingStatus)
                    || ExtractBillingStatus.SETTLING.name().equals(billingStatus);
            if (!canSettle || StrUtil.isBlank(lockedTask.getBillingTraceId()))
            {
                return null;
            }

            Map<String, Object> effectiveUsage = null;
            String existingJson = billingSnapshotService.getSnapshotJson(
                    taskId, SNAPSHOT_STAGE_SETTLE_PLAN);
            if (StrUtil.isNotBlank(existingJson))
            {
                try
                {
                    JSONObject existing = JSONUtil.parseObj(existingJson);
                    if (Objects.equals(lockedTask.getBillingTraceId(),
                            existing.getStr(SETTLE_PLAN_KEY_TRACE_ID)))
                    {
                        JSONObject storedUsage = existing.getJSONObject(SETTLE_PLAN_KEY_USAGE_DATA);
                        if (storedUsage == null)
                        {
                            log.error("结算计划缺少用量: taskId={}", taskId);
                            return null;
                        }
                        effectiveUsage = new LinkedHashMap<>(storedUsage);
                    }
                    else if (ExtractBillingStatus.SETTLING.name().equals(billingStatus))
                    {
                        log.error("结算计划与当前流水不一致: taskId={}", taskId);
                        return null;
                    }
                }
                catch (Exception planEx)
                {
                    log.error("结算计划解析失败: taskId={}", taskId, planEx);
                    return null;
                }
            }

            if (effectiveUsage == null)
            {
                if (usageData == null)
                {
                    return null;
                }
                effectiveUsage = new LinkedHashMap<>(usageData);
                Map<String, Object> settlePlan = new LinkedHashMap<>();
                settlePlan.put(SETTLE_PLAN_KEY_TRACE_ID, lockedTask.getBillingTraceId());
                settlePlan.put(SETTLE_PLAN_KEY_USAGE_DATA, effectiveUsage);
                billingSnapshotService.saveOrUpdateSnapshot(lockedTask,
                        SNAPSHOT_STAGE_SETTLE_PLAN, JSONUtil.toJsonStr(settlePlan));
            }

            if (!ExtractBillingStatus.SETTLING.name().equals(billingStatus))
            {
                LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
                update.eq(AidExtractTask::getId, taskId);
                update.eq(AidExtractTask::getUserId, userId);
                update.eq(AidExtractTask::getBillingTraceId, lockedTask.getBillingTraceId());
                update.eq(AidExtractTask::getBillingStatus, billingStatus);
                update.set(AidExtractTask::getBillingStatus, ExtractBillingStatus.SETTLING.name());
                update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
                if (extractTaskService.getBaseMapper().update(null, update) == 0)
                {
                    status.setRollbackOnly();
                    return null;
                }
            }
            return effectiveUsage;
        });
    }

    private Map<String, Object> loadSettlePlanUsage(AidExtractTask task)
    {
        if (task == null || StrUtil.isBlank(task.getBillingTraceId()))
        {
            return null;
        }
        String planJson = billingSnapshotService.getSnapshotJson(task.getId(), SNAPSHOT_STAGE_SETTLE_PLAN);
        if (StrUtil.isBlank(planJson))
        {
            return null;
        }
        try
        {
            JSONObject plan = JSONUtil.parseObj(planJson);
            if (!Objects.equals(task.getBillingTraceId(), plan.getStr(SETTLE_PLAN_KEY_TRACE_ID)))
            {
                return null;
            }
            JSONObject usage = plan.getJSONObject(SETTLE_PLAN_KEY_USAGE_DATA);
            return usage == null ? null : new LinkedHashMap<>(usage);
        }
        catch (Exception e)
        {
            log.error("读取结算计划失败: taskId={}", task.getId(), e);
            return null;
        }
    }

    @Override
    public boolean settleBilling(Long taskId, Long userId)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (task == null)
        {
            return true;
        }

        String billingStatus = task.getBillingStatus();

        // 已终态，无需处理
        if (ExtractBillingStatus.SUCCESS.name().equals(billingStatus))
        {
            return true;
        }

        // Step 1: CAS FROZEN → SETTLING（只有抢到的线程才能继续）
        if (ExtractBillingStatus.FROZEN.name().equals(billingStatus))
        {
            int rows = casUpdateBillingStatus(taskId, ExtractBillingStatus.FROZEN.name(), ExtractBillingStatus.SETTLING.name());
            if (rows == 0)
            {
                log.info("提取任务结算CAS抢锁失败, taskId={}", taskId);
                return false;
            }
        }
        else if (!ExtractBillingStatus.SETTLING.name().equals(billingStatus))
        {
            // 非 FROZEN 非 SETTLING，无法结算
            return true;
        }

        // 此处任务状态一定是 SETTLING（要么刚抢到，要么已经是补偿重试）

        BigDecimal frozenAmount = task.getFrozenAmount();
        if (frozenAmount == null || frozenAmount.compareTo(BigDecimal.ZERO) <= 0)
        {
            int fastRows = casUpdateBillingStatus(taskId, ExtractBillingStatus.SETTLING.name(), ExtractBillingStatus.SUCCESS.name());
            if (fastRows > 0)
            {
                log.info("提取任务结算成功（零金额）, taskId={}", taskId);
                clearResumeContextAfterTerminal(taskId, task.getBillingTraceId());
            }
            return true;
        }

        // Step 2: 执行账户结算（幂等，已执行则跳过）
        accountUpdateService.settle(userId, frozenAmount, task.getBillingTraceId(), "settle", "资产提取任务结算扣费");

        // Step 3: CAS SETTLING → SUCCESS
        int finalRows = casUpdateBillingStatus(taskId, ExtractBillingStatus.SETTLING.name(), ExtractBillingStatus.SUCCESS.name());
        if (finalRows > 0)
        {
            log.info("提取任务结算成功, taskId={}, userId={}, amount={}", taskId, userId, frozenAmount);
            clearResumeContextAfterTerminal(taskId, task.getBillingTraceId());
        }
        else
        {
            log.info("提取任务结算终态CAS失败（已被其他线程推进）, taskId={}", taskId);
        }

        return true;
    }

    /**
     * 差额结算：按 provider 实际 token usage 计算实际费用。
     * TOKEN 口径多退少补（实际高于预冻结时 settleExtraCharge 补扣、低于时退差额）；
     * 非 TOKEN 口径封顶到预冻结额（只退不补）。无快照或无 usageData 时降级为全额结算。
     */
    @Override
    public boolean settleBilling(Long taskId, Long userId, Map<String, Object> usageData)
    {
        return settleBilling(taskId, userId, usageData, null);
    }

    @Override
    public boolean settleBilling(Long taskId, Long userId, Map<String, Object> usageData,
                                 String expectedTraceId)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (task == null)
        {
            return true;
        }
        if (StrUtil.isNotBlank(expectedTraceId)
                && !Objects.equals(expectedTraceId, task.getBillingTraceId()))
        {
            log.info("忽略过期周期结算: taskId={}", taskId);
            return false;
        }
        if (StrUtil.isNotBlank(expectedTraceId) && !isWorkerSettlementStatus(task.getStatus()))
        {
            log.info("结算跳过，任务执行权已回收: taskId={}, status={}", taskId, task.getStatus());
            return false;
        }

        String billingStatus = task.getBillingStatus();

        // 已终态（全额结算完成），无需处理
        if (ExtractBillingStatus.SUCCESS.name().equals(billingStatus))
        {
            clearResumeContextAfterTerminal(taskId, task.getBillingTraceId());
            return true;
        }
        if (!ExtractBillingStatus.FROZEN.name().equals(billingStatus)
                && !ExtractBillingStatus.PARTIAL_SUCCESS.name().equals(billingStatus)
                && !ExtractBillingStatus.SETTLING.name().equals(billingStatus))
        {
            return true;
        }

        String frozenSnapshotJson = getBillingSnapshotJson(task, SNAPSHOT_STAGE_FROZEN);
        if (isMultiModelExtractSnapshot(frozenSnapshotJson)
                && usageData != null
                && usageData.containsKey(USAGE_KEY_AGGREGATION_COMPLETE)
                && !isCompleteMultiModelUsage(usageData))
        {
            log.warn("多模型提取用量尚未聚合完成，暂缓结算: taskId={}", taskId);
            return false;
        }

        // 先在短事务中持久化不可变用量计划，再进入 SETTLING。宕机重试只能重放该计划，
        // 禁止丢失内存 usage 后猜测为全额结算。
        Map<String, Object> effectiveUsageData = reserveOrLoadSettlePlan(
                taskId, userId, usageData, expectedTraceId);
        if (effectiveUsageData == null)
        {
            log.warn("提取任务缺少可重放结算计划，暂缓结算: taskId={}, billingStatus={}",
                    taskId, billingStatus);
            return false;
        }
        usageData = effectiveUsageData;
        if (isMultiModelExtractSnapshot(frozenSnapshotJson) && !isCompleteMultiModelUsage(usageData))
        {
            log.warn("多模型提取结算计划不完整，暂缓结算: taskId={}", taskId);
            return false;
        }
        task = extractTaskService.selectAidExtractTaskById(taskId);
        if (task == null || !ExtractBillingStatus.SETTLING.name().equals(task.getBillingStatus())
                || (StrUtil.isNotBlank(expectedTraceId)
                && (!Objects.equals(expectedTraceId, task.getBillingTraceId())
                || !isWorkerSettlementStatus(task.getStatus()))))
        {
            return false;
        }

        BigDecimal frozenAmount = task.getFrozenAmount();
        if (frozenAmount == null || frozenAmount.compareTo(BigDecimal.ZERO) <= 0)
        {
            int rows = casUpdateBillingStatus(taskId, ExtractBillingStatus.SETTLING.name(),
                    ExtractBillingStatus.SUCCESS.name());
            if (rows > 0)
            {
                clearResumeContextAfterTerminal(taskId, task.getBillingTraceId());
            }
            return true;
        }

        // 有快照 → 走统一结算（含差额/全额/降级），结算后快照标记终态并回写
        BigDecimal actualAmount = frozenAmount;
        String snapshotJson = frozenSnapshotJson;
        // 结算后的快照JSON，回写到任务表供审计
        String settledSnapshotJson = null;
        // 从快照中提取 meterType，用于判断是否走 TOKEN 补扣
        MeterType meterType = null;

        MultiModelSettleResult multiModelResult = calculateMultiModelSettle(
                snapshotJson, frozenAmount, usageData);
        String settleSnapshotJson = null;
        if (multiModelResult != null)
        {
            actualAmount = multiModelResult.actualAmount();
            meterType = multiModelResult.tokenOverageAllowed() ? MeterType.TOKEN : null;
            settledSnapshotJson = multiModelResult.settledSnapshotJson();
            log.info("多模型提取差额结算完成计算: taskId={}, frozen={}, actual={}, modelUsages={}",
                    taskId, frozenAmount, actualAmount,
                    usageData == null ? null : usageData.get(USAGE_KEY_MODEL_USAGES));
        }
        else
        {
            settleSnapshotJson = buildAggregateBatchSnapshotJson(snapshotJson, frozenAmount);
            if (settleSnapshotJson != null)
            {
                try
                {
                    BillingCalcResult settleResult = billingAmountCalculator
                            .calculateSettleAmount(frozenAmount, settleSnapshotJson, usageData);
                    if (settleResult.getAmount() != null)
                    {
                        actualAmount = settleResult.getAmount();
                    }
                    // 结算后快照含 actualInputTokens/actualOutputTokens/actualAmount/refundAmount
                    if (settleResult.getSnapshot() != null)
                    {
                        settledSnapshotJson = JSONUtil.toJsonStr(settleResult.getSnapshot());
                        // 提取 meterType
                        String mt = settleResult.getSnapshot().getMeterType();
                        if (mt != null)
                        {
                            try { meterType = MeterType.valueOf(mt); } catch (IllegalArgumentException ignored) { }
                        }
                    }
                    logTextExtractSettleSummary(taskId, userId, frozenAmount, actualAmount,
                            usageData, settleResult.getSnapshot());
                }
                catch (Exception e)
                {
                    log.error("SKU差额结算失败，降级按预扣金额结算, taskId={}", taskId, e);
                }
            }
        }

        // 账户账本统一保留四位小数，退款必须基于同一精度计算，确保结算额与退款额之和等于预冻结额。
        actualAmount = Objects.isNull(actualAmount)
                ? frozenAmount : BillingConstants.normalizeAccountAmount(actualAmount);

        // 结算冻结金额（settle 从 frozenBalance 扣减，金额不超过 frozenAmount）
        BigDecimal settleAmount = actualAmount.compareTo(frozenAmount) > 0 ? frozenAmount : actualAmount;
        accountUpdateService.settle(userId, settleAmount, task.getBillingTraceId(), "settle", "资产提取任务结算");

        // 补偿重试可能遇到历史两位小数消费流水；此时以已经落账的消费额为准计算剩余退款。
        if (actualAmount.compareTo(frozenAmount) < 0)
        {
            BigDecimal consumedAmount = accountUpdateService.resolveConsumedAmount(
                    task.getBillingTraceId(), actualAmount);
            if (consumedAmount.compareTo(actualAmount) != 0)
            {
                log.info("提取结算采用历史已落账消费额, taskId={}, calculated={}, consumed={}",
                        taskId, actualAmount, consumedAmount);
            }
            actualAmount = consumedAmount.min(frozenAmount);
            settleAmount = actualAmount;
        }

        // TOKEN 补扣：仅 meterType=TOKEN 且 actual > frozenAmount 时从可用余额补扣差额
        // 非 TOKEN 口径 actual > frozenAmount 时封顶到 frozenAmount（只退不补）
        BigDecimal extraRequired = BigDecimal.ZERO;
        BigDecimal extraCharged = BigDecimal.ZERO;
        boolean partialExtra = false;
        if (actualAmount.compareTo(frozenAmount) > 0 && meterType != MeterType.TOKEN)
        {
            log.info("非TOKEN口径actual>frozen，封顶到预扣金额, taskId={}, meterType={}, actual={}, frozen={}",
                    taskId, meterType, actualAmount, frozenAmount);
            actualAmount = frozenAmount;
        }
        if (meterType == MeterType.TOKEN && actualAmount.compareTo(frozenAmount) > 0)
        {
            extraRequired = actualAmount.subtract(frozenAmount);
            extraCharged = accountUpdateService.settleExtraCharge(
                    userId, extraRequired, task.getBillingTraceId(), "settle_extra", "提取TOKEN超预扣补扣");
            partialExtra = extraCharged.compareTo(extraRequired) < 0;
            BigDecimal finalSettled = frozenAmount.add(extraCharged);
            actualAmount = finalSettled;
            log.info("[提取TOKEN补扣] taskId={}, userId={}, preHold={}, calculatedActual={}, extraRequired={}, "
                            + "extraCharged={}, partialCharge={}, finalSettled={}",
                    taskId, userId, frozenAmount, settleAmount.add(extraRequired), extraRequired,
                    extraCharged, partialExtra, finalSettled);
            if (partialExtra)
            {
                log.info("[提取TOKEN补扣] 余额不足，已按最大余额部分补扣, taskId={}, userId={}, 应补={}, 实补={}",
                        taskId, userId, extraRequired, extraCharged);
            }
        }

        // 差额退回（仅 actual < frozen 时）
        BigDecimal refundAmount = frozenAmount.subtract(actualAmount);
        String amountAuditSource = StrUtil.isNotBlank(settledSnapshotJson)
                ? settledSnapshotJson : settleSnapshotJson;
        settledSnapshotJson = appendAmountAudit(amountAuditSource, actualAmount,
                refundAmount.max(BigDecimal.ZERO));
        if (refundAmount.compareTo(BigDecimal.ZERO) > 0)
        {
            // 调差额退回专用接口（changeType=settle_unfreeze），独立幂等键，
            // 避免与失败补偿路径 refundBilling 的 changeType=unfreeze 共用 (traceId, "unfreeze")
            // 导致幂等串台 / 重复扣减 frozen_balance。
            try
            {
                accountUpdateService.settleRefundFromFrozen(userId, refundAmount, task.getBillingTraceId(),
                        "settle_refund", "资产提取差额退回");
                logTextExtractRefundSummary(taskId, userId, frozenAmount, actualAmount, refundAmount);
            }
            catch (RuntimeException refundEx)
            {
                // consume 与 settle_unfreeze 均以流水幂等；差额未退回时保留 SETTLING，
                // 由统一计费补偿任务重试，禁止提前写 SUCCESS 形成永久冻结余额。
                log.error("差额退回失败，等待补偿重试, taskId={}, userId={}, "
                                + "frozen={}, actual={}, refund={}, traceId={}",
                        taskId, userId, frozenAmount, actualAmount, refundAmount, task.getBillingTraceId(),
                        refundEx);
                return false;
            }
        }

        // 补扣审计字段回写到快照
        if (extraRequired.compareTo(BigDecimal.ZERO) > 0)
        {
            try
            {
                String auditSnapshotJson = settledSnapshotJson != null
                        ? settledSnapshotJson : settleSnapshotJson;
                settledSnapshotJson = appendExtraChargeAudit(auditSnapshotJson,
                        extraRequired, extraCharged, partialExtra, actualAmount);
            }
            catch (Exception ex)
            {
                log.warn("补扣审计字段回写快照失败, taskId={}", taskId, ex);
            }
        }

        // 部分补扣 → PARTIAL_SUCCESS（后续定时任务追补），全额 → SUCCESS
        String targetBillingStatus = partialExtra
                ? ExtractBillingStatus.PARTIAL_SUCCESS.name()
                : ExtractBillingStatus.SUCCESS.name();

        if (settledSnapshotJson != null)
        {
            saveBillingSnapshot(taskId, SNAPSHOT_STAGE_SETTLED, settledSnapshotJson);
        }

        // 更新实际扣费 + 回写结算后快照 + CAS SETTLING → 终态
        LambdaUpdateWrapper<AidExtractTask> finalUpdate = Wrappers.lambdaUpdate();
        finalUpdate.eq(AidExtractTask::getId, taskId);
        finalUpdate.eq(AidExtractTask::getBillingStatus, ExtractBillingStatus.SETTLING.name());
        finalUpdate.set(AidExtractTask::getBillingStatus, targetBillingStatus);
        finalUpdate.set(AidExtractTask::getActualCost, actualAmount);
        // 回写结算后的快照：含 actualInputTokens/actualOutputTokens/actualAmount/refundAmount
        if (settledSnapshotJson != null)
        {
            finalUpdate.set(AidExtractTask::getBillingSnapshotJson, buildSnapshotRefJson(SNAPSHOT_STAGE_SETTLED));
        }
        int finalRows = extractTaskService.getBaseMapper().update(null, finalUpdate);

        if (finalRows > 0)
        {
            log.info("提取任务差额结算完成, taskId={}, frozen={}, actual={}, refund={}, targetStatus={}",
                    taskId, frozenAmount, actualAmount, refundAmount, targetBillingStatus);
            clearResumeContextAfterTerminal(taskId, task.getBillingTraceId());
            if (partialExtra)
            {
                log.info("部分补扣，提取任务进入PARTIAL_SUCCESS待追补, taskId={}, userId={}", taskId, userId);
            }
        }
        else
        {
            log.info("提取任务差额结算终态CAS失败（已被其他线程推进）, taskId={}", taskId);
        }

        return true;
    }

    /**
     * 资产提取按模型分项结算。完整聚合能区分“未调用”和“已调用但无 token 遥测”：
     * 未调用分项结算为零；已调用但遥测缺失时按实际成功调用数占预计调用数的比例兜底。
     * 旧调用方未提供完整聚合标记时仍按预冻结额结算，避免历史任务意外免费。
     */
    MultiModelSettleResult calculateMultiModelSettle(String snapshotJson,
                                                      BigDecimal frozenAmount,
                                                      Map<String, Object> usageData)
    {
        if (StrUtil.isBlank(snapshotJson))
        {
            return null;
        }
        try
        {
            JSONObject root = JSONUtil.parseObj(snapshotJson);
            if (!Objects.equals(BILLING_BATCH_TYPE_MULTI_MODEL_EXTRACT, root.getStr("batchType")))
            {
                return null;
            }
            JSONArray items = root.getJSONArray("items");
            if (items == null || items.isEmpty())
            {
                log.warn("多模型提取计费快照无分项，按预冻结额结算");
                return new MultiModelSettleResult(frozenAmount, false, snapshotJson);
            }

            Object rawModelUsages = usageData == null ? null : usageData.get(USAGE_KEY_MODEL_USAGES);
            boolean modelUsagesValid = isModelUsagesValid(rawModelUsages);
            JSONObject modelUsages = modelUsagesValid
                    ? JSONUtil.parseObj(rawModelUsages) : new JSONObject();
            boolean aggregationComplete = usageData != null
                    && usageData.containsKey(USAGE_KEY_AGGREGATION_COMPLETE)
                    && resolveBoolean(usageData.get(USAGE_KEY_AGGREGATION_COMPLETE))
                    && modelUsagesValid;
            JSONArray callUsages = resolveCallUsages(
                    usageData == null ? null : usageData.get(USAGE_KEY_CALL_USAGES));
            boolean perCallUsageAvailable = Objects.nonNull(callUsages);

            // 出现无法映射到计费分项的模型时，不能把缺失分项误判为“未调用”。
            List<String> itemModelCodes = new java.util.ArrayList<>();
            for (Object itemObj : items)
            {
                String itemModelCode = JSONUtil.parseObj(itemObj).getStr("modelCode");
                if (StrUtil.isNotBlank(itemModelCode))
                {
                    itemModelCodes.add(itemModelCode);
                }
            }
            if (aggregationComplete)
            {
                for (String usageModelCode : modelUsages.keySet())
                {
                    if (!itemModelCodes.contains(usageModelCode))
                    {
                        aggregationComplete = false;
                        log.error("提取用量模型无法匹配计费分项: usageModelCode={}, itemModelCodes={}",
                                usageModelCode, itemModelCodes);
                        break;
                    }
                }
            }

            BigDecimal totalActual = BigDecimal.ZERO;
            boolean tokenOverageAllowed = false;
            JSONArray settledItems = new JSONArray();
            for (Object itemObj : items)
            {
                JSONObject item = JSONUtil.parseObj(itemObj);
                String modelCode = item.getStr("modelCode");
                BigDecimal itemPreHold = resolveDecimal(item.get("preHoldAmount"), BigDecimal.ZERO);
                BigDecimal itemActual = itemPreHold;
                int expectedCallCount = Math.max(1,
                        resolveInt(item.get(SNAPSHOT_KEY_EXPECTED_CALL_COUNT), 1));
                BigDecimal unitPreHoldAmount = resolveDecimal(
                        item.get(SNAPSHOT_KEY_UNIT_PRE_HOLD_AMOUNT), null);
                if (unitPreHoldAmount == null)
                {
                    unitPreHoldAmount = itemPreHold.divide(
                            BigDecimal.valueOf(expectedCallCount), 12, java.math.RoundingMode.HALF_UP);
                }
                Object snapshotObj = item.get("snapshot");
                JSONObject modelUsage = StrUtil.isBlank(modelCode)
                        ? null : modelUsages.getJSONObject(modelCode);

                JSONArray callEstimates = item.getJSONArray(SNAPSHOT_KEY_CALL_ESTIMATES);
                if (aggregationComplete && StrUtil.isNotBlank(modelCode)
                        && perCallUsageAvailable && Objects.nonNull(callEstimates) && !callEstimates.isEmpty())
                {
                    PerCallItemSettleResult perCallResult = calculatePerCallItemSettle(
                            modelCode, itemPreHold, snapshotObj, callEstimates, callUsages, modelUsage);
                    itemActual = perCallResult.actualAmount();
                    tokenOverageAllowed = tokenOverageAllowed || perCallResult.tokenOverageAllowed();
                    item.set(SNAPSHOT_KEY_SETTLED_CALLS, perCallResult.settledCalls());
                    item.set("successfulCallCount", perCallResult.successfulCallCount());
                    item.set("usageCallCount", perCallResult.usageCallCount());
                    item.set("actualAmount", itemActual);
                    totalActual = totalActual.add(itemActual);
                    settledItems.add(item);
                    continue;
                }

                if (aggregationComplete && StrUtil.isNotBlank(modelCode))
                {
                    if (modelUsage == null || modelUsage.isEmpty())
                    {
                        // 完整聚合中不存在该模型，证明本轮没有创建该模型的媒体调用。
                        itemActual = BigDecimal.ZERO;
                    }
                    else
                    {
                        int successfulCallCount = Math.max(0, resolveInt(
                                modelUsage.get(USAGE_KEY_SUCCESSFUL_CALL_COUNT), 0));
                        int usageCallCount = Math.max(0, resolveInt(
                                modelUsage.get(USAGE_KEY_USAGE_CALL_COUNT), 0));
                        int successfulUsageCallCount = Math.max(0, resolveInt(
                                modelUsage.get(USAGE_KEY_SUCCESSFUL_USAGE_CALL_COUNT), 0));
                        int overlappingUsageCalls = Math.min(successfulUsageCallCount,
                                Math.min(successfulCallCount, usageCallCount));
                        long billableCallCountLong = (long) successfulCallCount
                                + usageCallCount - overlappingUsageCalls;
                        int billableCallCount = (int) Math.min(Integer.MAX_VALUE,
                                Math.max(0L, billableCallCountLong));
                        int inputTokens = Math.max(0, resolveInt(
                                modelUsage.get(USAGE_KEY_INPUT_TOKENS), 0));
                        int outputTokens = Math.max(0, resolveInt(
                                modelUsage.get(USAGE_KEY_OUTPUT_TOKENS), 0));
                        MeterType itemMeterType = resolveMeterType(null, snapshotObj);

                        if (billableCallCount <= 0)
                        {
                            itemActual = BigDecimal.ZERO;
                        }
                        else if (snapshotObj != null && isTokenUsagePriced(snapshotObj, itemMeterType)
                                && usageCallCount > 0 && (inputTokens > 0 || outputTokens > 0))
                        {
                            BillingCalcResult itemResult = billingAmountCalculator.calculateSettleAmount(
                                    itemPreHold, JSONUtil.toJsonStr(snapshotObj), modelUsage);
                            BillingSnapshot settledSnapshot = itemResult.getSnapshot();
                            BigDecimal knownUsageAmount = Objects.nonNull(itemResult.getAmount())
                                    ? itemResult.getAmount() : BigDecimal.ZERO;
                            int missingSuccessfulCalls = Math.max(
                                    0, successfulCallCount - overlappingUsageCalls);
                            BigDecimal missingUsageFallback = unitPreHoldAmount.multiply(
                                    BigDecimal.valueOf(Math.min(expectedCallCount, missingSuccessfulCalls)));
                            itemActual = knownUsageAmount.add(missingUsageFallback);

                            // 只有全部成功调用都有真实 usage 且不存在失败调用 usage 时，才允许 TOKEN 补扣。
                            boolean exactTokenUsage = overlappingUsageCalls == successfulCallCount
                                    && usageCallCount == overlappingUsageCalls;
                            if (exactTokenUsage)
                            {
                                tokenOverageAllowed = tokenOverageAllowed
                                        || itemActual.compareTo(itemPreHold) > 0;
                            }
                            else
                            {
                                itemActual = itemActual.min(itemPreHold);
                            }
                            if (settledSnapshot != null)
                            {
                                item.set("settledSnapshot", settledSnapshot);
                            }
                        }
                        else
                        {
                            // FIXED / SKU_PACKAGE，或 TOKEN 调用缺少遥测：按实际成功调用数比例结算。
                            int chargedCalls = Math.min(expectedCallCount, billableCallCount);
                            itemActual = unitPreHoldAmount.multiply(BigDecimal.valueOf(chargedCalls))
                                    .min(itemPreHold);
                        }
                        item.set("successfulCallCount", successfulCallCount);
                        item.set("usageCallCount", usageCallCount);
                    }
                }

                item.set("actualAmount", itemActual);
                totalActual = totalActual.add(itemActual);
                settledItems.add(item);
            }
            root.set("items", settledItems);
            root.set("preHoldAmount", frozenAmount);
            root.set("actualAmount", totalActual);
            root.set(USAGE_KEY_AGGREGATION_COMPLETE, aggregationComplete);
            return new MultiModelSettleResult(totalActual, tokenOverageAllowed, root.toString());
        }
        catch (Exception e)
        {
            log.error("多模型提取差额结算失败，按预冻结额结算", e);
            return new MultiModelSettleResult(frozenAmount, false, snapshotJson);
        }
    }

    /**
     * 新快照按媒体子任务逐次结算：每条真实 usage 独立使用冻结规则匹配 SKU，
     * 成功但缺少 usage 的调用仅按对应预估调用金额兜底，未发生的预计调用自动退回。
     */
    private PerCallItemSettleResult calculatePerCallItemSettle(
            String modelCode, BigDecimal itemPreHold, Object snapshotObj,
            JSONArray callEstimates, JSONArray callUsages, JSONObject modelUsage)
    {
        MeterType meterType = resolveMeterType(null, snapshotObj);
        boolean tokenUsagePriced = isTokenUsagePriced(snapshotObj, meterType);
        String snapshotJson = Objects.isNull(snapshotObj) ? null : JSONUtil.toJsonStr(snapshotObj);
        JSONArray settledCalls = new JSONArray();
        BigDecimal calculatedActual = BigDecimal.ZERO;
        int modelCallIndex = 0;
        int successfulCallCount = 0;
        int usageCallCount = 0;
        int successfulUsageCallCount = 0;

        for (Object callUsageObj : callUsages)
        {
            JSONObject callUsage = JSONUtil.parseObj(callUsageObj);
            if (!Objects.equals(modelCode, callUsage.getStr(CALL_USAGE_KEY_MODEL_CODE)))
            {
                continue;
            }

            JSONObject expectedCall = modelCallIndex < callEstimates.size()
                    ? JSONUtil.parseObj(callEstimates.get(modelCallIndex)) : null;
            modelCallIndex++;
            BigDecimal expectedAmount = Objects.isNull(expectedCall)
                    ? BigDecimal.ZERO
                    : resolveDecimal(expectedCall.get("preHoldAmount"), BigDecimal.ZERO);
            boolean successful = resolveBoolean(callUsage.get(CALL_USAGE_KEY_SUCCESSFUL));
            boolean hasUsage = resolveBoolean(callUsage.get(CALL_USAGE_KEY_HAS_USAGE));
            int inputTokens = Math.max(0, resolveInt(callUsage.get(USAGE_KEY_INPUT_TOKENS), 0));
            int outputTokens = Math.max(0, resolveInt(callUsage.get(USAGE_KEY_OUTPUT_TOKENS), 0));
            if (successful)
            {
                successfulCallCount++;
            }
            if (hasUsage)
            {
                usageCallCount++;
                if (successful)
                {
                    successfulUsageCallCount++;
                }
            }

            BigDecimal callActual = BigDecimal.ZERO;
            String billingSource = "NOT_BILLABLE";
            BillingSnapshot settledSnapshot = null;
            if (tokenUsagePriced && hasUsage && (inputTokens > 0 || outputTokens > 0))
            {
                Map<String, Object> singleUsage = new LinkedHashMap<>();
                singleUsage.put(USAGE_KEY_INPUT_TOKENS, inputTokens);
                singleUsage.put(USAGE_KEY_OUTPUT_TOKENS, outputTokens);
                BillingCalcResult callResult = billingAmountCalculator.calculateSettleAmount(
                        expectedAmount, snapshotJson, singleUsage);
                callActual = defaultAmount(callResult.getAmount());
                settledSnapshot = callResult.getSnapshot();
                billingSource = "PROVIDER_USAGE";
            }
            else if (successful || hasUsage)
            {
                // provider 没给可用 token 时沿用该次预计金额；超过预计调用数的未知用量不猜价。
                callActual = expectedAmount;
                billingSource = Objects.isNull(expectedCall) ? "NO_ESTIMATE" : "PRE_HOLD_FALLBACK";
            }
            calculatedActual = calculatedActual.add(callActual);

            Map<String, Object> settledCall = new LinkedHashMap<>();
            settledCall.put(CALL_USAGE_KEY_MEDIA_TASK_ID,
                    resolveLong(callUsage.get(CALL_USAGE_KEY_MEDIA_TASK_ID), 0L));
            settledCall.put(CALL_USAGE_KEY_SUCCESSFUL, successful);
            settledCall.put(CALL_USAGE_KEY_HAS_USAGE, hasUsage);
            settledCall.put(USAGE_KEY_INPUT_TOKENS, inputTokens);
            settledCall.put(USAGE_KEY_OUTPUT_TOKENS, outputTokens);
            settledCall.put("billingSource", billingSource);
            settledCall.put("actualAmount", callActual);
            if (Objects.nonNull(settledSnapshot))
            {
                settledCall.put("skuCode", settledSnapshot.getSkuCode());
                settledCall.put("skuName", settledSnapshot.getSkuName());
            }
            settledCalls.add(settledCall);
        }

        // 与旧协议一致：只有所有成功调用都有真实 usage，且不存在失败调用 usage，才允许 TOKEN 补扣。
        boolean exactTokenUsage = tokenUsagePriced
                && successfulUsageCallCount == successfulCallCount
                && usageCallCount == successfulUsageCallCount;
        BigDecimal actualAmount = exactTokenUsage
                ? calculatedActual : calculatedActual.min(itemPreHold);
        boolean tokenOverageAllowed = exactTokenUsage && actualAmount.compareTo(itemPreHold) > 0;
        if (Objects.nonNull(modelUsage))
        {
            int aggregatedSuccessful = Math.max(0,
                    resolveInt(modelUsage.get(USAGE_KEY_SUCCESSFUL_CALL_COUNT), 0));
            int aggregatedUsage = Math.max(0,
                    resolveInt(modelUsage.get(USAGE_KEY_USAGE_CALL_COUNT), 0));
            if (aggregatedSuccessful != successfulCallCount || aggregatedUsage != usageCallCount)
            {
                log.error("逐调用用量与模型聚合不一致，按保守上限结算: modelCode={}, callSuccessful={}, "
                                + "aggregateSuccessful={}, callUsage={}, aggregateUsage={}",
                        modelCode, successfulCallCount, aggregatedSuccessful, usageCallCount, aggregatedUsage);
                actualAmount = actualAmount.min(itemPreHold);
                tokenOverageAllowed = false;
            }
        }
        return new PerCallItemSettleResult(actualAmount, tokenOverageAllowed, settledCalls,
                successfulCallCount, usageCallCount);
    }

    /** 解析结算计划里的逐调用紧凑用量；字段缺失时返回 null，让旧快照走原聚合兼容分支。 */
    private JSONArray resolveCallUsages(Object rawCallUsages)
    {
        if (Objects.isNull(rawCallUsages))
        {
            return null;
        }
        try
        {
            return rawCallUsages instanceof JSONArray array
                    ? array : JSONUtil.parseArray(rawCallUsages);
        }
        catch (Exception e)
        {
            log.error("解析逐调用计费用量失败", e);
            return null;
        }
    }

    private record PerCallItemSettleResult(BigDecimal actualAmount,
                                           boolean tokenOverageAllowed,
                                           JSONArray settledCalls,
                                           int successfulCallCount,
                                           int usageCallCount)
    {
    }

    private MeterType resolveMeterType(BillingSnapshot settledSnapshot, Object originalSnapshot)
    {
        String meterType = Objects.nonNull(settledSnapshot) ? settledSnapshot.getMeterType() : null;
        if (StrUtil.isBlank(meterType) && originalSnapshot != null)
        {
            BillingSnapshot original = JSONUtil.toBean(JSONUtil.toJsonStr(originalSnapshot), BillingSnapshot.class);
            meterType = Objects.nonNull(original) ? original.getMeterType() : null;
        }
        if (StrUtil.isBlank(meterType))
        {
            return null;
        }
        try
        {
            return MeterType.valueOf(meterType);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private boolean isTokenUsagePriced(Object snapshotObj, MeterType meterType)
    {
        if (snapshotObj == null || meterType != MeterType.TOKEN)
        {
            return false;
        }
        BillingSnapshot snapshot = JSONUtil.toBean(JSONUtil.toJsonStr(snapshotObj), BillingSnapshot.class);
        return snapshot != null
                && BillingMode.of(snapshot.getBillingMode()) != BillingMode.FIXED
                && StrUtil.isNotBlank(snapshot.getBillingRuleJson());
    }

    private boolean isCompleteMultiModelUsage(Map<String, Object> usageData)
    {
        return usageData != null
                && usageData.containsKey(USAGE_KEY_AGGREGATION_COMPLETE)
                && resolveBoolean(usageData.get(USAGE_KEY_AGGREGATION_COMPLETE))
                && isModelUsagesValid(usageData.get(USAGE_KEY_MODEL_USAGES));
    }

    private boolean isModelUsagesValid(Object modelUsages)
    {
        return modelUsages instanceof Map<?, ?> || modelUsages instanceof JSONObject;
    }

    private boolean isMultiModelExtractSnapshot(String snapshotJson)
    {
        if (StrUtil.isBlank(snapshotJson))
        {
            return false;
        }
        try
        {
            return Objects.equals(BILLING_BATCH_TYPE_MULTI_MODEL_EXTRACT,
                    JSONUtil.parseObj(snapshotJson).getStr("batchType"));
        }
        catch (Exception e)
        {
            log.warn("识别提取计费快照失败", e);
            return false;
        }
    }

    private BigDecimal resolveDecimal(Object value, BigDecimal defaultValue)
    {
        if (value == null)
        {
            return defaultValue;
        }
        try
        {
            return new BigDecimal(String.valueOf(value));
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }

    private BigDecimal defaultAmount(BigDecimal amount)
    {
        return Objects.isNull(amount) ? BigDecimal.ZERO : amount;
    }

    private int resolveInt(Object value, int defaultValue)
    {
        if (value == null)
        {
            return defaultValue;
        }
        try
        {
            return Integer.parseInt(String.valueOf(value));
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }

    private long resolveLong(Object value, long defaultValue)
    {
        if (value == null)
        {
            return defaultValue;
        }
        try
        {
            return Long.parseLong(String.valueOf(value));
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }

    private boolean resolveBoolean(Object value)
    {
        return value instanceof Boolean booleanValue
                ? booleanValue : Boolean.parseBoolean(String.valueOf(value));
    }

    private String appendExtraChargeAudit(String snapshotJson, BigDecimal extraRequired,
                                          BigDecimal extraCharged, boolean partialExtra,
                                          BigDecimal actualAmount)
    {
        if (StrUtil.isBlank(snapshotJson))
        {
            return null;
        }
        JSONObject root = JSONUtil.parseObj(snapshotJson);
        if (Objects.equals(BILLING_BATCH_TYPE_MULTI_MODEL_EXTRACT, root.getStr("batchType")))
        {
            root.set("extraChargeRequired", extraRequired);
            root.set("extraChargeActual", extraCharged);
            root.set("partialExtraCharge", partialExtra);
            root.set("actualAmount", actualAmount);
            return root.toString();
        }

        BillingSnapshot snapshot = JSONUtil.toBean(snapshotJson, BillingSnapshot.class);
        snapshot.setExtraChargeRequired(extraRequired);
        snapshot.setExtraChargeActual(extraCharged);
        snapshot.setPartialExtraCharge(partialExtra);
        snapshot.setActualAmount(actualAmount);
        return JSONUtil.toJsonStr(snapshot);
    }

    private String appendAmountAudit(String snapshotJson, BigDecimal actualAmount,
                                     BigDecimal refundAmount)
    {
        if (StrUtil.isBlank(snapshotJson))
        {
            return null;
        }
        JSONObject root = JSONUtil.parseObj(snapshotJson);
        if (Objects.equals(BILLING_BATCH_TYPE_MULTI_MODEL_EXTRACT, root.getStr("batchType")))
        {
            root.set("actualAmount", actualAmount);
            root.set("refundAmount", refundAmount);
            return root.toString();
        }

        BillingSnapshot snapshot = JSONUtil.toBean(snapshotJson, BillingSnapshot.class);
        snapshot.setActualAmount(actualAmount);
        snapshot.setRefundAmount(refundAmount);
        return JSONUtil.toJsonStr(snapshot);
    }

    record MultiModelSettleResult(BigDecimal actualAmount,
                                  boolean tokenOverageAllowed,
                                  String settledSnapshotJson)
    {
    }

    /**
     * 批量任务快照存在两种结构：items[].snapshot 与顶层 settleSnapshot，两者均需兼容解析。
     * 能聚合出单个 BillingSnapshot 时走差额结算，否则降级按预冻结额结算。
     */
    private String buildAggregateBatchSnapshotJson(String snapshotJson, BigDecimal frozenAmount)
    {
        if (snapshotJson == null)
        {
            return null;
        }
        try
        {
            JSONObject root = JSONUtil.parseObj(snapshotJson);
            Object settleSnapshotObj = root.get("settleSnapshot");
            if (settleSnapshotObj != null)
            {
                BillingSnapshot aggregate = JSONUtil.toBean(JSONUtil.toJsonStr(settleSnapshotObj), BillingSnapshot.class);
                if (aggregate == null)
                {
                    return null;
                }
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("batchType", root.getStr("batchType"));
                Integer itemCount = root.getInt("itemCount");
                params.put("itemCount", itemCount == null ? 0 : itemCount);
                aggregate.setRequestParams(params);
                aggregate.setPreHoldAmount(frozenAmount);
                aggregate.setActualInputTokens(null);
                aggregate.setActualOutputTokens(null);
                aggregate.setActualAmount(null);
                aggregate.setRefundAmount(null);
                aggregate.setSettleTime(null);
                aggregate.setTextSettleDone(false);
                aggregate.setExtraChargeRequired(null);
                aggregate.setExtraChargeActual(null);
                aggregate.setPartialExtraCharge(null);
                return JSONUtil.toJsonStr(aggregate);
            }

            JSONArray items = root.getJSONArray("items");
            if (items == null || items.isEmpty())
            {
                return root.containsKey("batchType") ? null : snapshotJson;
            }

            BillingSnapshot aggregate = null;
            int itemCount = 0;
            int estimatedInputTokens = 0;
            int estimatedOutputTokens = 0;
            for (Object itemObj : items)
            {
                JSONObject item = JSONUtil.parseObj(itemObj);
                Object snapshotObj = item.get("snapshot");
                if (snapshotObj == null)
                {
                    continue;
                }
                BillingSnapshot snapshot = JSONUtil.toBean(JSONUtil.toJsonStr(snapshotObj), BillingSnapshot.class);
                if (snapshot == null)
                {
                    continue;
                }
                if (aggregate == null)
                {
                    aggregate = snapshot;
                }
                else if (!isSameSettlePricing(aggregate, snapshot))
                {
                    log.warn("批量计费快照存在不同计价口径，降级按预冻结结算: batchType={}", root.getStr("batchType"));
                    return null;
                }
                estimatedInputTokens += Objects.isNull(snapshot.getEstimatedInputTokens())
                        ? 0 : snapshot.getEstimatedInputTokens();
                estimatedOutputTokens += Objects.isNull(snapshot.getEstimatedOutputTokens())
                        ? 0 : snapshot.getEstimatedOutputTokens();
                itemCount++;
            }
            if (aggregate == null || itemCount == 0)
            {
                return null;
            }

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("batchType", root.getStr("batchType"));
            params.put("itemCount", itemCount);
            aggregate.setRequestParams(params);
            aggregate.setPreHoldAmount(frozenAmount);
            aggregate.setEstimatedInputTokens(estimatedInputTokens);
            aggregate.setEstimatedOutputTokens(estimatedOutputTokens);
            aggregate.setActualInputTokens(null);
            aggregate.setActualOutputTokens(null);
            aggregate.setActualAmount(null);
            aggregate.setRefundAmount(null);
            aggregate.setSettleTime(null);
            aggregate.setTextSettleDone(false);
            aggregate.setExtraChargeRequired(null);
            aggregate.setExtraChargeActual(null);
            aggregate.setPartialExtraCharge(null);
            return JSONUtil.toJsonStr(aggregate);
        }
        catch (Exception e)
        {
            log.warn("批量计费快照聚合失败，降级原快照结算: err={}", e.getMessage());
            return null;
        }
    }

    private boolean isSameSettlePricing(BillingSnapshot left, BillingSnapshot right)
    {
        return Objects.equals(left.getMeterType(), right.getMeterType())
                && Objects.equals(left.getBillingMode(), right.getBillingMode())
                && Objects.equals(left.getBillingRuleJson(), right.getBillingRuleJson())
                && Objects.equals(left.getInputPricePerMillion(), right.getInputPricePerMillion())
                && Objects.equals(left.getOutputPricePerMillion(), right.getOutputPricePerMillion())
                && Objects.equals(left.getPricePerSecond(), right.getPricePerSecond())
                && Objects.equals(left.getSkuPackagePrice(), right.getSkuPackagePrice())
                && Objects.equals(left.getFinalBillingMultiplier(), right.getFinalBillingMultiplier());
    }

    @Override
    public boolean refundBilling(Long taskId, Long userId)
    {
        return refundBilling(taskId, userId, null);
    }

    @Override
    public boolean refundBilling(Long taskId, Long userId, String expectedTraceId)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (task == null)
        {
            return true;
        }
        if (StrUtil.isNotBlank(expectedTraceId)
                && !Objects.equals(expectedTraceId, task.getBillingTraceId()))
        {
            log.info("忽略过期周期退款: taskId={}", taskId);
            return false;
        }
        if (StrUtil.isNotBlank(expectedTraceId)
                && !isExpectedRefundStatus(task.getStatus()))
        {
            log.info("退款跳过，任务周期无执行权: taskId={}, status={}", taskId, task.getStatus());
            return false;
        }

        String billingStatus = task.getBillingStatus();

        // 已终态，无需处理
        if (ExtractBillingStatus.FAILED.name().equals(billingStatus))
        {
            clearResumeContextAfterTerminal(taskId, task.getBillingTraceId());
            return true;
        }

        // Step 1: CAS FROZEN → REFUNDING（只有抢到的线程才能继续）
        if (ExtractBillingStatus.FROZEN.name().equals(billingStatus))
        {
            int rows;
            if (StrUtil.isBlank(expectedTraceId))
            {
                rows = casUpdateBillingStatus(taskId, ExtractBillingStatus.FROZEN.name(),
                        ExtractBillingStatus.REFUNDING.name());
            }
            else
            {
                LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
                update.eq(AidExtractTask::getId, taskId);
                update.eq(AidExtractTask::getBillingTraceId, expectedTraceId);
                update.eq(AidExtractTask::getBillingStatus, ExtractBillingStatus.FROZEN.name());
                update.set(AidExtractTask::getBillingStatus, ExtractBillingStatus.REFUNDING.name());
                update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
                rows = extractTaskService.getBaseMapper().update(null, update);
            }
            if (rows == 0)
            {
                log.info("提取任务退回CAS抢锁失败, taskId={}", taskId);
                return false;
            }
        }
        else if (!ExtractBillingStatus.REFUNDING.name().equals(billingStatus))
        {
            // 非 FROZEN 非 REFUNDING，无法退回
            return true;
        }

        // 此处任务状态一定是 REFUNDING

        BigDecimal frozenAmount = task.getFrozenAmount();
        if (frozenAmount == null || frozenAmount.compareTo(BigDecimal.ZERO) <= 0)
        {
            int fastRows = casUpdateBillingStatus(taskId, ExtractBillingStatus.REFUNDING.name(), ExtractBillingStatus.FAILED.name());
            if (fastRows > 0)
            {
                log.info("提取任务退回成功（零金额）, taskId={}", taskId);
                clearResumeContextAfterTerminal(taskId, task.getBillingTraceId());
            }
            return true;
        }

        // 任务表先持久化冻结意图、账户再独立提交冻结。若进程恰在两步之间退出，
        // 只能收口任务状态，不能从用户的聚合 frozen_balance 中误退其他任务的资金。
        if (StrUtil.isBlank(task.getBillingTraceId())
                || !hasFreezeRecordFailClosed(task.getBillingTraceId()))
        {
            int noFundsRows = casUpdateBillingStatus(taskId, ExtractBillingStatus.REFUNDING.name(),
                    ExtractBillingStatus.FAILED.name());
            if (noFundsRows > 0)
            {
                log.info("提取任务未发现冻结流水，仅收口计费状态, taskId={}, traceId={}",
                        taskId, task.getBillingTraceId());
                clearResumeContextAfterTerminal(taskId, task.getBillingTraceId());
            }
            return true;
        }

        // Step 2: 执行账户退回（幂等，已执行则跳过）
        accountUpdateService.refund(userId, frozenAmount, task.getBillingTraceId(), "refund", "资产提取任务失败退回");

        // Step 3: CAS REFUNDING → FAILED
        int finalRows = casUpdateBillingStatus(taskId, ExtractBillingStatus.REFUNDING.name(), ExtractBillingStatus.FAILED.name());
        if (finalRows > 0)
        {
            log.info("提取任务退回成功, taskId={}, userId={}, amount={}", taskId, userId, frozenAmount);
            clearResumeContextAfterTerminal(taskId, task.getBillingTraceId());
        }
        else
        {
            log.info("提取任务退回终态CAS失败（已被其他线程推进）, taskId={}", taskId);
        }

        return true;
    }

    private boolean isWorkerSettlementStatus(String status)
    {
        return Objects.equals(TASK_STATUS_PROCESSING, status)
                || Objects.equals(TASK_STATUS_FINALIZING, status);
    }

    private boolean isExpectedRefundStatus(String status)
    {
        return isWorkerSettlementStatus(status)
                || Objects.equals(TASK_STATUS_RECOVERING, status);
    }

    /**
     * 补偿结算：扫描 billing_status 为 FROZEN/SETTLING/REFUNDING 且超过2分钟的记录，重试结算或退回。
     * 三种场景：
     * - FROZEN：任务已终态但未开始结算/退回 → 按 taskStatus 决定 settle 或 refund
     * - SETTLING：结算中但未完成 → 重试结算（account 操作幂等）
     * - REFUNDING：退款中但未完成 → 重试退款（account 操作幂等）
     * settleBilling/refundBilling 无 @Transactional，self-invocation 安全（账户操作自带 REQUIRES_NEW）。
     */
    @Override
    public int retryStaleFrozenBillings(int batchSize)
    {
        int processed = 0;

        // 扫描 SETTLING：已抢到结算权但未完成
        processed += retryByBillingStatus(ExtractBillingStatus.SETTLING.name(), batchSize, true);

        // 扫描 REFUNDING：已抢到退款权但未完成
        processed += retryByBillingStatus(ExtractBillingStatus.REFUNDING.name(), batchSize, false);

        // 扫描 FROZEN：未开始结算/退回（原逻辑）
        processed += retryFrozenBatch(batchSize);

        return processed;
    }
    /**
     * 扫描指定 billingStatus 的过期任务并重试。
     * isSettle=true 时调用 settleBilling，否则调用 refundBilling。
     */
    private int retryByBillingStatus(String billingStatus, int batchSize, boolean isSettle)
    {
        LambdaQueryWrapper<AidExtractTask> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidExtractTask::getBillingStatus, billingStatus);
        wrapper.select(AidExtractTask::getId, AidExtractTask::getUserId,
                AidExtractTask::getStatus, AidExtractTask::getTaskType,
                AidExtractTask::getBillingStatus, AidExtractTask::getBillingTraceId,
                AidExtractTask::getBillingSnapshotJson);
        wrapper.lt(AidExtractTask::getUpdateTime, LocalDateTime.now().minusMinutes(2));
        wrapper.last("LIMIT " + batchSize);
        List<AidExtractTask> staleTasks = extractTaskService.list(wrapper);

        int processed = 0;
        for (AidExtractTask task : staleTasks)
        {
            try
            {
                boolean result;
                ResumeBillingContext resumeRollback = loadResumeRollbackContext(task.getId());
                Boolean resumeResult = retryResumeRollbackIfNeeded(
                        task.getId(), task.getUserId(), resumeRollback);
                if (resumeResult != null)
                {
                    result = resumeResult;
                }
                else if (isSettle)
                {
                    result = settleStaleBilling(task);
                }
                else
                {
                    result = refundBilling(task.getId(), task.getUserId());
                }
                if (result)
                {
                    processed++;
                }
            }
            catch (Exception e)
            {
                log.error("补偿处理失败, billingStatus={}, taskId={}", billingStatus, task.getId(), e);
            }
        }
        return processed;
    }

    /**
     * 扫描 FROZEN 状态且任务已终态的记录：失败任务退款，成功任务结算；
     * 资产提取的部分完成/取消也按本轮真实调用结算。
     */
    private int retryFrozenBatch(int batchSize)
    {
        LambdaQueryWrapper<AidExtractTask> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidExtractTask::getBillingStatus, ExtractBillingStatus.FROZEN.name());
        wrapper.and(group -> group
                .in(AidExtractTask::getStatus, "SUCCEEDED", "FAILED")
                .or(nested -> nested
                        .eq(AidExtractTask::getTaskType, TASK_TYPE_ASSET_EXTRACT)
                        .in(AidExtractTask::getStatus, "PARTIAL_FAILED", "CANCELLED")));
        wrapper.select(AidExtractTask::getId, AidExtractTask::getUserId,
                AidExtractTask::getStatus, AidExtractTask::getTaskType,
                AidExtractTask::getBillingStatus, AidExtractTask::getBillingTraceId,
                AidExtractTask::getBillingSnapshotJson);
        wrapper.lt(AidExtractTask::getUpdateTime, LocalDateTime.now().minusMinutes(2));
        wrapper.last("LIMIT " + batchSize);
        List<AidExtractTask> staleTasks = extractTaskService.list(wrapper);

        if (staleTasks.isEmpty())
        {
            return 0;
        }

        int processed = 0;
        for (AidExtractTask task : staleTasks)
        {
            try
            {
                boolean result;
                ResumeBillingContext resumeRollback = loadResumeRollbackContext(task.getId());
                Boolean resumeResult = retryResumeRollbackIfNeeded(
                        task.getId(), task.getUserId(), resumeRollback);
                if (resumeResult != null)
                {
                    result = resumeResult;
                }
                else if (!"FAILED".equals(task.getStatus()))
                {
                    result = settleStaleBilling(task);
                }
                else
                {
                    result = refundBilling(task.getId(), task.getUserId());
                }
                if (result)
                {
                    processed++;
                }
            }
            catch (Exception e)
            {
                log.error("补偿结算失败, taskId={}", task.getId(), e);
            }
        }

        log.info("FROZEN补偿完成: 扫描{}条, 成功{}条", staleTasks.size(), processed);
        return processed;
    }

    /** 补偿结算优先重放不可变计划；可聚合文本任务按本轮水位线恢复真实用量。 */
    boolean settleStaleBilling(AidExtractTask task)
    {
        Map<String, Object> plannedUsage = loadSettlePlanUsage(task);
        if (plannedUsage != null)
        {
            return settleBilling(task.getId(), task.getUserId(), plannedUsage);
        }
        if (ExtractBillingStatus.SETTLING.name().equals(task.getBillingStatus()))
        {
            log.error("SETTLING任务缺少结算计划，禁止猜测全额: taskId={}", task.getId());
            return false;
        }

        String snapshotJson = getBillingSnapshotJson(task, SNAPSHOT_STAGE_FROZEN);
        boolean recoverUsage = isMultiModelExtractSnapshot(snapshotJson)
                || Objects.equals(TASK_TYPE_STORYBOARD_IMAGE_PROMPT, task.getTaskType())
                || Objects.equals(TASK_TYPE_STORYBOARD_VIDEO_PROMPT, task.getTaskType());
        if (!recoverUsage)
        {
            return settleBilling(task.getId(), task.getUserId());
        }
        Map<String, Object> usageData = aggregateTokenUsage(task.getId());
        boolean complete = resolveBoolean(usageData.get(USAGE_KEY_AGGREGATION_COMPLETE));
        if (!complete || (isMultiModelExtractSnapshot(snapshotJson) && !isCompleteMultiModelUsage(usageData)))
        {
            log.warn("文本任务补偿等待用量收敛: taskId={}", task.getId());
            return false;
        }
        return settleBilling(task.getId(), task.getUserId(), usageData);
    }

    private void saveBillingSnapshot(Long taskId, String snapshotStage, String snapshotJson)
    {
        if (StrUtil.isBlank(snapshotJson))
        {
            billingSnapshotService.deleteSnapshot(taskId, snapshotStage);
            return;
        }
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (task == null)
        {
            throw new ServiceException("任务不存在");
        }
        billingSnapshotService.saveOrUpdateSnapshot(task, snapshotStage, snapshotJson);
    }

    private String getBillingSnapshotJson(AidExtractTask task, String snapshotStage)
    {
        if (task == null)
        {
            return null;
        }
        String snapshotJson = billingSnapshotService.getSnapshotJson(task.getId(), snapshotStage);
        if (StrUtil.isNotBlank(snapshotJson))
        {
            return snapshotJson;
        }
        return isSnapshotRefJson(task.getBillingSnapshotJson()) ? null : task.getBillingSnapshotJson();
    }

    private <T> void appendNullableEquals(LambdaUpdateWrapper<AidExtractTask> wrapper,
                                          SFunction<AidExtractTask, T> column, T value)
    {
        if (value == null)
        {
            wrapper.isNull(column);
        }
        else
        {
            wrapper.eq(column, value);
        }
    }

    /**
     * 旧内联快照没有 stage 引用时，已结算周期必须恢复到 SETTLED，退款/历史空周期恢复到 FROZEN。
     */
    String resolvePriorSnapshotStage(String priorBillingStatus, String priorSnapshotRef,
                                     String priorSnapshotJson)
    {
        String stage = resolveSnapshotStage(priorSnapshotRef);
        if (StrUtil.isNotBlank(stage) || StrUtil.isBlank(priorSnapshotJson))
        {
            return stage;
        }
        return ExtractBillingStatus.SUCCESS.name().equals(priorBillingStatus)
                ? SNAPSHOT_STAGE_SETTLED : SNAPSHOT_STAGE_FROZEN;
    }

    private AidExtractTask selectTaskForUpdate(Long taskId)
    {
        // 仅选择恢复事务所需字段，并通过行锁串行化新续跑、退款补偿和快照恢复。
        return extractTaskService.getBaseMapper().selectOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getProjectId,
                                AidExtractTask::getEpisodeId, AidExtractTask::getUserId,
                                AidExtractTask::getTaskType, AidExtractTask::getModelCode,
                                AidExtractTask::getStatus, AidExtractTask::getDelFlag,
                                AidExtractTask::getErrorMessage, AidExtractTask::getRemark,
                                AidExtractTask::getInputSnapshot, AidExtractTask::getTotalCount,
                                AidExtractTask::getBillingStatus, AidExtractTask::getBillingTraceId,
                                AidExtractTask::getFrozenAmount, AidExtractTask::getBillingSnapshotJson)
                        .eq(AidExtractTask::getId, taskId)
                        .last("FOR UPDATE"));
    }

    private boolean matchesActiveResumeCycle(AidExtractTask task, Long userId,
                                             ResumeBillingContext context)
    {
        return task != null
                && Objects.equals(userId, task.getUserId())
                && Objects.equals(context.resumeTraceId(), task.getBillingTraceId());
    }

    private boolean isValidResumeContext(ResumeBillingContext context)
    {
        return context != null && StrUtil.isNotBlank(context.resumeTraceId());
    }

    private boolean isResumeRollbackPending(ResumeBillingContext context)
    {
        return isValidResumeContext(context)
                && (RESUME_ROLLBACK_STATE_PREPARED.equals(context.rollbackState())
                || RESUME_ROLLBACK_STATE_FUNDS_FROZEN.equals(context.rollbackState())
                || RESUME_ROLLBACK_STATE_REQUIRED.equals(context.rollbackState()));
    }

    /**
     * @return null=不是待回滚续跑周期，交给原补偿逻辑；非 null=已由续跑 Saga 处理。
     */
    private Boolean retryResumeRollbackIfNeeded(Long taskId, Long userId,
                                                ResumeBillingContext context)
    {
        if (!isResumeRollbackPending(context))
        {
            return null;
        }
        // 账户冻结在派发锁内执行；通用计费补偿不得无锁抢跑。
        // TaskQueueService 会扫描结构化 RESUME_* stage，并在同一 task 派发锁内恢复。
        return false;
    }

    private boolean isSameResumeCycle(ResumeBillingContext left, ResumeBillingContext right)
    {
        return isValidResumeContext(left) && isValidResumeContext(right)
                && Objects.equals(left.resumeTraceId(), right.resumeTraceId())
                && Objects.equals(left.priorBillingStatus(), right.priorBillingStatus())
                && Objects.equals(left.priorTraceId(), right.priorTraceId())
                && Objects.equals(left.priorBillingSnapshotStage(), right.priorBillingSnapshotStage())
                && Objects.equals(left.resumeTaskState(), right.resumeTaskState())
                && Objects.equals(left.dispatchMode(), right.dispatchMode());
    }

    private ResumeBillingContext withRollbackState(ResumeBillingContext context, String rollbackState)
    {
        return new ResumeBillingContext(context.priorBillingStatus(), context.priorTraceId(),
                context.priorFrozenAmount(), context.priorBillingSnapshotJson(),
                context.priorBillingSnapshotRefJson(), context.priorBillingSnapshotStage(),
                context.resumeTraceId(), context.resumeTaskState(), context.dispatchMode(),
                context.dispatchIntentMillis(), rollbackState);
    }

    private ResumeBillingContext transitionResumeRollbackState(Long taskId,
                                                                ResumeBillingContext context,
                                                                String expectedState,
                                                                String targetState)
    {
        return transitionResumeRollbackContext(taskId, context,
                withRollbackState(context, targetState), expectedState);
    }

    private ResumeBillingContext transitionResumeRollbackContext(Long taskId,
                                                                  ResumeBillingContext context,
                                                                  ResumeBillingContext targetContext,
                                                                  String expectedState)
    {
        ResumeBillingContext transitioned = transactionTemplate.execute(status -> {
            AidExtractTask lockedTask = selectTaskForUpdate(taskId);
            ResumeBillingContext persisted = loadResumeRollbackContext(taskId);
            if (lockedTask == null
                    || !Objects.equals(context.resumeTraceId(), lockedTask.getBillingTraceId())
                    || !isSameResumeCycle(persisted, context)
                    || !Objects.equals(expectedState, persisted.rollbackState()))
            {
                return null;
            }
            if ((RESUME_ROLLBACK_STATE_PREPARED.equals(expectedState)
                    || RESUME_ROLLBACK_STATE_FUNDS_FROZEN.equals(expectedState))
                    && (!("PENDING".equals(lockedTask.getStatus()))
                    || !ExtractBillingStatus.FROZEN.name().equals(lockedTask.getBillingStatus())))
            {
                return null;
            }
            ResumeBillingContext updated = new ResumeBillingContext(
                    targetContext.priorBillingStatus(), targetContext.priorTraceId(),
                    targetContext.priorFrozenAmount(), targetContext.priorBillingSnapshotJson(),
                    targetContext.priorBillingSnapshotRefJson(), targetContext.priorBillingSnapshotStage(),
                    targetContext.resumeTraceId(), targetContext.resumeTaskState(),
                    targetContext.dispatchMode(), targetContext.dispatchIntentMillis(),
                    targetContext.rollbackState());
            saveResumeRollbackContext(lockedTask, updated);
            return updated;
        });
        if (transitioned == null)
        {
            log.error("续生回滚上下文状态推进失败: taskId={}, expected={}, target={}",
                    taskId, expectedState, targetContext.rollbackState());
            throw new ServiceException("计费状态异常");
        }
        return transitioned;
    }

    private boolean hasFreezeRecordFailClosed(String traceId)
    {
        try
        {
            return accountUpdateService.hasFreezeRecord(traceId);
        }
        catch (Exception e)
        {
            log.error("查询续生冻结流水失败，按已冻结处理: traceId={}", traceId, e);
            return true;
        }
    }

    private void saveResumeRollbackContext(AidExtractTask task, ResumeBillingContext context)
    {
        String targetStage = resumeSnapshotStage(context.rollbackState());
        if (StrUtil.isBlank(targetStage))
        {
            throw new ServiceException("计费状态异常");
        }
        billingSnapshotService.saveOrUpdateSnapshot(task, targetStage,
                serializeResumeBillingContext(context));
        for (String stage : RESUME_SNAPSHOT_STAGES)
        {
            if (!Objects.equals(stage, targetStage))
            {
                billingSnapshotService.deleteSnapshot(task.getId(), stage);
            }
        }
    }

    private ResumeBillingContext loadResumeRollbackContext(Long taskId)
    {
        AidExtractTaskBillingSnapshot snapshot = billingSnapshotService.getOne(
                Wrappers.<AidExtractTaskBillingSnapshot>lambdaQuery()
                        .select(AidExtractTaskBillingSnapshot::getId,
                                AidExtractTaskBillingSnapshot::getSnapshotStage,
                                AidExtractTaskBillingSnapshot::getSnapshotJson)
                        .eq(AidExtractTaskBillingSnapshot::getTaskId, taskId)
                        .in(AidExtractTaskBillingSnapshot::getSnapshotStage, RESUME_SNAPSHOT_STAGES)
                        .eq(AidExtractTaskBillingSnapshot::getDelFlag, "0")
                        .orderByDesc(AidExtractTaskBillingSnapshot::getUpdateTime,
                                AidExtractTaskBillingSnapshot::getId)
                        .last("LIMIT 1"), false);
        if (snapshot == null)
        {
            return null;
        }
        return deserializeResumeBillingContext(
                taskId, snapshot.getSnapshotJson(), snapshot.getSnapshotStage());
    }

    private ResumeBillingContext deserializeResumeBillingContext(Long taskId, String json,
                                                                  String snapshotStage)
    {
        if (StrUtil.isBlank(json))
        {
            return null;
        }
        try
        {
            JSONObject root = JSONUtil.parseObj(json);
            JSONObject taskStateJson = root.getJSONObject("resumeTaskState");
            ResumeTaskState taskState = taskStateJson == null ? null : new ResumeTaskState(
                    taskStateJson.getStr("status"), taskStateJson.getStr("errorMessage"),
                    taskStateJson.getStr("remark"), taskStateJson.getStr("inputSnapshot"),
                    taskStateJson.getInt("totalCount"));
            BigDecimal priorFrozen = root.get("priorFrozenAmount") == null ? null
                    : new BigDecimal(String.valueOf(root.get("priorFrozenAmount")));
            return new ResumeBillingContext(root.getStr("priorBillingStatus"),
                    root.getStr("priorTraceId"), priorFrozen,
                    root.getStr("priorBillingSnapshotJson"),
                    root.getStr("priorBillingSnapshotRefJson"),
                    root.getStr("priorBillingSnapshotStage"), root.getStr("resumeTraceId"),
                    taskState, root.getStr("dispatchMode"), root.getLong("dispatchIntentMillis"),
                    resumeStateFromSnapshotStage(snapshotStage, root.getStr("rollbackState")));
        }
        catch (Exception e)
        {
            log.error("续生回滚上下文解析失败: taskId={}", taskId, e);
            return null;
        }
    }

    private String serializeResumeBillingContext(ResumeBillingContext context)
    {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("priorBillingStatus", context.priorBillingStatus());
        root.put("priorTraceId", context.priorTraceId());
        root.put("priorFrozenAmount", context.priorFrozenAmount());
        root.put("priorBillingSnapshotJson", context.priorBillingSnapshotJson());
        root.put("priorBillingSnapshotRefJson", context.priorBillingSnapshotRefJson());
        root.put("priorBillingSnapshotStage", context.priorBillingSnapshotStage());
        root.put("resumeTraceId", context.resumeTraceId());
        root.put("dispatchMode", context.dispatchMode());
        root.put("dispatchIntentMillis", context.dispatchIntentMillis());
        root.put("rollbackState", context.rollbackState());
        ResumeTaskState taskState = context.resumeTaskState();
        if (taskState != null)
        {
            Map<String, Object> taskStateJson = new LinkedHashMap<>();
            taskStateJson.put("status", taskState.status());
            taskStateJson.put("errorMessage", taskState.errorMessage());
            taskStateJson.put("remark", taskState.remark());
            taskStateJson.put("inputSnapshot", taskState.inputSnapshot());
            taskStateJson.put("totalCount", taskState.totalCount());
            root.put("resumeTaskState", taskStateJson);
        }
        return JSONUtil.toJsonStr(root);
    }

    private void deleteResumeRollbackContextIfMatch(Long taskId, String resumeTraceId)
    {
        transactionTemplate.executeWithoutResult(status -> {
            selectTaskForUpdate(taskId);
            ResumeBillingContext persisted = loadResumeRollbackContext(taskId);
            if (persisted != null && Objects.equals(resumeTraceId, persisted.resumeTraceId()))
            {
                deleteAllResumeRollbackContexts(taskId);
            }
        });
    }

    private void deleteAllResumeRollbackContexts(Long taskId)
    {
        for (String stage : RESUME_SNAPSHOT_STAGES)
        {
            billingSnapshotService.deleteSnapshot(taskId, stage);
        }
    }

    private String resumeSnapshotStage(String rollbackState)
    {
        return switch (rollbackState)
        {
            case RESUME_ROLLBACK_STATE_PREPARED -> SNAPSHOT_STAGE_RESUME_PREPARED;
            case RESUME_ROLLBACK_STATE_FUNDS_FROZEN -> SNAPSHOT_STAGE_RESUME_FUNDS_FROZEN;
            case RESUME_ROLLBACK_STATE_DISPATCH_INTENT -> SNAPSHOT_STAGE_RESUME_DISPATCH_INTENT;
            case RESUME_ROLLBACK_STATE_DISPATCH_CONFIRMED -> SNAPSHOT_STAGE_RESUME_DISPATCH_CONFIRMED;
            case RESUME_ROLLBACK_STATE_REQUIRED -> SNAPSHOT_STAGE_RESUME_ROLLBACK_REQUIRED;
            default -> null;
        };
    }

    private String resumeStateFromSnapshotStage(String snapshotStage, String fallbackState)
    {
        return switch (snapshotStage)
        {
            case SNAPSHOT_STAGE_RESUME_PREPARED -> RESUME_ROLLBACK_STATE_PREPARED;
            case SNAPSHOT_STAGE_RESUME_FUNDS_FROZEN -> RESUME_ROLLBACK_STATE_FUNDS_FROZEN;
            case SNAPSHOT_STAGE_RESUME_DISPATCH_INTENT -> RESUME_ROLLBACK_STATE_DISPATCH_INTENT;
            case SNAPSHOT_STAGE_RESUME_DISPATCH_CONFIRMED -> RESUME_ROLLBACK_STATE_DISPATCH_CONFIRMED;
            case SNAPSHOT_STAGE_RESUME_ROLLBACK_REQUIRED -> RESUME_ROLLBACK_STATE_REQUIRED;
            default -> fallbackState;
        };
    }

    private void assertResumeContextReadyForNewCycle(AidExtractTask task)
    {
        ResumeBillingContext persisted = loadResumeRollbackContext(task.getId());
        if (persisted == null)
        {
            return;
        }
        if (!Objects.equals(persisted.resumeTraceId(), task.getBillingTraceId()))
        {
            deleteResumeRollbackContextIfMatch(task.getId(), persisted.resumeTraceId());
            return;
        }
        boolean completedDispatchCycle = RESUME_ROLLBACK_STATE_DISPATCH_INTENT.equals(
                persisted.rollbackState())
                || RESUME_ROLLBACK_STATE_DISPATCH_CONFIRMED.equals(persisted.rollbackState());
        boolean billingTerminal = ExtractBillingStatus.SUCCESS.name().equals(task.getBillingStatus())
                || ExtractBillingStatus.FAILED.name().equals(task.getBillingStatus());
        if (completedDispatchCycle && billingTerminal)
        {
            deleteResumeRollbackContextIfMatch(task.getId(), persisted.resumeTraceId());
            return;
        }
        log.info("续生被持久化回滚上下文阻止: taskId={}, rollbackState={}",
                task.getId(), persisted.rollbackState());
        throw new ServiceException("结算未完成");
    }

    private void clearResumeContextAfterTerminal(Long taskId, String billingTraceId)
    {
        try
        {
            ResumeBillingContext persisted = loadResumeRollbackContext(taskId);
            if (persisted == null || !Objects.equals(billingTraceId, persisted.resumeTraceId()))
            {
                return;
            }
            if (!RESUME_ROLLBACK_STATE_REQUIRED.equals(persisted.rollbackState()))
            {
                deleteResumeRollbackContextIfMatch(taskId, billingTraceId);
            }
        }
        catch (Exception e)
        {
            // 终态计费已完成，临时上下文清理由后续补偿/下次续跑继续处理，不反向打断结算。
            log.warn("清理续生临时回滚上下文失败: taskId={}", taskId, e);
        }
    }

    private String buildRestoredSnapshotRefJson(String snapshotStage, String snapshotJson)
    {
        return StrUtil.isBlank(snapshotStage) || StrUtil.isBlank(snapshotJson)
                ? null : buildSnapshotRefJson(snapshotStage);
    }

    private String buildSnapshotRefJson(String snapshotStage)
    {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("snapshotTable", "aid_extract_task_billing_snapshot");
        ref.put("snapshotStage", snapshotStage);
        return JSONUtil.toJsonStr(ref);
    }

    private boolean isSnapshotRefJson(String snapshotJson)
    {
        return StrUtil.isNotBlank(resolveSnapshotStage(snapshotJson));
    }

    private String resolveSnapshotStage(String snapshotJson)
    {
        if (StrUtil.isBlank(snapshotJson))
        {
            return null;
        }
        try
        {
            JSONObject json = JSONUtil.parseObj(snapshotJson);
            String snapshotTable = json.getStr("snapshotTable");
            String snapshotStage = json.getStr("snapshotStage");
            if (!"aid_extract_task_billing_snapshot".equals(snapshotTable))
            {
                return null;
            }
            if (SNAPSHOT_STAGE_FROZEN.equals(snapshotStage) || SNAPSHOT_STAGE_SETTLED.equals(snapshotStage))
            {
                return snapshotStage;
            }
            return null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private int casUpdateBillingStatus(Long taskId, String expectedStatus, String targetStatus)
    {
        // 状态推进时同步刷新更新时间，便于补偿扫描判断。
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.eq(AidExtractTask::getBillingStatus, expectedStatus);
        update.set(AidExtractTask::getBillingStatus, targetStatus);
        update.set(AidExtractTask::getUpdateTime, java.time.LocalDateTime.now());
        return extractTaskService.getBaseMapper().update(null, update);
    }

    private void logTextExtractSettleSummary(Long taskId, Long userId, BigDecimal preHoldAmount,
                                             BigDecimal actualAmount, Map<String, Object> usageData,
                                             BillingSnapshot snapshot)
    {
        if (snapshot == null || !"TEXT".equalsIgnoreCase(snapshot.getModelType()))
        {
            return;
        }
        // 兼容上游两套字段名：prompt_tokens/completion_tokens 与 input_tokens/output_tokens
        Object promptTokens = usageData == null ? null : usageData.get("prompt_tokens");
        Object completionTokens = usageData == null ? null : usageData.get("completion_tokens");
        Object inputTokens = usageData == null ? null : usageData.get("input_tokens");
        Object outputTokens = usageData == null ? null : usageData.get("output_tokens");
        Object totalTokens = usageData == null ? null : usageData.get("total_tokens");
        // 上游可能只返回 prompt/completion 或 input/output，total 缺失时按"输入+输出"自动兜底
        if (totalTokens == null)
        {
            totalTokens = sumTokensSafely(promptTokens != null ? promptTokens : inputTokens,
                    completionTokens != null ? completionTokens : outputTokens);
        }
        BigDecimal refundAmount = preHoldAmount.subtract(actualAmount).max(BigDecimal.ZERO);
        log.info("提取文本LLM完成扣费: taskId={}, userId={}, modelName={}, billingMode={}, skuCode={}, skuName={}, usageSource={}",
                taskId, userId, snapshot.getModelName(), snapshot.getBillingMode(),
                snapshot.getSkuCode(), snapshot.getSkuName(), resolveUsageSource(usageData));
        log.info("提取文本LLM完成扣费tokens: taskId={}, prompt_tokens={}, completion_tokens={}, input_tokens={}, output_tokens={}, total_tokens={}, actualInputTokens={}, actualOutputTokens={}",
                taskId,
                promptTokens != null ? promptTokens : inputTokens,
                completionTokens != null ? completionTokens : outputTokens,
                inputTokens, outputTokens, totalTokens,
                snapshot.getActualInputTokens(), snapshot.getActualOutputTokens());
        log.info("提取文本LLM完成扣费定价: taskId={}, inputPricePerMillion={}, outputPricePerMillion={}, baseAmount={}, modelMultiplier={}, globalMultiplier={}, finalMultiplier={}",
                taskId, snapshot.getInputPricePerMillion(), snapshot.getOutputPricePerMillion(),
                snapshot.getBaseAmount(), snapshot.getModelBillingMultiplier(),
                snapshot.getGlobalBillingMultiplier(), snapshot.getFinalBillingMultiplier());
        log.info("提取文本LLM完成扣费公式: taskId={}, preHold={}, actual={}, refund={} = preHold - actual",
                taskId, preHoldAmount, actualAmount, refundAmount);
    }

    private void logTextExtractRefundSummary(Long taskId, Long userId, BigDecimal preHoldAmount,
                                             BigDecimal actualAmount, BigDecimal refundAmount)
    {
        log.info("提取文本LLM退款说明: taskId={}, userId={}, refund={} = preHold({}) - actual({})",
                taskId, userId, refundAmount, preHoldAmount, actualAmount);
    }

    private String resolveUsageSource(Map<String, Object> usageData)
    {
        if (usageData == null || usageData.isEmpty())
        {
            return "EMPTY";
        }
        if (usageData.get("input_tokens") != null || usageData.get("output_tokens") != null
                || usageData.get("prompt_tokens") != null || usageData.get("completion_tokens") != null)
        {
            return "PROVIDER_REAL_USAGE";
        }
        if (usageData.get("input_tokens_estimate") != null || usageData.get("output_tokens_estimate") != null)
        {
            return "TOKEN_ESTIMATE";
        }
        if (usageData.get("total_chars_estimate") != null || usageData.get("output_chars_estimate") != null)
        {
            return "CHAR_ESTIMATE";
        }
        return "UNKNOWN";
    }

    /**
     * total_tokens 兜底：上游只返回 prompt/completion 或 input/output 时，按"输入+输出"自动求和。
     */
    private static Long sumTokensSafely(Object inputLike, Object outputLike)
    {
        Long a = toLongOrNull(inputLike);
        Long b = toLongOrNull(outputLike);
        if (a == null && b == null)
        {
            return null;
        }
        return (a == null ? 0L : a) + (b == null ? 0L : b);
    }

    private static Long toLongOrNull(Object v)
    {
        if (v == null)
        {
            return null;
        }
        if (v instanceof Number)
        {
            return ((Number) v).longValue();
        }
        try
        {
            return Long.parseLong(v.toString().trim());
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * 追补扫描：扫描 billing_status=PARTIAL_SUCCESS 的提取任务，从可用余额追补剩余差额。
     * 全额补完 → SUCCESS，仍不足 → 保持 PARTIAL_SUCCESS（下次扫描继续）。
     */
    @Override
    public int retryPartialExtraCharges(int batchSize)
    {
        LambdaQueryWrapper<AidExtractTask> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidExtractTask::getBillingStatus, ExtractBillingStatus.PARTIAL_SUCCESS.name());
        wrapper.select(AidExtractTask::getId, AidExtractTask::getUserId,
                AidExtractTask::getBillingTraceId,
                AidExtractTask::getFrozenAmount, AidExtractTask::getActualCost);
        wrapper.last("LIMIT " + batchSize);
        List<AidExtractTask> tasks = extractTaskService.list(wrapper);

        int processed = 0;
        for (AidExtractTask task : tasks)
        {
            try
            {
                boolean ok = retryPartialForExtractTask(task);
                if (ok)
                {
                    processed++;
                }
            }
            catch (Exception e)
            {
                log.error("提取任务追补失败, taskId={}", task.getId(), e);
            }
        }
        if (processed > 0)
        {
            log.info("提取任务追补扫描完成, total={}, processed={}", tasks.size(), processed);
        }
        return processed;
    }

    /**
     * 单个提取任务追补：从快照读取 extraChargeRequired，调用 settleExtraCharge 追补剩余差额。
     * settleExtraCharge 内部按 traceId 累计已补扣金额，不会重复扣。
     */
    private boolean retryPartialForExtractTask(AidExtractTask task)
    {
        // 从快照读取补扣信息
        String snapshotJson = getBillingSnapshotJson(task, SNAPSHOT_STAGE_SETTLED);
        if (StrUtil.isBlank(snapshotJson))
        {
            snapshotJson = getBillingSnapshotJson(task, SNAPSHOT_STAGE_FROZEN);
        }
        if (snapshotJson == null)
        {
            log.warn("提取追补跳过（无快照）, taskId={}", task.getId());
            return false;
        }
        BillingSnapshot snapshot = JSONUtil.toBean(snapshotJson, BillingSnapshot.class);
        if (snapshot.getExtraChargeRequired() == null
                || snapshot.getExtraChargeRequired().compareTo(BigDecimal.ZERO) <= 0)
        {
            log.warn("提取追补跳过（快照缺少补扣信息）, taskId={}", task.getId());
            return false;
        }
        BigDecimal extraRequired = snapshot.getExtraChargeRequired();

        // 调用 settleExtraCharge（内部按 traceId 累计，返回累计总额）
        BigDecimal totalCharged = accountUpdateService.settleExtraCharge(
                task.getUserId(), extraRequired, task.getBillingTraceId(),
                "settle_extra", "提取TOKEN追补");
        boolean fullyCharged = totalCharged.compareTo(extraRequired) >= 0;
        BigDecimal preHold = task.getFrozenAmount() != null ? task.getFrozenAmount() : BigDecimal.ZERO;
        BigDecimal finalSettled = preHold.add(totalCharged);

        // 更新快照审计字段；多模型批次保留 items 分项，不能序列化成单 BillingSnapshot 后丢失明细。
        String updatedSnapshotJson = appendExtraChargeAudit(snapshotJson,
                extraRequired, totalCharged, !fullyCharged, finalSettled);
        saveBillingSnapshot(task.getId(), SNAPSHOT_STAGE_SETTLED, updatedSnapshotJson);

        // CAS PARTIAL_SUCCESS → SUCCESS 或保持 PARTIAL_SUCCESS（更新金额+快照）
        String targetStatus = fullyCharged
                ? ExtractBillingStatus.SUCCESS.name()
                : ExtractBillingStatus.PARTIAL_SUCCESS.name();
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, task.getId());
        update.eq(AidExtractTask::getBillingStatus, ExtractBillingStatus.PARTIAL_SUCCESS.name());
        update.set(AidExtractTask::getBillingStatus, targetStatus);
        update.set(AidExtractTask::getActualCost, finalSettled);
        update.set(AidExtractTask::getBillingSnapshotJson, buildSnapshotRefJson(SNAPSHOT_STAGE_SETTLED));
        int rows = extractTaskService.getBaseMapper().update(null, update);

        if (rows > 0)
        {
            log.info("[提取TOKEN追补] taskId={}, userId={}, extraRequired={}, totalCharged={}, fullyCharged={}, finalSettled={}",
                    task.getId(), task.getUserId(), extraRequired, totalCharged, fullyCharged, finalSettled);
        }
        return rows > 0;
    }
}
