package com.aid.compose.service;

import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.aid.compose.domain.ComposePendingContext;
import com.aid.rps.queue.BatchTaskSlotReservation;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 接口1 合成批次的 Redis 暂存与并发控制。
 * 统一用 {@link StringRedisTemplate}（纯字符串序列化）读写，与既有任务队列 Key 一致。
 * 负责：待触发上下文暂存/读取/清理、已触发/已失败标记，
 * 保证同一 composeBatchId 仅触发一次合成。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComposeBatchStore {

    /** 待触发上下文 Key 前缀 */
    private static final String CTX_PREFIX = "compose:ctx:";

    /** 已触发标记 Key 前缀 */
    private static final String TRIGGERED_PREFIX = "compose:triggered:";

    /** 已失败标记 Key 前缀 */
    private static final String FAILED_PREFIX = "compose:failed:";

    /** 一键配音逻辑槽凭证（无 TTL，直到业务终态按 owner CAS 清理） */
    private static final String VOICEOVER_SLOT_PREFIX = "compose:batch:slot:";

    /** 剧集导出逻辑槽凭证（无 TTL，直到业务终态按 owner CAS 清理） */
    private static final String EXPORT_SLOT_PREFIX = "compose:export:slot:";

    /** 导出受理锁 Key 前缀（接口2：按剪辑记录防并发重复提交） */
    private static final String EXPORT_LOCK_PREFIX = "compose:export:lock:";

    /** 上下文/标记 TTL（小时）：覆盖配音最长等待窗口 */
    private static final long CTX_TTL_HOURS = 24L;

    /** 导出受理锁 TTL（秒）：覆盖「素材探测(最坏逐条 5s 超时) + 预冻结 + 任务落库」的受理窗口，留足余量 */
    private static final long EXPORT_LOCK_TTL_SECONDS = 180L;

    /** Redis 客户端 */
    private final StringRedisTemplate stringRedisTemplate;

    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> DELETE_SLOT_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then "
                            + "return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    /**
     * 暂存待触发上下文。
     *
     * @param context 上下文
     */
    public void saveContext(ComposePendingContext context) {
        if (context == null || StrUtil.isBlank(context.getComposeBatchId())) {
            return;
        }
        stringRedisTemplate.opsForValue().set(CTX_PREFIX + context.getComposeBatchId(),
                JSON.toJSONString(context), CTX_TTL_HOURS, TimeUnit.HOURS);
    }

    /**
     * 读取待触发上下文。
     *
     * @param batchId 批次号
     * @return 上下文，不存在/解析失败返回 null
     */
    public ComposePendingContext getContext(String batchId) {
        if (StrUtil.isBlank(batchId)) {
            return null;
        }
        String raw = stringRedisTemplate.opsForValue().get(CTX_PREFIX + batchId);
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            return JSON.parseObject(raw, ComposePendingContext.class);
        } catch (Exception e) {
            log.error("合成待触发上下文解析失败, batchId={}", batchId, e);
            return null;
        }
    }

    /**
     * 标记已触发（仅首次成功，幂等保证）。
     *
     * @param batchId 批次号
     * @return true=本次首次标记成功（可触发合成）
     */
    public boolean markTriggered(String batchId) {
        Boolean ok = stringRedisTemplate.opsForValue()
                .setIfAbsent(TRIGGERED_PREFIX + batchId, "1", CTX_TTL_HOURS, TimeUnit.HOURS);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 标记该批配音失败（批内任一配音失败则不再合成）。
     *
     * @param batchId 批次号
     */
    public void markFailed(String batchId) {
        stringRedisTemplate.opsForValue().set(FAILED_PREFIX + batchId, "1", CTX_TTL_HOURS, TimeUnit.HOURS);
    }

    /**
     * 是否已标记失败。
     *
     * @param batchId 批次号
     * @return true=已失败
     */
    public boolean isFailed(String batchId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(FAILED_PREFIX + batchId));
    }

    /**
     * 清理上下文（触发成功后）。
     *
     * @param batchId 批次号
     */
    public void clearContext(String batchId) {
        try {
            stringRedisTemplate.delete(CTX_PREFIX + batchId);
        } catch (Exception e) {
            log.warn("清理合成上下文异常, batchId={}", batchId, e);
        }
    }

    public void saveVoiceoverSlot(String batchId, BatchTaskSlotReservation reservation) {
        saveSlot(VOICEOVER_SLOT_PREFIX + batchId, reservation);
    }

    public BatchTaskSlotReservation getVoiceoverSlot(String batchId) {
        return getSlot(VOICEOVER_SLOT_PREFIX + batchId);
    }

    public void clearVoiceoverSlot(String batchId, BatchTaskSlotReservation reservation) {
        clearSlot(VOICEOVER_SLOT_PREFIX + batchId, reservation);
    }

    public void saveExportSlot(Long episodeEditorId, BatchTaskSlotReservation reservation) {
        saveSlot(EXPORT_SLOT_PREFIX + episodeEditorId, reservation);
    }

    public BatchTaskSlotReservation getExportSlot(Long episodeEditorId) {
        return getSlot(EXPORT_SLOT_PREFIX + episodeEditorId);
    }

    public void clearExportSlot(Long episodeEditorId, BatchTaskSlotReservation reservation) {
        clearSlot(EXPORT_SLOT_PREFIX + episodeEditorId, reservation);
    }

    private void saveSlot(String key, BatchTaskSlotReservation reservation) {
        if (StrUtil.isBlank(key) || reservation == null || StrUtil.isBlank(reservation.ownerToken())) {
            throw new IllegalArgumentException("slot reservation is required");
        }
        // 保存 owner 原文，凭证其它字段由 value JSON 恢复；另设 owner key 会引入跨 key 原子问题，
        // 因此把 owner 放在 JSON 前缀中，CAS 删除时比较完整 value。
        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(reservation));
    }

    private BatchTaskSlotReservation getSlot(String key) {
        String raw = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            return JSON.parseObject(raw, BatchTaskSlotReservation.class);
        } catch (Exception ex) {
            log.error("合成逻辑槽凭证解析失败, key={}", key, ex);
            throw ex;
        }
    }

    private void clearSlot(String key, BatchTaskSlotReservation reservation) {
        if (reservation == null) {
            return;
        }
        try {
            stringRedisTemplate.execute(DELETE_SLOT_SCRIPT, java.util.List.of(key),
                    JSON.toJSONString(reservation));
        } catch (Exception ex) {
            log.warn("清理合成逻辑槽凭证异常, key={}", key, ex);
        }
    }

    /**
     * 抢占导出受理锁（SETNX + TTL）：同一剪辑记录的导出受理串行化，
     * 防止并发双击/多开窗口在「防重查库」与「置合成中」之间的窗口期重复提交、重复冻结。
     *
     * @param episodeEditorId 剪辑记录ID
     * @return true=抢到锁
     */
    public boolean tryExportLock(Long episodeEditorId) {
        Boolean ok = stringRedisTemplate.opsForValue()
                .setIfAbsent(EXPORT_LOCK_PREFIX + episodeEditorId, "1",
                        EXPORT_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 释放导出受理锁。
     *
     * @param episodeEditorId 剪辑记录ID
     */
    public void unlockExport(Long episodeEditorId) {
        try {
            stringRedisTemplate.delete(EXPORT_LOCK_PREFIX + episodeEditorId);
        } catch (Exception e) {
            log.warn("释放导出受理锁异常, episodeEditorId={}", episodeEditorId, e);
        }
    }
}
