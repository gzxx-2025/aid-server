package com.aid.storyboard.listener;

import java.util.Date;
import java.util.Objects;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.aid.aid.domain.AidAudioRecord;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidGenRecordMapper;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidAudioRecordService;
import com.aid.common.utils.DateUtils;
import com.aid.enums.GenTypeEnum;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.enums.MediaType;
import com.aid.media.event.MediaTaskCompletedEvent;
import com.aid.media.event.MediaTaskOssPersistedEvent;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 对口型任务事件监听：把统一媒体任务（media_type=VIDEO 且 biz_task_type=lip_sync_record）的
 * 成功结果回填到 aid_audio_record.sync_video_url，并落一条配音类型生成记录（genType=compose）。
 *
 *   - SUCCEEDED 且 ossUrl 就绪 → 回填 sync_video_url（仅 OSS URL，不落上游临时地址）
 *   - SUCCEEDED 但 ossUrl 未就绪 → 等待 MediaTaskOssPersistedEvent 再回填
 *   - FAILED → 不回写业务表（退款由统一任务侧完成；前端经 AudioTaskVO.lipSyncStatus 派生字段感知失败）
 *
 * 回填带防串写守卫：仅当业务记录当前关联的 sync_media_task_id 为空或等于本任务时才写入，
 * 防止用户重新发起对口型后，旧任务迟到的终态事件覆盖新任务结果。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class LipSyncEventListener {

    /** 业务任务类型标识：与 StoryboardWorkbenchServiceImpl 提交 media_task 时的 bizTaskType 保持一致 */
    private static final String BIZ_TASK_TYPE_LIP_SYNC = "lip_sync_record";
    /** 对口型开启标记 */
    private static final int LIP_SYNC_ENABLED = 1;
    /** 生成记录状态：成功 */
    private static final int GEN_STATUS_SUCCESS = 1;
    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";
    /** 选中标记：未选中（对口型视频不自动设为使用中，由用户手动选择） */
    private static final int SELECTED_NO = 0;

    @Resource
    private AidMediaTaskMapper aidMediaTaskMapper;

    @Resource
    private IAidAudioRecordService aidAudioRecordService;

    @Resource
    private AidGenRecordMapper aidGenRecordMapper;

    /**
     * 媒体任务终态事件：成功且 OSS 就绪时回填对口型视频 URL；OSS 未就绪则等待 OSS 持久化事件。
     */
    @EventListener
    @Order(220)
    public void onMediaTaskCompleted(MediaTaskCompletedEvent event) {
        if (Objects.isNull(event) || Objects.isNull(event.getTaskId())) {
            return;
        }
        AidMediaTask mediaTask = aidMediaTaskMapper.selectById(event.getTaskId());
        if (!isLipSyncBizTask(mediaTask)) {
            return;
        }
        if (MediaTaskStatus.FAILED.name().equals(mediaTask.getStatus())) {
            // 失败不回写业务表：退款走统一任务侧，前端由 lipSyncStatus 派生字段感知
            log.info("LipSyncEventListener 对口型任务失败, audioRecordId={}, mediaTaskId={}",
                    mediaTask.getBizTaskId(), mediaTask.getId());
            return;
        }
        if (!MediaTaskStatus.SUCCEEDED.name().equals(mediaTask.getStatus())) {
            // 非终态不处理
            return;
        }
        if (StrUtil.isBlank(mediaTask.getOssUrl())) {
            // 关键：仅当 ossUrl 就绪才回填业务表，否则等待 MediaTaskOssPersistedEvent，
            // 避免把上游临时签名 URL 当成最终结果落库（过期即失效）。
            log.info("LipSyncEventListener 任务 SUCCEEDED 但 ossUrl 未就绪, 等待 OSS 事件, audioRecordId={}, mediaTaskId={}",
                    mediaTask.getBizTaskId(), mediaTask.getId());
            return;
        }
        fillSyncVideoUrl(mediaTask);
    }

    /**
     * 媒体任务 OSS 持久化完成事件：补偿路径，把 sync_video_url 回填为 OSS URL。
     */
    @EventListener
    @Order(220)
    public void onMediaTaskOssPersisted(MediaTaskOssPersistedEvent event) {
        if (Objects.isNull(event) || Objects.isNull(event.getTaskId())) {
            return;
        }
        AidMediaTask mediaTask = aidMediaTaskMapper.selectById(event.getTaskId());
        if (!isLipSyncBizTask(mediaTask)) {
            return;
        }
        if (StrUtil.isBlank(mediaTask.getOssUrl())) {
            log.warn("LipSyncEventListener ossPersisted 事件 ossUrl 仍为空, mediaTaskId={}", mediaTask.getId());
            return;
        }
        fillSyncVideoUrl(mediaTask);
    }

    /**
     * 回填 aid_audio_record（幂等）：sync_video_url = 任务 OSS URL，并补齐 enable_lip_sync / sync_media_task_id。
     * 防串写守卫：仅当记录当前 sync_media_task_id 为空或等于本任务时才写入，旧任务迟到事件不覆盖新任务结果。
     * 回填生效后同步落一条配音类型生成记录（genType=compose），使对口型视频出现在配音视频列表中。
     */
    private void fillSyncVideoUrl(AidMediaTask mediaTask) {
        boolean updated;
        try {
            LambdaUpdateWrapper<AidAudioRecord> update = new LambdaUpdateWrapper<>();
            update.eq(AidAudioRecord::getId, mediaTask.getBizTaskId());
            // 防串写：记录已关联其它（更新的）对口型任务时跳过本次回填
            update.and(w -> w.isNull(AidAudioRecord::getSyncMediaTaskId)
                    .or().eq(AidAudioRecord::getSyncMediaTaskId, mediaTask.getId()));
            update.set(AidAudioRecord::getSyncVideoUrl, mediaTask.getOssUrl());
            update.set(AidAudioRecord::getEnableLipSync, LIP_SYNC_ENABLED);
            update.set(AidAudioRecord::getSyncMediaTaskId, mediaTask.getId());
            update.set(AidAudioRecord::getUpdateTime, DateUtils.getNowDate());
            updated = aidAudioRecordService.update(update);
            log.info("LipSyncEventListener 对口型视频回填{}, audioRecordId={}, mediaTaskId={}",
                    updated ? "成功" : "跳过(记录不存在或已关联新任务)", mediaTask.getBizTaskId(), mediaTask.getId());
        } catch (Exception ex) {
            // 回填异常只记日志：任务表已有终态与 ossUrl，可由查询侧派生状态兜底展示
            log.error("LipSyncEventListener 对口型视频回填异常, audioRecordId={}, mediaTaskId={}",
                    mediaTask.getBizTaskId(), mediaTask.getId(), ex);
            return;
        }
        if (updated) {
            // 旧任务迟到事件被防串写守卫拦下时不落记录：配音视频列表只保留与业务记录一致的最新对口型产物
            writeLipSyncGenRecord(mediaTask);
        }
    }

    /**
     * 对口型成片落配音类型生成记录（genType=compose，幂等）：
     * 与一键/批量配音的合成视频同轨展示于配音视频列表（type=compose）；
     * 不自动设为使用中（is_selected=0），由用户手动选择，不影响原视频轨与 final_video_id。
     * 幂等按「分镜 + genType=compose + 成片地址」去重（Completed 与 OssPersisted 双事件可能重复触发）。
     *
     * @param mediaTask 已成功且 ossUrl 就绪的对口型任务
     */
    private void writeLipSyncGenRecord(AidMediaTask mediaTask) {
        try {
            // 查询字段精简：落记录仅需分镜归属（新增使用字段时此处必须同步补充）
            AidAudioRecord audioRecord = aidAudioRecordService.getOne(
                    new LambdaQueryWrapper<AidAudioRecord>()
                            .select(AidAudioRecord::getId, AidAudioRecord::getStoryboardId,
                                    AidAudioRecord::getProjectId, AidAudioRecord::getEpisodeId)
                            .eq(AidAudioRecord::getId, mediaTask.getBizTaskId())
                            .last("LIMIT 1"), false);
            if (Objects.isNull(audioRecord) || Objects.isNull(audioRecord.getStoryboardId())) {
                log.warn("LipSyncEventListener 配音记录缺失或无分镜归属,跳过落生成记录, audioRecordId={}, mediaTaskId={}",
                        mediaTask.getBizTaskId(), mediaTask.getId());
                return;
            }
            // 幂等：同分镜同成片地址只落一条（双事件重复触发 / OSS 补偿重发场景）
            Long existed = aidGenRecordMapper.selectCount(new LambdaQueryWrapper<AidGenRecord>()
                    .eq(AidGenRecord::getStoryboardId, audioRecord.getStoryboardId())
                    .eq(AidGenRecord::getGenType, GenTypeEnum.COMPOSE.getValue())
                    .eq(AidGenRecord::getFileUrl, mediaTask.getOssUrl())
                    .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL));
            if (Objects.nonNull(existed) && existed > 0) {
                return;
            }
            AidGenRecord record = new AidGenRecord();
            record.setUserId(mediaTask.getUserId());
            record.setProjectId(Objects.nonNull(mediaTask.getProjectId())
                    ? mediaTask.getProjectId() : audioRecord.getProjectId());
            record.setEpisodeId(Objects.nonNull(mediaTask.getEpisodeId())
                    ? mediaTask.getEpisodeId() : audioRecord.getEpisodeId());
            record.setStoryboardId(audioRecord.getStoryboardId());
            record.setGenType(GenTypeEnum.COMPOSE.getValue());
            record.setFileUrl(mediaTask.getOssUrl());
            record.setTaskId(mediaTask.getProviderTaskId());
            record.setStatus(GEN_STATUS_SUCCESS);
            record.setVideoDuration(mediaTask.getOutputDurationSeconds());
            record.setIsSelected(SELECTED_NO);
            record.setDelFlag(DEL_FLAG_NORMAL);
            record.setCreateTime(new Date());
            record.setCreateBy(Objects.isNull(mediaTask.getUserId()) ? "" : String.valueOf(mediaTask.getUserId()));
            aidGenRecordMapper.insert(record);
            log.info("LipSyncEventListener 对口型成片落配音生成记录, genRecordId={}, storyboardId={}, mediaTaskId={}",
                    record.getId(), audioRecord.getStoryboardId(), mediaTask.getId());
        } catch (Exception ex) {
            // 落记录属于展示增强：异常不影响 sync_video_url 主回填链路
            log.error("LipSyncEventListener 对口型成片落生成记录异常, audioRecordId={}, mediaTaskId={}",
                    mediaTask.getBizTaskId(), mediaTask.getId(), ex);
        }
    }

    /**
     * 统一判定：本次事件是否对应对口型业务任务（VIDEO + lip_sync_record + 关联业务 ID）。
     */
    private boolean isLipSyncBizTask(AidMediaTask mediaTask) {
        if (Objects.isNull(mediaTask)) {
            return false;
        }
        if (!Objects.equals(MediaType.VIDEO.name(), mediaTask.getMediaType())) {
            return false;
        }
        if (!BIZ_TASK_TYPE_LIP_SYNC.equals(mediaTask.getBizTaskType())
                || Objects.isNull(mediaTask.getBizTaskId())) {
            return false;
        }
        return true;
    }
}
