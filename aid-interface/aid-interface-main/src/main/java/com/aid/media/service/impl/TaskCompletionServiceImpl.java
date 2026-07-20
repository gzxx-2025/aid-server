package com.aid.media.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.billing.service.BillingFacadeService;
import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.compose.ComposeConstants;
import com.aid.compose.service.ComposeCompletionService;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.event.MediaTaskCompletedEvent;
import com.aid.media.event.MediaTaskOssPersistedEvent;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.service.MediaConcurrencyLimiter;
import com.aid.media.service.MediaTaskArchiveService;
import com.aid.media.service.TaskCompletionService;
import com.aid.media.util.MediaTaskPayloadSanitizer;
import com.aid.media.enums.MediaType;
import com.aid.modelhealth.service.ModelHealthRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 统一终态处理：回调与轮询都走同一入口，CAS 抢终态处理权，幂等收口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskCompletionServiceImpl implements TaskCompletionService {

    private final AidMediaTaskMapper aidMediaTaskMapper;
    private final BillingFacadeService billingFacadeService;
    private final MediaConcurrencyLimiter concurrencyLimiter;
    private final ApplicationEventPublisher eventPublisher;
    /** 合成终态收口（COMPOSE 分支，绕开模型计费） */
    private final ComposeCompletionService composeCompletionService;
    /** OSS/COS 配置管理：判断当前存储模式（COMPOSE 成片落我方 COS 输出桶时随终态直填 oss_url） */
    private final OssConfigManager ossConfigManager;
    /** 终态请求/响应异步归档与数据库载荷压缩 */
    private final MediaTaskArchiveService mediaTaskArchiveService;
    /** 模型健康采集：终态收口点按成功/失败累加时间桶计数（仅上游结果，内部吞异常） */
    private final ModelHealthRecorder modelHealthRecorder;

    /** 媒体类型：合成任务，走独立计费/回写分支 */
    private static final String COMPOSE_MEDIA_TYPE = ComposeConstants.MEDIA_TYPE_COMPOSE;
    /** 存储模式：腾讯云 COS */
    private static final String UPLOAD_MODE_COS = "cos";
    /** 腾讯云 COS 域名标识 */
    private static final String COS_HOST_MARK = ".myqcloud.com";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean completeTask(Long taskId, ProviderTaskResult taskResult) {
        AidMediaTask task = aidMediaTaskMapper.selectById(taskId);
        if (Objects.isNull(task)) {
            log.warn("completeTask 任务不存在, taskId={}", taskId);
            return false;
        }

        String currentStatus = task.getStatus();
        boolean canTransition = MediaTaskStatus.WAIT_POLL.name().equals(currentStatus)
            || MediaTaskStatus.WAIT_CALLBACK.name().equals(currentStatus)
            || MediaTaskStatus.PROCESSING.name().equals(currentStatus);
        if (!canTransition) {
            log.info("completeTask 任务已终态, taskId={}, status={}", taskId, currentStatus);
            return false;
        }

        String targetStatus;
        if (MediaTaskStatus.SUCCEEDED.name().equals(taskResult.getStatus())) {
            targetStatus = MediaTaskStatus.SUCCEEDED.name();
        } else if (MediaTaskStatus.FAILED.name().equals(taskResult.getStatus())) {
            targetStatus = MediaTaskStatus.FAILED.name();
        } else {
            return false;
        }

        String userStr = task.getUserId() != null ? String.valueOf(task.getUserId()) : "";
        LambdaUpdateWrapper<AidMediaTask> casWrapper = new LambdaUpdateWrapper<>();
        casWrapper.eq(AidMediaTask::getId, taskId);
        casWrapper.in(AidMediaTask::getStatus,
            MediaTaskStatus.WAIT_POLL.name(),
            MediaTaskStatus.WAIT_CALLBACK.name(),
            MediaTaskStatus.PROCESSING.name());
        casWrapper.set(AidMediaTask::getStatus, targetStatus);
        casWrapper.set(AidMediaTask::getUpdateBy, userStr);
        casWrapper.set(AidMediaTask::getUpdateTime, new Date());
        MediaTaskArchiveService.PreparedTerminalPayload preparedPayload =
            mediaTaskArchiveService.prepareTerminalPayload(task, targetStatus, taskResult.getRawResponse());
        if (!Objects.equals(preparedPayload.getRequestJson(), task.getRequestJson())) {
            casWrapper.set(AidMediaTask::getRequestJson, preparedPayload.getRequestJson());
        }

        if (MediaTaskStatus.SUCCEEDED.name().equals(targetStatus)) {
            casWrapper.set(AidMediaTask::getOriginUrl, taskResult.getResultUrl());
            casWrapper.set(AidMediaTask::getErrorMessage, null);
            // COMPOSE 成片由 MPS 直接写入我方 COS 输出桶（= 当前 COS 存储桶），文件即在我方存储；
            // 随 origin_url 一并把 oss_url 填成对象相对路径，避免后续再"下载→转存"
            //（COS 源站私有读会 403，导致 oss_url 空、状态卡 COMPOSING）。读取层按 cdnDomain 拼完整 URL。
            if (COMPOSE_MEDIA_TYPE.equals(task.getMediaType())) {
                String composeOssUrl = resolveComposeOssRelativePath(taskResult.getResultUrl());
                if (StrUtil.isNotBlank(composeOssUrl)) {
                    casWrapper.set(AidMediaTask::getOssUrl, composeOssUrl);
                }
            }
        } else {
            casWrapper.set(AidMediaTask::getErrorMessage,
                MediaTaskPayloadSanitizer.sanitizeForStorage(taskResult.getErrorMessage()));
        }
        casWrapper.set(AidMediaTask::getResponseJson, preparedPayload.getResponseJson());

        int rows = aidMediaTaskMapper.update(null, casWrapper);
        if (rows == 0) {
            log.info("completeTask CAS 失败, taskId={} 已被其他路径处理", taskId);
            return false;
        }
        mediaTaskArchiveService.archiveAfterCommit(preparedPayload);

        task = aidMediaTaskMapper.selectById(taskId);

        // 模型健康采集：本方法是回调/轮询双路径的 CAS 收口点，同一任务只进一次，天然去重；
        // 此处的 SUCCEEDED/FAILED 均来自上游返回结果（含超时无响应关单），符合"只记上游错误"口径。
        recordModelHealth(task, targetStatus, taskResult);

        // 回调与轮询双路径经本方法 CAS 收口，同一 taskId 仅进入一次，故结算/退款天然幂等。
        if (COMPOSE_MEDIA_TYPE.equals(task.getMediaType())) {
            if (MediaTaskStatus.SUCCEEDED.name().equals(targetStatus)) {
                composeCompletionService.onSucceeded(task, taskResult);
                // COMPOSE 直填 oss_url 后 oss_pending 恒为 0，OSS 补偿任务不会再扫到它；
                // 且 MPS 回调路径收口后没有 ensureOssPersisted 兜底（只有轮询路径有），
                // 若不在此处发布 OSS 持久化事件，ComposeResultListener 永远不触发，
                // aid_episode_editor.final_video_url / export_status 将卡在合成中。
                // 故 oss_url 就绪时统一在收口点发布事件（监听器幂等，轮询路径重复发布无副作用）。
                if (StrUtil.isNotBlank(task.getOssUrl())) {
                    registerAfterCommitOssPersisted(task);
                }
            } else {
                composeCompletionService.onFailed(task, taskResult);
            }
            registerAfterCommitRelease(task);
            // 成功时返回 true，使调度赢家继续执行 OSS 回写（成片相对路径回填业务表由 OSS 事件完成）。
            return true;
        }

        boolean billingWon;
        if (MediaTaskStatus.SUCCEEDED.name().equals(targetStatus)) {
            billingWon = billingFacadeService.settleBilling(
                task, buildSettleUsage(task, taskResult));
            log.info("completeTask 任务成功, taskId={}, billingWon={}", taskId, billingWon);
        } else {
            billingWon = billingFacadeService.refundBilling(task);
            log.info("completeTask 任务失败, taskId={}, error={}, billingWon={}",
                taskId, taskResult.getErrorMessage(), billingWon);
        }

        if (billingWon) {
            LambdaUpdateWrapper<AidMediaTask> billingUpdate = new LambdaUpdateWrapper<>();
            billingUpdate.eq(AidMediaTask::getId, taskId);
            billingUpdate.set(AidMediaTask::getActualCost, task.getActualCost());
            billingUpdate.set(AidMediaTask::getBillingSnapshotJson, task.getBillingSnapshotJson());
            billingUpdate.set(AidMediaTask::getBillingStatus, task.getBillingStatus());
            billingUpdate.set(AidMediaTask::getFrozenAmount, task.getFrozenAmount());
            billingUpdate.set(AidMediaTask::getUpdateBy, userStr);
            billingUpdate.set(AidMediaTask::getUpdateTime, new Date());
            aidMediaTaskMapper.update(null, billingUpdate);
        } else {
            AidMediaTask dbTask = aidMediaTaskMapper.selectById(taskId);
            if (dbTask != null) {
                task.setActualCost(dbTask.getActualCost());
                task.setBillingSnapshotJson(dbTask.getBillingSnapshotJson());
                task.setBillingStatus(dbTask.getBillingStatus());
                task.setFrozenAmount(dbTask.getFrozenAmount());
            }
        }

        // 必须在 afterCommit 中执行，确保 DB 终态落库成功后才释放名额和拉起新任务。
        registerAfterCommitRelease(task);

        if (billingWon && MediaTaskStatus.SUCCEEDED.name().equals(targetStatus)) {
            return true;
        }
        return billingWon;
    }

    /**
     * 模型健康采集：COMPOSE 为合成服务（MPS）非 AI 模型，不计入；
     * 成功耗时 = 任务创建到终态收口（异步生成任务的端到端生成耗时）。
     */
    private void recordModelHealth(AidMediaTask task, String targetStatus, ProviderTaskResult taskResult) {
        if (COMPOSE_MEDIA_TYPE.equals(task.getMediaType())) {
            return;
        }
        if (MediaTaskStatus.SUCCEEDED.name().equals(targetStatus)) {
            Long latencyMs = Objects.nonNull(task.getCreateTime())
                    ? System.currentTimeMillis() - task.getCreateTime().getTime() : null;
            modelHealthRecorder.recordSuccess(task.getModelName(), task.getMediaType(), latencyMs);
        } else {
            modelHealthRecorder.recordFailure(task.getModelName(), task.getMediaType(),
                    taskResult.getErrorMessage());
        }
    }

    /**
     * 统一终态入口按媒体类型传递实际用量，保证按张 / 按秒的结算依据不丢失。
     */
    private Map<String, Object> buildSettleUsage(AidMediaTask task, ProviderTaskResult taskResult) {
        if (Objects.equals(task.getMediaType(), MediaType.IMAGE.name())) {
            int actualCount = 0;
            if (Objects.nonNull(taskResult.getResultCount()) && taskResult.getResultCount() > 0) {
                actualCount = taskResult.getResultCount();
            } else if (taskResult.getResultUrls() != null && !taskResult.getResultUrls().isEmpty()) {
                actualCount = taskResult.getResultUrls().size();
            } else if (StrUtil.isNotBlank(taskResult.getResultUrl())) {
                actualCount = 1;
            }
            Map<String, Object> usage = new HashMap<>();
            usage.put("actualImageCount", Math.max(actualCount, 1));
            usage.put("resultCount", Math.max(actualCount, 1));
            return usage;
        }
        if (Objects.equals(task.getMediaType(), MediaType.VIDEO.name())
            && Objects.nonNull(taskResult.getVideoDurationSeconds())
            && taskResult.getVideoDurationSeconds() > 0) {
            Map<String, Object> usage = new HashMap<>();
            usage.put("actualDuration", taskResult.getVideoDurationSeconds());
            return usage;
        }
        return null;
    }

    /**
     * 解析 COMPOSE 成片的 COS 对象相对路径（以 {@code /} 起始，不含协议 / 域名 / query）。
     *
     * @param resultUrl 上游返回的成片地址
     * @return COS 对象相对路径，形如 {@code /compose_result/compose_xxx.mp4}；不适用时返回 null
     */
    private String resolveComposeOssRelativePath(String resultUrl) {
        if (StrUtil.isBlank(resultUrl)) {
            return null;
        }
        OssProperties properties = ossConfigManager.getOssProperties();
        // 非 COS 模式不直填：对外 CDN 可能指向别的存储，直引用会拼出错误地址。
        if (Objects.isNull(properties) || !UPLOAD_MODE_COS.equalsIgnoreCase(properties.getUploadMode())) {
            return null;
        }
        if (!resultUrl.toLowerCase().contains(COS_HOST_MARK)) {
            return null;
        }
        try {
            // 取 URI path，剥离协议 / 域名 / query。
            String path = java.net.URI.create(resultUrl).getPath();
            if (StrUtil.isBlank(path)) {
                return null;
            }
            return path.startsWith("/") ? path : "/" + path;
        } catch (Exception e) {
            log.error("resolveComposeOssRelativePath 解析成片对象路径失败, url={}, err={}", resultUrl, e.getMessage());
            return null;
        }
    }

    /**
     * 注册事务提交后回调：发布 OSS 持久化完成事件，驱动 ComposeResultListener 回填业务表
     * （aid_episode_editor.final_video_url / export_status 或 aid_gen_record）。
     * 必须在 afterCommit 中发布：监听器会重新 selectById 读任务，事务未提交时读不到终态。
     *
     * @param task 已终态且 oss_url 就绪的 COMPOSE 任务
     */
    private void registerAfterCommitOssPersisted(AidMediaTask task) {
        Long tid = task.getId();
        Long userId = task.getUserId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishEvent(new MediaTaskOssPersistedEvent(this, tid, userId));
                }
            });
        } else {
            // 无事务上下文时直接发布（降级）。
            eventPublisher.publishEvent(new MediaTaskOssPersistedEvent(this, tid, userId));
        }
    }

    /**
     * 注册事务提交后回调：释放并发坑位并发布完成事件触发 drainQueue。
     * 无事务上下文时直接执行（降级）。COMPOSE 与既有分支共用，保证收口语义一致。
     *
     * @param task 已终态任务
     */
    private void registerAfterCommitRelease(AidMediaTask task) {
        Long userId = task.getUserId();
        Long tid = task.getId();
        // 释放需带模型编码：四维限流按 全局/用户/模型/供应商 各自计数，缺一会导致模型/供应商维度泄漏。
        String modelName = task.getModelName();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    concurrencyLimiter.release(userId, modelName);
                    eventPublisher.publishEvent(new MediaTaskCompletedEvent(this, tid, userId));
                }
            });
        } else {
            // 无事务上下文时直接执行（降级）。
            concurrencyLimiter.release(userId, modelName);
            eventPublisher.publishEvent(new MediaTaskCompletedEvent(this, tid, userId));
        }
    }

    /**
     * 未提交僵尸任务收口后的事务提交回调：仅 PENDING（已占槽）才释放并发坑位，统一触发 drainQueue。
     *
     * @param task       已被关闭的任务
     * @param wasPending 关闭前是否为 PENDING（占槽）
     */
    private void registerAfterCommitReleaseForUnsubmitted(AidMediaTask task, boolean wasPending) {
        Long userId = task.getUserId();
        Long tid = task.getId();
        // 释放需带模型编码：四维限流按 全局/用户/模型/供应商 各自计数，缺一会导致模型/供应商维度泄漏。
        String modelName = task.getModelName();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (wasPending) {
                        concurrencyLimiter.release(userId, modelName);
                    }
                    eventPublisher.publishEvent(new MediaTaskCompletedEvent(this, tid, userId));
                }
            });
        } else {
            if (wasPending) {
                concurrencyLimiter.release(userId, modelName);
            }
            eventPublisher.publishEvent(new MediaTaskCompletedEvent(this, tid, userId));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean closeUnsubmittedTask(Long taskId, String errorMessage) {
        AidMediaTask task = aidMediaTaskMapper.selectById(taskId);
        if (Objects.isNull(task)) {
            log.warn("closeUnsubmittedTask 任务不存在, taskId={}", taskId);
            return false;
        }
        String currentStatus = task.getStatus();
        boolean canClose = MediaTaskStatus.PENDING.name().equals(currentStatus)
            || MediaTaskStatus.QUEUED.name().equals(currentStatus);
        if (!canClose) {
            log.info("closeUnsubmittedTask 任务已推进, taskId={}, status={}", taskId, currentStatus);
            return false;
        }
        // 仅 PENDING 任务抢占过并发坑位（QUEUED 因超并发未 acquire），关闭时只对 PENDING 释放，避免计数被错误减少。
        final boolean wasPending = MediaTaskStatus.PENDING.name().equals(currentStatus);
        //    竞态——若期间状态已变，CAS 失败、留待下一轮重判，避免 wasPending 与实际占槽情况不一致导致漏释放。
        String userStr = task.getUserId() != null ? String.valueOf(task.getUserId()) : "";
        LambdaUpdateWrapper<AidMediaTask> casWrapper = new LambdaUpdateWrapper<>();
        casWrapper.eq(AidMediaTask::getId, taskId);
        casWrapper.eq(AidMediaTask::getStatus, currentStatus);
        casWrapper.set(AidMediaTask::getStatus, MediaTaskStatus.FAILED.name());
        casWrapper.set(AidMediaTask::getErrorMessage,
            MediaTaskPayloadSanitizer.sanitizeForStorage(errorMessage));
        MediaTaskArchiveService.PreparedTerminalPayload preparedPayload =
            mediaTaskArchiveService.prepareTerminalPayload(
                task, MediaTaskStatus.FAILED.name(), task.getResponseJson());
        if (!Objects.equals(preparedPayload.getRequestJson(), task.getRequestJson())) {
            casWrapper.set(AidMediaTask::getRequestJson, preparedPayload.getRequestJson());
        }
        casWrapper.set(AidMediaTask::getResponseJson, preparedPayload.getResponseJson());
        casWrapper.set(AidMediaTask::getUpdateBy, userStr);
        casWrapper.set(AidMediaTask::getUpdateTime, new Date());
        int rows = aidMediaTaskMapper.update(null, casWrapper);
        if (rows == 0) {
            log.info("closeUnsubmittedTask CAS 失败, taskId={} 已被其他路径处理", taskId);
            return false;
        }
        mediaTaskArchiveService.archiveAfterCommit(preparedPayload);
        task = aidMediaTaskMapper.selectById(taskId);
        if (COMPOSE_MEDIA_TYPE.equals(task.getMediaType())) {
            ProviderTaskResult zombieResult = ProviderTaskResult.builder()
                .status(MediaTaskStatus.FAILED.name())
                .errorMessage(errorMessage)
                .build();
            composeCompletionService.onFailed(task, zombieResult);
            registerAfterCommitReleaseForUnsubmitted(task, wasPending);
            return true;
        }
        boolean billingWon = billingFacadeService.refundBilling(task);
        log.info("closeUnsubmittedTask 关闭僵尸任务, taskId={}, billingWon={}", taskId, billingWon);
        if (billingWon) {
            LambdaUpdateWrapper<AidMediaTask> billingUpdate = new LambdaUpdateWrapper<>();
            billingUpdate.eq(AidMediaTask::getId, taskId);
            billingUpdate.set(AidMediaTask::getBillingStatus, task.getBillingStatus());
            billingUpdate.set(AidMediaTask::getFrozenAmount, task.getFrozenAmount());
            billingUpdate.set(AidMediaTask::getUpdateBy, userStr);
            billingUpdate.set(AidMediaTask::getUpdateTime, new Date());
            aidMediaTaskMapper.update(null, billingUpdate);
        }
        Long userId = task.getUserId();
        Long tid = task.getId();
        // 释放需带模型编码：四维限流按 全局/用户/模型/供应商 各自计数，缺一会导致模型/供应商维度泄漏。
        String modelName = task.getModelName();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (wasPending) {
                        concurrencyLimiter.release(userId, modelName);
                    }
                    eventPublisher.publishEvent(new MediaTaskCompletedEvent(this, tid, userId));
                }
            });
        } else {
            if (wasPending) {
                concurrencyLimiter.release(userId, modelName);
            }
            eventPublisher.publishEvent(new MediaTaskCompletedEvent(this, tid, userId));
        }
        return billingWon;
    }
}
