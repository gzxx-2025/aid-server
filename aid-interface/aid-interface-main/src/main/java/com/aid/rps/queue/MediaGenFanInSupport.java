package com.aid.rps.queue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.common.core.redis.RedisCache;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 媒体生成批量任务「非阻塞事件驱动扇入」通用支撑组件，抽取失败计数/收尾 CAS/bizSeq 反解等公共设施。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class MediaGenFanInSupport
{
    /** 父任务在 bizSeq 编码中的步长。 */
    public static final long BIZ_SEQ_PARENT_FACTOR = 1_000_000L;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 失败计数 Redis key 前缀 */
    private static final String REDIS_FAIL_PREFIX = "media:fanin:fail:";
    /** 收尾幂等标记 Redis key 前缀 */
    private static final String REDIS_FINAL_PREFIX = "media:fanin:final:";
    /** 扇入计数 / 标记 TTL（秒）：覆盖批量最长存活 + 余量 */
    private static final long FANIN_TTL_SECONDS = 6L * 3600L;

    @Resource
    private RedisCache redisCache;

    @Resource
    private TaskLeaseManager leaseManager;

    @Resource
    private AidMediaTaskMapper aidMediaTaskMapper;

    /** 扇入对账收尾依赖的业务收尾入口（{@code @Lazy} 打破与生成 Service 的循环依赖） */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.aid.storyboard.service.IStoryboardImageGenerationService imageGenerationService;

    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.aid.storyboard.service.IStoryboardVideoGenerationService videoGenerationService;

    /** 出图/出视频等「非阻塞扇入型」任务的 biz_task_type 白名单 */
    private static final java.util.Set<String> FANIN_BIZ_TASK_TYPES = java.util.Set.of(
            "storyboard_image_generate", "storyboard_video_generate");

    /** bizSeq 反解父任务 ID */
    public Long decodeParentTaskId(long bizSeq)
    {
        return bizSeq / BIZ_SEQ_PARENT_FACTOR;
    }

    /**
     * media 调度中心轮询子任务时调用：扇入型子任务则给其父 extract 任务续租，防止父任务租约过期被误判失败退款。
     */
    public void renewParentLeaseIfFanIn(String bizTaskType, Long bizTaskId)
    {
        if (Objects.isNull(bizTaskId) || bizTaskType == null || !FANIN_BIZ_TASK_TYPES.contains(bizTaskType))
        {
            return;
        }
        try
        {
            leaseManager.touchLease(decodeParentTaskId(bizTaskId));
        }
        catch (Exception e)
        {
            log.warn("媒体扇入父任务续租异常(不阻断): bizTaskId={}", bizTaskId, e);
        }
    }

    /** 是否属于扇入型生成任务类型（出图/出片） */
    public boolean isFanInTaskType(String taskType)
    {
        return taskType != null && FANIN_BIZ_TASK_TYPES.contains(taskType);
    }

    /**
     * 父任务是否仍有「在途」media 子任务（含 media 层排队未轮询）；查询异常保守返回 true，杜绝重复扣费。
     */
    public boolean hasInflightMedia(Long parentTaskId)
    {
        if (Objects.isNull(parentTaskId)) { return false; }
        try
        {
            long lo = Math.multiplyExact(parentTaskId, BIZ_SEQ_PARENT_FACTOR);
            long hi = Math.addExact(lo, BIZ_SEQ_PARENT_FACTOR);
            LambdaQueryWrapper<AidMediaTask> w = new LambdaQueryWrapper<>();
            w.ge(AidMediaTask::getBizTaskId, lo);
            w.lt(AidMediaTask::getBizTaskId, hi);
            w.in(AidMediaTask::getBizTaskType, FANIN_BIZ_TASK_TYPES);
            // 在途 = 既非 FAILED，也非「SUCCEEDED 且 oss_url 已就绪」：
            //   ① 未终态(PENDING/QUEUED/WAIT_POLL/WAIT_CALLBACK/PROCESSING) → 在途；
            //   ② SUCCEEDED 但 oss_url 为空 → OSS 持久化未完成（事件未发），仍在途，避免慢 OSS 下父任务被误回收。
            w.ne(AidMediaTask::getStatus, "FAILED");
            w.and(q -> q.ne(AidMediaTask::getStatus, "SUCCEEDED")
                    .or().isNull(AidMediaTask::getOssUrl)
                    .or().eq(AidMediaTask::getOssUrl, ""));
            Long cnt = aidMediaTaskMapper.selectCount(w);
            return cnt != null && cnt > 0;
        }
        catch (Exception e)
        {
            log.warn("媒体扇入在途子任务查询异常(保守按在途处理,不回收): parentTaskId={}", parentTaskId, e);
            return true;
        }
    }

    /** 给父任务续租（供僵尸回收守卫在确认仍有在途子任务时刷新租约） */
    public void renewParentLease(Long parentTaskId)
    {
        if (Objects.isNull(parentTaskId)) { return; }
        try { leaseManager.touchLease(parentTaskId); }
        catch (Exception e) { log.warn("媒体扇入父任务续租异常(不阻断): parentTaskId={}", parentTaskId, e); }
    }

    /**
     * 扇入孤儿对账收尾（幂等）：租约失活且无在途子任务却仍卡 PROCESSING 时，按子任务记录幂等重放终态事件。
     *
     * @param parentTaskId 父任务 ID（aid_extract_task.id）
     * @param taskType     父任务类型
     */
    public void reconcileFanInParent(Long parentTaskId, String taskType)
    {
        if (Objects.isNull(parentTaskId) || !isFanInTaskType(taskType)) { return; }
        List<AidMediaTask> subs;
        try
        {
            long lo = Math.multiplyExact(parentTaskId, BIZ_SEQ_PARENT_FACTOR);
            long hi = Math.addExact(lo, BIZ_SEQ_PARENT_FACTOR);
            LambdaQueryWrapper<AidMediaTask> w = new LambdaQueryWrapper<>();
            w.ge(AidMediaTask::getBizTaskId, lo);
            w.lt(AidMediaTask::getBizTaskId, hi);
            w.in(AidMediaTask::getBizTaskType, FANIN_BIZ_TASK_TYPES);
            subs = aidMediaTaskMapper.selectList(w);
        }
        catch (Exception e)
        {
            log.warn("扇入对账查询子任务异常(放弃对账): parentTaskId={}", parentTaskId, e);
            return;
        }
        if (subs == null || subs.isEmpty()) { return; }
        boolean isVideo = "storyboard_video_generate".equals(taskType);
        for (AidMediaTask mt : subs)
        {
            try
            {
                String st = mt.getStatus();
                boolean succeeded = "SUCCEEDED".equals(st) && StrUtil.isNotBlank(mt.getOssUrl());
                boolean failed = "FAILED".equals(st);
                // 仍在途（未终态）→ 不重放（调用前应已确认无在途，这里二次保护）
                if (!succeeded && !failed) { continue; }
                if (isVideo)
                {
                    if (videoGenerationService != null)
                    {
                        videoGenerationService.onMediaVideoTaskTerminal(mt.getId(), succeeded, succeeded ? mt.getOssUrl() : null);
                    }
                }
                else if (imageGenerationService != null)
                {
                    imageGenerationService.onMediaImageTaskTerminal(mt.getId(), succeeded, succeeded ? mt.getOssUrl() : null);
                }
            }
            catch (Exception e)
            {
                log.warn("扇入对账重放子任务事件异常(忽略单条): parentTaskId={}, mediaTaskId={}", parentTaskId, mt.getId(), e);
            }
        }
        log.warn("扇入孤儿对账收尾已执行: parentTaskId={}, taskType={}, 子任务数={}", parentTaskId, taskType, subs.size());
    }

    /** 失败计数 +1（durable，首个 +1 时设 TTL）。 */
    public void incrFail(Long taskId)
    {
        if (Objects.isNull(taskId)) { return; }
        try
        {
            String key = REDIS_FAIL_PREFIX + taskId;
            Long v = redisCache.redisTemplate.opsForValue().increment(key);
            if (v != null && v == 1L)
            {
                redisCache.redisTemplate.expire(key, FANIN_TTL_SECONDS, TimeUnit.SECONDS);
            }
        }
        catch (Exception e)
        {
            log.warn("媒体扇入失败计数异常(不阻断): taskId={}", taskId, e);
        }
    }

    /** 读取失败计数（读失败按 0）。 */
    public int getFailCount(Long taskId)
    {
        if (Objects.isNull(taskId)) { return 0; }
        try
        {
            Object v = redisCache.redisTemplate.opsForValue().get(REDIS_FAIL_PREFIX + taskId);
            return v == null ? 0 : Integer.parseInt(String.valueOf(v));
        }
        catch (Exception e)
        {
            log.warn("媒体扇入失败计数读取异常(按0): taskId={}", taskId, e);
            return 0;
        }
    }

    /**
     * CAS 抢占收尾权：仅首个调用返回 true，保证并发事件下唯一一次收尾。
     */
    public boolean tryWinFinalize(Long taskId)
    {
        try
        {
            Boolean won = redisCache.redisTemplate.opsForValue()
                    .setIfAbsent(REDIS_FINAL_PREFIX + taskId, "1", FANIN_TTL_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(won);
        }
        catch (Exception e)
        {
            log.warn("媒体扇入收尾CAS异常(按未抢到): taskId={}", taskId, e);
            return false;
        }
    }

    /** 收尾完成后清理扇入键（失败计数 + 收尾标记一并清理，避免续生重跑时抢不到收尾权） */
    public void cleanup(Long taskId)
    {
        if (Objects.isNull(taskId)) { return; }
        try { redisCache.redisTemplate.delete(REDIS_FAIL_PREFIX + taskId); }
        catch (Exception ignore) { /* ignore */ }
        try { redisCache.redisTemplate.delete(REDIS_FINAL_PREFIX + taskId); }
        catch (Exception ignore) { /* ignore */ }
    }

    /** 从父任务 input_snapshot 解析 storyboardIds。 */
    public List<Long> parseStoryboardIds(String inputSnapshot)
    {
        List<Long> ids = new ArrayList<>();
        if (StrUtil.isBlank(inputSnapshot)) { return ids; }
        try
        {
            JsonNode arr = OBJECT_MAPPER.readTree(inputSnapshot).path("storyboardIds");
            if (arr.isArray())
            {
                for (JsonNode n : arr) { if (n.canConvertToLong()) { ids.add(n.asLong()); } }
            }
        }
        catch (Exception e)
        {
            log.warn("媒体扇入解析 storyboardIds 失败: {}", e.getMessage());
        }
        return ids;
    }

    /** 镜头锁快照项：storyboardId + lockToken。 */
    public static final class ShotLockRef
    {
        public final long storyboardId;
        public final String lockToken;

        public ShotLockRef(long storyboardId, String lockToken)
        {
            this.storyboardId = storyboardId;
            this.lockToken = lockToken;
        }
    }

    /** 从父任务 input_snapshot 解析 shots[].(storyboardId, lockToken)，供收尾释放镜头锁。 */
    public List<ShotLockRef> parseShotLocks(String inputSnapshot)
    {
        List<ShotLockRef> refs = new ArrayList<>();
        if (StrUtil.isBlank(inputSnapshot)) { return refs; }
        try
        {
            JsonNode shots = OBJECT_MAPPER.readTree(inputSnapshot).path("shots");
            if (shots.isArray())
            {
                for (JsonNode s : shots)
                {
                    long sid = s.path("storyboardId").asLong(0L);
                    String token = s.path("lockToken").asText(null);
                    if (sid > 0 && StrUtil.isNotBlank(token))
                    {
                        refs.add(new ShotLockRef(sid, token));
                    }
                }
            }
        }
        catch (Exception e)
        {
            log.warn("媒体扇入解析 shots 镜头锁失败: {}", e.getMessage());
        }
        return refs;
    }
}
