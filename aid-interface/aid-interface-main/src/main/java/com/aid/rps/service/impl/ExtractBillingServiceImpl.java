package com.aid.rps.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.service.IAidExtractTaskBillingSnapshotService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.enums.MeterType;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.BillingAmountCalculator;
import com.aid.billing.service.BillingRecordMetadataService;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.common.exception.ServiceException;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.rps.enums.ExtractBillingStatus;
import com.aid.rps.service.IExtractBillingService;

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

    @Autowired
    private IAidExtractTaskService extractTaskService;

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

    @Override
    public void prepareBilling(Long taskId, Long userId, BigDecimal frozenAmount, String billingSnapshotJson)
    {
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
            // freeze 失败（余额不足/账户异常）：回滚任务表的 traceId 预占
            try
            {
                rollbackBillingTraceOnFreezeFail(taskId, traceId, priorSnapshotJson, priorSnapshotRef);
            }
            catch (Exception rollbackEx)
            {
                log.error("freeze 失败后回滚 billing_trace_id 异常, taskId={}, traceId={}", taskId, traceId, rollbackEx);
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
    public void rearmBillingForResume(Long taskId, Long userId, BigDecimal frozenAmount, String billingSnapshotJson)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (task == null)
        {
            throw new ServiceException("任务不存在");
        }
        // 旧计费周期快照（冻结失败时原样恢复，避免污染首跑计费状态）
        String oldBillingStatus = task.getBillingStatus();
        String oldTraceId = task.getBillingTraceId();
        BigDecimal oldFrozen = task.getFrozenAmount();
        String oldSnapshotRef = task.getBillingSnapshotJson();
        String oldSnapshotJson = resolveBillingSnapshotJson(taskId, oldSnapshotRef);

        BigDecimal frozen = frozenAmount == null ? BigDecimal.ZERO : frozenAmount;
        String traceId = IdUtil.fastSimpleUUID();

        // CAS：仅旧周期已完整结算（SUCCESS）或已退款（FAILED）可重置为新一轮 FROZEN；
        // PARTIAL_SUCCESS（首跑欠扣待追补）不允许重置，防止覆盖旧周期追补快照导致欠款无法追扣
        LambdaUpdateWrapper<AidExtractTask> reset = Wrappers.lambdaUpdate();
        reset.eq(AidExtractTask::getId, taskId);
        reset.in(AidExtractTask::getBillingStatus,
                ExtractBillingStatus.SUCCESS.name(), ExtractBillingStatus.FAILED.name());
        reset.set(AidExtractTask::getBillingTraceId, traceId);
        reset.set(AidExtractTask::getFrozenAmount, frozen);
        reset.set(AidExtractTask::getBillingSnapshotJson,
                StrUtil.isBlank(billingSnapshotJson) ? null : buildSnapshotRefJson(SNAPSHOT_STAGE_FROZEN));
        reset.set(AidExtractTask::getBillingStatus, ExtractBillingStatus.FROZEN.name());
        int rows = extractTaskService.getBaseMapper().update(null, reset);
        if (rows == 0)
        {
            log.info("续生重置计费CAS失败（旧周期非SUCCESS/FAILED，可能仍在结算/追补）, taskId={}, billingStatus={}", taskId, oldBillingStatus);
            throw new ServiceException("结算未完成");
        }

        if (frozen.compareTo(BigDecimal.ZERO) <= 0)
        {
            try
            {
                saveBillingSnapshot(taskId, SNAPSHOT_STAGE_FROZEN, billingSnapshotJson);
            }
            catch (RuntimeException snapshotEx)
            {
                rollbackRearmBillingFields(taskId, traceId, oldBillingStatus, oldTraceId,
                        oldFrozen, oldSnapshotJson, oldSnapshotRef);
                throw snapshotEx;
            }
            return;
        }
        try
        {
            saveBillingSnapshot(taskId, SNAPSHOT_STAGE_FROZEN, billingSnapshotJson);
        }
        catch (RuntimeException snapshotEx)
        {
            rollbackRearmBillingFields(taskId, traceId, oldBillingStatus, oldTraceId,
                    oldFrozen, oldSnapshotJson, oldSnapshotRef);
            throw snapshotEx;
        }
        try
        {
            String bizName = billingRecordMetadataService.buildExtractBizName(task, true);
            String modelCodes = billingRecordMetadataService.resolveExtractModelCodes(task);
            accountUpdateService.freeze(userId, frozen, traceId, "extract", bizName, modelCodes);
            log.info("续生重置计费并冻结成功, taskId={}, userId={}, traceId={}, frozen={}", taskId, userId, traceId, frozen);
        }
        catch (RuntimeException freezeEx)
        {
            // 冻结失败：原样恢复旧计费周期（旧 status/trace/frozen/snapshot），CAS 限定只回滚我们刚写入的这条
            rollbackRearmBillingFields(taskId, traceId, oldBillingStatus, oldTraceId,
                    oldFrozen, oldSnapshotJson, oldSnapshotRef);
            log.error("续生冻结失败已恢复旧计费周期, taskId={}, traceId={}, oldStatus={}", taskId, traceId, oldBillingStatus, freezeEx);
            throw freezeEx;
        }
    }

    @Override
    public boolean rollbackResumeBilling(Long taskId, Long userId, String priorBillingStatus,
                                         String priorTraceId, BigDecimal priorFrozenAmount,
                                         String priorBillingSnapshotJson, String priorBillingSnapshotRefJson)
    {
        boolean refunded;
        try { refunded = refundBilling(taskId, userId); }
        catch (Exception e)
        {
            log.error("续生回滚退款异常, taskId={}", taskId, e);
            refunded = false;
        }
        //    退款未确认时返回 false，保留本轮 FROZEN/REFUNDING + resume trace/金额不动，交统一补偿继续退款，
        //    调用方据 false 把任务置 FAILED（不可续生），避免覆盖 trace 后冻结款找不到、且 task 仍显示可续生
        if (!refunded)
        {
            log.warn("续生回滚退款未确认，保留本轮计费周期(trace/金额)待统一补偿，不恢复上一轮字段, taskId={}", taskId);
            return false;
        }
        String priorSnapshotStage = resolveSnapshotStage(priorBillingSnapshotRefJson);
        if (StrUtil.isBlank(priorSnapshotStage) && StrUtil.isNotBlank(priorBillingSnapshotJson))
        {
            priorSnapshotStage = SNAPSHOT_STAGE_FROZEN;
        }
        LambdaUpdateWrapper<AidExtractTask> restore = Wrappers.lambdaUpdate();
        restore.eq(AidExtractTask::getId, taskId);
        restore.set(AidExtractTask::getBillingStatus, priorBillingStatus);
        restore.set(AidExtractTask::getBillingTraceId, priorTraceId);
        restore.set(AidExtractTask::getFrozenAmount, priorFrozenAmount);
        restore.set(AidExtractTask::getBillingSnapshotJson,
                buildRestoredSnapshotRefJson(priorSnapshotStage, priorBillingSnapshotJson));
        extractTaskService.getBaseMapper().update(null, restore);
        restoreSnapshotAfterRollback(taskId, priorSnapshotStage, priorBillingSnapshotJson);
        log.info("续生入队失败已回滚本轮冻结并恢复上一轮计费周期, taskId={}, priorStatus={}", taskId, priorBillingStatus);
        return true;
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
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (task == null)
        {
            return true;
        }

        String billingStatus = task.getBillingStatus();

        // 已终态（全额结算完成），无需处理
        if (ExtractBillingStatus.SUCCESS.name().equals(billingStatus))
        {
            return true;
        }

        // Step 1: CAS FROZEN/PARTIAL_SUCCESS → SETTLING
        // PARTIAL_SUCCESS 也允许重新进入结算，用于追补剩余差额
        if (ExtractBillingStatus.FROZEN.name().equals(billingStatus)
                || ExtractBillingStatus.PARTIAL_SUCCESS.name().equals(billingStatus))
        {
            int rows = casUpdateBillingStatus(taskId, billingStatus, ExtractBillingStatus.SETTLING.name());
            if (rows == 0)
            {
                log.info("提取任务差额结算CAS抢锁失败, taskId={}, billingStatus={}", taskId, billingStatus);
                return false;
            }
        }
        else if (!ExtractBillingStatus.SETTLING.name().equals(billingStatus))
        {
            return true;
        }

        BigDecimal frozenAmount = task.getFrozenAmount();
        if (frozenAmount == null || frozenAmount.compareTo(BigDecimal.ZERO) <= 0)
        {
            casUpdateBillingStatus(taskId, ExtractBillingStatus.SETTLING.name(), ExtractBillingStatus.SUCCESS.name());
            return true;
        }

        // 有快照 → 走统一结算（含差额/全额/降级），结算后快照标记终态并回写
        BigDecimal actualAmount = frozenAmount;
        String snapshotJson = getBillingSnapshotJson(task, SNAPSHOT_STAGE_FROZEN);
        // 结算后的快照JSON，回写到任务表供审计
        String settledSnapshotJson = null;
        // 从快照中提取 meterType，用于判断是否走 TOKEN 补扣
        MeterType meterType = null;

        String settleSnapshotJson = buildAggregateBatchSnapshotJson(snapshotJson, frozenAmount);
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
                logTextExtractSettleSummary(taskId, userId, frozenAmount, actualAmount, usageData, settleResult.getSnapshot());
            }
            catch (Exception e)
            {
                log.error("SKU差额结算失败，降级按预扣金额结算, taskId={}", taskId, e);
            }
        }

        // 结算冻结金额（settle 从 frozenBalance 扣减，金额不超过 frozenAmount）
        BigDecimal settleAmount = actualAmount.compareTo(frozenAmount) > 0 ? frozenAmount : actualAmount;
        accountUpdateService.settle(userId, settleAmount, task.getBillingTraceId(), "settle", "资产提取任务结算");

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
                // settle 已成功（frozen_balance 已扣减 actualAmount），差额退回失败时
                // 不能把整个 settleBilling 流程打断——任务状态机仍需推进到 SUCCESS，否则会卡在 SETTLING。
                // 失败原因通常是 frozen_balance 被异常路径提前清空，由运维扫 aid_balance_log 兜底处理。
                log.error("[BG-ALERT] 差额退回失败但 settle 已扣减成功，需人工介入核账, taskId={}, userId={}, "
                                + "frozen={}, actual={}, refund={}, traceId={}",
                        taskId, userId, frozenAmount, actualAmount, refundAmount, task.getBillingTraceId(),
                        refundEx);
            }
        }

        // 补扣审计字段回写到快照
        if (extraRequired.compareTo(BigDecimal.ZERO) > 0)
        {
            try
            {
                BillingSnapshot snap = settledSnapshotJson != null
                        ? JSONUtil.toBean(settledSnapshotJson, BillingSnapshot.class)
                        : (settleSnapshotJson != null ? JSONUtil.toBean(settleSnapshotJson, BillingSnapshot.class) : null);
                if (snap != null)
                {
                    snap.setExtraChargeRequired(extraRequired);
                    snap.setExtraChargeActual(extraCharged);
                    snap.setPartialExtraCharge(partialExtra);
                    snap.setActualAmount(actualAmount);
                    settledSnapshotJson = JSONUtil.toJsonStr(snap);
                }
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
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (task == null)
        {
            return true;
        }

        String billingStatus = task.getBillingStatus();

        // 已终态，无需处理
        if (ExtractBillingStatus.FAILED.name().equals(billingStatus))
        {
            return true;
        }

        // Step 1: CAS FROZEN → REFUNDING（只有抢到的线程才能继续）
        if (ExtractBillingStatus.FROZEN.name().equals(billingStatus))
        {
            int rows = casUpdateBillingStatus(taskId, ExtractBillingStatus.FROZEN.name(), ExtractBillingStatus.REFUNDING.name());
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
        }
        else
        {
            log.info("提取任务退回终态CAS失败（已被其他线程推进）, taskId={}", taskId);
        }

        return true;
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
        wrapper.select(AidExtractTask::getId, AidExtractTask::getUserId, AidExtractTask::getStatus);
        wrapper.lt(AidExtractTask::getUpdateTime, LocalDateTime.now().minusMinutes(2));
        wrapper.last("LIMIT " + batchSize);
        List<AidExtractTask> staleTasks = extractTaskService.list(wrapper);

        int processed = 0;
        for (AidExtractTask task : staleTasks)
        {
            try
            {
                boolean result;
                if (isSettle)
                {
                    result = settleBilling(task.getId(), task.getUserId());
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
     * 扫描 FROZEN 状态且任务已终态的记录，按 taskStatus 决定 settle 或 refund。
     */
    private int retryFrozenBatch(int batchSize)
    {
        LambdaQueryWrapper<AidExtractTask> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidExtractTask::getBillingStatus, ExtractBillingStatus.FROZEN.name());
        wrapper.in(AidExtractTask::getStatus, "SUCCEEDED", "FAILED");
        wrapper.select(AidExtractTask::getId, AidExtractTask::getUserId, AidExtractTask::getStatus);
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
                if ("SUCCEEDED".equals(task.getStatus()))
                {
                    result = settleBilling(task.getId(), task.getUserId());
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

    private void rollbackRearmBillingFields(Long taskId, String traceId, String oldBillingStatus,
                                            String oldTraceId, BigDecimal oldFrozen,
                                            String oldSnapshotJson, String oldSnapshotRef)
    {
        String oldSnapshotStage = resolveSnapshotStage(oldSnapshotRef);
        if (StrUtil.isBlank(oldSnapshotStage) && StrUtil.isNotBlank(oldSnapshotJson))
        {
            oldSnapshotStage = SNAPSHOT_STAGE_FROZEN;
        }
        LambdaUpdateWrapper<AidExtractTask> rollback = Wrappers.lambdaUpdate();
        rollback.eq(AidExtractTask::getId, taskId);
        rollback.eq(AidExtractTask::getBillingTraceId, traceId);
        rollback.eq(AidExtractTask::getBillingStatus, ExtractBillingStatus.FROZEN.name());
        rollback.set(AidExtractTask::getBillingStatus, oldBillingStatus);
        rollback.set(AidExtractTask::getBillingTraceId, oldTraceId);
        rollback.set(AidExtractTask::getFrozenAmount, oldFrozen);
        rollback.set(AidExtractTask::getBillingSnapshotJson,
                buildRestoredSnapshotRefJson(oldSnapshotStage, oldSnapshotJson));
        int rows = extractTaskService.getBaseMapper().update(null, rollback);
        if (rows > 0)
        {
            restoreSnapshotAfterRollback(taskId, oldSnapshotStage, oldSnapshotJson);
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

        // 更新快照审计字段
        snapshot.setExtraChargeActual(totalCharged);
        snapshot.setPartialExtraCharge(!fullyCharged);
        snapshot.setActualAmount(finalSettled);
        String updatedSnapshotJson = JSONUtil.toJsonStr(snapshot);
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
