package com.aid.compose.listener;

import java.util.Date;
import java.util.Objects;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidAudioRecord;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidAudioRecordMapper;
import com.aid.aid.mapper.AidEpisodeEditorMapper;
import com.aid.aid.mapper.AidGenRecordMapper;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.compose.ComposeConstants;
import com.aid.enums.EpisodeStatusEnum;
import com.aid.enums.ProjectStatusEnum;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.event.MediaTaskOssPersistedEvent;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * COMPOSE 成片业务回写监听器：成片 OSS 持久化完成后，以相对路径回填业务表。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComposeResultListener {

    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 电影成片在 aid_episode_editor 中的剧集ID标识 */
    private static final Long MOVIE_EPISODE_ID = 0L;

    /** 媒体任务 Mapper */
    private final AidMediaTaskMapper aidMediaTaskMapper;

    /** 抽卡记录 Mapper（接口1 回写） */
    private final AidGenRecordMapper aidGenRecordMapper;

    /** 配音记录 Mapper（接口1 回写取分镜归属） */
    private final AidAudioRecordMapper aidAudioRecordMapper;

    /** 剧集剪辑 Mapper（接口2 回写） */
    private final AidEpisodeEditorMapper aidEpisodeEditorMapper;

    /** 项目服务（成片导出成功后项目状态联动为「完成未提交(2)」可提审） */
    private final IAidComicProjectService aidComicProjectService;

    /** 剧集服务（成片导出成功后剧集状态联动为「完成未审核(2)」可提审） */
    private final IAidComicEpisodeService aidComicEpisodeService;

    @EventListener
    @Order(300)
    public void onMediaTaskOssPersisted(MediaTaskOssPersistedEvent event) {
        if (Objects.isNull(event) || Objects.isNull(event.getTaskId())) {
            return;
        }
        try {
            AidMediaTask task = aidMediaTaskMapper.selectById(event.getTaskId());
            if (Objects.isNull(task) || !ComposeConstants.MEDIA_TYPE_COMPOSE.equals(task.getMediaType())) {
                return;
            }
            if (!MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus()) || StrUtil.isBlank(task.getOssUrl())) {
                log.warn("COMPOSE 成片回写跳过(未成功或 ossUrl 为空), taskId={}", task.getId());
                return;
            }
            String category = task.getCallbackCategory();
            if (ComposeConstants.CALLBACK_EPISODE_EDITOR.equalsIgnoreCase(category)) {
                writeEpisodeEditor(task);
            } else {
                // 默认接口1：落 aid_gen_record（genType=compose）
                writeGenRecord(task);
            }
        } catch (Exception ex) {
            log.error("ComposeResultListener 成片回写异常, taskId={}", event.getTaskId(), ex);
        }
    }

    /**
     * 接口2 成功回写（双槽位）：
     * 内容已进入审核流程（项目/剧集状态=审核中(3)或审核通过(4)）→ 新成片写 pending_video_url 待审槽，
     * final_video_url 保留旧片继续公开展示，重新过审后由审核通过钩子转正；
     * 其它状态（草稿/制作中/完成未提交/审核失败）→ 照旧直接写 final_video_url 并清空待审槽。
     * 两种情况均置 exportStatus=2 + exportProgress=100。
     *
     * @param task COMPOSE 任务
     */
    private void writeEpisodeEditor(AidMediaTask task) {
        if (Objects.isNull(task.getCallbackRecordId())) {
            log.warn("COMPOSE 接口2 回写缺少 callbackRecordId, taskId={}", task.getId());
            return;
        }
        // 查询字段精简：槽位判定只需归属字段（新增使用字段时此处必须同步补充）
        AidEpisodeEditor editor = aidEpisodeEditorMapper.selectOne(new LambdaQueryWrapper<AidEpisodeEditor>()
                .select(AidEpisodeEditor::getId, AidEpisodeEditor::getProjectId, AidEpisodeEditor::getEpisodeId)
                .eq(AidEpisodeEditor::getId, task.getCallbackRecordId())
                .last("LIMIT 1"));
        // 审核中/已过审内容重新导出：新片入待审槽，公开展示的旧片不受影响
        boolean toPendingSlot = Objects.nonNull(editor) && isContentInAuditFlow(editor);

        LambdaUpdateWrapper<AidEpisodeEditor> update = new LambdaUpdateWrapper<>();
        update.eq(AidEpisodeEditor::getId, task.getCallbackRecordId());
        if (toPendingSlot) {
            update.set(AidEpisodeEditor::getPendingVideoUrl, task.getOssUrl());
        } else {
            update.set(AidEpisodeEditor::getFinalVideoUrl, task.getOssUrl());
            // 覆盖 final 时清残留待审片，避免陈旧 pending 误触发"待重审"提示
            update.set(AidEpisodeEditor::getPendingVideoUrl, null);
        }
        update.set(AidEpisodeEditor::getExportStatus, ComposeConstants.EXPORT_STATUS_SUCCESS);
        update.set(AidEpisodeEditor::getExportProgress, 100);
        update.set(AidEpisodeEditor::getErrorMsg, null);
        update.set(AidEpisodeEditor::getUpdateTime, new Date());
        aidEpisodeEditorMapper.update(null, update);
        log.info("COMPOSE 接口2 成片回写完成, taskId={}, episodeEditorId={}, slot={}",
                task.getId(), task.getCallbackRecordId(), toPendingSlot ? "pending" : "final");
        // 成片已生成：项目/剧集状态从草稿/制作中联动为「完成(2)」可提审（条件更新，审核流程中的状态不受影响）
        markAuditableAfterExport(task.getCallbackRecordId());
    }

    /**
     * 判断成片对应内容是否已进入审核流程（审核中(3)或审核通过(4)）。
     * 处于该区间时旧成片可能正在公开展示（公开口径 status∈(3,4) 且 is_public=1），
     * 新成片必须走待审槽，不允许直接覆盖。
     *
     * @param editor 剪辑记录（含归属）
     * @return true=审核中或已过审
     */
    private boolean isContentInAuditFlow(AidEpisodeEditor editor) {
        if (Objects.isNull(editor.getProjectId()) || Objects.isNull(editor.getEpisodeId())) {
            return false;
        }
        try {
            if (Objects.equals(editor.getEpisodeId(), MOVIE_EPISODE_ID)) {
                // 查询字段精简：仅需状态（新增使用字段时此处必须同步补充）
                AidComicProject project = aidComicProjectService.getOne(Wrappers.<AidComicProject>lambdaQuery()
                        .select(AidComicProject::getId, AidComicProject::getStatus)
                        .eq(AidComicProject::getId, editor.getProjectId())
                        .eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL)
                        .last("LIMIT 1"));
                return Objects.nonNull(project)
                        && (Objects.equals(project.getStatus(), ProjectStatusEnum.AUDITING.getValue())
                        || Objects.equals(project.getStatus(), ProjectStatusEnum.AUDIT_PASSED.getValue()));
            }
            AidComicEpisode episode = aidComicEpisodeService.getOne(Wrappers.<AidComicEpisode>lambdaQuery()
                    .select(AidComicEpisode::getId, AidComicEpisode::getStatus)
                    .eq(AidComicEpisode::getId, editor.getEpisodeId())
                    .eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL)
                    .last("LIMIT 1"));
            return Objects.nonNull(episode)
                    && (Objects.equals(episode.getStatus(), EpisodeStatusEnum.AUDITING.getValue())
                    || Objects.equals(episode.getStatus(), EpisodeStatusEnum.AUDIT_PASSED.getValue()));
        } catch (Exception ex) {
            // 判定异常按"未进入审核流程"处理（写 final 槽），保持导出主链路可用
            log.error("COMPOSE 槽位判定异常, editorId={}", editor.getId(), ex);
            return false;
        }
    }

    /**
     * 成片导出成功后的审核状态联动：
     * 电影成片（episode_id=0）→ 项目状态 草稿(0)/制作中(1) → 完成未提交(2)；
     * 剧集成片（episode_id=剧集ID）→ 剧集状态 草稿(0)/制作中(1) → 完成未审核(2)。
     * 仅从 0/1 推进（条件更新幂等）：审核中(3)/审核通过(4)/审核失败(5) 不在此处变更，
     * 避免 OSS 持久化事件重复触发时把已进入审核流程的状态拉回。
     * 状态联动失败仅记录日志，不影响成片回写主链路。
     *
     * @param episodeEditorId 剪辑记录ID
     */
    private void markAuditableAfterExport(Long episodeEditorId) {
        try {
            // 查询字段精简：状态联动只需归属字段（新增使用字段时此处必须同步补充）
            AidEpisodeEditor editor = aidEpisodeEditorMapper.selectOne(new LambdaQueryWrapper<AidEpisodeEditor>()
                    .select(AidEpisodeEditor::getId, AidEpisodeEditor::getProjectId, AidEpisodeEditor::getEpisodeId)
                    .eq(AidEpisodeEditor::getId, episodeEditorId)
                    .last("LIMIT 1"));
            if (Objects.isNull(editor) || Objects.isNull(editor.getProjectId())
                    || Objects.isNull(editor.getEpisodeId())) {
                return;
            }
            if (Objects.equals(editor.getEpisodeId(), MOVIE_EPISODE_ID)) {
                // 电影：项目级成片，项目状态推进为「完成未提交(2)」
                boolean updated = aidComicProjectService.update(Wrappers.<AidComicProject>lambdaUpdate()
                        .eq(AidComicProject::getId, editor.getProjectId())
                        .eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL)
                        .in(AidComicProject::getStatus, ProjectStatusEnum.DRAFT.getValue(),
                                ProjectStatusEnum.PROCESSING.getValue())
                        .set(AidComicProject::getStatus, ProjectStatusEnum.FINISHED_UNSUBMITTED.getValue())
                        .set(AidComicProject::getUpdateTime, new Date()));
                if (updated) {
                    log.info("COMPOSE 成片导出成功，项目状态联动为完成可提审, projectId={}", editor.getProjectId());
                }
                return;
            }
            // 剧集：该集状态推进为「完成未审核(2)」
            boolean updated = aidComicEpisodeService.update(Wrappers.<AidComicEpisode>lambdaUpdate()
                    .eq(AidComicEpisode::getId, editor.getEpisodeId())
                    .eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL)
                    .in(AidComicEpisode::getStatus, EpisodeStatusEnum.DRAFT.getValue(),
                            EpisodeStatusEnum.PROCESSING.getValue())
                    .set(AidComicEpisode::getStatus, EpisodeStatusEnum.FINISHED_UNAUDITED.getValue())
                    .set(AidComicEpisode::getUpdateTime, new Date()));
            if (updated) {
                log.info("COMPOSE 成片导出成功，剧集状态联动为完成可提审, projectId={}, episodeId={}",
                        editor.getProjectId(), editor.getEpisodeId());
            }
        } catch (Exception ex) {
            // 状态联动属于增强逻辑，异常不阻断成片回写主链路
            log.error("COMPOSE 成片导出成功后状态联动异常, episodeEditorId={}", episodeEditorId, ex);
        }
    }

    /**
     * 接口1 成功回写：新增 aid_gen_record（genType=compose），仅存相对路径。
     *
     * @param task COMPOSE 任务
     */
    private void writeGenRecord(AidMediaTask task) {
        // 幂等：OSS 持久化事件可能多次发布（轮询终态 + OSS 定时补偿），按 providerTaskId + genType 去重，避免重复插入成片记录
        if (StrUtil.isNotBlank(task.getProviderTaskId())) {
            Long existed = aidGenRecordMapper.selectCount(new LambdaQueryWrapper<AidGenRecord>()
                    .eq(AidGenRecord::getTaskId, task.getProviderTaskId())
                    .eq(AidGenRecord::getGenType, ComposeConstants.GEN_TYPE_COMPOSE)
                    .eq(AidGenRecord::getDelFlag, "0"));
            if (Objects.nonNull(existed) && existed > 0) {
                log.info("COMPOSE 接口1 成片已回写,跳过重复插入, taskId={}, providerTaskId={}",
                        task.getId(), task.getProviderTaskId());
                return;
            }
        }
        AidAudioRecord sourceAudioRecord = loadFirstAudioRecord(task.getComposeBatchId());
        Long storyboardId = Objects.nonNull(task.getCallbackRecordId())
                ? task.getCallbackRecordId()
                : (Objects.isNull(sourceAudioRecord) ? null : sourceAudioRecord.getStoryboardId());
        if (Objects.isNull(storyboardId)) {
            log.error("COMPOSE 接口1 成片回写缺少 storyboardId, taskId={}, batchId={}",
                    task.getId(), task.getComposeBatchId());
            return;
        }
        Long projectId = Objects.nonNull(task.getProjectId())
                ? task.getProjectId()
                : (Objects.isNull(sourceAudioRecord) ? null : sourceAudioRecord.getProjectId());
        Long episodeId = Objects.nonNull(task.getEpisodeId())
                ? task.getEpisodeId()
                : (Objects.isNull(sourceAudioRecord) ? null : sourceAudioRecord.getEpisodeId());
        AidGenRecord record = new AidGenRecord();
        record.setUserId(task.getUserId());
        record.setProjectId(projectId);
        record.setEpisodeId(episodeId);
        record.setStoryboardId(storyboardId);
        record.setGenType(ComposeConstants.GEN_TYPE_COMPOSE);
        record.setFileUrl(task.getOssUrl());
        record.setTaskId(task.getProviderTaskId());
        record.setStatus(ComposeConstants.GEN_STATUS_SUCCESS);
        record.setVideoDuration(task.getOutputDurationSeconds());
        record.setIsSelected(0);
        record.setDelFlag("0");
        record.setCreateTime(new Date());
        aidGenRecordMapper.insert(record);
        log.info("COMPOSE 接口1 成片回写完成, taskId={}, genRecordId={}, batchId={}",
                task.getId(), record.getId(), task.getComposeBatchId());
    }

    /**
     * 按合成批次查询一条配音记录，用于成片记录复用分镜归属。
     *
     * @param composeBatchId 合成批次号
     * @return 配音记录
     */
    private AidAudioRecord loadFirstAudioRecord(String composeBatchId) {
        if (StrUtil.isBlank(composeBatchId)) {
            return null;
        }
        return aidAudioRecordMapper.selectOne(new LambdaQueryWrapper<AidAudioRecord>()
                .select(AidAudioRecord::getId, AidAudioRecord::getProjectId,
                        AidAudioRecord::getEpisodeId, AidAudioRecord::getStoryboardId)
                .eq(AidAudioRecord::getComposeBatchId, composeBatchId)
                .eq(AidAudioRecord::getDelFlag, "0")
                .isNotNull(AidAudioRecord::getStoryboardId)
                .orderByAsc(AidAudioRecord::getId)
                .last("LIMIT 1"));
    }
}
