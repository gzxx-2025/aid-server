package com.aid.compose.listener;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.aid.aid.domain.AidAudioRecord;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidAudioRecordMapper;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.compose.ComposeConstants;
import com.aid.compose.domain.ComposeCommand;
import com.aid.compose.domain.ComposeGroup;
import com.aid.compose.domain.ComposePendingContext;
import com.aid.compose.service.ComposeBatchStore;
import com.aid.compose.service.ComposeBatchSlotCoordinator;
import com.aid.compose.service.CoreComposeService;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.event.MediaTaskOssPersistedEvent;
import com.aid.media.util.AudioDurationProber;
import com.aid.rps.queue.BatchParentSubmissionGuard;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 接口1 配音就绪监听器：监听 {@link MediaTaskOssPersistedEvent}，按 composeBatchId 聚合判齐后触发核心合成。
 * 顺序 @Order(300) 在 {@code AudioRecordEventListener}(@Order(200)) 之后执行，确保本批配音记录的
 * status/audio_url 已被回填为 SUCCEEDED 后再判齐。Redis 分布式锁 + 已触发标记保证同批仅触发一次合成；
 * 批内任一配音 FAILED 则标记失败、跳过合成（保留已发起配音）；未齐保持等待。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@Order(300)
@RequiredArgsConstructor
public class ComposeAudioReadyListener {

    /** 业务任务类型：配音记录 */
    private static final String BIZ_TYPE_AUDIO_RECORD = ComposeConstants.BIZ_TASK_TYPE_AUDIO_RECORD;

    /** 毫秒 → 秒换算 */
    private static final double MS_PER_SECOND = 1000.0;

    /** 媒体任务 Mapper */
    private final AidMediaTaskMapper aidMediaTaskMapper;

    /** 配音记录 Mapper */
    private final AidAudioRecordMapper aidAudioRecordMapper;

    /** 合成批次 Redis 暂存/并发控制 */
    private final ComposeBatchStore composeBatchStore;

    private final ComposeBatchSlotCoordinator composeBatchSlotCoordinator;

    private final BatchParentSubmissionGuard batchParentSubmissionGuard;

    /** 核心合成方法 */
    private final CoreComposeService coreComposeService;

    /** 媒体 URL 解析器：相对路径 → 完整 URL */
    private final MediaUrlResolver mediaUrlResolver;

    @EventListener
    @Order(300)
    public void onMediaTaskOssPersisted(MediaTaskOssPersistedEvent event) {
        if (Objects.isNull(event) || Objects.isNull(event.getTaskId())) {
            return;
        }
        try {
            AidMediaTask mediaTask = aidMediaTaskMapper.selectById(event.getTaskId());
            if (Objects.isNull(mediaTask) || !BIZ_TYPE_AUDIO_RECORD.equalsIgnoreCase(mediaTask.getBizTaskType())
                    || Objects.isNull(mediaTask.getBizTaskId())) {
                return;
            }
            AidAudioRecord audioRecord = aidAudioRecordMapper.selectById(mediaTask.getBizTaskId());
            if (Objects.isNull(audioRecord) || StrUtil.isBlank(audioRecord.getComposeBatchId())) {
                // 非接口1 合成批次的普通配音，忽略
                return;
            }
            handleBatch(audioRecord.getComposeBatchId());
        } catch (Exception ex) {
            log.error("ComposeAudioReadyListener 处理事件异常, taskId={}", event.getTaskId(), ex);
        }
    }

    /**
     * 判齐并触发合成。
     *
     * @param batchId 合成批次号
     */
    private void handleBatch(String batchId) {
        try {
            batchParentSubmissionGuard.execute("voiceover:" + batchId, () -> {
                handleBatchLocked(batchId);
                return Boolean.TRUE;
            });
        } catch (Exception ex) {
            log.error("接口1 合成触发锁定异常, batchId={}", batchId, ex);
        }
    }

    /** watchdog submission guard 内复核业务终态并同步受理合成。 */
    private void handleBatchLocked(String batchId) {
        List<AidAudioRecord> records = listBatchRecords(batchId);
        if (CollectionUtil.isEmpty(records)) {
            return;
        }
        AidMediaTask persistedComposeTask = findPersistedComposeTask(batchId);
        if (Objects.nonNull(persistedComposeTask)) {
            try {
                composeBatchStore.markTriggered(batchId);
            } catch (Exception markerEx) {
                log.warn("接口1 已受理合成标记写入失败, batchId={}, err={}", batchId, markerEx.getMessage());
            }
            if (MediaTaskStatus.FAILED.name().equals(persistedComposeTask.getStatus())) {
                persistBatchFailed(batchId, "合成失败");
                markFailedBestEffort(batchId);
                composeBatchSlotCoordinator.releaseVoiceover(batchId);
            }
            // 任意持久化 COMPOSE 记录均说明同步受理已经越过边界；活跃/成功任务等待统一终态与 OSS 回写。
            return;
        }
        boolean anyFailed = records.stream()
                .anyMatch(r -> MediaTaskStatus.FAILED.name().equals(r.getStatus()));
        if (anyFailed) {
            markFailedBestEffort(batchId);
            composeBatchSlotCoordinator.releaseVoiceover(batchId);
            log.info("接口1 批内配音失败, 跳过合成, batchId={}", batchId);
            return;
        }
        boolean allSucceeded = records.stream()
                .allMatch(r -> MediaTaskStatus.SUCCEEDED.name().equals(r.getStatus()));
        if (!allSucceeded) {
            return;
        }
        try {
            ComposePendingContext context = composeBatchStore.getContext(batchId);
            if (Objects.isNull(context) || CollectionUtil.isEmpty(context.getItems())) {
                // 上下文缺失（Redis 过期/丢失）永远无法合成：标记失败让进度查询收敛终态，避免批次永久卡"合成中"
                persistBatchFailed(batchId, "合成上下文缺失");
                markFailedBestEffort(batchId);
                composeBatchSlotCoordinator.releaseVoiceover(batchId);
                log.error("接口1 合成上下文缺失,已标记批次失败, batchId={}", batchId);
                return;
            }
            ComposeCommand command = buildCommand(context, records);
            try {
                composeBatchStore.markTriggered(batchId);
            } catch (Exception markerEx) {
                // Redis 标记只用于加速；watchdog guard + 持久 COMPOSE 任务才是幂等边界。
                log.warn("接口1 合成触发标记写入失败, batchId={}, err={}", batchId, markerEx.getMessage());
            }
            coreComposeService.compose(command);
            try {
                composeBatchStore.clearContext(batchId);
            } catch (Exception clearEx) {
                log.warn("接口1 合成上下文清理失败, batchId={}, err={}", batchId, clearEx.getMessage());
            }
            log.info("接口1 配音齐全, 触发合成, batchId={}, groups={}", batchId, command.getGroups().size());
        } catch (Exception ex) {
            AidMediaTask acceptedComposeTask = findPersistedComposeTask(batchId);
            if (Objects.nonNull(acceptedComposeTask)) {
                // 同步调用抛错但任务已落库时，以统一媒体任务的真实终态为准，不能把已受理任务伪造失败。
                log.warn("接口1 合成调用异常但任务已受理, batchId={}, mediaTaskId={}, status={}",
                        batchId, acceptedComposeTask.getId(), acceptedComposeTask.getStatus());
                return;
            }
            // 未产生持久媒体任务才收口业务失败；DB 是 Redis 丢失后的失败真源。
            persistBatchFailed(batchId, "合成失败");
            markFailedBestEffort(batchId);
            composeBatchSlotCoordinator.releaseVoiceover(batchId);
            log.error("接口1 触发合成异常,已标记批次失败, batchId={}", batchId, ex);
        }
    }

    private void markFailedBestEffort(String batchId) {
        try {
            composeBatchStore.markFailed(batchId);
        } catch (Exception ex) {
            log.warn("接口1 合成失败标记写入异常, batchId={}, err={}", batchId, ex.getMessage());
        }
    }

    /**
     * 查询同批配音记录。
     * 查询字段精简：仅 select 判齐与装配合成指令必需列（id/storyboard_id/status/audio_url/duration_ms），
     * 不拉 tts_text 等长文本列；后续扩展取数请同步增列。
     *
     * @param batchId 批次号
     * @return 配音记录列表
     */
    private List<AidAudioRecord> listBatchRecords(String batchId) {
        LambdaQueryWrapper<AidAudioRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(AidAudioRecord::getId, AidAudioRecord::getStoryboardId,
                AidAudioRecord::getStatus, AidAudioRecord::getAudioUrl, AidAudioRecord::getDurationMs);
        wrapper.eq(AidAudioRecord::getComposeBatchId, batchId);
        wrapper.orderByAsc(AidAudioRecord::getId);
        return aidAudioRecordMapper.selectList(wrapper);
    }

    private AidMediaTask findPersistedComposeTask(String batchId) {
        LambdaQueryWrapper<AidMediaTask> query = new LambdaQueryWrapper<>();
        query.select(AidMediaTask::getId, AidMediaTask::getStatus, AidMediaTask::getOssUrl);
        query.eq(AidMediaTask::getComposeBatchId, batchId);
        query.eq(AidMediaTask::getMediaType, ComposeConstants.MEDIA_TYPE_COMPOSE);
        query.orderByDesc(AidMediaTask::getId);
        query.last("LIMIT 1");
        return aidMediaTaskMapper.selectOne(query);
    }

    /** 把“全配音成功但无法创建合成”的失败持久化，供 Redis 丢失后的 DB 判活恢复。 */
    private void persistBatchFailed(String batchId, String errorMessage) {
        LambdaUpdateWrapper<AidAudioRecord> update = new LambdaUpdateWrapper<>();
        update.eq(AidAudioRecord::getComposeBatchId, batchId);
        update.set(AidAudioRecord::getStatus, MediaTaskStatus.FAILED.name());
        update.set(AidAudioRecord::getErrorMessage, errorMessage);
        update.set(AidAudioRecord::getUpdateTime, new Date());
        aidAudioRecordMapper.update(null, update);
    }

    /**
     * 装配 ComposeCommand：用暂存上下文（分镜视频）叠加 aid_audio_record（配音URL/时长）。
     * 纯配音合成：不设字幕、不设背景音乐（由成片合成导出阶段处理）。
     *
     * @param context 待触发上下文
     * @param records 同批配音记录
     * @return 合成指令
     */
    private ComposeCommand buildCommand(ComposePendingContext context, List<AidAudioRecord> records) {
        Map<Long, AidAudioRecord> recordById = new HashMap<>();
        for (AidAudioRecord r : records) {
            recordById.put(r.getId(), r);
        }
        List<ComposeGroup> groups = new ArrayList<>();
        for (ComposePendingContext.Item item : context.getItems()) {
            ComposeGroup group = new ComposeGroup();
            List<String> videoUrls = new ArrayList<>();
            videoUrls.add(item.getVideoUrl());
            group.setVideoUrls(videoUrls);
            List<Double> videoDurations = new ArrayList<>();
            videoDurations.add(item.getVideoDuration());
            group.setVideoDurations(videoDurations);
            // 配音：有 audioRecordId 才填配音轨，否则该组无配音（合成时补 Empty）
            if (Objects.nonNull(item.getAudioRecordId())) {
                AidAudioRecord record = recordById.get(item.getAudioRecordId());
                if (Objects.nonNull(record) && StrUtil.isNotBlank(record.getAudioUrl())) {
                    String fullAudioUrl = mediaUrlResolver.toFullUrl(record.getAudioUrl());
                    List<String> audioUrls = new ArrayList<>();
                    audioUrls.add(fullAudioUrl);
                    group.setAudioUrls(audioUrls);
                    List<Double> audioDurations = new ArrayList<>();
                    // 配音真实时长是段对齐的基准：厂商未回传（豆包等 duration_ms=null）时下载探测并回填，
                    // 缺失会导致该段被误判"无配音时长"，成片音画错位、片尾黑屏
                    Integer durationMs = resolveDurationMs(record, fullAudioUrl);
                    audioDurations.add(Objects.isNull(durationMs) ? null : durationMs / MS_PER_SECOND);
                    group.setAudioDurations(audioDurations);
                }
            }
            groups.add(group);
        }

        ComposeCommand command = new ComposeCommand();
        command.setGroups(groups);
        command.setUserId(context.getUserId());
        command.setProjectId(context.getProjectId());
        command.setEpisodeId(context.getEpisodeId());
        command.setResolution(context.getResolution());
        command.setAlignStrategy(context.getAlignStrategy());
        command.setComposeBatchId(context.getComposeBatchId());
        command.setCallbackCategory(ComposeConstants.CALLBACK_GEN_RECORD);
        command.setCallbackRecordId(resolveFirstStoryboardId(records));
        return command;
    }

    private Long resolveFirstStoryboardId(List<AidAudioRecord> records) {
        if (CollectionUtil.isEmpty(records)) {
            return null;
        }
        for (AidAudioRecord record : records) {
            if (Objects.nonNull(record) && Objects.nonNull(record.getStoryboardId())) {
                return record.getStoryboardId();
            }
        }
        return null;
    }

    /**
     * 解析配音真实时长（毫秒）：库有值直接用；缺失则下载音频探测（mp3/wav 帧解析），
     * 探测成功回填 aid_audio_record.duration_ms（后续合成/对口型复用，不再重复下载）。
     *
     * @param record       配音记录
     * @param fullAudioUrl 完整可下载 URL
     * @return 时长毫秒；探测失败返回 null（合成层按"时长未知"保守处理）
     */
    private Integer resolveDurationMs(AidAudioRecord record, String fullAudioUrl) {
        if (Objects.nonNull(record.getDurationMs()) && record.getDurationMs() > 0) {
            return record.getDurationMs();
        }
        Integer probed = AudioDurationProber.probeDurationMs(fullAudioUrl);
        if (Objects.isNull(probed) || probed <= 0) {
            log.warn("接口1 配音时长探测失败, audioRecordId={}", record.getId());
            return null;
        }
        try {
            LambdaUpdateWrapper<AidAudioRecord> update = new LambdaUpdateWrapper<>();
            update.eq(AidAudioRecord::getId, record.getId());
            update.set(AidAudioRecord::getDurationMs, probed);
            update.set(AidAudioRecord::getUpdateTime, new Date());
            aidAudioRecordMapper.update(null, update);
        } catch (Exception ex) {
            // 回填失败不影响本次合成（时长已拿到），下次触发再探测
            log.warn("接口1 配音时长回填失败, audioRecordId={}, err={}", record.getId(), ex.getMessage());
        }
        log.info("接口1 配音时长探测回填, audioRecordId={}, durationMs={}", record.getId(), probed);
        return probed;
    }
}
