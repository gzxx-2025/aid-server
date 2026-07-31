package com.aid.rps.queue;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

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

    /** 清除取消标记（幂等，不抛出） */
    public void clearCancelled(Long taskId)
    {
        if (Objects.isNull(taskId)) { return; }
        try { redisCache.deleteObject(CANCEL_FLAG_PREFIX + taskId); }
        catch (Exception e) { log.warn("清除取消标记异常: taskId={}", taskId, e); }
    }
}
