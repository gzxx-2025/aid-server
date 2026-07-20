package com.aid.billing.service.impl;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidBalanceLog;
import com.aid.aid.domain.AidUserProfile;
import com.aid.aid.mapper.AidUserProfileMapper;
import com.aid.aid.service.IAidBalanceLogService;
import com.aid.aid.service.IAidUserProfileService;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.notify.wechat.config.WechatNotifyConfig;
import com.aid.notify.wechat.service.IWechatNotifyConfigService;
import com.aid.notify.wechat.service.IWechatNotifyService;

import lombok.extern.slf4j.Slf4j;

/**
 * 统一账户变更执行器实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AccountUpdateServiceImpl implements IAccountUpdateService
{
    @Autowired
    private IAidUserProfileService aidUserProfileService;

    @Autowired
    private IAidBalanceLogService aidBalanceLogService;

    @Autowired
    private AidUserProfileMapper aidUserProfileMapper;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private IWechatNotifyConfigService wechatNotifyConfigService;

    @Autowired
    private IWechatNotifyService wechatNotifyService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** userId 级账户变更锁 */
    private static final String ACCOUNT_LOCK_PREFIX = "account:update:lock:";
    /** 锁等待超时：最多等 10 秒 */
    private static final long LOCK_WAIT_SECONDS = 10;
    /** 锁初始 TTL（秒），持有期间由 watchdog 自动续约 */
    private static final long LOCK_TTL_SECONDS = 30;
    /** watchdog 续约间隔（毫秒）：TTL 的 1/3，保证至少续约一次后才会过期 */
    private static final long LOCK_RENEW_INTERVAL_MS = (LOCK_TTL_SECONDS * 1000L) / 3L;
    /** 账户操作最长允许持有锁的时间（秒），超过则放弃续约，避免悬挂事务被无限续 */
    private static final long LOCK_MAX_HOLD_SECONDS = 180;

    /**
     * 安全释放锁 Lua 脚本：只有当锁值等于当前持有者 token 时才删除。
     * 避免线程 A 的锁过期后，误删线程 B 获得的新锁。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /**
     * 续约 Lua 脚本：仅当锁值仍等于本线程 token 时，才刷新 TTL（毫秒）。
     * 过期或已被他人抢占时返回 0，watchdog 据此自停。
     */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    /**
     * watchdog 调度池核心线程数。
     * 单线程池在高并发持锁场景下续约任务会排队，续约延迟可能导致锁被动过期，
     * 因此采用多线程池，单次 Redis 抖动或日志阻塞不会拖慢其他用户的续约。
     */
    private static final int LOCK_RENEW_POOL_SIZE = 4;

    /**
     * 共享的 watchdog 调度池：多线程并行跑所有用户锁的续约任务。
     * 应用关闭时由 @PreDestroy 释放。
     */
    private final ScheduledExecutorService lockRenewExecutor = Executors.newScheduledThreadPool(
            LOCK_RENEW_POOL_SIZE,
            new ThreadFactory()
            {
                private final AtomicLong counter = new AtomicLong(0);

                @Override
                public Thread newThread(Runnable r)
                {
                    Thread t = new Thread(r, "account-lock-watchdog-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    @PreDestroy
    public void shutdownLockRenewExecutor()
    {
        lockRenewExecutor.shutdownNow();
    }

    /**
     * 冻结：锁内事务中完成幂等检查 + 余额校验 + 冻结，防止并发超额冻结。
     * 余额不足时抛 ServiceException("余额不足")，由调用方感知。
     * 采用条件 SQL 扣减（UPDATE ... SET balance=balance-? WHERE balance>=?），
     * 即使 Redis 锁被动过期、另一线程已改动 balance，也不会发生 Lost Update。
     */
    @Override
    public void freeze(Long userId, BigDecimal amount, String traceId, String bizType, String bizName)
    {
        freeze(userId, amount, traceId, bizType, bizName, null);
    }

    @Override
    public void freeze(Long userId, BigDecimal amount, String traceId, String bizType, String bizName,
                       String modelCode)
    {
        AtomicBoolean notifyLowBalance = new AtomicBoolean(false);
        executeWithUserLock(userId, () -> {
            newTx().executeWithoutResult(s -> {
                // 幂等：同一 traceId + changeType 已执行则跳过
                if (balanceLogExists(traceId, "freeze"))
                {
                    log.info("冻结已执行，跳过, traceId={}", traceId);
                    return;
                }

                AidUserProfile profile = getProfileInternal(userId);
                BigDecimal balance = profile.getBalance() == null ? BigDecimal.ZERO : profile.getBalance();

                // 余额校验必须在锁内事务中做，防止并发超额冻结
                if (balance.compareTo(amount) < 0)
                {
                    log.info("冻结余额不足, userId={}, balance={}, amount={}", userId, balance, amount);
                    throw new ServiceException("余额不足");
                }

                // 条件扣减：balance -= amount AND frozen += amount，WHERE balance >= amount
                int affected = casBalanceFrozen(userId, amount.negate(), amount, amount, null);
                if (affected == 0)
                {
                    // 并发窗口：另一事务在读-写之间已扣掉余额
                    log.warn("冻结并发冲突，触发重试, userId={}, amount={}", userId, amount);
                    throw new ServiceException("系统繁忙");
                }
                BigDecimal afterBalance = balance.subtract(amount);
                notifyLowBalance.set(isBelowBalanceReminderThreshold(afterBalance));
                saveBalanceLog(userId, "freeze", amount.negate(), balance, afterBalance, traceId,
                        bizType, bizName, modelCode);
            });
        });
        notifyLowBalanceIfNeeded(notifyLowBalance.get(), userId, bizType, bizName, amount);
    }

    @Override
    public void settle(Long userId, BigDecimal amount, String traceId, String bizType, String bizName)
    {
        executeWithUserLock(userId, () -> {
            newTx().executeWithoutResult(s -> {
                // 幂等：同一 traceId + changeType 已执行则跳过
                if (balanceLogExists(traceId, "consume"))
                {
                    log.info("结算已执行，跳过, traceId={}", traceId);
                    return;
                }

                AidUserProfile profile = getProfileInternal(userId);
                BigDecimal balance = profile.getBalance() == null ? BigDecimal.ZERO : profile.getBalance();

                // 条件扣减冻结余额（frozen -= amount AND frozen >= amount），totalConsumption += amount
                int affected = casBalanceFrozen(userId, null, amount.negate(), amount, amount);
                if (affected == 0)
                {
                    log.warn("结算并发冲突或冻结不足, userId={}, amount={}", userId, amount);
                    throw new ServiceException("系统繁忙");
                }
                saveBalanceLog(userId, "consume", amount.negate(), balance, balance, traceId, bizType, bizName);
            });
        });
    }

    @Override
    public void refund(Long userId, BigDecimal amount, String traceId, String bizType, String bizName)
    {
        executeWithUserLock(userId, () -> {
            newTx().executeWithoutResult(s -> {
                // 幂等：同一 traceId + changeType 已执行则跳过
                if (balanceLogExists(traceId, "unfreeze"))
                {
                    log.info("退回已执行，跳过, traceId={}", traceId);
                    return;
                }

                AidUserProfile profile = getProfileInternal(userId);
                BigDecimal balance = profile.getBalance() == null ? BigDecimal.ZERO : profile.getBalance();

                // 条件扣减冻结（frozen -= amount AND frozen >= amount），balance += amount
                int affected = casBalanceFrozen(userId, amount, amount.negate(), amount, null);
                if (affected == 0)
                {
                    log.warn("退回并发冲突或冻结不足, userId={}, amount={}", userId, amount);
                    throw new ServiceException("系统繁忙");
                }
                BigDecimal afterBalance = balance.add(amount);
                saveBalanceLog(userId, "unfreeze", amount, balance, afterBalance, traceId, bizType, bizName);
            });
        });
    }

    /**
     * 结算差额退回（从冻结余额）：资金动作与 {@link #refund} 相同，唯一区别是 changeType="settle_unfreeze"，
     * 与 refund 的 "unfreeze" 幂等键分开，避免失败补偿的全额退与成功结算的差额退共用幂等键互相串台。
     */
    @Override
    public void settleRefundFromFrozen(Long userId, BigDecimal amount, String traceId, String bizType, String bizName)
    {
        executeWithUserLock(userId, () -> {
            newTx().executeWithoutResult(s -> {
                // 幂等：同一 traceId + changeType 已执行则跳过
                if (balanceLogExists(traceId, "settle_unfreeze"))
                {
                    log.info("结算差额退回（冻结）已执行，跳过, traceId={}", traceId);
                    return;
                }

                AidUserProfile profile = getProfileInternal(userId);
                BigDecimal balance = profile.getBalance() == null ? BigDecimal.ZERO : profile.getBalance();

                // 条件扣减冻结：frozen -= amount AND frozen >= amount，balance += amount
                int affected = casBalanceFrozen(userId, amount, amount.negate(), amount, null);
                if (affected == 0)
                {
                    log.warn("结算差额退回（冻结）并发冲突或冻结不足, userId={}, amount={}, traceId={}",
                            userId, amount, traceId);
                    throw new ServiceException("系统繁忙");
                }
                BigDecimal afterBalance = balance.add(amount);
                saveBalanceLog(userId, "settle_unfreeze", amount, balance, afterBalance, traceId, bizType, bizName);
            });
        });
    }

    @Override
    public void settleRefund(Long userId, BigDecimal amount, String traceId, String bizType, String bizName)
    {
        executeWithUserLock(userId, () -> {
            newTx().executeWithoutResult(s -> {
                // 幂等：同一 traceId + changeType 已执行则跳过
                if (balanceLogExists(traceId, "settle_refund"))
                {
                    log.info("结算差额退回已执行，跳过, traceId={}", traceId);
                    return;
                }

                AidUserProfile profile = getProfileInternal(userId);
                BigDecimal balance = profile.getBalance() == null ? BigDecimal.ZERO : profile.getBalance();

                // 条件扣减 totalConsumption（totalConsumption -= amount AND totalConsumption >= amount），balance += amount
                int affected = casBalanceFrozen(userId, amount, null, null, amount.negate());
                if (affected == 0)
                {
                    log.warn("settleRefund 并发冲突或消费累计不足, userId={}, amount={}", userId, amount);
                    throw new ServiceException("系统繁忙");
                }
                BigDecimal afterBalance = balance.add(amount);
                saveBalanceLog(userId, "settle_refund", amount, balance, afterBalance, traceId, bizType, bizName);
            });
        });
    }

    @Override
    public void directConsume(Long userId, BigDecimal amount, String traceId, String bizType, String bizName)
    {
        AtomicBoolean notifyLowBalance = new AtomicBoolean(false);
        executeWithUserLock(userId, () -> {
            newTx().executeWithoutResult(s -> {
                // 幂等：同一 traceId + changeType 已执行则跳过
                if (balanceLogExists(traceId, "consume"))
                {
                    log.info("直接消费已执行，跳过, traceId={}", traceId);
                    return;
                }

                AidUserProfile profile = getProfileInternal(userId);
                BigDecimal balance = profile.getBalance() == null ? BigDecimal.ZERO : profile.getBalance();

                // 余额校验必须在锁内事务中做
                if (balance.compareTo(amount) < 0)
                {
                    log.info("直接消费余额不足, userId={}, balance={}, amount={}", userId, balance, amount);
                    throw new ServiceException("余额不足");
                }

                // 条件扣减 balance（balance -= amount AND balance >= amount），totalConsumption += amount
                int affected = casBalanceFrozen(userId, amount.negate(), null, amount, amount);
                if (affected == 0)
                {
                    log.warn("直接消费并发冲突, userId={}, amount={}", userId, amount);
                    throw new ServiceException("系统繁忙");
                }
                BigDecimal afterBalance = balance.subtract(amount);
                notifyLowBalance.set(isBelowBalanceReminderThreshold(afterBalance));
                saveBalanceLog(userId, "consume", amount.negate(), balance, afterBalance, traceId, bizType, bizName);
            });
        });
        notifyLowBalanceIfNeeded(notifyLowBalance.get(), userId, bizType, bizName, amount);
    }

    @Override
    public void recharge(Long userId, BigDecimal amount, String traceId, String bizType, String bizName)
    {
        executeWithUserLock(userId, () -> {
            newTx().executeWithoutResult(s -> {
                // 幂等：同一 traceId + changeType 已执行则跳过
                if (balanceLogExists(traceId, "recharge"))
                {
                    log.info("充值已执行，跳过, traceId={}", traceId);
                    return;
                }

                AidUserProfile profile = getProfileInternal(userId);
                BigDecimal balance = profile.getBalance() == null ? BigDecimal.ZERO : profile.getBalance();

                // 条件 delta 更新（balance += amount, totalRecharge += amount，无下限要求）
                int affected = casRecharge(userId, amount);
                if (affected == 0)
                {
                    log.warn("充值并发冲突, userId={}, amount={}", userId, amount);
                    throw new ServiceException("系统繁忙");
                }
                BigDecimal afterBalance = balance.add(amount);
                enableBalanceReminderIfQualified(userId, amount);
                saveBalanceLog(userId, "recharge", amount, balance, afterBalance, traceId, bizType, bizName);
            });
        });
    }

    @Override
    public BigDecimal settleExtraCharge(Long userId, BigDecimal extra, String traceId, String bizType, String bizName)
    {
        // 用 AtomicReference 从锁内事务中带出累计补扣总额（含本次）
        java.util.concurrent.atomic.AtomicReference<BigDecimal> totalCharged = new java.util.concurrent.atomic.AtomicReference<>(BigDecimal.ZERO);
        AtomicBoolean notifyLowBalance = new AtomicBoolean(false);
        executeWithUserLock(userId, () -> {
            newTx().executeWithoutResult(s -> {
                // 查询同 traceId 已累计补扣金额，支持部分补扣后重试补齐
                BigDecimal alreadyCharged = sumExtraCharged(traceId);
                BigDecimal remaining = extra.subtract(alreadyCharged);
                if (remaining.compareTo(BigDecimal.ZERO) <= 0)
                {
                    // 已全额补完，跳过
                    log.info("补扣已全额完成，跳过, traceId={}, extra={}, alreadyCharged={}", traceId, extra, alreadyCharged);
                    totalCharged.set(alreadyCharged);
                    return;
                }

                AidUserProfile profile = getProfileInternal(userId);
                BigDecimal balance = profile.getBalance() == null ? BigDecimal.ZERO : profile.getBalance();

                // 按可用余额上限补扣：min(remaining, balance)，余额不足时部分补扣
                BigDecimal actualExtra = remaining.min(balance.max(BigDecimal.ZERO));
                if (actualExtra.compareTo(BigDecimal.ZERO) <= 0)
                {
                    log.info("补扣跳过(余额为0), userId={}, remaining={}, balance={}, alreadyCharged={}", userId, remaining, balance, alreadyCharged);
                    totalCharged.set(alreadyCharged);
                    return;
                }

                // 条件扣减（balance -= actualExtra AND balance >= actualExtra），totalConsumption += actualExtra
                int affected = casBalanceFrozen(userId, actualExtra.negate(), null, actualExtra, actualExtra);
                if (affected == 0)
                {
                    // 并发冲突：可能另一线程已把余额扣掉一部分。不抛异常，等待下一轮重试。
                    log.warn("补扣并发冲突, userId={}, actualExtra={}", userId, actualExtra);
                    totalCharged.set(alreadyCharged);
                    return;
                }
                BigDecimal afterBalance = balance.subtract(actualExtra);
                notifyLowBalance.set(isBelowBalanceReminderThreshold(afterBalance));
                saveBalanceLog(userId, "settle_extra", actualExtra.negate(), balance, afterBalance, traceId, bizType, bizName);
                // 返回累计总补扣额（历史 + 本次）
                totalCharged.set(alreadyCharged.add(actualExtra));
                log.info("补扣成功, traceId={}, 本次补扣={}, 累计补扣={}, 总需补扣={}", traceId, actualExtra, totalCharged.get(), extra);
            });
        });
        notifyLowBalanceIfNeeded(notifyLowBalance.get(), userId, bizType, bizName, extra);
        return totalCharged.get();
    }

    /**
     * 查询同一 traceId 下 changeType="settle_extra" 已累计补扣金额。
     * amount 在流水中为负数（扣款），取绝对值求和。
     * 在锁内事务中调用，保证原子性。
     */
    private BigDecimal sumExtraCharged(String traceId)
    {
        List<AidBalanceLog> logs = aidBalanceLogService.list(
                Wrappers.<AidBalanceLog>lambdaQuery()
                        .eq(AidBalanceLog::getRelatedId, traceId)
                        .eq(AidBalanceLog::getChangeType, "settle_extra")
                        .select(AidBalanceLog::getAmount)
        );
        if (logs == null || logs.isEmpty())
        {
            return BigDecimal.ZERO;
        }
        return logs.stream()
                .map(l -> l.getAmount() == null ? BigDecimal.ZERO : l.getAmount().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public void adminAdjust(Long userId, BigDecimal delta, String traceId, String bizName)
    {
        if (delta == null || delta.signum() == 0)
        {
            log.info("管理员调整金额为空或为零, userId={}", userId);
            throw new ServiceException("金额有误");
        }
        executeWithUserLock(userId, () -> {
            newTx().executeWithoutResult(s -> {
                // 幂等：同一 traceId + changeType 已执行则跳过
                if (balanceLogExists(traceId, "admin_adjust"))
                {
                    log.info("管理员调整已执行，跳过, traceId={}", traceId);
                    return;
                }

                AidUserProfile profile = getProfileInternal(userId);
                BigDecimal balance = profile.getBalance() == null ? BigDecimal.ZERO : profile.getBalance();

                // 扣减时锁内校验余额充足，防止扣成负数
                if (delta.signum() < 0 && balance.compareTo(delta.abs()) < 0)
                {
                    log.info("管理员扣减余额不足, userId={}, balance={}, delta={}", userId, balance, delta);
                    throw new ServiceException("余额不足");
                }

                // 仅调整 balance（不动累计充值/消费），扣减时条件保护 balance >= |delta|
                int affected = casBalanceFrozen(userId, delta, null, null, null);
                if (affected == 0)
                {
                    log.warn("管理员调整并发冲突或余额不足, userId={}, delta={}", userId, delta);
                    throw new ServiceException("系统繁忙");
                }
                BigDecimal afterBalance = balance.add(delta);
                // bizType 固定 admin_adjust，便于流水审计与前端筛选
                saveBalanceLog(userId, "admin_adjust", delta, balance, afterBalance, traceId, "admin_adjust", bizName);
            });
        });
    }

    /**
     * 营销奖励入账：仅增加可用余额（不计累计充值/消费），changeType=reward。
     * 幂等：同一 traceId + reward 已执行则跳过，注册赠送/邀请返佣重复触发不会重复发放。
     */
    @Override
    public void reward(Long userId, BigDecimal amount, String traceId, String bizType, String bizName)
    {
        if (amount == null || amount.signum() <= 0)
        {
            log.info("奖励金额为空或非正数, userId={}, amount={}, traceId={}", userId, amount, traceId);
            throw new ServiceException("金额有误");
        }
        executeWithUserLock(userId, () -> {
            newTx().executeWithoutResult(s -> {
                // 幂等：同一 traceId + changeType 已执行则跳过
                if (balanceLogExists(traceId, "reward"))
                {
                    log.info("奖励已发放，跳过, traceId={}", traceId);
                    return;
                }

                AidUserProfile profile = getProfileInternal(userId);
                BigDecimal balance = profile.getBalance() == null ? BigDecimal.ZERO : profile.getBalance();

                // 仅调整 balance（正向增加，不动累计充值/消费）
                int affected = casBalanceFrozen(userId, amount, null, null, null);
                if (affected == 0)
                {
                    log.warn("奖励入账并发冲突, userId={}, amount={}, traceId={}", userId, amount, traceId);
                    throw new ServiceException("系统繁忙");
                }
                BigDecimal afterBalance = balance.add(amount);
                saveBalanceLog(userId, "reward", amount, balance, afterBalance, traceId, bizType, bizName);
            });
        });
    }

    @Override
    public AidUserProfile getProfile(Long userId)
    {
        return getProfileInternal(userId);
    }

    @Override
    public void precheckBalance(Long userId, BigDecimal amount)
    {
        // 无预估金额（免计费/预估失败）不预检，交由后续 freeze 锁内兜底
        if (Objects.isNull(userId) || Objects.isNull(amount) || amount.compareTo(BigDecimal.ZERO) <= 0)
        {
            return;
        }
        AidUserProfile profile = getProfileInternal(userId);
        BigDecimal balance = profile.getBalance() == null ? BigDecimal.ZERO : profile.getBalance();
        if (balance.compareTo(amount) < 0)
        {
            log.info("余额前置预检不足, userId={}, balance={}, required={}", userId, balance, amount);
            throw new ServiceException("余额不足");
        }
    }
    /**
     * 幂等检查：根据 traceId + changeType 查询是否已执行过该账户操作。
     * 在锁内事务中调用，保证原子性。
     */
    private boolean balanceLogExists(String traceId, String changeType)
    {
        return aidBalanceLogService.count(
                Wrappers.<AidBalanceLog>lambdaQuery()
                        .eq(AidBalanceLog::getRelatedId, traceId)
                        .eq(AidBalanceLog::getChangeType, changeType)
                        .last("LIMIT 1")
        ) > 0;
    }

    /**
     * 创建 REQUIRES_NEW 独立事务模板。
     * 事务在锁内独立提交，不依赖外层事务，确保锁释放前数据已落库。
     */
    private TransactionTemplate newTx()
    {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return txTemplate;
    }

    /**
     * 按 userId 抢分布式锁后执行操作，抢不到则短重试。
     * 关键：锁值写入当前持有者唯一 token（UUID），释放时用 Lua 脚本原子 CAS 删除。
     * 这样即使当前线程事务因 DB 抖动超过 LOCK_TTL_SECONDS 导致锁被动过期，
     * 其他线程抢到新锁后，本线程在 finally 里也不会误删别人的锁。
     * 不同用户完全并行，同一用户串行。
     */
    @SuppressWarnings("unchecked")
    private void executeWithUserLock(Long userId, Runnable action)
    {
        String lockKey = ACCOUNT_LOCK_PREFIX + userId;
        // 每次抢锁生成唯一持有者 token，用于安全释放 / 续约
        String token = UUID.randomUUID().toString();
        for (int i = 0; i < LOCK_WAIT_SECONDS; i++)
        {
            Boolean locked = redisCache.redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, token, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
            if (locked != null && locked)
            {
                // 启动 watchdog：持有锁期间每 LOCK_RENEW_INTERVAL_MS 续约一次，
                // 仅当锁值仍等于本线程 token 才刷新 TTL，避免误续他人新锁。
                // 超过 LOCK_MAX_HOLD_SECONDS 强制停止续约，防止悬挂事务被无限续。
                long startMs = System.currentTimeMillis();
                ScheduledFuture<?> watchdog = lockRenewExecutor.scheduleAtFixedRate(() -> {
                    try
                    {
                        long heldSeconds = (System.currentTimeMillis() - startMs) / 1000L;
                        if (heldSeconds >= LOCK_MAX_HOLD_SECONDS)
                        {
                            log.error("账户锁持有超过最大时长，停止续约, userId={}, heldSeconds={}", userId, heldSeconds);
                            throw new RuntimeException("lock-hold-exceeded");
                        }
                        Long renewed = (Long) redisCache.redisTemplate.execute(
                                RENEW_SCRIPT,
                                Collections.singletonList(lockKey),
                                token,
                                String.valueOf(LOCK_TTL_SECONDS * 1000L));
                        if (renewed == null || renewed == 0L)
                        {
                            log.warn("账户锁续约失败（锁可能已被其他持有者抢占或过期）, userId={}, token={}", userId, token);
                            throw new RuntimeException("lock-renew-failed");
                        }
                    }
                    catch (RuntimeException re)
                    {
                        // 抛出 RuntimeException 让 ScheduledExecutor 自动取消后续调度
                        throw re;
                    }
                    catch (Exception ex)
                    {
                        log.error("账户锁续约异常, userId={}", userId, ex);
                    }
                }, LOCK_RENEW_INTERVAL_MS, LOCK_RENEW_INTERVAL_MS, TimeUnit.MILLISECONDS);

                try
                {
                    action.run();
                    return;
                }
                finally
                {
                    // 先停 watchdog，避免释放后又续上
                    watchdog.cancel(false);
                    // 仅当锁值仍等于本线程 token 时才删除，避免误删他人新锁
                    try
                    {
                        Long released = (Long) redisCache.redisTemplate.execute(
                                UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
                        if (released == null || released == 0L)
                        {
                            log.warn("账户锁释放时已非当前持有者（可能已超时被抢占）, userId={}, token={}", userId, token);
                        }
                    }
                    catch (Exception ex)
                    {
                        log.error("账户锁释放异常, userId={}", userId, ex);
                    }
                }
            }
            // 抢不到，等 1 秒重试
            try
            {
                TimeUnit.SECONDS.sleep(1);
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                throw new ServiceException("系统繁忙");
            }
        }
        log.warn("账户锁获取超时, userId={}", userId);
        throw new ServiceException("系统繁忙");
    }

    /**
     * 条件 delta 扣减/增加 balance+frozen+totalConsumption。
     */
    private int casBalanceFrozen(Long userId,
                                 BigDecimal balanceDelta,
                                 BigDecimal frozenDelta,
                                 BigDecimal balanceMinIfNegative,
                                 BigDecimal consumptionDelta)
    {
        // 计算条件阈值：只有在扣减（delta<0 或 consumption-减）时才需要 min 校验
        BigDecimal balanceMin = null;
        BigDecimal frozenMin = null;
        BigDecimal consumptionMin = null;
        if (balanceDelta != null && balanceDelta.signum() < 0)
        {
            balanceMin = balanceMinIfNegative != null ? balanceMinIfNegative : balanceDelta.abs();
        }
        if (frozenDelta != null && frozenDelta.signum() < 0)
        {
            frozenMin = frozenDelta.abs();
        }
        if (consumptionDelta != null && consumptionDelta.signum() < 0)
        {
            consumptionMin = consumptionDelta.abs();
        }
        return aidUserProfileMapper.casDeltaUpdate(userId, balanceDelta, frozenDelta, consumptionDelta, null,
                balanceMin, frozenMin, consumptionMin);
    }

    /**
     * 充值场景：balance += amount, totalRecharge += amount
     */
    private int casRecharge(Long userId, BigDecimal amount)
    {
        return aidUserProfileMapper.casDeltaUpdate(userId, amount, null, null, amount, null, null, null);
    }

    private void enableBalanceReminderIfQualified(Long userId, BigDecimal rechargeAmount)
    {
        WechatNotifyConfig config = wechatNotifyConfigService.getConfig();
        BigDecimal threshold = config.getBalanceReminderThreshold() == null
                ? BigDecimal.ZERO : config.getBalanceReminderThreshold();
        if (rechargeAmount == null || rechargeAmount.compareTo(threshold) <= 0)
        {
            return;
        }
        aidUserProfileService.update(Wrappers.<AidUserProfile>lambdaUpdate()
                .eq(AidUserProfile::getUserId, userId)
                .eq(AidUserProfile::getDelFlag, "0")
                .set(AidUserProfile::getBalanceReminderAvailable, 1)
                .set(AidUserProfile::getUpdateTime, DateUtils.getNowDate())
                .set(AidUserProfile::getUpdateBy, "system"));
    }

    private boolean isBelowBalanceReminderThreshold(BigDecimal afterBalance)
    {
        WechatNotifyConfig config = wechatNotifyConfigService.getConfig();
        BigDecimal threshold = config.getBalanceReminderThreshold() == null
                ? BigDecimal.ZERO : config.getBalanceReminderThreshold();
        return afterBalance != null && afterBalance.compareTo(threshold) < 0;
    }

    private void notifyLowBalanceIfNeeded(boolean shouldNotify, Long userId, String bizType,
                                          String bizName, BigDecimal requiredAmount)
    {
        if (!shouldNotify)
        {
            return;
        }
        wechatNotifyService.notifyBalanceInsufficient(userId, bizType, null, requiredAmount);
    }

    private AidUserProfile getProfileInternal(Long userId)
    {
        // 已删除档案不允许参与任何账务操作（与余额提醒等链路的 del_flag 口径一致）
        AidUserProfile profile = aidUserProfileService.getOne(
                Wrappers.<AidUserProfile>lambdaQuery()
                        .eq(AidUserProfile::getUserId, userId)
                        .eq(AidUserProfile::getDelFlag, "0")
                        .last("limit 1")
        );
        if (profile == null)
        {
            log.error("账户档案缺失或已删除, userId={}", userId);
            throw new ServiceException("账户不存在");
        }
        return profile;
    }

    private void saveBalanceLog(Long userId, String changeType, BigDecimal amount,
                                 BigDecimal beforeBalance, BigDecimal afterBalance,
                                 String traceId, String bizType, String bizName)
    {
        saveBalanceLog(userId, changeType, amount, beforeBalance, afterBalance,
                traceId, bizType, bizName, null);
    }

    private void saveBalanceLog(Long userId, String changeType, BigDecimal amount,
                                 BigDecimal beforeBalance, BigDecimal afterBalance,
                                 String traceId, String bizType, String bizName, String modelCode)
    {
        AidBalanceLog balanceLog = new AidBalanceLog();
        balanceLog.setUserId(userId);
        balanceLog.setChangeType(changeType);
        balanceLog.setAmount(amount);
        balanceLog.setBeforeBalance(beforeBalance);
        balanceLog.setAfterBalance(afterBalance);
        balanceLog.setRelatedId(traceId);
        balanceLog.setBizType(bizType);
        balanceLog.setBizName(bizName);
        balanceLog.setModelCode(modelCode);
        balanceLog.setDelFlag("0");
        // 创建时间必填：C 端消耗明细需按时间展示
        balanceLog.setCreateTime(DateUtils.getNowDate());
        aidBalanceLogService.save(balanceLog);
    }
}
