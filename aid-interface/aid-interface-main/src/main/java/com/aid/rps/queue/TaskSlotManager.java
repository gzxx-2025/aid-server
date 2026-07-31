package com.aid.rps.queue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 业务任务并发名额管理器（全局 + 用户），名额用运行中任务 ZSet 表达，可对账、幂等、双维原子准入。
 *
 * 只表达「同时进行的业务批次任务数」，不表达供应商 / 模型的上游请求并发——后者由
 * {@link com.aid.media.service.MediaConcurrencyLimiter} 按 aid_media_task 在途数唯一执行，
 * 避免同一概念在两处配置、两处计数。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskSlotManager
{
    private static final String SLOT_COORDINATION_LOCK = "taskq:occ:coordination-lock";

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    /** Redis Cluster 不允许旧 key 跨 slot 执行 Lua，首次检测后切换到单 key 命令模式。 */
    private final AtomicBoolean singleKeyMode = new AtomicBoolean(false);

    /**
     * 占用名额自过期窗口（毫秒）：占用项 score=过期时刻，作为「带租约的自过期信号量」结构性兜底，
     * 存活任务由 {@link #renewOccupancy} 每调度拍续期，失活孤儿最多被持有本窗口时长。
     */
    public static final long SLOT_OCCUPANCY_TTL_MS = 180_000L;

    /**
     * 原子准入脚本：先剔除过期与自身占用项，再判定双维 ZCARD 是否达上限，全通过则两集合 ZADD。
     * KEYS[1..2]=全局/用户占用集；ARGV[1..2]=各维上限；ARGV[3]=taskId；ARGV[4]=过期时刻 score；ARGV[5]=now。
     * 返回值：1=成功；-1=全局满；-2=用户满。
     */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            "local member = ARGV[3] " +
            "local score = ARGV[4] " +
            "local now = ARGV[5] " +
            // 自过期：先按 score < now 剔除已过期（未续期）的失效占用项，避免把"僵尸名额"计入 size
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. now) " +
            "redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', '(' .. now) " +
            // 幂等：再移除自身，避免重复占用把自己计入 size
            "redis.call('ZREM', KEYS[1], member) " +
            "redis.call('ZREM', KEYS[2], member) " +
            "if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[1]) then return -1 end " +
            "if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[2]) then return -2 end " +
            // score = 过期时刻（now + TTL）；存活期间由 reconcileTerminalSlots 续期顺延
            "redis.call('ZADD', KEYS[1], score, member) " +
            "redis.call('ZADD', KEYS[2], score, member) " +
            "return 1",
            Long.class);

    /** 释放脚本：从两个集合移除该 taskId（幂等） */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREM', KEYS[1], ARGV[1]) " +
            "redis.call('ZREM', KEYS[2], ARGV[1]) " +
            "return 1",
            Long.class);

    /** 续期脚本：ZADD XX 仅顺延已存在成员的过期时刻，绝不新增，供 reconcileTerminalSlots 给存活任务续期 */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZADD', KEYS[1], 'XX', ARGV[2], ARGV[1]) " +
            "redis.call('ZADD', KEYS[2], 'XX', ARGV[2], ARGV[1]) " +
            "return 1",
            Long.class);

    /** 计数脚本：单次往返内先按 score &lt; now 剔除过期占用项、再 ZCARD 返回当前占用数 */
    private static final DefaultRedisScript<Long> EVICT_AND_COUNT_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[1]) " +
            "return redis.call('ZCARD', KEYS[1])",
            Long.class);

    /** 单 key 准入预处理：清理过期项、移除自身后返回当前占用数。 */
    private static final DefaultRedisScript<Long> PREPARE_AND_COUNT_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[2]) " +
                    "redis.call('ZREM', KEYS[1], ARGV[1]) " +
                    "return redis.call('ZCARD', KEYS[1])",
            Long.class);

    /** 单 key 续期：仅更新已存在成员。 */
    private static final DefaultRedisScript<Long> RENEW_ONE_SCRIPT = new DefaultRedisScript<>(
            "return redis.call('ZADD', KEYS[1], 'XX', ARGV[2], ARGV[1])",
            Long.class);

    /**
     * 尝试为任务原子获取双维并发名额。
     *
     * @param taskId      任务ID
     * @param userId      用户ID
     * @param globalLimit 全局上限
     * @param userLimit   用户上限
     * @return 1=成功；负数=对应维度已满（-1全局/-2用户）；null=Redis异常
     */
    public Long tryAcquire(Long taskId, Long userId, int globalLimit, int userLimit)
    {
        if (!singleKeyMode.get())
        {
            try
            {
                List<String> keys = Arrays.asList(
                        TaskQueueKeys.OCC_GLOBAL,
                        TaskQueueKeys.userOccKey(userId));
                long now = System.currentTimeMillis();
                long score = now + SLOT_OCCUPANCY_TTL_MS;
                return stringRedisTemplate.execute(
                        ACQUIRE_SCRIPT,
                        keys,
                        String.valueOf(globalLimit),
                        String.valueOf(userLimit),
                        String.valueOf(taskId),
                        String.valueOf(score),
                        String.valueOf(now));
            }
            catch (Exception e)
            {
                if (!isCrossSlotError(e))
                {
                    log.error("并发名额获取异常, taskId={}", taskId, e);
                    return null;
                }
                singleKeyMode.set(true);
                log.info("Redis Cluster 已启用单 key 名额协调模式");
            }
        }

        return tryAcquireSingleKey(taskId, userId, globalLimit, userLimit);
    }

    /**
     * Cluster 兼容准入：保留全部旧 key，通过独立分布式锁串行两个单 key Lua。
     * 旧实例在 Cluster 上的跨 slot Lua 会在任何写入前被 Redis 拒绝，因此不会与本路径交错超卖。
     */
    private Long tryAcquireSingleKey(Long taskId, Long userId, int globalLimit, int userLimit)
    {
        RLock lock = acquireCoordinationLock();
        if (lock == null)
        {
            return null;
        }
        String member = String.valueOf(taskId);
        String userKey = TaskQueueKeys.userOccKey(userId);
        try
        {
            long now = System.currentTimeMillis();
            Long globalCount = prepareAndCount(TaskQueueKeys.OCC_GLOBAL, member, now);
            Long userCount = prepareAndCount(userKey, member, now);
            if (globalCount == null || userCount == null)
            {
                return null;
            }
            if (globalCount >= globalLimit)
            {
                return -1L;
            }
            if (userCount >= userLimit)
            {
                return -2L;
            }

            double expiresAt = now + SLOT_OCCUPANCY_TTL_MS;
            stringRedisTemplate.opsForZSet().add(TaskQueueKeys.OCC_GLOBAL, member, expiresAt);
            stringRedisTemplate.opsForZSet().add(userKey, member, expiresAt);
            return 1L;
        }
        catch (Exception e)
        {
            // 双维写入任一步结果不确定时全部撤销，不让任务带着半个名额放行。
            cleanupPartialOccupancy(member, userKey);
            log.error("并发名额单 key 获取异常, taskId={}", taskId, e);
            return null;
        }
        finally
        {
            releaseCoordinationLock(lock);
        }
    }

    /**
     * 续期任务双维名额的过期时刻（顺延一个 {@link #SLOT_OCCUPANCY_TTL_MS} 窗口，仅更新已存在成员，幂等吞异常）。
     */
    public void renewOccupancy(Long taskId, Long userId)
    {
        if (taskId == null)
        {
            return;
        }
        if (!singleKeyMode.get())
        {
            try
            {
                List<String> keys = Arrays.asList(
                        TaskQueueKeys.OCC_GLOBAL,
                        TaskQueueKeys.userOccKey(userId));
                long score = System.currentTimeMillis() + SLOT_OCCUPANCY_TTL_MS;
                stringRedisTemplate.execute(RENEW_SCRIPT, keys, String.valueOf(taskId), String.valueOf(score));
                return;
            }
            catch (Exception e)
            {
                if (!isCrossSlotError(e))
                {
                    log.warn("并发名额续期失败(忽略), taskId={}", taskId, e);
                    return;
                }
                singleKeyMode.set(true);
            }
        }

        RLock lock = acquireCoordinationLock();
        if (lock == null)
        {
            return;
        }
        try
        {
            String member = String.valueOf(taskId);
            String score = String.valueOf(System.currentTimeMillis() + SLOT_OCCUPANCY_TTL_MS);
            stringRedisTemplate.execute(RENEW_ONE_SCRIPT,
                    Collections.singletonList(TaskQueueKeys.OCC_GLOBAL), member, score);
            stringRedisTemplate.execute(RENEW_ONE_SCRIPT,
                    Collections.singletonList(TaskQueueKeys.userOccKey(userId)), member, score);
        }
        catch (Exception e)
        {
            log.warn("并发名额单 key 续期失败(忽略), taskId={}", taskId, e);
        }
        finally
        {
            releaseCoordinationLock(lock);
        }
    }

    /** 释放任务的双维并发名额（幂等，终态/取消/僵尸回收/对账统一调用） */
    public void release(Long taskId, Long userId)
    {
        if (!singleKeyMode.get())
        {
            try
            {
                List<String> keys = Arrays.asList(
                        TaskQueueKeys.OCC_GLOBAL,
                        TaskQueueKeys.userOccKey(userId));
                stringRedisTemplate.execute(RELEASE_SCRIPT, keys, String.valueOf(taskId));
                return;
            }
            catch (Exception e)
            {
                if (!isCrossSlotError(e))
                {
                    log.warn("并发名额释放失败, taskId={}", taskId, e);
                    return;
                }
                singleKeyMode.set(true);
            }
        }

        RLock lock = acquireCoordinationLock();
        if (lock == null)
        {
            return;
        }
        try
        {
            String member = String.valueOf(taskId);
            stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.OCC_GLOBAL, member);
            stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.userOccKey(userId), member);
        }
        catch (Exception e)
        {
            log.warn("并发名额单 key 释放失败, taskId={}", taskId, e);
        }
        finally
        {
            releaseCoordinationLock(lock);
        }
    }

    /** 全局当前并发占用数 */
    public long getGlobalOccupied()
    {
        return evictExpiredThenCount(TaskQueueKeys.OCC_GLOBAL);
    }

    /** 用户当前并发占用数 */
    public long getUserOccupied(Long userId)
    {
        return evictExpiredThenCount(TaskQueueKeys.userOccKey(userId));
    }

    /** 先按 score &lt; now 剔除过期占用项，再返回当前占用数（单次 Redis 往返，异常降级为直接 ZCARD） */
    private long evictExpiredThenCount(String key)
    {
        try
        {
            Long n = stringRedisTemplate.execute(EVICT_AND_COUNT_SCRIPT,
                    java.util.Collections.singletonList(key),
                    String.valueOf(System.currentTimeMillis()));
            return n == null ? 0L : n;
        }
        catch (Exception e)
        {
            log.debug("剔除过期占用项并计数异常(降级直接计数), key={}: {}", key, e.getMessage());
            Long n = stringRedisTemplate.opsForZSet().zCard(key);
            return n == null ? 0L : n;
        }
    }

    private Long prepareAndCount(String key, String member, long now)
    {
        return stringRedisTemplate.execute(PREPARE_AND_COUNT_SCRIPT,
                Collections.singletonList(key), member, String.valueOf(now));
    }

    private void cleanupPartialOccupancy(String member, String userKey)
    {
        try { stringRedisTemplate.opsForZSet().remove(TaskQueueKeys.OCC_GLOBAL, member); }
        catch (Exception e) { log.warn("并发名额全局半写补偿失败, taskId={}", member, e); }
        try { stringRedisTemplate.opsForZSet().remove(userKey, member); }
        catch (Exception e) { log.warn("并发名额用户半写补偿失败, taskId={}", member, e); }
    }

    private RLock acquireCoordinationLock()
    {
        RLock lock = redissonClient.getLock(SLOT_COORDINATION_LOCK);
        try
        {
            return lock.tryLock() ? lock : null;
        }
        catch (Exception e)
        {
            log.warn("并发名额协调锁获取失败", e);
            return null;
        }
    }

    private void releaseCoordinationLock(RLock lock)
    {
        if (lock == null)
        {
            return;
        }
        try
        {
            if (lock.isHeldByCurrentThread())
            {
                lock.unlock();
            }
        }
        catch (Exception e)
        {
            log.debug("并发名额协调锁释放失败，由 watchdog 收敛: {}", e.getMessage());
        }
    }

    private boolean isCrossSlotError(Throwable error)
    {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 12; depth++)
        {
            String message = current.getMessage();
            if (message != null)
            {
                String upper = message.toUpperCase(java.util.Locale.ROOT);
                if (upper.contains("CROSSSLOT") || upper.contains("SAME SLOT"))
                {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /** 全局占用集合内的全部 taskId（对账用） */
    public Set<String> getGlobalOccupants()
    {
        return stringRedisTemplate.opsForZSet().range(TaskQueueKeys.OCC_GLOBAL, 0, -1);
    }
}
