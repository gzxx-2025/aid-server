package com.aid.consumer;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.billing.model.BillingSnapshot;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.utils.DateUtils;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.rps.dto.ExtractTaskMessage;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.service.IExtractBillingService;
import com.aid.rps.service.IRpsFormImageBusinessService;
import com.aid.rps.service.impl.RpsFormImageBusinessServiceImpl;
import com.aid.rps.sse.AssetExtractSseManager;
import com.aid.rps.vo.RpsAssetVO;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.beans.factory.annotation.Value;

/**
 * 资产提取 / 图片高清 MQ 消费者
 * <p>
 * 使用 @RocketMQMessageListener 注解声明式消费，
 * 由 rocketmq-spring-boot-starter 自动扫描并创建消费者容器。
 * 连接参数从 application.yml 的 rocketmq 前缀自动注入。
 * </p>
 * <p>
 * 支持两类任务（同一 topic，不同 tag）：
 * <ul>
 *   <li>tag=extract —— 资产提取（原有）</li>
 *   <li>tag=image_upscale —— 图片高清（v2.24.0 新增）</li>
 * </ul>
 * 按 task.taskType 分发到不同的业务 Service。
 * </p>
 * <p>
 * 注意：onMessage 方法本身不加 @Transactional。
 * 核心提取逻辑 doExtract() 自带事务，失败时内部事务回滚。
 * 状态更新（PROCESSING/FAILED/SUCCEEDED）使用独立新事务，
 * 避免外层事务被标记 rollback-only 导致状态更新也一起回滚。
 * </p>
 *
 * @author aid_author
 * @date 2026-04-15
 */
/**
 * v2.59.0：消费并发可配置化。
 * <p>
 * RocketMQ 此版本（2.3.1）下 {@code @RocketMQMessageListener.consumeThreadMax} 是编译期常量、不可注入，
 * 因此改为实现 {@link RocketMQPushConsumerLifecycleListener}，在容器启动回调里用
 * {@code consumer.setConsumeThreadMax/Min(...)} 从配置（aid.taskq.mq.consume-thread-max）动态覆盖，
 * 注解与 RocketMQ 版本均不变。
 * </p>
 * <p>
 * 约定：消费并发应 ≥ 全局并发上限，让 MQ 永不成为业务瓶颈（真正的闸门在调度层 TaskQueueService）。
 * </p>
 */
@Slf4j
@Service
@RocketMQMessageListener(topic = "ASSET_EXTRACT_TOPIC",
        selectorExpression = "extract || image_upscale",
        consumerGroup = "asset_extract_consumer_group",
        consumeThreadMax = 20
)
public class AssetExtractConsumer implements RocketMQListener<String>, RocketMQPushConsumerLifecycleListener
{

    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
    /** 用户主动取消（区分于 FAILED） */
    private static final String TASK_STATUS_CANCELLED = "CANCELLED";
    /** 任务类型：图片高清 */
    private static final String TASK_TYPE_IMAGE_UPSCALE = "image_upscale";
    /** 任务类型：批量形态生成父任务 */
    private static final String TASK_TYPE_FORM_GENERATE_BATCH = "form_generate_batch";
    /** 任务类型：批量形态图生成父任务 */
    private static final String TASK_TYPE_FORM_IMAGE_BATCH = "form_image_batch";
    /** 任务类型：批量角色设定卡生成父任务 */
    private static final String TASK_TYPE_FORM_CARD_IMAGE_BATCH = "form_card_image_batch";
    /** 任务类型：批量分镜脚本生成父任务 */
    private static final String TASK_TYPE_STORYBOARD_SCRIPT_BATCH = "storyboard_script_batch";

    /** 任务类型：批量分镜图脚本（图生图 prompt）生成父任务 */
    private static final String TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH = "storyboard_image_prompt_batch";

    /** 任务类型：批量分镜视频提示词生成父任务（视觉导演v3.0） */
    private static final String TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH = "storyboard_video_prompt_batch";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 消费最大线程数（可配置，默认 20；约定 ≥ 全局并发上限）。 */
    @Value("${aid.taskq.mq.consume-thread-max:20}")
    private int consumeThreadMax;

    /** 消费最小线程数（可配置，默认 20）。 */
    @Value("${aid.taskq.mq.consume-thread-min:20}")
    private int consumeThreadMin;

    /**
     * RocketMQ 消费者容器启动前回调：用配置覆盖消费并发。
     * <p>不更换 RocketMQ 版本、不改注解，仅在运行期把线程数调成可配置值。</p>
     */
    @Override
    public void prepareStart(DefaultMQPushConsumer consumer)
    {
        int max = consumeThreadMax > 0 ? consumeThreadMax : 20;
        int min = consumeThreadMin > 0 ? Math.min(consumeThreadMin, max) : max;
        consumer.setConsumeThreadMax(max);
        consumer.setConsumeThreadMin(min);
        log.info("[v2.59.0] RocketMQ 消费并发已设置: consumeThreadMin={}, consumeThreadMax={}", min, max);
    }

    @Autowired
    private IAidExtractTaskService extractTaskService;

    @Autowired
    private IAssetExtractService assetExtractService;

    @Autowired
    private com.aid.rps.service.IStoryboardScriptService storyboardScriptService;

    @Autowired
    private com.aid.rps.service.IStoryboardImagePromptService storyboardImagePromptService;

    @Autowired
    private com.aid.rps.service.IStoryboardVideoPromptService storyboardVideoPromptService;

    @Autowired
    private IRpsFormImageBusinessService rpsFormImageBusinessService;

    @Autowired
    private AssetExtractSseManager sseManager;

    @Autowired
    private IWechatNotifyService wechatNotifyService;

    @Autowired
    private IExtractBillingService extractBillingService;

    @Autowired
    private AidMediaTaskMapper aidMediaTaskMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RedisCache redisCache;

    /** 步骤合并链式触发器：提示词批量终态后自动发起出图/出视频（仅合并任务生效，自带过滤与幂等）。 */
    @Autowired
    private com.aid.storyboard.service.impl.StoryboardStepChainService storyboardStepChainService;

    @Override
    public void onMessage(String messageBody)
    {
        log.info("收到提取任务消息: body={}", messageBody);

        ExtractTaskMessage message;
        Long taskIdFromBody = null;
        try
        {
            message = OBJECT_MAPPER.readValue(messageBody, ExtractTaskMessage.class);
            taskIdFromBody = message == null ? null : message.getTaskId();
        }
        catch (Exception e)
        {
            // CX7：反序列化失败时尝试正则抽 taskId 兜底标记 FAILED + 退款，
            // 避免消息体格式错误时任务永远卡 PENDING。
            log.error("反序列化提取任务消息失败: body={}", messageBody, e);
            Long fallbackTaskId = extractTaskIdFromBrokenMessage(messageBody);
            if (fallbackTaskId != null)
            {
                failTaskAndRefund(fallbackTaskId, "消息格式错误");
            }
            // 直接 return，不抛异常给 MQ；让 broker 把消息 ack 掉避免无限重试
            return;
        }

        Long taskId = message.getTaskId();
        Long userId = message.getUserId();

        // 1. 幂等校验：只有 PENDING 状态才执行
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task))
        {
            // 任务不存在：可能是 producer 端外层 @Transactional 还没提交（broker 比 commit 早），
            // 或者真的被删除了。两种情况都让 RocketMQ 自动重投：
            //   - 事务可见性场景：稍后重投时即可读到，正常进入处理；
            //   - 真删除场景：重投仍读不到，进入 DLQ 后人工排查。
            // 抛 RuntimeException 让 rocketmq-spring 把消息标记为 RECONSUME_LATER。
            log.warn("任务尚不可见或不存在，触发MQ重投: taskId={}", taskId);
            throw new RuntimeException("任务尚不可见或不存在: taskId=" + taskId);
        }
        if (Objects.isNull(task.getStatus()))
        {
            // 极少数情况：事务半提交导致 entity 字段为 null，等价于上面"尚不可见"，重投。
            log.warn("任务status为null（事务未完全可见），触发MQ重投: taskId={}", taskId);
            throw new RuntimeException("任务尚未完全可见: taskId=" + taskId);
        }
        if (!Objects.equals(TASK_STATUS_PENDING, task.getStatus()))
        {
            log.info("跳过非PENDING任务: taskId={}, status={}", taskId, task.getStatus());
            return;
        }

        // CX7：handleXxx 内部已有自己的 try-catch + 锁释放兜底，这里再加一层最外层保险，
        // 确保 RuntimeException / Error 都不会让消息以"成功消费"的姿态被 ack 走但任务却没被清理。
        // 任意未处理异常 → 标记 FAILED + 退款 + 释放锁，然后吞掉异常返回（不让 MQ 进 16 次重试 + DLQ 黑洞）。
        try
        {
            // v2.59.0：登记执行租约（重启自愈据租约判活），handler 内会 CAS 进 PROCESSING
            assetExtractService.markTaskProcessing(taskId);
            // 2. 按任务类型分发
            String taskType = task.getTaskType();
            if (TASK_TYPE_IMAGE_UPSCALE.equals(taskType))
            {
                handleImageUpscale(taskId, userId);
            }
            else if (TASK_TYPE_FORM_GENERATE_BATCH.equals(taskType))
            {
                handleFormGenerateBatch(taskId, userId);
            }
            else if (TASK_TYPE_FORM_IMAGE_BATCH.equals(taskType))
            {
                handleFormImageBatch(taskId, userId);
            }
            else if (TASK_TYPE_FORM_CARD_IMAGE_BATCH.equals(taskType))
            {
                handleFormCardImageBatch(taskId, userId);
            }
            else if (TASK_TYPE_STORYBOARD_SCRIPT_BATCH.equals(taskType))
            {
                handleStoryboardScriptBatch(taskId, userId);
            }
            else if (TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH.equals(taskType))
            {
                handleStoryboardImagePromptBatch(taskId, userId);
            }
            else if (TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH.equals(taskType))
            {
                handleStoryboardVideoPromptBatch(taskId, userId);
            }
            else
            {
                handleExtract(taskId, message, userId);
            }
        }
        catch (Throwable outerEx)
        {
            log.error("[CX7] MQ 消费顶层兜底异常: taskId={}, userId={}", taskId, userId, outerEx);
            try
            {
                failTaskAndRefund(taskId, "consumer 异常: " + outerEx.getClass().getSimpleName());
            }
            catch (Exception cleanupEx)
            {
                log.error("[CX7] 顶层兜底清理失败: taskId={}", taskId, cleanupEx);
            }
            // 不重抛——让消息被 ack 掉，避免反复重试 16 次后才知道任务挂了
        }
        finally
        {
            // v2.59.0：handler 返回时任务已是终态（SUCCEEDED/FAILED/CANCELLED/PARTIAL_FAILED），
            // 统一释放多维并发名额 + 执行租约（幂等），让排队任务尽快递补。
            try
            {
                assetExtractService.releaseTaskSlots(taskId);
            }
            catch (Exception slotEx)
            {
                log.warn("[v2.59.0] 释放并发名额异常(不影响业务): taskId={}", taskId, slotEx);
            }
        }
    }

    /**
     * CX7 兜底：从损坏的消息体里正则抽 taskId。
     * <p>消息格式约定为 JSON {"taskId":266,...}，正则匹配 "taskId":数字。</p>
     */
    private Long extractTaskIdFromBrokenMessage(String body)
    {
        if (body == null) { return null; }
        try
        {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"taskId\"\\s*:\\s*(\\d+)").matcher(body);
            if (m.find())
            {
                return Long.parseLong(m.group(1));
            }
        }
        catch (Exception ignore) { }
        return null;
    }

    /**
     * CX7 兜底：把任务标记 FAILED + 退冻结 + 释放锁。
     * <p>用于"消息损坏"和"顶层未捕获异常"两个场景的统一清理。所有步骤独立 try-catch 不互相影响。</p>
     */
    private void failTaskAndRefund(Long taskId, String reason)
    {
        if (taskId == null) { return; }
        AidExtractTask task = null;
        try
        {
            task = extractTaskService.selectAidExtractTaskById(taskId);
        }
        catch (Exception ex)
        {
            log.error("[CX7] 兜底加载任务失败, taskId={}", taskId, ex);
        }
        // 1. 标记 FAILED（CAS PENDING/PROCESSING → FAILED）
        try
        {
            LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
            update.eq(AidExtractTask::getId, taskId);
            update.in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING);
            update.set(AidExtractTask::getStatus, TASK_STATUS_FAILED);
            update.set(AidExtractTask::getErrorMessage, reason);
            update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            extractTaskService.getBaseMapper().update(null, update);
        }
        catch (Exception ex)
        {
            log.error("[CX7] 兜底标记 FAILED 失败, taskId={}", taskId, ex);
        }
        if (task == null) { return; }
        // 2. 退冻结
        if (task.getUserId() != null)
        {
            try
            {
                extractBillingService.refundBilling(taskId, task.getUserId());
            }
            catch (Exception ex)
            {
                log.error("[CX7] 兜底退款失败, taskId={}", taskId, ex);
            }
        }
        // 3. 释放对应类型的 Redis 锁
        try
        {
            String type = task.getTaskType();
            if (type == null || "asset_extract".equals(type)
                    || TASK_TYPE_FORM_GENERATE_BATCH.equals(type)
                    || TASK_TYPE_FORM_IMAGE_BATCH.equals(type)
                    || TASK_TYPE_STORYBOARD_SCRIPT_BATCH.equals(type))
            {
                assetExtractService.releaseExtractLock(task.getProjectId(), task.getEpisodeId());
            }
            else if (TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH.equals(type))
            {
                // 分镜图脚本批量任务用独立的 storyboard:image_prompt:lock，
                // 复用 releaseBatchFormLocks 的统一释放分支
                assetExtractService.releaseBatchFormLocks(task.getId(), type);
            }
            else if (TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH.equals(type))
            {
                // 视频提示词批量任务用独立的 storyboard:video_prompt:lock
                assetExtractService.releaseBatchFormLocks(task.getId(), type);
            }
            else if (TASK_TYPE_FORM_CARD_IMAGE_BATCH.equals(type))
            {
                // 批量设定卡父任务：释放所有子项（白底图 imageId）防重锁
                assetExtractService.releaseBatchFormLocks(task.getId(), type);
            }
            else if (TASK_TYPE_IMAGE_UPSCALE.equals(type))
            {
                Long imageId = resolveImageIdFromInputSnapshot(task.getInputSnapshot());
                if (imageId != null)
                {
                    redisCache.deleteObject(RpsFormImageBusinessServiceImpl.buildUpscaleLockKey(imageId));
                }
            }
        }
        catch (Exception ex)
        {
            log.error("[CX7] 兜底释放 Redis 锁失败, taskId={}", taskId, ex);
        }
        // 4. 推送 SSE 终态（v2.60.9）：已连接前端只轮询 Redis 快照、不读 DB，
        //    顶层兜底若不写终态事件，前端会一直等到 5 分钟超时。这里补一次结构化 error 终态。
        try
        {
            com.aid.common.error.TaskErrorResult errorResult =
                    com.aid.common.error.ErrorNormalizer.normalize(new RuntimeException(reason));
            sseManager.sendError(taskId, errorResult);
        }
        catch (Exception ex)
        {
            log.warn("[CX7] 兜底推送 SSE 终态失败(不影响清理): taskId={}", taskId, ex);
        }
        wechatNotifyService.notifyTaskTerminal(taskId);
    }

    /**
     * 从 inputSnapshot JSON 中提取 imageId（用于 image_upscale 任务释放锁）。
     */
    private Long resolveImageIdFromInputSnapshot(String snapshot)
    {
        if (StrUtil.isBlank(snapshot)) { return null; }
        try
        {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"imageId\"\\s*:\\s*(\\d+)").matcher(snapshot);
            if (m.find())
            {
                return Long.parseLong(m.group(1));
            }
        }
        catch (Exception ignore) { }
        return null;
    }

    // ==================== 资产提取（原有逻辑） ====================

    private void handleExtract(Long taskId, ExtractTaskMessage message, Long userId)
    {
        // 用新事务更新为 PROCESSING（不依赖外层事务）
        updateTaskInNewTransaction(taskId, TASK_STATUS_PROCESSING, (String) null, userId);

        // CX6 修复：将 releaseExtractLock 放进 try-finally，无论成功/失败/取消/JVM 异常退出，
        // 锁都会在终态时显式释放，避免锁泄漏让用户被卡 15 分钟。
        boolean lockReleased = false;
        try
        {
            // 执行核心提取逻辑（doExtract 自带 @Transactional，失败自动回滚其内部操作）
            List<RpsAssetVO> results = assetExtractService.doExtract(
                    taskId, message.getProjectId(), message.getEpisodeId(), userId);

            // 用户主动取消：doExtract 在循环检查点 break 后返回部分结果。
            // 此时按"已开始项正常计费"原则，按实际 token 用量结算，但状态标记为 CANCELLED 而非 SUCCEEDED。
            if (assetExtractService.isTaskCancelled(taskId))
            {
                updateTaskInNewTransaction(taskId, TASK_STATUS_CANCELLED, "用户取消", userId);
                sseManager.sendCancelled(taskId, "用户取消");
                // 已开始的 LLM 调用按实际用量结算（差额退回）
                try
                {
                    Map<String, Object> usageData = aggregateTokenUsage(taskId);
                    log.info("MQ提取被取消，按实际用量结算: taskId={}, usageData={}", taskId, usageData);
                    extractBillingService.settleBilling(taskId, userId, usageData);
                }
                catch (Exception billingEx)
                {
                    log.error("MQ取消结算失败（不影响业务结果）, taskId={}, userId={}", taskId, userId, billingEx);
                }
                // 清理：释放 extract 锁 + cancel flag，确保用户可立即重新提取
                cleanupCancelledExtractTask(taskId, message.getProjectId(), message.getEpisodeId());
                lockReleased = true;
                log.info("MQ消费提取被取消: taskId={}, partialCount={}", taskId, results.size());
                return;
            }

            // 成功：新事务更新结果
            updateTaskSuccess(taskId, results.size(), OBJECT_MAPPER.writeValueAsString(results));
            // 释放 extract 锁（成功路径也需要主动释放，避免等 TTL）
            assetExtractService.releaseExtractLock(message.getProjectId(), message.getEpisodeId());
            lockReleased = true;
            sseManager.sendComplete(taskId, results);

            // 任务级差额结算：聚合实际 token usage，SKU 定价差额退回（独立 try-catch）
            try
            {
                Map<String, Object> usageData = aggregateTokenUsage(taskId);
                log.info("MQ提取主任务结算前usageData: taskId={}, usageData={}", taskId, usageData);
                extractBillingService.settleBilling(taskId, userId, usageData);
            }
            catch (Exception billingEx)
            {
                log.error("MQ消费提取结算失败（不影响业务结果）, taskId={}, userId={}", taskId, userId, billingEx);
            }

            log.info("MQ消费提取完成: taskId={}, count={}", taskId, results.size());
        }
        catch (com.aid.rps.exception.PartialExtractionException partialEx)
        {
            // ★ v2.51.0：场景道具提取部分完成——已成功 chunk 的资产已落库，标记 PARTIAL_FAILED
            log.warn("MQ消费提取部分完成: taskId={}, partialCount={}", taskId, partialEx.getPartialAssets().size());
            try
            {
                String partialResultJson = OBJECT_MAPPER.writeValueAsString(partialEx.getPartialAssets());
                updateTaskInNewTransaction(taskId, TASK_STATUS_PARTIAL_FAILED, partialEx.getMessage(), userId);
                // 把已成功的资产列表写入 result_data
                com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.aid.aid.domain.AidExtractTask> partialUpd =
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate();
                partialUpd.eq(com.aid.aid.domain.AidExtractTask::getId, taskId);
                partialUpd.set(com.aid.aid.domain.AidExtractTask::getResultData, partialResultJson);
                partialUpd.set(com.aid.aid.domain.AidExtractTask::getTotalCount, partialEx.getPartialAssets().size());
                extractTaskService.update(partialUpd);
                wechatNotifyService.notifyTaskTerminal(taskId);
            }
            catch (Exception updateEx)
            {
                log.error("MQ消费部分完成状态更新异常: taskId={}", taskId, updateEx);
            }
            // 按已消耗 token 结算（差额退回未消耗部分）
            try
            {
                Map<String, Object> usageData = aggregateTokenUsage(taskId);
                log.info("MQ提取部分完成结算: taskId={}, usageData={}", taskId, usageData);
                extractBillingService.settleBilling(taskId, userId, usageData);
            }
            catch (Exception billingEx)
            {
                log.error("MQ消费部分完成结算失败: taskId={}, userId={}", taskId, userId, billingEx);
            }
            sseManager.sendPartialFailed(taskId, partialEx.getPartialAssets(),
                    "提取部分完成，可继续生成（已提取" + partialEx.getPartialAssets().size() + "个资产）");
        }
        catch (Exception e)
        {
            log.error("MQ消费提取失败: taskId={}, userId={}", taskId, userId, e);
            // 使用 ErrorNormalizer 归一化错误
            com.aid.common.error.TaskErrorResult errorResult = com.aid.common.error.ErrorNormalizer.normalize(e);
            // 失败：新事务更新状态为 FAILED（结构化版本，不受 doExtract 事务回滚影响）
            updateTaskInNewTransaction(taskId, TASK_STATUS_FAILED, errorResult, userId);
            // 任务级退回（独立 try-catch，避免吞掉锁释放）
            try
            {
                extractBillingService.refundBilling(taskId, userId);
            }
            catch (Exception refundEx)
            {
                log.error("MQ消费失败后退款异常, taskId={}, userId={}", taskId, userId, refundEx);
            }
            sseManager.sendError(taskId, errorResult);
        }
        finally
        {
            // 兜底释放：成功/取消路径已显式释放（lockReleased=true）；
            // 失败 / 中间异常 / sseManager 抛错等情况都走这里释放，杜绝锁泄漏。
            if (!lockReleased)
            {
                try
                {
                    assetExtractService.releaseExtractLock(message.getProjectId(), message.getEpisodeId());
                }
                catch (Exception lockEx)
                {
                    log.error("MQ消费finally兜底释放extract锁异常, taskId={}, projectId={}, episodeId={}",
                            taskId, message.getProjectId(), message.getEpisodeId(), lockEx);
                }
            }
        }
    }

    /**
     * 提取任务被取消后的清理：释放 extract 锁 + 清除 cancel flag。
     * 两个操作分别 try-catch，保证锁释放不被 cancel flag 清除异常阻断。
     */
    private void cleanupCancelledExtractTask(Long taskId, Long projectId, Long episodeId)
    {
        try
        {
            assetExtractService.releaseExtractLock(projectId, episodeId);
        }
        catch (Exception e)
        {
            log.warn("MQ取消后释放extract锁异常: taskId={}, projectId={}, episodeId={}", taskId, projectId, episodeId, e);
        }
        try
        {
            assetExtractService.clearCancelFlag(taskId);
        }
        catch (Exception e)
        {
            log.warn("MQ取消后清除cancelFlag异常: taskId={}", taskId, e);
        }
    }

    // ==================== 批量形态生成父任务（v2.28.0） ====================

    /**
     * 处理批量形态生成父任务。
     * <p>
     * 流程：CAS PENDING→PROCESSING → SSE进度 → 逐项执行 doFormGenerateBatch →
     * 成功 SUCCEEDED → SSE complete。取消 → CANCELLED。失败 → FAILED。
     * 计费由 doFormGenerateBatch 内部按项处理（未做父任务级预冻结）。
     * </p>
     */
    private void handleFormGenerateBatch(Long taskId, Long userId)
    {
        updateTaskInNewTransaction(taskId, TASK_STATUS_PROCESSING, (String) null, userId);
        sseManager.sendStepProgress(taskId, "form_generate", 5,
                "init", "初始化批量形态生成...", 0, 1);

        try
        {
            // 核心逻辑由 Service 执行，返回 resultData JSON
            String resultJson = assetExtractService.doFormGenerateBatch(taskId, userId);

            // 判断是否被取消：只有真的跳过了剩余项才算 CANCELLED，否则仍算 SUCCEEDED
            if (assetExtractService.isTaskCancelled(taskId))
            {
                assetExtractService.clearCancelFlag(taskId);
                if (hasBatchSkippedItems(resultJson))
                {
                    // 确实有未开始任务被跳过 → CANCELLED + 保留已完成部分结果
                    updateTaskCancelledWithResult(taskId, resultJson);
                    sseManager.sendCancelled(taskId, "剩余任务已取消");
                    log.info("MQ批量形态生成被取消(有剩余项被跳过): taskId={}", taskId);
                    return;
                }
                // 用户点了停止但当前项已是最后一项，全部已处理完 → 按成功结算
                log.info("MQ批量形态生成: 取消标记已清除，全部项目已处理完毕，按成功结算: taskId={}", taskId);
            }

            // 成功（即使有部分失败也记 SUCCEEDED，失败明细在 resultData）
            updateTaskSuccessWithResult(taskId, resultJson);
            // 解析 resultData 作为 complete 事件推送
            try
            {
                Object resultObj = OBJECT_MAPPER.readValue(resultJson, Map.class);
                sseManager.sendComplete(taskId, resultObj);
            }
            catch (Exception e)
            {
                sseManager.sendComplete(taskId, resultJson);
            }
            log.info("MQ批量形态生成完成: taskId={}", taskId);
        }
        catch (Exception e)
        {
            log.error("MQ批量形态生成失败: taskId={}, userId={}", taskId, userId, e);
            com.aid.common.error.TaskErrorResult batchFormError = com.aid.common.error.ErrorNormalizer.normalize(e);
            updateTaskInNewTransaction(taskId, TASK_STATUS_FAILED, batchFormError, userId);
            // 异常中断：补释放所有剩余锁
            assetExtractService.releaseBatchFormLocks(taskId, TASK_TYPE_FORM_GENERATE_BATCH);
            sseManager.sendError(taskId, batchFormError);
        }
    }

    // ==================== 批量形态图生成父任务（v2.28.0） ====================

    /**
     * 处理批量形态图生成父任务。
     * <p>
     * 流程同 handleFormGenerateBatch，图片计费由媒体主链路内部处理。
     * </p>
     */
    private void handleFormImageBatch(Long taskId, Long userId)
    {
        updateTaskInNewTransaction(taskId, TASK_STATUS_PROCESSING, (String) null, userId);
        sseManager.sendStepProgress(taskId, "form_image_gen", 5,
                "init", "初始化批量形态图生成...", 0, 1);

        try
        {
            String resultJson = assetExtractService.doFormImageBatch(taskId, userId);

            // 判断是否被取消：只有真的跳过了剩余项才算 CANCELLED，否则仍算 SUCCEEDED
            if (assetExtractService.isTaskCancelled(taskId))
            {
                assetExtractService.clearCancelFlag(taskId);
                if (hasBatchSkippedItems(resultJson))
                {
                    // 确实有未开始任务被跳过 → CANCELLED + 保留已完成部分结果
                    updateTaskCancelledWithResult(taskId, resultJson);
                    sseManager.sendCancelled(taskId, "剩余任务已取消");
                    log.info("MQ批量形态图生成被取消(有剩余项被跳过): taskId={}", taskId);
                    return;
                }
                // 用户点了停止但当前项已是最后一项，全部已处理完 → 按成功结算
                log.info("MQ批量形态图生成: 取消标记已清除，全部项目已处理完毕，按成功结算: taskId={}", taskId);
            }

            updateTaskSuccessWithResult(taskId, resultJson);
            try
            {
                Object resultObj = OBJECT_MAPPER.readValue(resultJson, Map.class);
                sseManager.sendComplete(taskId, resultObj);
            }
            catch (Exception e)
            {
                sseManager.sendComplete(taskId, resultJson);
            }
            log.info("MQ批量形态图生成完成: taskId={}", taskId);
        }
        catch (Exception e)
        {
            log.error("MQ批量形态图生成失败: taskId={}, userId={}", taskId, userId, e);
            com.aid.common.error.TaskErrorResult batchImgError = com.aid.common.error.ErrorNormalizer.normalize(e);
            updateTaskInNewTransaction(taskId, TASK_STATUS_FAILED, batchImgError, userId);
            assetExtractService.releaseBatchFormLocks(taskId, TASK_TYPE_FORM_IMAGE_BATCH);
            sseManager.sendError(taskId, batchImgError);
        }
    }

    // ==================== 批量角色设定卡生成父任务（白底图 → 设定卡） ====================

    /**
     * 处理批量角色设定卡生成父任务。
     * <p>
     * 流程同 handleFormImageBatch：CAS PENDING→PROCESSING → 逐张执行 doFormCardImageBatch →
     * 成功 SUCCEEDED → SSE complete；取消有剩余项 → CANCELLED；异常 → FAILED + 释放所有子项锁。
     * 图片计费由媒体主链路内部处理。
     * </p>
     */
    private void handleFormCardImageBatch(Long taskId, Long userId)
    {
        updateTaskInNewTransaction(taskId, TASK_STATUS_PROCESSING, (String) null, userId);
        sseManager.sendStepProgress(taskId, "form_card_image_gen", 5,
                "init", "初始化批量角色设定卡生成...", 0, 1);

        try
        {
            String resultJson = assetExtractService.doFormCardImageBatch(taskId, userId);

            // 判断是否被取消：只有真的跳过了剩余项才算 CANCELLED，否则仍算 SUCCEEDED
            if (assetExtractService.isTaskCancelled(taskId))
            {
                assetExtractService.clearCancelFlag(taskId);
                if (hasBatchSkippedItems(resultJson))
                {
                    // 确实有未开始任务被跳过 → CANCELLED + 保留已完成部分结果
                    updateTaskCancelledWithResult(taskId, resultJson);
                    sseManager.sendCancelled(taskId, "剩余任务已取消");
                    log.info("MQ批量设定卡生成被取消(有剩余项被跳过): taskId={}", taskId);
                    return;
                }
                // 用户点了停止但当前项已是最后一项，全部已处理完 → 按成功结算
                log.info("MQ批量设定卡生成: 取消标记已清除，全部项目已处理完毕，按成功结算: taskId={}", taskId);
            }

            // v2.60.8 同口径：部分（含全部）失败 → 推 PARTIAL_FAILED 终态，禁止用 complete
            // （否则前端会误判整批成功、丢失续生/重试入口；successCount=0 时也必须走这里）
            if (hasFailedItems(resultJson))
            {
                updateTaskPartialFailedWithResult(taskId, resultJson);
                try
                {
                    Object resultObj = OBJECT_MAPPER.readValue(resultJson, Map.class);
                    sseManager.sendPartialFailed(taskId, resultObj, "部分设定卡生成失败，可续生");
                }
                catch (Exception ex)
                {
                    sseManager.sendPartialFailed(taskId, resultJson, "部分设定卡生成失败，可续生");
                }
                log.info("MQ批量设定卡生成部分失败: taskId={}", taskId);
                return;
            }

            updateTaskSuccessWithResult(taskId, resultJson);
            try
            {
                Object resultObj = OBJECT_MAPPER.readValue(resultJson, Map.class);
                sseManager.sendComplete(taskId, resultObj);
            }
            catch (Exception e)
            {
                sseManager.sendComplete(taskId, resultJson);
            }
            log.info("MQ批量设定卡生成完成: taskId={}", taskId);
        }
        catch (Exception e)
        {
            log.error("MQ批量设定卡生成失败: taskId={}, userId={}", taskId, userId, e);
            com.aid.common.error.TaskErrorResult batchCardError = com.aid.common.error.ErrorNormalizer.normalize(e);
            updateTaskInNewTransaction(taskId, TASK_STATUS_FAILED, batchCardError, userId);
            assetExtractService.releaseBatchFormLocks(taskId, TASK_TYPE_FORM_CARD_IMAGE_BATCH);
            sseManager.sendError(taskId, batchCardError);
        }
    }

    // ==================== 批量分镜脚本生成（v2.50.0） ====================

    /**
     * 处理批量分镜脚本生成父任务。
     * <p>
     * 流程同 handleFormGenerateBatch：CAS PENDING→PROCESSING → 逐场景执行 → 成功/取消/失败。
     * 核心逻辑委托给 StoryboardScriptServiceImpl.doStoryboardScriptBatch。
     * </p>
     */
    private void handleStoryboardScriptBatch(Long taskId, Long userId)
    {
        updateTaskInNewTransaction(taskId, TASK_STATUS_PROCESSING, (String) null, userId);
        sseManager.sendStepProgress(taskId, "storyboard_script", 5,
                "init", "初始化分镜脚本批量生成...", 0, 1);

        try
        {
            String resultJson = storyboardScriptService.doStoryboardScriptBatch(taskId, userId);

            if (assetExtractService.isTaskCancelled(taskId))
            {
                assetExtractService.clearCancelFlag(taskId);
                if (hasBatchSkippedItems(resultJson))
                {
                    updateTaskCancelledWithResult(taskId, resultJson);
                    sseManager.sendCancelled(taskId, "剩余场景已取消");
                    log.info("MQ分镜脚本批量被取消: taskId={}", taskId);
                    return;
                }
                log.info("MQ分镜脚本批量: 取消标记已清除，全部场景已处理完毕: taskId={}", taskId);
            }

            // v2.60.8：部分失败 → 推 PARTIAL_FAILED（修复 P1：原先无条件 updateTaskSuccessWithResult
            // 会把 Service 内部已标的 PARTIAL_FAILED 覆盖成 SUCCEEDED，导致前端丢失续生入口判断）
            if (hasFailedItems(resultJson))
            {
                updateTaskPartialFailedWithResult(taskId, resultJson);
                try
                {
                    Object resultObj = OBJECT_MAPPER.readValue(resultJson, Map.class);
                    sseManager.sendPartialFailed(taskId, resultObj, "部分场景生成失败，可续生");
                }
                catch (Exception ex)
                {
                    sseManager.sendPartialFailed(taskId, resultJson, "部分场景生成失败，可续生");
                }
                log.info("MQ分镜脚本批量部分失败: taskId={}", taskId);
                return;
            }

            updateTaskSuccessWithResult(taskId, resultJson);
            try
            {
                Object resultObj = OBJECT_MAPPER.readValue(resultJson, Map.class);
                sseManager.sendComplete(taskId, resultObj);
            }
            catch (Exception e)
            {
                sseManager.sendComplete(taskId, resultJson);
            }
            log.info("MQ分镜脚本批量完成: taskId={}", taskId);
        }
        catch (Exception e)
        {
            log.error("MQ分镜脚本批量失败: taskId={}, userId={}", taskId, userId, e);
            com.aid.common.error.TaskErrorResult error = com.aid.common.error.ErrorNormalizer.normalize(e);
            updateTaskInNewTransaction(taskId, TASK_STATUS_FAILED, error, userId);
            assetExtractService.releaseBatchFormLocks(taskId, TASK_TYPE_STORYBOARD_SCRIPT_BATCH);
            sseManager.sendError(taskId, error);
        }
    }

    // ==================== 批量分镜图脚本（图生图 prompt）（v2.54.0） ====================

    /**
     * 处理批量分镜图脚本生成父任务。
     * <p>
     * 流程同 {@link #handleStoryboardScriptBatch}：CAS PENDING→PROCESSING → 逐镜执行 → 成功/取消/失败/部分失败。
     * 核心逻辑委托给 {@code StoryboardImagePromptServiceImpl.doStoryboardImagePromptBatch}。
     * </p>
     */
    private void handleStoryboardImagePromptBatch(Long taskId, Long userId)
    {
        updateTaskInNewTransaction(taskId, TASK_STATUS_PROCESSING, (String) null, userId);
        sseManager.sendStepProgress(taskId, "storyboard_image_prompt", 5,
                "init", "初始化分镜图脚本批量生成...", 0, 1);

        try
        {
            String resultJson = storyboardImagePromptService.doStoryboardImagePromptBatch(taskId, userId);

            if (assetExtractService.isTaskCancelled(taskId))
            {
                assetExtractService.clearCancelFlag(taskId);
                if (hasBatchSkippedItems(resultJson))
                {
                    updateTaskCancelledWithResult(taskId, resultJson);
                    sseManager.sendCancelled(taskId, "剩余镜头已取消");
                    log.info("MQ分镜图脚本批量被取消: taskId={}", taskId);
                    return;
                }
                log.info("MQ分镜图脚本批量: 取消标记已清除，全部镜头已处理完毕: taskId={}", taskId);
            }

            // 部分失败 → 推 PARTIAL_FAILED（与分镜脚本批量同口径）
            if (hasFailedItems(resultJson))
            {
                // 先同步触发链式下一步（出图），拿到子任务 ID 后连同 partial_failed 事件一起下发前端
                com.aid.storyboard.dto.ChainTriggerResult chain =
                        storyboardStepChainService.onPromptBatchTerminal(taskId, TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH, "PARTIAL_FAILED");
                resultJson = appendChainSuccess(resultJson, chain);
                resultJson = appendChainFailure(resultJson, chain);
                updateTaskPartialFailedWithResult(taskId, resultJson);
                // v2.60.8：部分失败必须推 partial_failed 终态，禁止用 complete（会让前端误判完全成功、丢失续生入口）
                try
                {
                    Object resultObj = OBJECT_MAPPER.readValue(resultJson, Map.class);
                    sseManager.sendPartialFailed(taskId, resultObj, "部分镜头生成失败，可续生", chain.getChildTaskIds(), chain.getChildTaskType());
                }
                catch (Exception ex)
                {
                    sseManager.sendPartialFailed(taskId, resultJson, "部分镜头生成失败，可续生", chain.getChildTaskIds(), chain.getChildTaskType());
                }
                log.info("MQ分镜图脚本批量部分失败: taskId={}", taskId);
                return;
            }

            // 先同步触发链式下一步（出图），拿到子任务 ID 后连同 complete 事件一起下发前端
            com.aid.storyboard.dto.ChainTriggerResult chain =
                    storyboardStepChainService.onPromptBatchTerminal(taskId, TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH, "SUCCEEDED");
            if (chain.isChainFailed())
            {
                resultJson = appendChainFailure(resultJson, chain);
                updateTaskPartialFailedWithResult(taskId, resultJson);
                String terminalMessage = chainTerminalMessage(chain, "出图提交失败");
                try
                {
                    Object resultObj = OBJECT_MAPPER.readValue(resultJson, Map.class);
                    sseManager.sendPartialFailed(taskId, resultObj, terminalMessage, chain.getChildTaskIds(), chain.getChildTaskType());
                }
                catch (Exception e)
                {
                    sseManager.sendPartialFailed(taskId, resultJson, terminalMessage, chain.getChildTaskIds(), chain.getChildTaskType());
                }
                log.warn("MQ分镜图提示词批量完成但自动出图提交失败: taskId={}, message={}", taskId, terminalMessage);
                return;
            }
            resultJson = appendChainSuccess(resultJson, chain);
            updateTaskSuccessWithResult(taskId, resultJson);
            try
            {
                Object resultObj = OBJECT_MAPPER.readValue(resultJson, Map.class);
                sseManager.sendComplete(taskId, resultObj, chain.getChildTaskIds(), chain.getChildTaskType());
            }
            catch (Exception e)
            {
                sseManager.sendComplete(taskId, resultJson, chain.getChildTaskIds(), chain.getChildTaskType());
            }
            log.info("MQ分镜图脚本批量完成: taskId={}", taskId);
        }
        catch (Exception e)
        {
            log.error("MQ分镜图脚本批量失败: taskId={}, userId={}", taskId, userId, e);
            com.aid.common.error.TaskErrorResult error = com.aid.common.error.ErrorNormalizer.normalize(e);
            updateTaskInNewTransaction(taskId, TASK_STATUS_FAILED, error, userId);
            assetExtractService.releaseBatchFormLocks(taskId, TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH);
            sseManager.sendError(taskId, error);
        }
    }

    // ==================== 批量分镜视频提示词（视觉导演v3.0） ====================

    /**
     * 处理批量分镜视频提示词生成父任务。
     * <p>流程同 handleStoryboardImagePromptBatch。</p>
     */
    private void handleStoryboardVideoPromptBatch(Long taskId, Long userId)
    {
        updateTaskInNewTransaction(taskId, TASK_STATUS_PROCESSING, (String) null, userId);
        sseManager.sendStepProgress(taskId, "storyboard_video_prompt", 5,
                "init", "初始化视频提示词批量生成...", 0, 1);

        try
        {
            String resultJson = storyboardVideoPromptService.doStoryboardVideoPromptBatch(taskId, userId);

            if (assetExtractService.isTaskCancelled(taskId))
            {
                assetExtractService.clearCancelFlag(taskId);
                if (hasBatchSkippedItems(resultJson))
                {
                    updateTaskCancelledWithResult(taskId, resultJson);
                    sseManager.sendCancelled(taskId, "剩余镜头已取消");
                    log.info("MQ视频提示词批量被取消: taskId={}", taskId);
                    return;
                }
            }

            if (hasFailedItems(resultJson))
            {
                // 先同步触发链式下一步（出片），拿到子任务 ID 后连同 partial_failed 事件一起下发前端
                com.aid.storyboard.dto.ChainTriggerResult chain =
                        storyboardStepChainService.onPromptBatchTerminal(taskId, TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH, "PARTIAL_FAILED");
                resultJson = appendChainSuccess(resultJson, chain);
                resultJson = appendChainFailure(resultJson, chain);
                updateTaskPartialFailedWithResult(taskId, resultJson);
                // v2.60.8：部分失败推 partial_failed 终态，禁止用 complete
                try { sseManager.sendPartialFailed(taskId, OBJECT_MAPPER.readValue(resultJson, Map.class), "部分镜头生成失败，可续生", chain.getChildTaskIds(), chain.getChildTaskType()); }
                catch (Exception ex) { sseManager.sendPartialFailed(taskId, resultJson, "部分镜头生成失败，可续生", chain.getChildTaskIds(), chain.getChildTaskType()); }
                log.info("MQ视频提示词批量部分失败: taskId={}", taskId);
                return;
            }

            // 先同步触发链式下一步（出片），拿到子任务 ID 后连同 complete 事件一起下发前端
            com.aid.storyboard.dto.ChainTriggerResult chain =
                    storyboardStepChainService.onPromptBatchTerminal(taskId, TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH, "SUCCEEDED");
            if (chain.isChainFailed())
            {
                resultJson = appendChainFailure(resultJson, chain);
                updateTaskPartialFailedWithResult(taskId, resultJson);
                String terminalMessage = chainTerminalMessage(chain, "视频提交失败");
                try { sseManager.sendPartialFailed(taskId, OBJECT_MAPPER.readValue(resultJson, Map.class), terminalMessage, chain.getChildTaskIds(), chain.getChildTaskType()); }
                catch (Exception e) { sseManager.sendPartialFailed(taskId, resultJson, terminalMessage, chain.getChildTaskIds(), chain.getChildTaskType()); }
                log.warn("MQ视频提示词批量完成但自动出片提交失败: taskId={}, message={}", taskId, terminalMessage);
                return;
            }
            resultJson = appendChainSuccess(resultJson, chain);
            updateTaskSuccessWithResult(taskId, resultJson);
            try { sseManager.sendComplete(taskId, OBJECT_MAPPER.readValue(resultJson, Map.class), chain.getChildTaskIds(), chain.getChildTaskType()); }
            catch (Exception e) { sseManager.sendComplete(taskId, resultJson, chain.getChildTaskIds(), chain.getChildTaskType()); }
            log.info("MQ视频提示词批量完成: taskId={}", taskId);
        }
        catch (Exception e)
        {
            log.error("MQ视频提示词批量失败: taskId={}, userId={}", taskId, userId, e);
            com.aid.common.error.TaskErrorResult error = com.aid.common.error.ErrorNormalizer.normalize(e);
            updateTaskInNewTransaction(taskId, TASK_STATUS_FAILED, error, userId);
            assetExtractService.releaseBatchFormLocks(taskId, TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH);
            sseManager.sendError(taskId, error);
        }
    }

    /**
     * 判断 result_data JSON 是否含失败项（successCount + skipCount < totalCount 即视为有失败）。
     */
    private boolean hasFailedItems(String resultJson)
    {
        if (resultJson == null || resultJson.isEmpty())
        {
            return false;
        }
        try
        {
            Map<?, ?> result = OBJECT_MAPPER.readValue(resultJson, Map.class);
            Number total = (Number) result.get("totalCount");
            Number success = (Number) result.get("successCount");
            Number skip = (Number) result.get("skipCount");
            int t = total == null ? 0 : total.intValue();
            int s = success == null ? 0 : success.intValue();
            int k = skip == null ? 0 : skip.intValue();
            return t > 0 && (s + k) < t;
        }
        catch (Exception e)
        {
            log.warn("分镜图脚本任务结果解析失败（按无失败项处理）: err={}", e.getMessage());
            return false;
        }
    }

    /** 将链式子任务提交失败原因追加到父任务结果中。 */
    private String appendChainSuccess(String resultJson, com.aid.storyboard.dto.ChainTriggerResult chain)
    {
        if (Objects.isNull(chain) || CollectionUtil.isEmpty(chain.getChildTaskIds()))
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
            result.put("chainChildTaskId", chain.getChildTaskId());
            result.put("chainChildTaskIds", chain.getChildTaskIds());
            result.put("chainChildTaskType", chain.getChildTaskType());
            return OBJECT_MAPPER.writeValueAsString(result);
        }
        catch (Exception e)
        {
            log.warn("追加链式子任务信息异常: err={}", e.getMessage());
            return resultJson;
        }
    }

    private String appendChainFailure(String resultJson, com.aid.storyboard.dto.ChainTriggerResult chain)
    {
        if (Objects.isNull(chain) || !chain.isChainFailed())
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
            result.put("chainMessage", chainTerminalMessage(chain, "提交失败"));
            if (StrUtil.isNotBlank(chain.getChildTaskType()))
            {
                result.put("chainChildTaskType", chain.getChildTaskType());
            }
            if (CollectionUtil.isNotEmpty(chain.getChildTaskIds()))
            {
                result.put("chainChildTaskId", chain.getChildTaskId());
                result.put("chainChildTaskIds", chain.getChildTaskIds());
            }
            return OBJECT_MAPPER.writeValueAsString(result);
        }
        catch (Exception e)
        {
            log.warn("追加链式任务失败原因异常: err={}", e.getMessage());
            return resultJson;
        }
    }

    private String chainTerminalMessage(com.aid.storyboard.dto.ChainTriggerResult chain, String fallback)
    {
        if (Objects.nonNull(chain) && chain.isChainFailed() && StrUtil.isNotBlank(chain.getMessage()))
        {
            return chain.getMessage();
        }
        return fallback;
    }

    /**
     * 更新任务为 PARTIAL_FAILED（带 resultData JSON）。
     */
    private void updateTaskPartialFailedWithResult(Long taskId, String resultJson)
    {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        txTemplate.executeWithoutResult(s -> {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.aid.aid.domain.AidExtractTask> update =
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate();
            update.eq(com.aid.aid.domain.AidExtractTask::getId, taskId);
            update.set(com.aid.aid.domain.AidExtractTask::getStatus, "PARTIAL_FAILED");
            update.set(com.aid.aid.domain.AidExtractTask::getResultData, resultJson);
            update.set(com.aid.aid.domain.AidExtractTask::getUpdateTime, com.aid.common.utils.DateUtils.getNowDate());
            extractTaskService.update(update);
        });
        wechatNotifyService.notifyTaskTerminal(taskId);
    }

    /**
     * 更新任务成功（带 resultData JSON），不计算 totalCount。
     */
    private void updateTaskSuccessWithResult(Long taskId, String resultJson)
    {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        txTemplate.executeWithoutResult(s -> {
            LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
            update.eq(AidExtractTask::getId, taskId);
            // 终态即最终：仅从 PENDING/PROCESSING 推进为 SUCCEEDED，避免覆盖已被自愈回收判定的 FAILED/CANCELLED
            update.in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING);
            update.set(AidExtractTask::getStatus, TASK_STATUS_SUCCEEDED);
            update.set(AidExtractTask::getResultData, resultJson);
            update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            extractTaskService.update(update);
        });
        wechatNotifyService.notifyTaskTerminal(taskId);
    }

    /**
     * 更新任务为 CANCELLED 并保留已完成的部分结果。
     * <p>
     * 批量父任务处理中被取消时，已完成项的结果仍写入 result_data，
     * 确保前端查询任务详情时能看到"做到了哪里、哪些成功、哪些失败"。
     * </p>
     */
    private void updateTaskCancelledWithResult(Long taskId, String resultJson)
    {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        txTemplate.executeWithoutResult(s -> {
            LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
            update.eq(AidExtractTask::getId, taskId);
            update.set(AidExtractTask::getStatus, TASK_STATUS_CANCELLED);
            update.set(AidExtractTask::getResultData, resultJson);
            update.set(AidExtractTask::getErrorMessage, "用户取消");
            update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            extractTaskService.update(update);
        });
    }

    /**
     * 判断批量任务结果中是否存在被跳过（未处理）的项目。
     * <p>
     * 解析 resultJson 中的 totalCount / successCount / failCount，
     * 若 processedCount（= successCount + failCount）< totalCount，
     * 说明有项目因取消而被跳过，终态应为 CANCELLED；否则全部已处理，应为 SUCCEEDED。
     * </p>
     */
    @SuppressWarnings("unchecked")
    private boolean hasBatchSkippedItems(String resultJson)
    {
        try
        {
            Map<String, Object> result = OBJECT_MAPPER.readValue(resultJson, Map.class);
            int totalCount = ((Number) result.getOrDefault("totalCount", 0)).intValue();
            int successCount = ((Number) result.getOrDefault("successCount", 0)).intValue();
            int failCount = ((Number) result.getOrDefault("failCount", 0)).intValue();
            int processedCount = successCount + failCount;
            return processedCount < totalCount;
        }
        catch (Exception e)
        {
            // 解析失败时保守处理，视为有跳过项
            log.warn("解析批量结果JSON失败，默认按有跳过项处理: {}", e.getMessage());
            return true;
        }
    }

    // ==================== 图片高清（v2.24.0 新增） ====================

    /**
     * 处理图片高清任务。
     * <p>
     * 流程：CAS PENDING→PROCESSING → cancel check → 调 doUpscaleImage → cancel check → SUCCEEDED → SSE。
     * 失败不覆盖原图，标记 FAILED，SSE推短错误。
     * 图片计费由统一媒体主链路 mediaGenerationService.generateImage 内部处理，
     * 此处不做任务级 prepareBilling/settleBilling。
     * </p>
     * <p>
     * 取消语义（对齐父任务模型）：
     * - PENDING 取消：由 cancelTask CAS 完成，Consumer 幂等校验跳过；
     * - PROCESSING 取消：当前高清为单图任务，若执行前发现 cancel flag 则直接标 CANCELLED + 释放锁；
     *   若已开始执行，当前项放行，执行完成后检查 cancel flag 决定最终状态。
     * </p>
     */
    private void handleImageUpscale(Long taskId, Long userId)
    {
        // 从任务记录读 imageId，用于 finally 释放 Redis 锁
        Long imageId = resolveImageIdFromTask(taskId);
        String lockKey = RpsFormImageBusinessServiceImpl.buildUpscaleLockKey(imageId);

        // CAS: PENDING → PROCESSING
        updateTaskInNewTransaction(taskId, TASK_STATUS_PROCESSING, (String) null, userId);

        // 执行前 cancel check：PENDING→PROCESSING 期间用户可能已调 /cancel 写了 cancel flag
        if (assetExtractService.isTaskCancelled(taskId))
        {
            updateTaskInNewTransaction(taskId, TASK_STATUS_CANCELLED, "用户取消", userId);
            sseManager.sendCancelled(taskId, "用户取消");
            assetExtractService.clearCancelFlag(taskId);
            // 释放防重锁
            try { redisCache.deleteObject(lockKey); } catch (Exception ignored) { }
            log.info("MQ高清任务执行前检测到取消: taskId={}", taskId);
            return;
        }

        // SSE 推送进行中
        sseManager.sendStepProgress(taskId, "image_upscale", 20,
                "image_gen", "正在进行图片高清处理...", 1, 1);

        try
        {
            // 调用业务 Service 执行高清生成 + 覆盖原图
            Map<String, Object> result = rpsFormImageBusinessService.doUpscaleImage(taskId, userId);

            // 执行后 cancel check：当前单图任务已完成（原图已覆盖），保留结果快照，状态标为 CANCELLED
            if (assetExtractService.isTaskCancelled(taskId))
            {
                // CANCELLED + resultData 一起写入，确保前端查任务详情能看到已完成结果
                String resultJson = OBJECT_MAPPER.writeValueAsString(result);
                updateTaskCancelledWithResult(taskId, resultJson);
                sseManager.sendCancelled(taskId, "用户取消");
                assetExtractService.clearCancelFlag(taskId);
                log.info("MQ高清任务执行后检测到取消(resultData已保留): taskId={}, resultJson={}", taskId, resultJson);
                // finally 块会释放锁
                return;
            }

            // 正常成功：更新任务状态 + SSE
            updateTaskSuccess(taskId, 1, OBJECT_MAPPER.writeValueAsString(result));
            sseManager.sendComplete(taskId, result);

            log.info("MQ消费高清完成: taskId={}, imageId={}", taskId, result.get("imageId"));
        }
        catch (Exception e)
        {
            log.error("MQ消费高清失败: taskId={}, userId={}", taskId, userId, e);
            // 归一化错误，结构化推送
            com.aid.common.error.TaskErrorResult errorResult = com.aid.common.error.ErrorNormalizer.normalize(e);
            updateTaskInNewTransaction(taskId, TASK_STATUS_FAILED, errorResult, userId);
            sseManager.sendError(taskId, errorResult);
        }
        finally
        {
            // 不管成功、失败还是取消，主动释放 Redis 防重锁
            try
            {
                redisCache.deleteObject(lockKey);
            }
            catch (Exception lockEx)
            {
                log.warn("释放高清防重锁失败: taskId={}, lockKey={}", taskId, lockKey, lockEx);
            }
        }
    }

    /**
     * 从 aid_extract_task.input_snapshot 读取 imageId（用于构建锁 key）。
     * 解析失败返回 null，不影响主流程。
     */
    private Long resolveImageIdFromTask(Long taskId)
    {
        try
        {
            AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
            if (Objects.isNull(task) || StrUtil.isBlank(task.getInputSnapshot()))
            {
                return null;
            }
            Map<String, Object> snapshot = OBJECT_MAPPER.readValue(task.getInputSnapshot(), Map.class);
            Object idVal = snapshot.get("imageId");
            return idVal != null ? Long.valueOf(String.valueOf(idVal)) : null;
        }
        catch (Exception e)
        {
            log.warn("解析高清任务imageId失败: taskId={}", taskId, e);
            return null;
        }
    }

    /**
     * 在独立新事务中更新任务状态
     * <p>
     * 使用 REQUIRES_NEW 传播级别，确保即使外层事务被标记 rollback-only，
     * 状态更新也能成功提交到数据库。
     * </p>
     */
    private void updateTaskInNewTransaction(Long taskId, String status, String errorMessage, Long userId)
    {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        txTemplate.executeWithoutResult(s -> {
            LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
            update.eq(AidExtractTask::getId, taskId);
            update.set(AidExtractTask::getStatus, status);
            update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            if (errorMessage != null)
            {
                update.set(AidExtractTask::getErrorMessage, errorMessage);
            }
            if (userId != null)
            {
                update.set(AidExtractTask::getUserId, userId);
            }
            extractTaskService.update(update);
        });
        if (TASK_STATUS_FAILED.equals(status))
        {
            wechatNotifyService.notifyTaskTerminal(taskId);
        }
    }

    /**
     * 在独立新事务中更新任务状态（结构化版本兼容入口）：保留此重载避免调用点大改,
     * 但内部只写 errorMessage 到 DB。结构化错误由 SSE/API 响应层从 errorMessage
     * 实时归一化得到,不进行 DB 持久化。
     * <p>
     * 注意：DB 存的是原始上游错误文案（rawMessage），而非友好文案。
     * 这样运行时 ErrorNormalizer.normalizeByMessage(task.getErrorMessage()) 才能正确归一化。
     * </p>
     */
    private void updateTaskInNewTransaction(Long taskId, String status, com.aid.common.error.TaskErrorResult errorResult, Long userId)
    {
        // 优先存 rawMessage（上游原文），fallback 到 userMessage（友好文案）
        String dbMessage = errorResult.getRawMessage() != null ? errorResult.getRawMessage() : errorResult.getUserMessage();
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        txTemplate.executeWithoutResult(s -> {
            LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
            update.eq(AidExtractTask::getId, taskId);
            update.set(AidExtractTask::getStatus, status);
            update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            update.set(AidExtractTask::getErrorMessage, cn.hutool.core.util.StrUtil.sub(
                    dbMessage != null ? dbMessage : "任务失败", 0, 255));
            if (userId != null)
            {
                update.set(AidExtractTask::getUserId, userId);
            }
            extractTaskService.update(update);
        });
        if (TASK_STATUS_FAILED.equals(status))
        {
            wechatNotifyService.notifyTaskTerminal(taskId);
        }
    }

    private void updateTaskSuccess(Long taskId, int totalCount, String resultData)
    {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        txTemplate.executeWithoutResult(s -> {
            LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
            update.eq(AidExtractTask::getId, taskId);
            // 终态即最终：仅从 PENDING/PROCESSING 推进为 SUCCEEDED，避免覆盖已被自愈回收判定的 FAILED/CANCELLED
            update.in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING);
            update.set(AidExtractTask::getStatus, TASK_STATUS_SUCCEEDED);
            update.set(AidExtractTask::getTotalCount, totalCount);
            update.set(AidExtractTask::getResultData, resultData);
            update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            extractTaskService.update(update);
        });
        wechatNotifyService.notifyTaskTerminal(taskId);
    }

    /**
     * 聚合本次提取任务所有 LLM 切片的实际 token usage
     * <p>
     * 查询 aid_media_task（biz_task_type=extract, biz_task_id=taskId），
     * 从 billing_snapshot_json 中累加 actualInputTokens + actualOutputTokens。
     * 如果无实际数据，返回空 map，结算时降级按预扣金额。
     * </p>
     */
    private Map<String, Object> aggregateTokenUsage(Long taskId)
    {
        List<AidMediaTask> mediaTasks = aidMediaTaskMapper.selectList(
                Wrappers.<AidMediaTask>lambdaQuery()
                        .eq(AidMediaTask::getBizTaskId, taskId)
                        .eq(AidMediaTask::getBizTaskType, "extract"));

        if (CollectionUtil.isEmpty(mediaTasks))
        {
            log.info("聚合usage: extractTaskId={}, 无子媒体任务, 返回空usage", taskId);
            return Map.of();
        }

        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int hasSnapshotCount = 0;
        int hasTokenCount = 0;

        for (AidMediaTask mt : mediaTasks)
        {
            String snapshotJson = mt.getBillingSnapshotJson();
            if (StrUtil.isBlank(snapshotJson))
            {
                log.info("聚合usage: extractTaskId={}, 子任务mediaTaskId={}, billingSnapshotJson为空, 跳过",
                        taskId, mt.getId());
                continue;
            }
            hasSnapshotCount++;
            try
            {
                BillingSnapshot snapshot = cn.hutool.json.JSONUtil.toBean(snapshotJson, BillingSnapshot.class);
                if (snapshot.getActualInputTokens() != null)
                {
                    totalInputTokens += snapshot.getActualInputTokens();
                    hasTokenCount++;
                }
                if (snapshot.getActualOutputTokens() != null)
                {
                    totalOutputTokens += snapshot.getActualOutputTokens();
                }
            }
            catch (Exception e)
            {
                log.warn("解析媒体任务快照失败: mediaTaskId={}", mt.getId(), e);
            }
        }

        log.info("聚合usage汇总: extractTaskId={}, 子任务总数={}, 有快照数={}, 有token数={}, totalInput={}, totalOutput={}",
                taskId, mediaTasks.size(), hasSnapshotCount, hasTokenCount, totalInputTokens, totalOutputTokens);

        if (totalInputTokens > 0 || totalOutputTokens > 0)
        {
            return Map.of("input_tokens", totalInputTokens, "output_tokens", totalOutputTokens);
        }
        return Map.of();
    }
}
