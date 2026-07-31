package com.aid.rps.queue;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.common.utils.DateUtils;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.rps.sse.AssetExtractSseManager;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 批量文本任务「本地派发」终态编排器（双模式地基），与 MQ Consumer 的终态编排同义，集中一处避免重复维护。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class BatchTaskLocalOrchestrator
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    private static final String TASK_STATUS_FINALIZING = "FINALIZING";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
    private static final String TASK_STATUS_CANCELLED = "CANCELLED";
    private static final String TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH = "storyboard_image_prompt_batch";
    private static final String TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH = "storyboard_video_prompt_batch";
    private static final String CHILD_TASK_TYPE_IMAGE = "storyboard_image_generate";
    private static final String CHILD_TASK_TYPE_VIDEO = "storyboard_video_generate";

    @Resource
    private IAidExtractTaskService extractTaskService;

    /** 名额释放（低层队列服务，单向依赖，不反向依赖业务 Service） */
    @Resource
    private TaskQueueService taskQueueService;

    /** 取消标记（低层 Redis 管理器，单向依赖） */
    @Resource
    private TaskCancelFlagManager taskCancelFlagManager;

    @Resource
    private AssetExtractSseManager sseManager;

    @Resource
    private IWechatNotifyService wechatNotifyService;

    /** 事件发布器：提示词批量终态后发布事件，由合并链式触发器监听（解耦避免循环依赖） */
    @Resource
    private ApplicationEventPublisher eventPublisher;

    /** 文案 / 标识规格：各业务提供少量差异文案与任务类型，编排骨架共用 */
    public static final class Spec
    {
        /**
         * SSE 步骤 key（如 storyboard_script / storyboard_image_prompt）。
         */
        public final String stepKey;
        /** 初始化进度文案 */
        public final String initMessage;
        /** 取消（有未处理项被跳过）时的 SSE 文案 */
        public final String cancelSkippedMessage;
        /** 部分失败时的 SSE 文案 */
        public final String partialMessage;
        /** 任务类型（失败时释放批量锁用） */
        public final String taskType;
        /** 是否在「有失败项」时置 PARTIAL_FAILED（true=保留续生入口；false=有失败也记 SUCCEEDED） */
        public final boolean partialOnFailed;

        public Spec(String stepKey, String initMessage, String cancelSkippedMessage,
                    String partialMessage, String taskType)
        {
            this(stepKey, initMessage, cancelSkippedMessage, partialMessage, taskType, true);
        }

        public Spec(String stepKey, String initMessage, String cancelSkippedMessage,
                    String partialMessage, String taskType, boolean partialOnFailed)
        {
            this.stepKey = stepKey;
            this.initMessage = initMessage;
            this.cancelSkippedMessage = cancelSkippedMessage;
            this.partialMessage = partialMessage;
            this.taskType = taskType;
            this.partialOnFailed = partialOnFailed;
        }
    }

    /**
     * 本地执行入口：置 PROCESSING + SSE 初始化 → 调用执行体 → 取消/部分失败/成功分流 → finally 释放并发名额。
     *
     * @param taskId               父任务 ID
     * @param userId               用户 ID
     * @param spec                 业务差异规格
     * @param body                 自洽执行体（返回 resultJson）
     * @param onFailureReleaseLocks 失败时释放业务锁的回调（回调注入避免反向依赖高层业务 Service）
     */
    public void run(Long taskId, Long userId, Spec spec, Supplier<String> body, Runnable onFailureReleaseLocks)
    {
        String dispatchToken = taskQueueService.currentLocalDispatchToken(taskId);
        if (StrUtil.isBlank(dispatchToken) || !claimExecution(taskId, dispatchToken))
        {
            log.info("本地批量任务周期已变化，跳过执行: taskId={}, type={}", taskId, spec.taskType);
            return;
        }
        // 本地批量任务可能长时间阻塞在 LLM / 上游调用中，必须持续续租避免被运行期自愈误判为中断。
        taskQueueService.markProcessing(taskId);
        sseManager.sendStepProgress(taskId, spec.stepKey, 5, "init", spec.initMessage, 0, 1);
        try
        {
            String resultJson = body.get();
            if (!claimFinalization(taskId, dispatchToken))
            {
                log.info("本地批量任务终结权已失效: taskId={}, type={}", taskId, spec.taskType);
                return;
            }

            // 取消：仅当确有未处理项被跳过才置 CANCELLED；否则视为已处理完毕，继续成功/部分失败分流
            if (taskCancelFlagManager.isCancelled(taskId))
            {
                taskCancelFlagManager.clearCancelled(taskId);
                if (hasBatchSkippedItems(resultJson))
                {
                    if (!updateResult(taskId, dispatchToken, TASK_STATUS_CANCELLED, resultJson, "用户取消"))
                    {
                        log.info("本地批量任务取消收口跳过，周期已变化: taskId={}", taskId);
                        return;
                    }
                    sseManager.sendCancelled(taskId, spec.cancelSkippedMessage);
                    log.info("本地批量任务被取消: taskId={}, type={}", taskId, spec.taskType);
                    return;
                }
                log.info("本地批量任务: 取消标记已清除，全部项已处理完毕: taskId={}, type={}", taskId, spec.taskType);
            }

            // 部分失败 → PARTIAL_FAILED（保留续生入口，禁止用 complete 误判完全成功）
            if (spec.partialOnFailed && hasFailedItems(resultJson))
            {
                // 先同步发布终态事件（合并任务链式触发器据此触发出图/出片并回填子任务 ID），再推 partial_failed
                BatchPromptTerminalEvent event = publishTerminal(taskId, spec.taskType, TASK_STATUS_PARTIAL_FAILED);
                resultJson = appendChainSuccess(resultJson, event);
                resultJson = appendChainFailure(resultJson, event);
                if (!updateResult(taskId, dispatchToken, TASK_STATUS_PARTIAL_FAILED, resultJson, null))
                {
                    log.info("本地批量任务部分失败收口跳过，周期已变化: taskId={}", taskId);
                    return;
                }
                wechatNotifyService.notifyTaskTerminal(taskId);
                sendPartialFailedSafely(taskId, resultJson, chainTerminalMessage(event, spec.partialMessage),
                        event.getChainChildTaskIds(), event.getChainChildTaskType());
                log.info("本地批量任务部分失败: taskId={}, type={}", taskId, spec.taskType);
                return;
            }

            // 先同步发布终态事件（合并任务链式触发器据此触发出图/出片并回填子任务 ID），再推 complete
            BatchPromptTerminalEvent event = publishTerminal(taskId, spec.taskType, TASK_STATUS_SUCCEEDED);
            if (event.isChainFailed())
            {
                resultJson = appendChainFailure(resultJson, event);
                if (!updateResult(taskId, dispatchToken, TASK_STATUS_PARTIAL_FAILED, resultJson, null))
                {
                    log.info("本地批量任务链式失败收口跳过，周期已变化: taskId={}", taskId);
                    return;
                }
                wechatNotifyService.notifyTaskTerminal(taskId);
                sendPartialFailedSafely(taskId, resultJson, chainTerminalMessage(event, "提交失败"),
                        event.getChainChildTaskIds(), event.getChainChildTaskType());
                log.warn("本地批量任务完成但链式子任务提交失败: taskId={}, type={}, message={}",
                        taskId, spec.taskType, event.getChainMessage());
                return;
            }
            resultJson = appendChainSuccess(resultJson, event);
            if (!updateResult(taskId, dispatchToken, TASK_STATUS_SUCCEEDED, resultJson, null))
            {
                log.info("本地批量任务成功收口跳过，周期已变化: taskId={}", taskId);
                return;
            }
            wechatNotifyService.notifyTaskTerminal(taskId);
            sendCompleteSafely(taskId, resultJson, event.getChainChildTaskIds(), event.getChainChildTaskType());
            log.info("本地批量任务完成: taskId={}, type={}", taskId, spec.taskType);
        }
        catch (Exception e)
        {
            log.error("本地批量任务失败: taskId={}, type={}, userId={}", taskId, spec.taskType, userId, e);
            com.aid.common.error.TaskErrorResult error = com.aid.common.error.ErrorNormalizer.normalize(e);
            if (!updateFailed(taskId, dispatchToken, error))
            {
                log.info("本地批量任务失败收口跳过，周期已变化: taskId={}", taskId);
                return;
            }
            wechatNotifyService.notifyTaskTerminal(taskId);
            if (onFailureReleaseLocks != null)
            {
                try { onFailureReleaseLocks.run(); }
                catch (Exception ignore) { /* 锁释放失败不阻断终态 */ }
            }
            sseManager.sendError(taskId, error);
        }
        finally
        {
            try { taskQueueService.releaseSlots(taskId, dispatchToken); }
            catch (Exception ex) { log.warn("本地批量任务释放名额异常(不影响业务): taskId={}", taskId, ex); }
        }
    }

    /**
     * 发布提示词批量终态事件（合并链式触发器监听，解耦避免循环依赖）。
     * Spring 同步事件：publishEvent 阻塞直到监听器（StoryboardStepChainService）执行完出图/出片触发，
     * 并把子任务 ID 回填进 event。发布方读回填字段塞进终态事件 payload。
     *
     * @return 已回填链式子任务信息的 event（触发失败/非合并任务时 chain 字段为 null）
     */
    private BatchPromptTerminalEvent publishTerminal(Long taskId, String taskType, String status)
    {
        BatchPromptTerminalEvent event = new BatchPromptTerminalEvent(this, taskId, taskType, status);
        boolean publishFailed = false;
        try { eventPublisher.publishEvent(event); }
        catch (Exception e)
        {
            publishFailed = true;
            log.warn("发布批量终态事件异常(不阻断): taskId={}, type={}", taskId, taskType, e);
        }
        if (publishFailed && !event.isChainRequested() && hasChainNext(taskId))
        {
            event.setChainRequested(true);
            event.setChainFailed(true);
            event.setChainMessage("提交失败");
            event.setChainChildTaskType(chainChildType(taskType));
        }
        return event;
    }

    private boolean hasChainNext(Long taskId)
    {
        if (Objects.isNull(taskId))
        {
            return false;
        }
        try
        {
            AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
            if (Objects.isNull(task) || StrUtil.isBlank(task.getInputSnapshot()))
            {
                return false;
            }
            JsonNode chainNode = OBJECT_MAPPER.readTree(task.getInputSnapshot()).path("chainNext");
            return !chainNode.isMissingNode() && !chainNode.isNull() && !chainNode.isEmpty();
        }
        catch (Exception ex)
        {
            log.warn("本地批量终态事件异常后解析chainNext失败: taskId={}, err={}", taskId, ex.getMessage());
            return false;
        }
    }

    private String chainChildType(String taskType)
    {
        if (TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH.equals(taskType))
        {
            return CHILD_TASK_TYPE_IMAGE;
        }
        if (TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH.equals(taskType))
        {
            return CHILD_TASK_TYPE_VIDEO;
        }
        return null;
    }

    /**
     * 原子领取 LOCAL 任务。本地队列包装层可能已提前完成领取，因此 PROCESSING + 同令牌也视为幂等成功。
     */
    private boolean claimExecution(Long taskId, String dispatchToken)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.eq(AidExtractTask::getStatus, "PENDING");
        update.eq(AidExtractTask::getBillingTraceId, dispatchToken);
        update.set(AidExtractTask::getStatus, TASK_STATUS_PROCESSING);
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        if (extractTaskService.getBaseMapper().update(null, update) > 0)
        {
            return true;
        }

        AidExtractTask current = extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getStatus,
                                AidExtractTask::getBillingTraceId)
                        .eq(AidExtractTask::getId, taskId)
                        .last("LIMIT 1"), false);
        return Objects.nonNull(current)
                && Objects.equals(TASK_STATUS_PROCESSING, current.getStatus())
                && Objects.equals(dispatchToken, current.getBillingTraceId());
    }

    private boolean claimFinalization(Long taskId, String dispatchToken)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.eq(AidExtractTask::getBillingTraceId, dispatchToken);
        update.eq(AidExtractTask::getStatus, TASK_STATUS_PROCESSING);
        update.set(AidExtractTask::getStatus, TASK_STATUS_FINALIZING);
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        return extractTaskService.getBaseMapper().update(null, update) > 0;
    }

    /** 终态 + resultData 一起落库（成功 / 部分失败 / 取消）。 */
    private boolean updateResult(Long taskId, String dispatchToken, String status,
                                 String resultJson, String errorMessage)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.eq(AidExtractTask::getBillingTraceId, dispatchToken);
        update.in(AidExtractTask::getStatus, TASK_STATUS_PROCESSING, TASK_STATUS_FINALIZING);
        update.set(AidExtractTask::getStatus, status);
        if (Objects.nonNull(resultJson))
        {
            update.set(AidExtractTask::getResultData, resultJson);
        }
        if (Objects.nonNull(errorMessage))
        {
            update.set(AidExtractTask::getErrorMessage, errorMessage);
        }
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        return extractTaskService.getBaseMapper().update(null, update) > 0;
    }

    /** 失败终态：存归一化后的短文案。 */
    private boolean updateFailed(Long taskId, String dispatchToken,
                                 com.aid.common.error.TaskErrorResult error)
    {
        String msg = Objects.isNull(error) ? "生成失败" : StrUtil.blankToDefault(error.getUserMessage(), "生成失败");
        return updateResult(taskId, dispatchToken, TASK_STATUS_FAILED, null, StrUtil.sub(msg, 0, 80));
    }

    private void sendPartialFailedSafely(Long taskId, String resultJson, String message,
                                         List<Long> chainChildTaskIds, String chainChildTaskType)
    {
        try { sseManager.sendPartialFailed(taskId, OBJECT_MAPPER.readValue(resultJson, Map.class), message, chainChildTaskIds, chainChildTaskType); }
        catch (Exception ex) { sseManager.sendPartialFailed(taskId, resultJson, message, chainChildTaskIds, chainChildTaskType); }
    }

    private void sendCompleteSafely(Long taskId, String resultJson, List<Long> chainChildTaskIds, String chainChildTaskType)
    {
        try { sseManager.sendComplete(taskId, OBJECT_MAPPER.readValue(resultJson, Map.class), chainChildTaskIds, chainChildTaskType); }
        catch (Exception ex) { sseManager.sendComplete(taskId, resultJson, chainChildTaskIds, chainChildTaskType); }
    }

    /** 将链式子任务提交失败原因追加到父任务结果中。 */
    private String appendChainSuccess(String resultJson, BatchPromptTerminalEvent event)
    {
        if (Objects.isNull(event) || CollectionUtil.isEmpty(event.getChainChildTaskIds()))
        {
            return resultJson;
        }
        try
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = StrUtil.isBlank(resultJson)
                    ? new java.util.LinkedHashMap<>()
                    : OBJECT_MAPPER.readValue(resultJson, Map.class);
            result.remove("chainFailed");
            result.remove("chainMessage");
            result.put("chainChildTaskId", event.getChainChildTaskId());
            result.put("chainChildTaskIds", event.getChainChildTaskIds());
            result.put("chainChildTaskType", event.getChainChildTaskType());
            return OBJECT_MAPPER.writeValueAsString(result);
        }
        catch (Exception e)
        {
            log.warn("追加链式子任务信息异常: err={}", e.getMessage());
            return resultJson;
        }
    }

    private String appendChainFailure(String resultJson, BatchPromptTerminalEvent event)
    {
        if (Objects.isNull(event) || !event.isChainFailed())
        {
            return resultJson;
        }
        try
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = StrUtil.isBlank(resultJson)
                    ? new java.util.LinkedHashMap<>()
                    : OBJECT_MAPPER.readValue(resultJson, Map.class);
            result.put("chainFailed", true);
            result.put("chainMessage", chainTerminalMessage(event, "提交失败"));
            if (StrUtil.isNotBlank(event.getChainChildTaskType()))
            {
                result.put("chainChildTaskType", event.getChainChildTaskType());
            }
            if (CollectionUtil.isNotEmpty(event.getChainChildTaskIds()))
            {
                result.put("chainChildTaskId", event.getChainChildTaskId());
                result.put("chainChildTaskIds", event.getChainChildTaskIds());
            }
            return OBJECT_MAPPER.writeValueAsString(result);
        }
        catch (Exception e)
        {
            log.warn("追加链式任务失败原因异常: err={}", e.getMessage());
            return resultJson;
        }
    }

    private String chainTerminalMessage(BatchPromptTerminalEvent event, String fallback)
    {
        if (Objects.nonNull(event) && event.isChainFailed() && StrUtil.isNotBlank(event.getChainMessage()))
        {
            return event.getChainMessage();
        }
        return fallback;
    }

    /** result_data 是否含失败项（successCount + skipCount < totalCount）。 */
    private boolean hasFailedItems(String resultJson)
    {
        if (StrUtil.isBlank(resultJson)) { return false; }
        try
        {
            Map<?, ?> result = OBJECT_MAPPER.readValue(resultJson, Map.class);
            int t = intVal(result.get("totalCount"));
            int s = intVal(result.get("successCount"));
            int k = intVal(result.get("skipCount"));
            return t > 0 && (s + k) < t;
        }
        catch (Exception e)
        {
            log.warn("本地批量任务结果解析失败（按无失败项处理）: err={}", e.getMessage());
            return false;
        }
    }

    /** result_data 是否存在被取消跳过的项（successCount + failCount < totalCount）。 */
    private boolean hasBatchSkippedItems(String resultJson)
    {
        if (StrUtil.isBlank(resultJson)) { return false; }
        try
        {
            Map<?, ?> result = OBJECT_MAPPER.readValue(resultJson, Map.class);
            int t = intVal(result.get("totalCount"));
            int s = intVal(result.get("successCount"));
            int f = intVal(result.get("failCount"));
            return t > 0 && (s + f) < t;
        }
        catch (Exception e)
        {
            log.warn("本地批量任务跳过项解析失败（按无跳过处理）: err={}", e.getMessage());
            return false;
        }
    }

    private int intVal(Object v)
    {
        return (v instanceof Number) ? ((Number) v).intValue() : 0;
    }
}
