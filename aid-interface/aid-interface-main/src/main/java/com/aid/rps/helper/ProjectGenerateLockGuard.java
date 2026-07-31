package com.aid.rps.helper;

import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.common.core.redis.RedisCache;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 项目 / 剧集级生成任务防重锁的安全获取器（含僵尸锁自愈）。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class ProjectGenerateLockGuard
{
    /** SETNX → INSERT 宽限期：60 秒。锁年龄低于此值时不允许清理（典型 INSERT 路径耗时 &lt; 2s，60s 留足容错）。 */
    private static final long STALE_GRACE_MS = 60L * 1000L;

    /** 任务活跃状态枚举（与 {@code AssetExtractServiceImpl} 保持一致） */
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String DEL_FLAG_NORMAL = "0";

    /** 仅当锁值仍等于传入 token 时才 DEL 的 Lua 脚本，防止误删被他人重抢的新锁。 */
    private static final DefaultRedisScript<Long> CAS_DEL_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private IAidExtractTaskService extractTaskService;

    /** 抢锁结果，acquired 为 false 时调用方应抛出「任务处理中」拒绝本次请求 */
    public static final class AcquireResult
    {
        /** 是否成功持锁 */
        private final boolean acquired;
        /** 当前持有的锁 token（仅 acquired=true 时有值，可用于后续 CAS 释放） */
        private final String token;

        private AcquireResult(boolean acquired, String token)
        {
            this.acquired = acquired;
            this.token = token;
        }

        public boolean isAcquired() { return acquired; }
        public String getToken() { return token; }

        static AcquireResult success(String token) { return new AcquireResult(true, token); }
        static AcquireResult busy() { return new AcquireResult(false, null); }
    }

    /**
     * 抢锁，失败时按「DB 活跃任务复核 + 锁年龄检查 + CAS 清理 + 重抢」自愈。
     *
     * @param lockKey     完整 Redis Key（调用方负责拼接，例如 {@code "storyboard:script:lock:84:0"}）
     * @param ttlSeconds  锁 TTL 秒数（建议按业务工作量动态计算并留足裕度）
     * @param taskType    该锁对应的 {@code aid_extract_task.task_type}，用于 DB 活跃任务复核
     * @param projectId   项目 id（用于 DB 活跃任务复核）
     * @param episodeId   剧集 id（用于 DB 活跃任务复核；分集为 0 表示项目级）
     * @return {@link AcquireResult}：acquired=true 表示已持锁；false 表示真并发，调用方应直接拒绝
     */
    public AcquireResult tryAcquireWithStaleClean(String lockKey, long ttlSeconds,
                                                  String taskType, Long projectId, Long episodeId)
    {
        // token 取当前毫秒值，用于后续判断锁年龄 + CAS 释放
        String lockToken = String.valueOf(System.currentTimeMillis());
        Boolean locked = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockToken, ttlSeconds, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(locked))
        {
            return AcquireResult.success(lockToken);
        }

        // 抢锁失败：DB 复核
        if (hasActiveTaskInDb(taskType, projectId, episodeId))
        {
            log.info("项目级锁占用拦截(DB有活跃任务): lockKey={}, taskType={}, projectId={}, episodeId={}",
                    lockKey, taskType, projectId, episodeId);
            return AcquireResult.busy();
        }

        // DB 无活跃任务：看锁年龄
        Object existing = redisCache.getCacheObject(lockKey);
        if (Objects.isNull(existing))
        {
            // 锁刚刚自然过期 → 直接重抢一次
            Boolean reLockedAfterExpire = redisCache.redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockToken, ttlSeconds, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(reLockedAfterExpire))
            {
                log.info("[CX6] 项目级锁过期后重抢被同瞬抢占, lockKey={}", lockKey);
                return AcquireResult.busy();
            }
            return AcquireResult.success(lockToken);
        }

        String existingToken = String.valueOf(existing);
        if (!isStaleByAge(existingToken))
        {
            // 现存锁还在宽限期里——持有者大概率刚 SETNX 还没 INSERT，不能误删
            log.info("[CX6] 项目级抢锁失败但锁年龄未过宽限期, 视为真并发: lockKey={}, projectId={}, episodeId={}",
                    lockKey, projectId, episodeId);
            return AcquireResult.busy();
        }

        log.warn("[CX6] 检测到项目级锁泄漏(年龄超限+DB无活跃任务), CAS清理: lockKey={}, taskType={}",
                lockKey, taskType);
        if (!casDeleteIfMatch(lockKey, existingToken))
        {
            log.info("[CX6] 项目级僵尸锁CAS清理失败(锁已变化), 视为真并发: lockKey={}", lockKey);
            return AcquireResult.busy();
        }

        Boolean reLocked = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockToken, ttlSeconds, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(reLocked))
        {
            log.info("[CX6] 项目级僵尸锁清理后再次被抢占, lockKey={}", lockKey);
            return AcquireResult.busy();
        }
        return AcquireResult.success(lockToken);
    }

    /**
     * DB 复核：是否存在该 {@code (projectId, episodeId, taskType)} 维度下的活跃任务。
     * 活跃判定：status ∈ {PENDING, QUEUED, PROCESSING} 且未删除。
     */
    private boolean hasActiveTaskInDb(String taskType, Long projectId, Long episodeId)
    {
        try
        {
            return extractTaskService.count(
                    Wrappers.<AidExtractTask>lambdaQuery()
                            .eq(AidExtractTask::getProjectId, projectId)
                            .eq(AidExtractTask::getEpisodeId, episodeId)
                            .eq(AidExtractTask::getTaskType, taskType)
                            .in(AidExtractTask::getStatus, STATUS_PENDING, STATUS_QUEUED, STATUS_PROCESSING)
                            .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)) > 0;
        }
        catch (Exception e)
        {
            // DB 复核异常 → 保守按"有活跃任务"处理，宁可放行不了也不要误清锁
            log.warn("项目级锁DB活跃任务复核异常, 保守按真并发处理: taskType={}, projectId={}, episodeId={}",
                    taskType, projectId, episodeId, e);
            return true;
        }
    }

    /**
     * 判断锁 token 是否已超过 SETNX→INSERT 宽限期。
     * 解析失败一律视为可清理（脏数据 / 老格式）。
     */
    private boolean isStaleByAge(String tokenWithTs)
    {
        if (StrUtil.isBlank(tokenWithTs))
        {
            return true;
        }
        try
        {
            long acquiredAt = Long.parseLong(tokenWithTs);
            return System.currentTimeMillis() - acquiredAt > STALE_GRACE_MS;
        }
        catch (NumberFormatException ignored)
        {
            // 老格式（如 "1"）或异常 token：视为脏数据，按可清理处理
            return true;
        }
    }

    /**
     * Lua CAS 仅当锁当前值等于传入 token 时才 DEL，防止误删被他人重抢的新锁。
     *
     * @return true=成功删除（确认是当初读到的同一把脏锁）；false=锁值已变 / 已不存在 / 异常
     */
    @SuppressWarnings("unchecked")
    private boolean casDeleteIfMatch(String key, String existingToken)
    {
        if (StrUtil.isBlank(existingToken))
        {
            return false;
        }
        try
        {
            Object ret = redisCache.redisTemplate.execute(
                    CAS_DEL_SCRIPT,
                    Collections.singletonList(key),
                    existingToken);
            return Objects.nonNull(ret) && ((Number) ret).longValue() > 0L;
        }
        catch (Exception e)
        {
            log.warn("项目级僵尸锁CAS清理异常: key={}, msg={}", key, e.getMessage());
            return false;
        }
    }

    /**
     * CAS 释放锁：仅当锁当前值仍等于持锁 token 时才 DEL，避免误删他人重抢的新锁。
     *
     * @param lockKey  锁 key
     * @param token    抢锁时 {@link AcquireResult#getToken()} 返回的 token，可空（空时走裸 DEL 兼容老调用）
     */
    public void releaseIfMatch(String lockKey, String token)
    {
        if (StrUtil.isBlank(lockKey))
        {
            return;
        }
        if (StrUtil.isBlank(token))
        {
            // 兼容：调用方未传 token（如 Consumer 跨进程释放），退化为裸 DEL
            try { redisCache.deleteObject(lockKey); }
            catch (Exception e) { log.warn("项目级锁裸 DEL 释放异常: key={}, msg={}", lockKey, e.getMessage()); }
            return;
        }
        // 复用 casDeleteIfMatch（返回值在此场景不关键，删不掉说明锁已被自愈清理或被他人重抢，无需感知）
        casDeleteIfMatch(lockKey, token);
    }
}
