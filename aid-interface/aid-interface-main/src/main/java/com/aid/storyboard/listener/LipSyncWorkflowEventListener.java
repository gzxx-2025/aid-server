package com.aid.storyboard.listener;

import java.util.Objects;
import com.aid.rps.queue.BatchTaskExecutionRejectedException;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.aid.media.event.MediaParentReconcileEvent;
import com.aid.media.event.MediaTaskCompletedEvent;
import com.aid.media.event.MediaTaskOssPersistedEvent;
import com.aid.storyboard.service.IStoryboardLipSyncService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 对口型持久化编排监听器。
 * 顺序晚于音频记录(200)和对口型产物(220)监听器，确保业务快照先回填，再推进父任务。
 */
@Slf4j
@Component
public class LipSyncWorkflowEventListener {

    private static final String TASK_TYPE_LIP_SYNC_BATCH = "storyboard_lip_sync_generate";
    private static final String TASK_TYPE_LIP_SYNC_SINGLE = "storyboard_lip_sync_single";

    @Resource
    private IStoryboardLipSyncService storyboardLipSyncService;

    @EventListener
    @Order(300)
    public void onMediaTaskCompleted(MediaTaskCompletedEvent event) {
        if (Objects.nonNull(event)) {
            advanceSafely(event.getTaskId());
        }
    }

    @EventListener
    @Order(300)
    public void onMediaTaskOssPersisted(MediaTaskOssPersistedEvent event) {
        if (Objects.nonNull(event)) {
            advanceSafely(event.getTaskId());
        }
    }

    @EventListener
    @Order(300)
    public void onParentReconcile(MediaParentReconcileEvent event) {
        if (Objects.isNull(event) || Objects.isNull(event.getParentTaskId())
                || !isLipSyncTaskType(event.getTaskType())) {
            return;
        }
        try {
            storyboardLipSyncService.reconcileParentTask(event.getParentTaskId());
        } catch (BatchTaskExecutionRejectedException ignored) {
            log.debug("对口型任务周期已结束，忽略迟到事件");
        } catch (Exception ex) {
            log.error("对口型父任务对账异常, taskId={}", event.getParentTaskId(), ex);
        }
    }

    private void advanceSafely(Long mediaTaskId) {
        if (Objects.isNull(mediaTaskId)) {
            return;
        }
        try {
            storyboardLipSyncService.onChildMediaTaskChanged(mediaTaskId);
        } catch (BatchTaskExecutionRejectedException ignored) {
            log.debug("对口型任务周期已结束，忽略迟到事件");
        } catch (Exception ex) {
            // 媒体任务终态与统一结算已完成，编排异常交给重启对账补偿，不能反向污染公共媒体链路。
            log.error("对口型子任务事件推进异常, mediaTaskId={}", mediaTaskId, ex);
        }
    }

    private boolean isLipSyncTaskType(String taskType) {
        return TASK_TYPE_LIP_SYNC_BATCH.equals(taskType)
                || TASK_TYPE_LIP_SYNC_SINGLE.equals(taskType);
    }
}
