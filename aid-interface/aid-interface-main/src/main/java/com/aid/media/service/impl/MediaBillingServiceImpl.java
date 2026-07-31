package com.aid.media.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.billing.service.BillingRecordMetadataService;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.enums.MediaBillingStatus;
import com.aid.media.service.IMediaBillingService;
import com.aid.notify.wechat.service.IWechatNotifyService;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 媒体任务三阶段计费实现：预冻结 → 结算/退回。
 * 职责：
 * - 任务级幂等（billingTraceId + Redis 锁）
 * - 任务计费状态三步 CAS（FROZEN → SETTLING/REFUNDING → 终态）
 * - 委托 IAccountUpdateService 执行账户变更（幂等守卫，余额校验+冻结/结算/退回均在锁内独立事务完成）
 * 账户操作由 AccountUpdateService 自持 REQUIRES_NEW 事务，本类不包大事务。
 */
@Slf4j
@Service
public class MediaBillingServiceImpl implements IMediaBillingService {

    @Autowired
    private AidMediaTaskMapper aidMediaTaskMapper;

    @Autowired
    private IAccountUpdateService accountUpdateService;

    @Autowired
    private BillingRecordMetadataService billingRecordMetadataService;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private IWechatNotifyService wechatNotifyService;

    // 幂等锁 Redis Key 前缀，防止重复冻结。
    private static final String BILLING_LOCK_PREFIX = "media:billing:lock:";

    /**
     * 幂等锁释放脚本：UUID token + Lua CAS 释放，避免锁 TTL 到期后误删其他线程新抢到的锁。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    @Override
    public void prepareBilling(AidMediaTask task, AiModelConfigVo modelConfig) {
        Long userId = task.getUserId();
        if (userId == null) {
            task.setBillingStatus(MediaBillingStatus.INIT.name());
            return;
        }

        if (task.getBillingTraceId() != null) {
            return;
        }

        String traceId = IdUtil.fastSimpleUUID();
        task.setBillingTraceId(traceId);

        BigDecimal frozenAmount = modelConfig.getCostCredits() == null
            ? BigDecimal.ZERO
            : modelConfig.getCostCredits();

        // 冻结金额为 0 时直接标记成功。
        if (frozenAmount.compareTo(BigDecimal.ZERO) <= 0) {
            task.setBillingStatus(MediaBillingStatus.FROZEN.name());
            task.setFrozenAmount(BigDecimal.ZERO);
            return;
        }

        String lockKey = BILLING_LOCK_PREFIX + task.getId();
        String lockToken = UUID.randomUUID().toString();
        Boolean locked = redisCache.redisTemplate.opsForValue()
            .setIfAbsent(lockKey, lockToken, 30, TimeUnit.SECONDS);
        if (locked == null || !locked) {
            log.warn("计费幂等锁获取失败, taskId={}", task.getId());
            throw new ServiceException("系统繁忙");
        }

        try {
            String bizName = billingRecordMetadataService.buildMediaBizName(task);
            accountUpdateService.freeze(userId, frozenAmount, traceId, "create", bizName,
                    StrUtil.blankToDefault(modelConfig.getModelCode(), task.getModelName()));

            task.setFrozenAmount(frozenAmount);
            task.setBillingStatus(MediaBillingStatus.FROZEN.name());

            log.info("预冻结成功, userId={}, traceId={}, frozenAmount={}", userId, traceId, frozenAmount);
        } catch (ServiceException e) {
            if ("余额不足".equals(e.getMessage())) {
                task.setBillingStatus(MediaBillingStatus.FAILED.name());
                Long bizId = task.getBizTaskId() != null ? task.getBizTaskId() : task.getId();
                String bizType = task.getBizTaskType() != null ? task.getBizTaskType() : "media";
                wechatNotifyService.notifyBalanceInsufficient(userId, bizType, bizId, frozenAmount);
            }
            throw e;
        } finally {
            try {
                redisCache.redisTemplate.execute(
                        UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockToken);
            } catch (Exception ignore) {
                log.warn("计费幂等锁释放异常, taskId={}, lockKey={}", task.getId(), lockKey);
            }
        }
    }

    @Override
    public boolean settleBilling(AidMediaTask task) {
        String billingStatus = task.getBillingStatus();

        // 已终态，无需处理
        if (MediaBillingStatus.SUCCESS.name().equals(billingStatus)) {
            return true;
        }

        // Step 1: CAS FROZEN → SETTLING（只有抢到的线程才能继续）
        if (MediaBillingStatus.FROZEN.name().equals(billingStatus)) {
            int rows = casUpdateBillingStatus(task.getId(), MediaBillingStatus.FROZEN.name(), MediaBillingStatus.SETTLING.name());
            if (rows == 0) {
                log.info("媒体结算CAS抢锁失败, taskId={}", task.getId());
                return false;
            }
            task.setBillingStatus(MediaBillingStatus.SETTLING.name());
        } else if (!MediaBillingStatus.SETTLING.name().equals(billingStatus)) {
            return true;
        }

        // 此处任务状态一定是 SETTLING

        BigDecimal frozenAmount = task.getFrozenAmount();
        if (frozenAmount == null || frozenAmount.compareTo(BigDecimal.ZERO) <= 0) {
            int fastRows = casUpdateBillingStatus(task.getId(), MediaBillingStatus.SETTLING.name(), MediaBillingStatus.SUCCESS.name());
            if (fastRows > 0) { task.setBillingStatus(MediaBillingStatus.SUCCESS.name()); }
            return true;
        }
        Long userId = task.getUserId();
        if (userId == null) {
            int fastRows = casUpdateBillingStatus(task.getId(), MediaBillingStatus.SETTLING.name(), MediaBillingStatus.SUCCESS.name());
            if (fastRows > 0) { task.setBillingStatus(MediaBillingStatus.SUCCESS.name()); }
            return true;
        }

        // Step 2: 执行账户结算（幂等，已执行则跳过）
        accountUpdateService.settle(userId, frozenAmount, task.getBillingTraceId(), "create", "媒体任务结算扣费");

        // Step 3: CAS SETTLING → SUCCESS
        int finalRows = casUpdateBillingStatus(task.getId(), MediaBillingStatus.SETTLING.name(), MediaBillingStatus.SUCCESS.name());
        if (finalRows > 0)
        {
            task.setBillingStatus(MediaBillingStatus.SUCCESS.name());
            log.info("结算成功, userId={}, traceId={}, amount={}", userId, task.getBillingTraceId(), frozenAmount);
        }
        else
        {
            log.info("结算终态CAS失败（已被其他线程推进）, taskId={}", task.getId());
        }
        return true;
    }

    @Override
    public boolean refundBilling(AidMediaTask task) {
        String billingStatus = task.getBillingStatus();

        // 已终态，无需处理
        if (MediaBillingStatus.FAILED.name().equals(billingStatus)) {
            return true;
        }

        // Step 1: CAS FROZEN → REFUNDING（只有抢到的线程才能继续）
        if (MediaBillingStatus.FROZEN.name().equals(billingStatus)) {
            int rows = casUpdateBillingStatus(task.getId(), MediaBillingStatus.FROZEN.name(), MediaBillingStatus.REFUNDING.name());
            if (rows == 0) {
                log.info("媒体退回CAS抢锁失败, taskId={}", task.getId());
                return false;
            }
            task.setBillingStatus(MediaBillingStatus.REFUNDING.name());
        } else if (!MediaBillingStatus.REFUNDING.name().equals(billingStatus)) {
            return true;
        }

        // 此处任务状态一定是 REFUNDING

        BigDecimal frozenAmount = task.getFrozenAmount();
        if (frozenAmount == null || frozenAmount.compareTo(BigDecimal.ZERO) <= 0) {
            int fastRows = casUpdateBillingStatus(task.getId(), MediaBillingStatus.REFUNDING.name(), MediaBillingStatus.FAILED.name());
            if (fastRows > 0) { task.setBillingStatus(MediaBillingStatus.FAILED.name()); }
            return true;
        }
        Long userId = task.getUserId();
        if (userId == null) {
            int fastRows = casUpdateBillingStatus(task.getId(), MediaBillingStatus.REFUNDING.name(), MediaBillingStatus.FAILED.name());
            if (fastRows > 0) { task.setBillingStatus(MediaBillingStatus.FAILED.name()); }
            return true;
        }

        // Step 2: 执行账户退回（幂等，已执行则跳过）
        accountUpdateService.refund(userId, frozenAmount, task.getBillingTraceId(), "refund", "媒体任务失败退回");

        // Step 3: CAS REFUNDING → FAILED
        int finalRows = casUpdateBillingStatus(task.getId(), MediaBillingStatus.REFUNDING.name(), MediaBillingStatus.FAILED.name());
        if (finalRows > 0)
        {
            task.setBillingStatus(MediaBillingStatus.FAILED.name());
            log.info("退回成功, userId={}, traceId={}, amount={}", userId, task.getBillingTraceId(), frozenAmount);
        }
        else
        {
            log.info("退回终态CAS失败（已被其他线程推进）, taskId={}", task.getId());
        }
        return true;
    }

    /**
     * 补偿扫描：扫描 billing_status 为 SETTLING/REFUNDING/FROZEN 且超过2分钟的媒体任务。
     * 三种场景：
     * - SETTLING：结算中但未完成 → 重试结算（account 操作幂等）
     * - REFUNDING：退款中但未完成 → 重试退款（account 操作幂等）
     * - FROZEN：未开始结算/退回 → 按任务状态决定 settle 或 refund
     */
    @Override
    public int retryStaleBillings(int batchSize)
    {
        int processed = 0;

        // 扫描 SETTLING：已抢到结算权但未完成
        processed += retryByBillingStatus(MediaBillingStatus.SETTLING.name(), batchSize, true);

        // 扫描 REFUNDING：已抢到退款权但未完成
        processed += retryByBillingStatus(MediaBillingStatus.REFUNDING.name(), batchSize, false);

        // 扫描 FROZEN：未开始结算/退回（需要按任务状态判断方向）
        processed += retryFrozenBatch(batchSize);

        return processed;
    }
    /**
     * 扫描指定 billingStatus 的过期媒体任务并重试。
     * isSettle=true 时调用 settleBilling，否则调用 refundBilling。
     */
    private int retryByBillingStatus(String billingStatus, int batchSize, boolean isSettle)
    {
        LambdaQueryWrapper<AidMediaTask> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidMediaTask::getBillingStatus, billingStatus);
        wrapper.select(AidMediaTask::getId, AidMediaTask::getUserId, AidMediaTask::getBillingStatus,
                AidMediaTask::getFrozenAmount, AidMediaTask::getBillingTraceId, AidMediaTask::getStatus);
        wrapper.lt(AidMediaTask::getUpdateTime, LocalDateTime.now().minusMinutes(2));
        wrapper.last("LIMIT " + batchSize);
        List<AidMediaTask> staleTasks = aidMediaTaskMapper.selectList(wrapper);

        int processed = 0;
        for (AidMediaTask task : staleTasks)
        {
            try
            {
                boolean result;
                if (isSettle)
                {
                    result = settleBilling(task);
                }
                else
                {
                    result = refundBilling(task);
                }
                if (result)
                {
                    processed++;
                }
            }
            catch (Exception e)
            {
                log.error("媒体补偿处理失败, billingStatus={}, taskId={}", billingStatus, task.getId(), e);
            }
        }
        return processed;
    }

    /**
     * 扫描 FROZEN 状态的媒体任务，按任务状态决定 settle 或 refund。
     * 仅处理已终态（SUCCEEDED / FAILED）的任务，跳过进行中的任务。
     */
    private int retryFrozenBatch(int batchSize)
    {
        LambdaQueryWrapper<AidMediaTask> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidMediaTask::getBillingStatus, MediaBillingStatus.FROZEN.name());
        wrapper.in(AidMediaTask::getStatus, "SUCCEEDED", "FAILED");
        wrapper.select(AidMediaTask::getId, AidMediaTask::getUserId, AidMediaTask::getBillingStatus,
                AidMediaTask::getFrozenAmount, AidMediaTask::getBillingTraceId, AidMediaTask::getStatus);
        wrapper.lt(AidMediaTask::getUpdateTime, LocalDateTime.now().minusMinutes(2));
        wrapper.last("LIMIT " + batchSize);
        List<AidMediaTask> staleTasks = aidMediaTaskMapper.selectList(wrapper);

        if (staleTasks.isEmpty())
        {
            return 0;
        }

        int processed = 0;
        for (AidMediaTask task : staleTasks)
        {
            try
            {
                boolean result;
                if ("SUCCEEDED".equals(task.getStatus()))
                {
                    result = settleBilling(task);
                }
                else
                {
                    result = refundBilling(task);
                }
                if (result)
                {
                    processed++;
                }
            }
            catch (Exception e)
            {
                log.error("媒体FROZEN补偿失败, taskId={}", task.getId(), e);
            }
        }

        log.info("媒体FROZEN补偿完成: 扫描{}条, 成功{}条", staleTasks.size(), processed);
        return processed;
    }

    /**
     * CAS 更新媒体任务计费状态，同步刷新 update_time 便于审计状态推进。
     */
    private int casUpdateBillingStatus(Long taskId, String expectedStatus, String targetStatus) {
        return aidMediaTaskMapper.update(null, new LambdaUpdateWrapper<AidMediaTask>()
            .eq(AidMediaTask::getId, taskId)
            .eq(AidMediaTask::getBillingStatus, expectedStatus)
            .set(AidMediaTask::getBillingStatus, targetStatus)
            .set(AidMediaTask::getUpdateTime, new java.util.Date())
        );
    }
}
