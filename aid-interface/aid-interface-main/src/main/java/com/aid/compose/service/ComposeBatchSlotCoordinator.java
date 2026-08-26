package com.aid.compose.service;

import java.util.Objects;

import org.springframework.stereotype.Component;

import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.mapper.AidEpisodeEditorMapper;
import com.aid.rps.queue.BatchTaskLogicalType;
import com.aid.rps.queue.BatchTaskSlotReservation;
import com.aid.rps.queue.BatchTaskSlotService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 旧一键配音、剧集导出的逻辑槽协调器。
 *
 * @author 视觉AID
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ComposeBatchSlotCoordinator {

    private final BatchTaskSlotService batchTaskSlotService;
    private final ComposeBatchActivityProbe activityProbe;
    private final ComposeBatchStore composeBatchStore;
    private final AidEpisodeEditorMapper aidEpisodeEditorMapper;

    public BatchTaskSlotReservation acquireVoiceover(Long projectId, Long episodeId, String batchId) {
        BatchTaskSlotReservation reservation = batchTaskSlotService.acquireExternalSlot(
                projectId, episodeId, BatchTaskLogicalType.STORYBOARD_AUDIO_GENERATE,
                () -> activityProbe.hasActiveVoiceover(projectId, episodeId));
        try {
            composeBatchStore.saveVoiceoverSlot(batchId, reservation);
            return reservation;
        } catch (RuntimeException ex) {
            batchTaskSlotService.release(reservation);
            throw ex;
        }
    }

    public void releaseVoiceover(String batchId) {
        try {
            BatchTaskSlotReservation reservation = composeBatchStore.getVoiceoverSlot(batchId);
            if (reservation == null || activityProbe.hasActiveVoiceover(
                    reservation.projectId(), reservation.episodeId())) {
                return;
            }
            batchTaskSlotService.release(reservation);
            composeBatchStore.clearVoiceoverSlot(batchId, reservation);
        } catch (Exception ex) {
            log.warn("一键配音逻辑槽释放失败: batchId={}, err={}", batchId, ex.getMessage());
        }
    }

    public BatchTaskSlotReservation acquireExport(Long projectId, Long episodeId) {
        return batchTaskSlotService.acquireExternalSlot(
                projectId, episodeId, BatchTaskLogicalType.EPISODE_EXPORT,
                () -> activityProbe.hasActiveExport(projectId, episodeId));
    }

    public void bindExport(Long episodeEditorId, BatchTaskSlotReservation reservation) {
        composeBatchStore.saveExportSlot(episodeEditorId, reservation);
    }

    public void releaseExport(Long episodeEditorId, String expectedRunToken) {
        try {
            AidEpisodeEditor editor = aidEpisodeEditorMapper.selectById(episodeEditorId);
            if (editor == null || !Objects.equals(expectedRunToken, editor.getExportTaskId())) {
                return;
            }
            BatchTaskSlotReservation reservation = composeBatchStore.getExportSlot(episodeEditorId);
            if (reservation == null || activityProbe.hasActiveExport(
                    reservation.projectId(), reservation.episodeId())) {
                return;
            }
            batchTaskSlotService.release(reservation);
            composeBatchStore.clearExportSlot(episodeEditorId, reservation);
        } catch (Exception ex) {
            log.warn("成片导出逻辑槽释放失败: episodeEditorId={}, err={}",
                    episodeEditorId, ex.getMessage());
        }
    }

    public void abortVoiceover(String batchId, BatchTaskSlotReservation reservation) {
        batchTaskSlotService.release(reservation);
        composeBatchStore.clearVoiceoverSlot(batchId, reservation);
    }

    public void abortExport(Long episodeEditorId, BatchTaskSlotReservation reservation) {
        batchTaskSlotService.release(reservation);
        composeBatchStore.clearExportSlot(episodeEditorId, reservation);
    }
}
