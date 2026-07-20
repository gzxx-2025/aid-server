package com.aid.rps.queue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cn.hutool.core.collection.CollectionUtil;

/**
 * 多维并发名额管理器（全局 + 用户 + 模型 + 服务商），名额用运行中任务 ZSet 表达，可对账、幂等、四维原子准入。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskSlotManager
{
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 占用名额自过期窗口（毫秒）：占用项 score=过期时刻，作为「带租约的自过期信号量」结构性兜底，
     * 存活任务由 {@link #renewOccupancy} 每调度拍续期，失活孤儿最多被持有本窗口时长。
     */
    public static final long SLOT_OCCUPANCY_TTL_MS = 180_000L;

    /**
     * 原子准入脚本：先剔除过期与自身占用项，再判定四维 ZCARD 是否达上限，全通过则四集合 ZADD。
     * KEYS[1..4]=全局/用户/模型/服务商占用集；ARGV[1..4]=各维上限；ARGV[5]=taskId；ARGV[6]=过期时刻 score；ARGV[7]=now。
     * 返回值：1=成功；-1=全局满；-2=用户满；-3=模型满；-4=服务商满。
     */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            "local member = ARGV[5] " +
            "local score = ARGV[6] " +
            "local now = ARGV[7] " +
            // 自过期：先按 score < now 剔除已过期（未续期）的失效占用项，避免把"僵尸名额"计入 size
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. now) " +
            "redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', '(' .. now) " +
            "redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', '(' .. now) " +
            "redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', '(' .. now) " +
            // 幂等：再移除自身，避免重复占用把自己计入 size
            "redis.call('ZREM', KEYS[1], member) " +
            "redis.call('ZREM', KEYS[2], member) " +
            "redis.call('ZREM', KEYS[3], member) " +
            "redis.call('ZREM', KEYS[4], member) " +
            "if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[1]) then return -1 end " +
            "if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[2]) then return -2 end " +
            "if redis.call('ZCARD', KEYS[3]) >= tonumber(ARGV[3]) then return -3 end " +
            "if redis.call('ZCARD', KEYS[4]) >= tonumber(ARGV[4]) then return -4 end " +
            // score = 过期时刻（now + TTL）；存活期间由 reconcileTerminalSlots 续期顺延
            "redis.call('ZADD', KEYS[1], score, member) " +
            "redis.call('ZADD', KEYS[2], score, member) " +
            "redis.call('ZADD', KEYS[3], score, member) " +
            "redis.call('ZADD', KEYS[4], score, member) " +
            "return 1",
            Long.class);

    /** 释放脚本：从四个集合移除该 taskId（幂等） */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREM', KEYS[1], ARGV[1]) " +
            "redis.call('ZREM', KEYS[2], ARGV[1]) " +
            "redis.call('ZREM', KEYS[3], ARGV[1]) " +
            "redis.call('ZREM', KEYS[4], ARGV[1]) " +
            "return 1",
            Long.class);

    /** 续期脚本：ZADD XX 仅顺延已存在成员的过期时刻，绝不新增，供 reconcileTerminalSlots 给存活任务续期 */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZADD', KEYS[1], 'XX', ARGV[2], ARGV[1]) " +
            "redis.call('ZADD', KEYS[2], 'XX', ARGV[2], ARGV[1]) " +
            "redis.call('ZADD', KEYS[3], 'XX', ARGV[2], ARGV[1]) " +
            "redis.call('ZADD', KEYS[4], 'XX', ARGV[2], ARGV[1]) " +
            "return 1",
            Long.class);

    /** 计数脚本：单次往返内先按 score &lt; now 剔除过期占用项、再 ZCARD 返回当前占用数 */
    private static final DefaultRedisScript<Long> EVICT_AND_COUNT_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[1]) " +
            "return redis.call('ZCARD', KEYS[1])",
            Long.class);

    /**
     * 尝试为任务原子获取四维并发名额。
     *
     * @param taskId        任务ID
     * @param userId        用户ID
     * @param modelCode     模型编码
     * @param providerId    服务商ID（可空）
     * @param globalLimit   全局上限
     * @param userLimit     用户上限
     * @param modelLimit    模型上限（不限传 Integer.MAX_VALUE）
     * @param providerLimit 服务商上限（不限传 Integer.MAX_VALUE）
     * @return 1=成功；负数=对应维度已满（-1全局/-2用户/-3模型/-4服务商）；null=Redis异常
     */
    public Long tryAcquire(Long taskId, Long userId, String modelCode, Long providerId,
                           int globalLimit, int userLimit, int modelLimit, int providerLimit)
    {
        try
        {
            List<String> keys = Arrays.asList(
                    TaskQueueKeys.OCC_GLOBAL,
                    TaskQueueKeys.userOccKey(userId),
                    TaskQueueKeys.modelOccKey(modelCode),
                    TaskQueueKeys.providerOccKey(providerId));
            long now = System.currentTimeMillis();
            long score = now + SLOT_OCCUPANCY_TTL_MS; // 过期时刻
            return stringRedisTemplate.execute(
                    ACQUIRE_SCRIPT,
                    keys,
                    String.valueOf(globalLimit),
                    String.valueOf(userLimit),
                    String.valueOf(modelLimit),
                    String.valueOf(providerLimit),
                    String.valueOf(taskId),
                    String.valueOf(score),
                    String.valueOf(now));
        }
        catch (Exception e)
        {
            log.error("并发名额获取异常, taskId={}", taskId, e);
            return null;
        }
    }

    /**
     * 续期任务四维名额的过期时刻（顺延一个 {@link #SLOT_OCCUPANCY_TTL_MS} 窗口，仅更新已存在成员，幂等吞异常）。
     */
    public void renewOccupancy(Long taskId, Long userId, String modelCode, Long providerId)
    {
        if (taskId == null)
        {
            return;
        }
        try
        {
            List<String> keys = Arrays.asList(
                    TaskQueueKeys.OCC_GLOBAL,
                    TaskQueueKeys.userOccKey(userId),
                    TaskQueueKeys.modelOccKey(modelCode),
                    TaskQueueKeys.providerOccKey(providerId));
            long score = System.currentTimeMillis() + SLOT_OCCUPANCY_TTL_MS;
            stringRedisTemplate.execute(RENEW_SCRIPT, keys, String.valueOf(taskId), String.valueOf(score));
        }
        catch (Exception e)
        {
            log.warn("并发名额续期失败(忽略), taskId={}", taskId, e);
        }
    }

    /** 释放任务的四维并发名额（幂等，终态/取消/僵尸回收/对账统一调用） */
    public void release(Long taskId, Long userId, String modelCode, Long providerId)
    {
        try
        {
            List<String> keys = Arrays.asList(
                    TaskQueueKeys.OCC_GLOBAL,
                    TaskQueueKeys.userOccKey(userId),
                    TaskQueueKeys.modelOccKey(modelCode),
                    TaskQueueKeys.providerOccKey(providerId));
            stringRedisTemplate.execute(RELEASE_SCRIPT, keys, String.valueOf(taskId));
        }
        catch (Exception e)
        {
            log.warn("并发名额释放失败, taskId={}", taskId, e);
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

    /** 模型当前并发占用数 */
    public long getModelOccupied(String modelCode)
    {
        return evictExpiredThenCount(TaskQueueKeys.modelOccKey(modelCode));
    }

    /** 服务商当前并发占用数 */
    public long getProviderOccupied(Long providerId)
    {
        return evictExpiredThenCount(TaskQueueKeys.providerOccKey(providerId));
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

    /** 全局占用集合内的全部 taskId（对账用） */
    public Set<String> getGlobalOccupants()
    {
        return stringRedisTemplate.opsForZSet().range(TaskQueueKeys.OCC_GLOBAL, 0, -1);
    }

    /**
     * 批量获取多个模型的并发占用数（一次 pipeline 往返，避免 N 次 ZCARD）。
     *
     * @param modelCodes 模型编码集合
     * @return modelCode -> 占用数（缺失/异常按 0 记）
     */
    public java.util.Map<String, Long> getModelOccupiedBatch(java.util.List<String> modelCodes)
    {
        java.util.Map<String, Long> result = new java.util.HashMap<>();
        if (CollectionUtil.isEmpty(modelCodes))
        {
            return result;
        }
        List<String> keys = new java.util.ArrayList<>(modelCodes.size());
        for (String code : modelCodes)
        {
            keys.add(TaskQueueKeys.modelOccKey(code));
        }
        List<Long> counts = pipelineZCard(keys);
        for (int i = 0; i < modelCodes.size(); i++)
        {
            Long c = (counts != null && i < counts.size()) ? counts.get(i) : null;
            result.put(modelCodes.get(i), c == null ? 0L : c);
        }
        return result;
    }

    /**
     * 批量获取多个服务商的并发占用数（一次 pipeline 往返）。
     *
     * @param providerIds 服务商ID集合
     * @return providerId -> 占用数（缺失/异常按 0 记）
     */
    public java.util.Map<Long, Long> getProviderOccupiedBatch(java.util.List<Long> providerIds)
    {
        java.util.Map<Long, Long> result = new java.util.HashMap<>();
        if (CollectionUtil.isEmpty(providerIds))
        {
            return result;
        }
        List<String> keys = new java.util.ArrayList<>(providerIds.size());
        for (Long id : providerIds)
        {
            keys.add(TaskQueueKeys.providerOccKey(id));
        }
        List<Long> counts = pipelineZCard(keys);
        for (int i = 0; i < providerIds.size(); i++)
        {
            Long c = (counts != null && i < counts.size()) ? counts.get(i) : null;
            result.put(providerIds.get(i), c == null ? 0L : c);
        }
        return result;
    }

    /** 对一组 key 执行 pipeline ZCARD，返回与入参等长同序的计数列表（异常返回 null，调用方按 0 兜底） */
    private List<Long> pipelineZCard(List<String> keys)
    {
        if (CollectionUtil.isEmpty(keys))
        {
            return java.util.Collections.emptyList();
        }
        try
        {
            List<Object> raw = stringRedisTemplate.executePipelined(
                    (org.springframework.data.redis.core.RedisCallback<Object>) connection ->
                    {
                        org.springframework.data.redis.connection.StringRedisConnection sconn =
                                (org.springframework.data.redis.connection.StringRedisConnection) connection;
                        for (String key : keys)
                        {
                            sconn.zCard(key);
                        }
                        return null;
                    });
            List<Long> counts = new java.util.ArrayList<>(keys.size());
            for (Object o : raw)
            {
                counts.add(o instanceof Number ? ((Number) o).longValue() : 0L);
            }
            return counts;
        }
        catch (Exception e)
        {
            log.warn("批量 ZCARD 占用数失败(监控降级，按0兜底), keys={}", keys.size(), e);
            return null;
        }
    }
}
