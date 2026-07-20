package com.aid.common.aid.lock;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一分布式锁模板。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLockTemplate
{
    /** 通用锁 Key 前缀。 */
    public static final String KEY_PREFIX = "aid:lock:";

    /**
     * 安全释放锁 Lua 脚本：只有 key 仍等于本次 token 时才删除。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 抢锁后执行动作，抢锁失败返回 {@code null}。
     *
     * @param bizKey  业务 Key（会自动拼接 {@link #KEY_PREFIX}）
     * @param ttl     锁持有时间
     * @param unit    时间单位
     * @param action  锁内要执行的动作
     * @param      结果类型
     * @return 抢到锁时返回 action 返回值；抢不到返回 {@code null}
     */
    public <T> T tryExecute(String bizKey, long ttl, TimeUnit unit, Supplier<T> action)
    {
        if (bizKey == null || bizKey.isEmpty())
        {
            throw new IllegalArgumentException("bizKey 不能为空");
        }
        String lockKey = KEY_PREFIX + bizKey;
        String token = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, token, ttl, unit);
        if (acquired == null || !acquired)
        {
            return null;
        }
        try
        {
            return action.get();
        }
        finally
        {
            try
            {
                Long released = stringRedisTemplate.execute(
                        UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
                if (released == null || released == 0L)
                {
                    // 释放时锁已非本持有者（可能被动过期/被他人抢占），仅打日志不抛
                    log.warn("分布式锁释放时已非当前持有者, lockKey={}, token={}", lockKey, token);
                }
            }
            catch (Exception e)
            {
                log.error("分布式锁释放异常, lockKey={}", lockKey, e);
            }
        }
    }

    /**
     * 抢锁失败时抛出 RuntimeException（由调用方决定包装成 ServiceException）。
     */
    public <T> T tryExecuteOrFail(String bizKey, long ttl, TimeUnit unit, Supplier<T> action, String failMessage)
    {
        String lockKey = KEY_PREFIX + bizKey;
        String token = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, token, ttl, unit);
        if (acquired == null || !acquired)
        {
            throw new RuntimeException(failMessage != null ? failMessage : "系统繁忙");
        }
        try
        {
            return action.get();
        }
        finally
        {
            try
            {
                stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
            }
            catch (Exception ignore)
            {
                log.warn("分布式锁释放异常, lockKey={}", lockKey);
            }
        }
    }
}
