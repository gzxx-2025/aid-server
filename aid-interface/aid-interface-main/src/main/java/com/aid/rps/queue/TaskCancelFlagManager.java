package com.aid.rps.queue;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import com.aid.common.core.redis.RedisCache;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 任务取消标记低层管理器（Redis），高层 Service 与低层编排器共用的单一来源。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class TaskCancelFlagManager
{
    /** 取消标记 key 前缀 */
    public static final String CANCEL_FLAG_PREFIX = "asset:extract:cancel:";
    /** 取消标记 TTL（秒） */
    public static final long CANCEL_FLAG_TTL_SECONDS = 6L * 60L * 60L;

    @Resource
    private RedisCache redisCache;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> CLEAR_IF_CYCLE_MATCHES = new DefaultRedisScript<>(
            "local v=redis.call('GET', KEYS[1]); "
                    + "if v==ARGV[1] or v=='1' or v=='\"1\"' then return redis.call('DEL', KEYS[1]) end; return 0",
            Long.class);

    /** 是否已标记取消 */
    public boolean isCancelled(Long taskId)
    {
        if (Objects.isNull(taskId)) { return false; }
        return redisCache.hasKey(CANCEL_FLAG_PREFIX + taskId);
    }

    /** 写入取消标记（带 TTL） */
    public void setCancelled(Long taskId)
    {
        if (Objects.isNull(taskId)) { return; }
        redisCache.setCacheObject(CANCEL_FLAG_PREFIX + taskId, "1", (int) CANCEL_FLAG_TTL_SECONDS, TimeUnit.SECONDS);
    }

    /** 写入带派发周期的取消标记。 */
    public void setCancelled(Long taskId, String dispatchToken)
    {
        if (Objects.isNull(taskId) || dispatchToken == null || dispatchToken.isBlank()) { return; }
        stringRedisTemplate.opsForValue().set(
                CANCEL_FLAG_PREFIX + taskId, dispatchToken,
                CANCEL_FLAG_TTL_SECONDS, TimeUnit.SECONDS);
    }

    /** 仅清除指定派发周期的取消标记；兼容升级前值为 1 的存量标记。 */
    public void clearCancelled(Long taskId, String dispatchToken)
    {
        if (Objects.isNull(taskId) || dispatchToken == null || dispatchToken.isBlank()) { return; }
        try
        {
            stringRedisTemplate.execute(CLEAR_IF_CYCLE_MATCHES,
                    java.util.List.of(CANCEL_FLAG_PREFIX + taskId), dispatchToken);
        }
        catch (Exception e)
        {
            log.warn("按周期清除取消标记异常: taskId={}", taskId, e);
        }
    }
}
