package com.aid.compose.service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidAudioRecord;
import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidAudioRecordMapper;
import com.aid.aid.mapper.AidEpisodeEditorMapper;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.compose.ComposeConstants;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.rps.queue.BatchTaskExternalActivityProbe;
import com.aid.rps.queue.BatchTaskLogicalType;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;

/**
 * 旧一键配音与剧集导出的数据库判活器。
 *
 * @author 视觉AID
 */
@Component
@RequiredArgsConstructor
public class ComposeBatchActivityProbe implements BatchTaskExternalActivityProbe {

    private static final String DEL_FLAG_NORMAL = "0";
    private static final String LEGACY_BATCH_PREFIX = "cb_";
    private static final List<String> MEDIA_TERMINAL_STATUSES = List.of(
            MediaTaskStatus.SUCCEEDED.name(), MediaTaskStatus.FAILED.name());

    private final AidAudioRecordMapper aidAudioRecordMapper;
    private final AidMediaTaskMapper aidMediaTaskMapper;
    private final AidEpisodeEditorMapper aidEpisodeEditorMapper;

    @Override
    public boolean supports(String logicalType) {
        return Objects.equals(BatchTaskLogicalType.STORYBOARD_AUDIO_GENERATE, logicalType)
                || Objects.equals(BatchTaskLogicalType.EPISODE_EXPORT, logicalType);
    }

    @Override
    public boolean hasActiveTask(Long projectId, Long episodeId, String logicalType) {
        if (Objects.equals(BatchTaskLogicalType.STORYBOARD_AUDIO_GENERATE, logicalType)) {
            return hasActiveVoiceover(projectId, episodeId);
        }
        return Objects.equals(BatchTaskLogicalType.EPISODE_EXPORT, logicalType)
                && hasActiveExport(projectId, episodeId);
    }

    public boolean hasActiveVoiceover(Long projectId, Long episodeId) {
        Long activeAudioRecords = aidAudioRecordMapper.selectCount(Wrappers.<AidAudioRecord>lambdaQuery()
                .eq(AidAudioRecord::getProjectId, projectId)
                .eq(AidAudioRecord::getEpisodeId, episodeId)
                .likeRight(AidAudioRecord::getComposeBatchId, LEGACY_BATCH_PREFIX)
                .eq(AidAudioRecord::getDelFlag, DEL_FLAG_NORMAL)
                .and(wrapper -> wrapper.isNull(AidAudioRecord::getStatus)
                        .or().notIn(AidAudioRecord::getStatus, MEDIA_TERMINAL_STATUSES)));
        if (activeAudioRecords != null && activeAudioRecords > 0) {
            return true;
        }

        QueryWrapper<AidAudioRecord> succeededBatchQuery = new QueryWrapper<>();
        succeededBatchQuery.select("compose_batch_id")
                .eq("project_id", projectId)
                .eq("episode_id", episodeId)
                .likeRight("compose_batch_id", LEGACY_BATCH_PREFIX)
                .eq("del_flag", DEL_FLAG_NORMAL)
                .groupBy("compose_batch_id")
                .having("SUM(CASE WHEN status IS NULL OR status <> {0} THEN 1 ELSE 0 END) = 0",
                        MediaTaskStatus.SUCCEEDED.name());
        List<String> succeededBatchIds = aidAudioRecordMapper.selectObjs(succeededBatchQuery).stream()
                .filter(Objects::nonNull).map(String::valueOf).toList();
        if (succeededBatchIds.isEmpty()) {
            return false;
        }
        List<AidMediaTask> composeTasks = aidMediaTaskMapper.selectList(
                Wrappers.<AidMediaTask>lambdaQuery()
                        .select(AidMediaTask::getId, AidMediaTask::getComposeBatchId,
                                AidMediaTask::getStatus, AidMediaTask::getOssUrl)
                        .in(AidMediaTask::getComposeBatchId, succeededBatchIds)
                        .eq(AidMediaTask::getProjectId, projectId)
                        .eq(AidMediaTask::getEpisodeId, episodeId)
                        .eq(AidMediaTask::getMediaType, ComposeConstants.MEDIA_TYPE_COMPOSE)
                        .orderByDesc(AidMediaTask::getId));
        Map<String, AidMediaTask> latestComposeByBatch = new LinkedHashMap<>();
        for (AidMediaTask composeTask : composeTasks) {
            latestComposeByBatch.putIfAbsent(composeTask.getComposeBatchId(), composeTask);
        }
        for (String batchId : succeededBatchIds) {
            // 全部 SUCCEEDED 到事件监听器创建 COMPOSE 之间也属于活跃窗口；不能仅靠 Redis 新鲜度。
            AidMediaTask composeTask = latestComposeByBatch.get(batchId);
            if (composeTask == null || composeTask.getStatus() == null
                    || composeTask.getStatus().isBlank()
                    || !MEDIA_TERMINAL_STATUSES.contains(composeTask.getStatus())) {
                return true;
            }
            if (MediaTaskStatus.SUCCEEDED.name().equals(composeTask.getStatus())
                    && StrUtil.isBlank(composeTask.getOssUrl())) {
                return true;
            }
        }
        return false;
    }

    public boolean hasActiveExport(Long projectId, Long episodeId) {
        Long active = aidEpisodeEditorMapper.selectCount(Wrappers.<AidEpisodeEditor>lambdaQuery()
                .eq(AidEpisodeEditor::getProjectId, projectId)
                .eq(AidEpisodeEditor::getEpisodeId, episodeId)
                .eq(AidEpisodeEditor::getExportStatus, ComposeConstants.EXPORT_STATUS_COMPOSING)
                .eq(AidEpisodeEditor::getDelFlag, DEL_FLAG_NORMAL));
        return active != null && active > 0;
    }
}
