package com.aid.compose.service.impl;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidEpisodeEditorMapper;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.compose.ComposeConstants;
import com.aid.compose.domain.ComposeBillingSnapshot;
import com.aid.compose.service.ComposeBillingService;
import com.aid.compose.service.ComposeCompletionService;
import com.aid.media.enums.MediaBillingStatus;
import com.aid.media.provider.ProviderTaskResult;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 合成终态收口结算/退款实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComposeCompletionServiceImpl implements ComposeCompletionService {

    /** 合成中进度上限：100 保留给成功收口 */
    private static final int MAX_RUNNING_PROGRESS = 99;

    /** 媒体任务 Mapper */
    private final AidMediaTaskMapper aidMediaTaskMapper;

    /** 剧集剪辑 Mapper（接口2 回写） */
    private final AidEpisodeEditorMapper aidEpisodeEditorMapper;

    /** 合成计费服务 */
    private final ComposeBillingService composeBillingService;

    @Override
    public void onSucceeded(AidMediaTask task, ProviderTaskResult result) {
        ComposeBillingSnapshot snapshot = parseSnapshot(task);
        // 实际输出秒数：优先取 MPS 解析值，缺失回退预估秒数（避免漏结算）
        long actualSeconds = resolveActualSeconds(result, snapshot);
        if (Objects.nonNull(snapshot)) {
            composeBillingService.settle(task.getUserId(), actualSeconds, snapshot, task.getBillingTraceId());
        } else {
            log.warn("合成成功但计费快照缺失, 跳过结算, taskId={}", task.getId());
        }
        // 持久化实际输出时长 + 计费状态（成片业务回写由 OSS 持久化事件以相对路径完成）
        LambdaUpdateWrapper<AidMediaTask> update = new LambdaUpdateWrapper<>();
        update.eq(AidMediaTask::getId, task.getId());
        update.set(AidMediaTask::getOutputDurationSeconds, actualSeconds);
        update.set(AidMediaTask::getBillingStatus, MediaBillingStatus.SUCCESS.name());
        update.set(AidMediaTask::getFrozenAmount, BigDecimal.ZERO);
        update.set(AidMediaTask::getUpdateTime, new Date());
        aidMediaTaskMapper.update(null, update);
        log.info("合成成功收口完成, taskId={}, actualSeconds={}", task.getId(), actualSeconds);
    }

    @Override
    public void onFailed(AidMediaTask task, ProviderTaskResult result) {
        ComposeBillingSnapshot snapshot = parseSnapshot(task);
        if (Objects.nonNull(snapshot)) {
            composeBillingService.refund(task.getUserId(), snapshot, task.getBillingTraceId());
        } else {
            log.warn("合成失败但计费快照缺失, 跳过退款, taskId={}", task.getId());
        }
        LambdaUpdateWrapper<AidMediaTask> update = new LambdaUpdateWrapper<>();
        update.eq(AidMediaTask::getId, task.getId());
        update.set(AidMediaTask::getBillingStatus, MediaBillingStatus.FAILED.name());
        update.set(AidMediaTask::getFrozenAmount, BigDecimal.ZERO);
        update.set(AidMediaTask::getUpdateTime, new Date());
        aidMediaTaskMapper.update(null, update);
        // 接口2 失败终态回写：exportStatus=3 + errorMsg
        if (ComposeConstants.CALLBACK_EPISODE_EDITOR.equalsIgnoreCase(task.getCallbackCategory())
                && Objects.nonNull(task.getCallbackRecordId())) {
            writeEpisodeEditorFailed(task, result);
        }
        log.info("合成失败收口完成, taskId={}", task.getId());
    }

    @Override
    public void onProgress(AidMediaTask task, Integer progress) {
        // 仅接口2 任务回写进度；进度为空/非法直接忽略
        if (Objects.isNull(progress)
                || !ComposeConstants.CALLBACK_EPISODE_EDITOR.equalsIgnoreCase(task.getCallbackCategory())
                || Objects.isNull(task.getCallbackRecordId())) {
            return;
        }
        // 收敛到 [0,99]：100 由成功收口写入，避免"进度100但状态还是合成中"的错觉
        int clamped = Math.max(0, Math.min(MAX_RUNNING_PROGRESS, progress));
        try {
            LambdaUpdateWrapper<AidEpisodeEditor> update = new LambdaUpdateWrapper<>();
            update.eq(AidEpisodeEditor::getId, task.getCallbackRecordId());
            // 仅在仍「合成中」时回写，且进度只增不减（并发轮询/回调乱序时防回退）
            update.eq(AidEpisodeEditor::getExportStatus, ComposeConstants.EXPORT_STATUS_COMPOSING);
            update.and(w -> w.isNull(AidEpisodeEditor::getExportProgress)
                    .or().lt(AidEpisodeEditor::getExportProgress, clamped));
            update.set(AidEpisodeEditor::getExportProgress, clamped);
            update.set(AidEpisodeEditor::getUpdateTime, new Date());
            aidEpisodeEditorMapper.update(null, update);
        } catch (Exception ex) {
            // 进度回写属于展示增强，失败不影响调度主链路
            log.warn("合成进度回写失败, taskId={}, episodeEditorId={}, progress={}",
                    task.getId(), task.getCallbackRecordId(), progress, ex);
        }
    }

    /**
     * 回写 aid_episode_editor 失败终态。
     *
     * @param task   COMPOSE 任务
     * @param result Provider 查询结果
     */
    private void writeEpisodeEditorFailed(AidMediaTask task, ProviderTaskResult result) {
        String errorMsg = Objects.nonNull(result) && StrUtil.isNotBlank(result.getErrorMessage())
                ? result.getErrorMessage() : "合成失败";
        LambdaUpdateWrapper<AidEpisodeEditor> update = new LambdaUpdateWrapper<>();
        update.eq(AidEpisodeEditor::getId, task.getCallbackRecordId());
        update.set(AidEpisodeEditor::getExportStatus, ComposeConstants.EXPORT_STATUS_FAILED);
        update.set(AidEpisodeEditor::getErrorMsg, errorMsg);
        update.set(AidEpisodeEditor::getUpdateTime, new Date());
        aidEpisodeEditorMapper.update(null, update);
    }

    /**
     * 解析任务上的合成计费快照。
     *
     * @param task COMPOSE 任务
     * @return 计费快照，缺失/解析失败返回 null
     */
    private ComposeBillingSnapshot parseSnapshot(AidMediaTask task) {
        if (StrUtil.isBlank(task.getBillingSnapshotJson())) {
            return null;
        }
        try {
            return JSON.parseObject(task.getBillingSnapshotJson(), ComposeBillingSnapshot.class);
        } catch (Exception e) {
            log.error("合成计费快照解析失败, taskId={}", task.getId(), e);
            return null;
        }
    }

    /**
     * 解析实际输出秒数：优先 MPS 实际时长，缺失回退预估秒数。
     *
     * @param result   Provider 查询结果
     * @param snapshot 计费快照
     * @return 实际秒数
     */
    private long resolveActualSeconds(ProviderTaskResult result, ComposeBillingSnapshot snapshot) {
        if (Objects.nonNull(result) && Objects.nonNull(result.getOutputDurationSeconds())
                && result.getOutputDurationSeconds() > 0) {
            return result.getOutputDurationSeconds();
        }
        return Objects.nonNull(snapshot) ? snapshot.getEstimatedSeconds() : 0L;
    }
}
