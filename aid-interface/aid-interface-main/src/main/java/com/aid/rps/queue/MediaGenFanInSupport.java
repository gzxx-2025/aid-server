package com.aid.rps.queue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aid.common.utils.DateUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.domain.AidExtractTask;
import com.aid.common.error.TaskErrorSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.service.IAidMediaTaskService;
import com.aid.common.error.ErrorNormalizer;
import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorPresentation;
import com.aid.common.error.TaskErrorResult;
import com.aid.common.core.redis.RedisCache;
import com.aid.media.event.MediaParentReconcileEvent;
import com.aid.storyboard.service.IStoryboardImageGenerationService;
import com.aid.storyboard.service.IStoryboardVideoGenerationService;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 媒体生成批量任务「非阻塞事件驱动扇入」通用支撑组件，抽取失败计数/收尾 CAS/bizSeq 反解等公共设施。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class MediaGenFanInSupport
{
    /** 父任务在 bizSeq 编码中的步长。 */
    public static final long BIZ_SEQ_PARENT_FACTOR = 1_000_000L;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 失败计数 Redis key 前缀 */
    private static final String REDIS_FAIL_PREFIX = "media:fanin:fail:";
    /** 提交阶段代表性失败原因 Redis key 前缀 */
    private static final String REDIS_FAILURE_MESSAGE_PREFIX = "media:fanin:failure-message:";
    /** 收尾幂等标记 Redis key 前缀 */
    private static final String REDIS_FINAL_PREFIX = "media:fanin:final:";
    /** 扇入计数 / 标记 TTL（秒）：覆盖批量最长存活 + 余量 */
    private static final long FANIN_TTL_SECONDS = 6L * 3600L;
    /** 扇入异常原因只保留排查所需长度。 */
    private static final int FAILURE_MESSAGE_MAX_LENGTH = 2000;
    /** 原子保留更明确的失败原因。 */
    private static final DefaultRedisScript<Long> SAVE_FAILURE_MESSAGE_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('GET', KEYS[1]); "
                    + "if current then "
                    + "local delimiter = string.find(current, '\\n', 1, true); "
                    + "local oldPriority = 0; "
                    + "if delimiter then oldPriority = tonumber(string.sub(current, 1, delimiter - 1)) or 0; end; "
                    + "if oldPriority >= tonumber(ARGV[1]) then return 0; end; "
                    + "end; "
                    + "redis.call('SET', KEYS[1], ARGV[1] .. '\\n' .. ARGV[2], 'EX', ARGV[3]); "
                    + "return 1;",
            Long.class);

    @Resource
    private RedisCache redisCache;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private TaskLeaseManager leaseManager;

    @Resource
    private IAidMediaTaskService mediaTaskService;

    @Resource
    private IAidExtractTaskService extractTaskService;

    @Resource
    private ApplicationEventPublisher eventPublisher;

    @Lazy
    @Autowired(required = false)
    private IStoryboardImageGenerationService imageGenerationService;

    @Lazy
    @Autowired(required = false)
    private IStoryboardVideoGenerationService videoGenerationService;

    /** 出图/出视频等「非阻塞扇入型」任务的 biz_task_type 白名单 */
    private static final Set<String> ENCODED_FANIN_BIZ_TASK_TYPES = Set.of(
            "storyboard_image_generate", "storyboard_video_generate");

    /** 需要由媒体子任务守护租约并参与重启对账的父任务类型。 */
    private static final Set<String> FANIN_PARENT_TASK_TYPES = Set.of(
            "storyboard_image_generate", "storyboard_video_generate",
            "storyboard_lip_sync_generate", "storyboard_lip_sync_single");

    /** 使用显式 parent_task_id 的持久化业务编排类型。 */
    private static final Set<String> DURABLE_WORKFLOW_TASK_TYPES = Set.of(
            "storyboard_lip_sync_generate", "storyboard_lip_sync_single");

    /** bizSeq 反解父任务 ID */
    public Long decodeParentTaskId(long bizSeq)
    {
        return bizSeq / BIZ_SEQ_PARENT_FACTOR;
    }

    /**
     * media 调度中心轮询子任务时调用：扇入型子任务则给其父 extract 任务续租，防止父任务租约过期被误判失败退款。
     */
    public void renewParentLeaseIfFanIn(String bizTaskType, Long bizTaskId, Long parentTaskId)
    {
        if (Objects.nonNull(parentTaskId))
        {
            renewParentLease(parentTaskId);
            return;
        }
        if (Objects.isNull(bizTaskId) || Objects.isNull(bizTaskType)
                || !ENCODED_FANIN_BIZ_TASK_TYPES.contains(bizTaskType))
        {
            return;
        }
        try
        {
            leaseManager.touchLease(decodeParentTaskId(bizTaskId));
        }
        catch (Exception e)
        {
            log.warn("媒体扇入父任务续租异常(不阻断): bizTaskId={}", bizTaskId, e);
        }
    }

    /** 是否属于扇入型生成任务类型（出图/出片） */
    public boolean isFanInTaskType(String taskType)
    {
        return Objects.nonNull(taskType) && FANIN_PARENT_TASK_TYPES.contains(taskType);
    }

    /** 是否为显式父子关联、由业务事件恢复的持久化编排任务。 */
    public boolean isDurableWorkflowTaskType(String taskType)
    {
        return Objects.nonNull(taskType) && DURABLE_WORKFLOW_TASK_TYPES.contains(taskType);
    }

    /**
     * 父任务是否仍有「在途」media 子任务（含 media 层排队未轮询）；查询异常保守返回 true，杜绝重复扣费。
     */
    public boolean hasInflightMedia(Long parentTaskId)
    {
        if (Objects.isNull(parentTaskId)) { return false; }
        try
        {
            long lo = Math.multiplyExact(parentTaskId, BIZ_SEQ_PARENT_FACTOR);
            long hi = Math.addExact(lo, BIZ_SEQ_PARENT_FACTOR);
            LambdaQueryWrapper<AidMediaTask> w = new LambdaQueryWrapper<>();
            w.and(q -> q.eq(AidMediaTask::getParentTaskId, parentTaskId)
                    .or(encoded -> encoded.ge(AidMediaTask::getBizTaskId, lo)
                            .lt(AidMediaTask::getBizTaskId, hi)
                            .in(AidMediaTask::getBizTaskType, ENCODED_FANIN_BIZ_TASK_TYPES)));
            // 在途 = 既非 FAILED，也非「SUCCEEDED 且 oss_url 已就绪」：
            //   ① 未终态(PENDING/QUEUED/WAIT_POLL/WAIT_CALLBACK/PROCESSING) → 在途；
            //   ② SUCCEEDED 但 oss_url 为空 → OSS 持久化未完成（事件未发），仍在途，避免慢 OSS 下父任务被误回收。
            w.ne(AidMediaTask::getStatus, "FAILED");
            w.and(q -> q.ne(AidMediaTask::getStatus, "SUCCEEDED")
                    .or().isNull(AidMediaTask::getOssUrl)
                    .or().eq(AidMediaTask::getOssUrl, ""));
            return mediaTaskService.count(w) > 0;
        }
        catch (Exception e)
        {
            log.warn("媒体扇入在途子任务查询异常(保守按在途处理,不回收): parentTaskId={}", parentTaskId, e);
            return true;
        }
    }

    /** 给父任务续租（供僵尸回收守卫在确认仍有在途子任务时刷新租约） */
    public void renewParentLease(Long parentTaskId)
    {
        if (Objects.isNull(parentTaskId)) { return; }
        try { leaseManager.touchLease(parentTaskId); }
        catch (Exception e) { log.warn("媒体扇入父任务续租异常(不阻断): parentTaskId={}", parentTaskId, e); }
    }

    /**
     * 扇入孤儿对账收尾（幂等）：租约失活且无在途子任务却仍卡 PROCESSING 时，按子任务记录幂等重放终态事件。
     *
     * @param parentTaskId 父任务 ID（aid_extract_task.id）
     * @param taskType     父任务类型
     */
    public void reconcileFanInParent(Long parentTaskId, String taskType)
    {
        if (Objects.isNull(parentTaskId) || !isFanInTaskType(taskType)) { return; }
        if (isDurableWorkflowTaskType(taskType))
        {
            eventPublisher.publishEvent(new MediaParentReconcileEvent(this, parentTaskId, taskType));
            return;
        }
        List<AidMediaTask> subs;
        try
        {
            long lo = Math.multiplyExact(parentTaskId, BIZ_SEQ_PARENT_FACTOR);
            long hi = Math.addExact(lo, BIZ_SEQ_PARENT_FACTOR);
            LambdaQueryWrapper<AidMediaTask> w = new LambdaQueryWrapper<>();
            w.ge(AidMediaTask::getBizTaskId, lo);
            w.lt(AidMediaTask::getBizTaskId, hi);
            w.in(AidMediaTask::getBizTaskType, ENCODED_FANIN_BIZ_TASK_TYPES);
            subs = mediaTaskService.list(w);
        }
        catch (Exception e)
        {
            log.warn("扇入对账查询子任务异常(放弃对账): parentTaskId={}", parentTaskId, e);
            return;
        }
        if (Objects.isNull(subs) || subs.isEmpty()) { return; }
        boolean isVideo = "storyboard_video_generate".equals(taskType);
        for (AidMediaTask mt : subs)
        {
            try
            {
                String st = mt.getStatus();
                boolean succeeded = "SUCCEEDED".equals(st) && StrUtil.isNotBlank(mt.getOssUrl());
                boolean failed = "FAILED".equals(st);
                // 仍在途（未终态）→ 不重放（调用前应已确认无在途，这里二次保护）
                if (!succeeded && !failed) { continue; }
                if (isVideo)
                {
                    if (Objects.nonNull(videoGenerationService))
                    {
                        videoGenerationService.onMediaVideoTaskTerminal(mt.getId(), succeeded, succeeded ? mt.getOssUrl() : null);
                    }
                }
                else if (Objects.nonNull(imageGenerationService))
                {
                    imageGenerationService.onMediaImageTaskTerminal(mt.getId(), succeeded, succeeded ? mt.getOssUrl() : null);
                }
            }
            catch (Exception e)
            {
                log.warn("扇入对账重放子任务事件异常(忽略单条): parentTaskId={}, mediaTaskId={}", parentTaskId, mt.getId(), e);
            }
        }
        log.warn("扇入孤儿对账收尾已执行: parentTaskId={}, taskType={}, 子任务数={}", parentTaskId, taskType, subs.size());
    }

    /** 失败计数 +1（durable，首个 +1 时设 TTL）。 */
    public void incrFail(Long taskId)
    {
        if (Objects.isNull(taskId)) { return; }
        try
        {
            String key = REDIS_FAIL_PREFIX + taskId;
            Long v = redisCache.redisTemplate.opsForValue().increment(key);
            if (Objects.nonNull(v) && v == 1L)
            {
                redisCache.redisTemplate.expire(key, FANIN_TTL_SECONDS, TimeUnit.SECONDS);
            }
        }
        catch (Exception e)
        {
            log.warn("媒体扇入失败计数异常(不阻断): taskId={}", taskId, e);
        }
    }

    /**
     * 记录失败数量并保留提交阶段的代表性异常。
     * 媒体任务尚未创建就失败时，父任务仍可得到实际错误原因。
     */
    public void incrFail(Long taskId, String errorMessage)
    {
        recordFirstFailureMessage(taskId, errorMessage);
        incrFail(taskId);
    }

    /** 调用方持有父任务提交锁；无媒体子任务的失败也持久化到本轮父任务结果。 */
    public void incrFail(Long taskId, Long storyboardId, int take, String executionTraceId, Throwable cause) {
        AidExtractTask parent = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(parent) || !Objects.equals(executionTraceId, parent.getBillingTraceId())) {
            throw new BatchTaskExecutionRejectedException();
        }
        TaskErrorResult error = ErrorNormalizer.normalize(String.valueOf(taskId), null, parent.getModelCode(), cause);
        try {
            ObjectNode result = StrUtil.isBlank(parent.getResultData()) ? OBJECT_MAPPER.createObjectNode()
                    : (ObjectNode) OBJECT_MAPPER.readTree(parent.getResultData());
            int runNo = currentRunNo(parent);
            ArrayNode failures = OBJECT_MAPPER.createArrayNode();
            for (JsonNode item : result.path("failedItems")) {
                if (item.path("runNo").asInt(0) != runNo) { continue; }
                if (item.path("storyboardId").asLong() == storyboardId && item.path("take").asInt() == take) {
                    return; // 同一执行周期同一槽位已记录，不能重复增加失败数。
                }
                failures.add(item);
            }
            Map<String, Object> failure = failureItem(storyboardId, take, null, error);
            failure.put("runNo", runNo);
            failures.add(OBJECT_MAPPER.valueToTree(failure));
            result.set("failedItems", failures);
            boolean updated = extractTaskService.update(Wrappers.<AidExtractTask>lambdaUpdate()
                    .eq(AidExtractTask::getId, taskId)
                    .eq(AidExtractTask::getBillingTraceId, executionTraceId)
                    .in(AidExtractTask::getStatus, "PENDING", "PROCESSING")
                    .set(AidExtractTask::getResultData, result.toString())
                    .set(AidExtractTask::getUpdateTime, DateUtils.getNowDate())
                    .set(AidExtractTask::getUpdateBy, "system"));
            if (!updated) {
                throw new BatchTaskExecutionRejectedException();
            }
        } catch (BatchTaskExecutionRejectedException rejected) {
            throw rejected;
        } catch (Exception ex) {
            throw new IllegalStateException("任务失败明细保存失败", ex);
        }
        recordFirstFailureMessage(taskId, error.getUserMessage());
        // 本地提交失败按持久化槽位计数，不叠加进媒体事件计数；重启重放仍是同一项。
    }

    /** 汇总本轮提交失败与各逻辑槽位最新媒体任务的安全原因。 */
    public List<Map<String, Object>> resolveFailureItems(AidExtractTask parent, String taskType) {
        Map<String, Map<String, Object>> items = new LinkedHashMap<>();
        try {
            if (StrUtil.isNotBlank(parent.getResultData())) {
                for (JsonNode item : OBJECT_MAPPER.readTree(parent.getResultData()).path("failedItems")) {
                    if (item.path("runNo").asInt(0) != currentRunNo(parent)) { continue; }
                    TaskErrorResult error = TaskErrorSnapshot.read(item.toString());
                    if (Objects.isNull(error)) {
                        error = ErrorNormalizer.classifyByMessage(item.path("errorMessage").asText());
                    }
                    Long storyboardId = item.path("storyboardId").asLong();
                    int take = item.path("take").asInt();
                    Long mediaTaskId = item.hasNonNull("mediaTaskId") ? item.path("mediaTaskId").asLong() : null;
                    items.put(storyboardId + ":" + take, failureItem(storyboardId, take, mediaTaskId, error));
                }
            }
            JsonNode snapshot = OBJECT_MAPPER.readTree(parent.getInputSnapshot());
            JsonNode shots = snapshot.path("allShots");
            if (!shots.isArray()) { shots = snapshot.path("shots"); }
            long lower = Math.multiplyExact(parent.getId(), BIZ_SEQ_PARENT_FACTOR);
            List<AidMediaTask> tasks = mediaTaskService.list(Wrappers.<AidMediaTask>lambdaQuery()
                    .select(AidMediaTask::getId, AidMediaTask::getBizTaskId, AidMediaTask::getStatus,
                            AidMediaTask::getModelName, AidMediaTask::getErrorMessage, AidMediaTask::getErrorDetailJson)
                    .ge(AidMediaTask::getBizTaskId, lower)
                    .lt(AidMediaTask::getBizTaskId, Math.addExact(lower, BIZ_SEQ_PARENT_FACTOR))
                    .eq(AidMediaTask::getBizTaskType, taskType)
                    .orderByDesc(AidMediaTask::getId));
            Set<Long> seen = new HashSet<>();
            for (AidMediaTask task : tasks) {
                if (!seen.add(task.getBizTaskId())) { continue; }
                long offset = task.getBizTaskId() - lower;
                int ordinal = (int) (offset / 1000L);
                int take = (int) (offset % 1000L) + 1;
                if (!shots.isArray() || ordinal < 0 || ordinal >= shots.size()) { continue; }
                Long storyboardId = shots.path(ordinal).path("storyboardId").asLong();
                String key = storyboardId + ":" + take;
                if ("FAILED".equals(task.getStatus())) {
                    // 本轮提交已失败但还未创建新媒体任务时，以本轮原因优先。
                    items.putIfAbsent(key, failureItem(storyboardId, take, task.getId(), TaskErrorSnapshot.fromTask(task)));
                }
            }
            for (Map<String, Object> item : items.values()) { item.put("runNo", currentRunNo(parent)); }
            return new ArrayList<>(items.values());
        } catch (Exception ex) {
            log.error("批量任务失败明细读取异常, taskId={}", parent.getId(), ex);
            throw new IllegalStateException("任务失败明细读取失败", ex);
        }
    }

    private int currentRunNo(AidExtractTask parent) {
        try {
            return StrUtil.isBlank(parent.getInputSnapshot()) ? 0
                    : OBJECT_MAPPER.readTree(parent.getInputSnapshot()).path("runNo").asInt(0);
        } catch (Exception ex) {
            throw new IllegalStateException("任务执行快照无效", ex);
        }
    }

    private Map<String, Object> failureItem(Long storyboardId, int take, Long mediaTaskId, TaskErrorResult error) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("storyboardId", storyboardId);
        item.put("take", take);
        item.put("mediaTaskId", mediaTaskId);
        item.put("errorCode", error.getErrorCode());
        item.put("errorType", error.getErrorType());
        item.put("errorSource", error.getErrorSource());
        item.put("errorMessage", error.getUserMessage());
        item.put("userMessage", error.getUserMessage());
        item.put("retryable", error.isRetryable());
        return item;
    }

    private void recordFirstFailureMessage(Long taskId, String errorMessage)
    {
        if (Objects.isNull(taskId) || StrUtil.isBlank(errorMessage)) { return; }
        try
        {
            String message = StrUtil.sub(errorMessage, 0, FAILURE_MESSAGE_MAX_LENGTH);
            TaskErrorResult classified = ErrorNormalizer.classifyByMessage(message);
            int priority = TaskErrorPresentation.specificity(classified);
            // Lua 参数必须使用字符串序列化，确保 tonumber(ARGV) 接收到纯数字。
            stringRedisTemplate.execute(
                    SAVE_FAILURE_MESSAGE_SCRIPT,
                    Collections.singletonList(REDIS_FAILURE_MESSAGE_PREFIX + taskId),
                    String.valueOf(priority),
                    message,
                    String.valueOf(FANIN_TTL_SECONDS));
        }
        catch (Exception e)
        {
            log.warn("媒体扇入失败原因记录异常(不阻断): taskId={}", taskId, e);
        }
    }

    private String getFirstFailureMessage(Long taskId)
    {
        if (Objects.isNull(taskId)) { return null; }
        try
        {
            String value = stringRedisTemplate.opsForValue()
                    .get(REDIS_FAILURE_MESSAGE_PREFIX + taskId);
            if (Objects.isNull(value))
            {
                return null;
            }
            String stored = value;
            int delimiter = stored.indexOf('\n');
            if (delimiter >= 0)
            {
                return stored.substring(delimiter + 1);
            }
            // 兼容旧版通用 RedisTemplate 写入的 JSON 字符串值。
            if (stored.length() >= 2 && stored.startsWith("\"") && stored.endsWith("\""))
            {
                return OBJECT_MAPPER.readValue(stored, String.class);
            }
            return stored;
        }
        catch (Exception e)
        {
            log.warn("媒体扇入失败原因读取异常(不阻断): taskId={}", taskId, e);
            return null;
        }
    }

    /** 读取失败计数（读失败按 0）。 */
    public int getFailCount(Long taskId)
    {
        if (Objects.isNull(taskId)) { return 0; }
        try
        {
            Object v = redisCache.redisTemplate.opsForValue().get(REDIS_FAIL_PREFIX + taskId);
            return (Objects.isNull(v) ? 0 : Integer.parseInt(String.valueOf(v))) + submissionFailureCount(taskId);
        }
        catch (Exception e)
        {
            log.warn("媒体扇入失败计数读取异常: taskId={}", taskId, e);
            return submissionFailureCount(taskId);
        }
    }

    private int submissionFailureCount(Long taskId) {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || StrUtil.isBlank(task.getResultData())) { return 0; }
        try {
            int count = 0;
            for (JsonNode item : OBJECT_MAPPER.readTree(task.getResultData()).path("failedItems")) {
                if (!item.hasNonNull("mediaTaskId") && item.path("take").asInt() > 0
                        && item.path("runNo").asInt(0) == currentRunNo(task)) { count++; }
            }
            return count;
        } catch (Exception ex) {
            throw new IllegalStateException("任务失败计数读取失败", ex);
        }
    }

    /**
     * 从父任务的失败媒体子任务中选取一个可解释的根因。
     * 优先返回已归类的具体错误，避免父任务用“生成失败”覆盖真人检测、队列繁忙等真实原因。
     */
    public TaskErrorResult resolveRepresentativeFailure(Long parentTaskId, String taskType, String fallbackModelCode)
    {
        if (Objects.isNull(parentTaskId))
        {
            return TaskErrorResult.of(TaskErrorCode.AI_GENERATION_FAILED, "子任务全部失败");
        }
        AidExtractTask parent = extractTaskService.selectAidExtractTaskById(parentTaskId);
        if (Objects.nonNull(parent)) {
            TaskErrorResult representative = null;
            for (Map<String, Object> item : resolveFailureItems(parent, taskType)) {
                TaskErrorResult candidate = TaskErrorSnapshot.read(toFailureJson(item));
                if (Objects.isNull(representative)
                        || TaskErrorPresentation.specificity(candidate) > TaskErrorPresentation.specificity(representative)) {
                    representative = candidate;
                }
            }
            if (Objects.nonNull(representative)) { return representative; }
        }
        TaskErrorResult firstFallback = null;
        try
        {
            long lo = Math.multiplyExact(parentTaskId, BIZ_SEQ_PARENT_FACTOR);
            long hi = Math.addExact(lo, BIZ_SEQ_PARENT_FACTOR);
            LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
            // 查询字段精简：错误归类只读取模型、错误信息和排序字段。
            wrapper.select(AidMediaTask::getId, AidMediaTask::getModelName,
                    AidMediaTask::getErrorMessage, AidMediaTask::getErrorDetailJson, AidMediaTask::getUpdateTime);
            wrapper.ge(AidMediaTask::getBizTaskId, lo);
            wrapper.lt(AidMediaTask::getBizTaskId, hi);
            if (StrUtil.isNotBlank(taskType))
            {
                wrapper.eq(AidMediaTask::getBizTaskType, taskType);
            }
            wrapper.eq(AidMediaTask::getStatus, "FAILED");
            wrapper.isNotNull(AidMediaTask::getErrorMessage);
            wrapper.ne(AidMediaTask::getErrorMessage, "");
            wrapper.orderByDesc(AidMediaTask::getUpdateTime, AidMediaTask::getId);
            wrapper.last("LIMIT 50");
            List<AidMediaTask> failedTasks = mediaTaskService.list(wrapper);

            for (AidMediaTask failedTask : failedTasks)
            {
                String modelCode = StrUtil.blankToDefault(failedTask.getModelName(), fallbackModelCode);
                TaskErrorResult result = TaskErrorSnapshot.resolve(
                        failedTask.getErrorDetailJson(), modelCode, failedTask.getErrorMessage());
                if (!TaskErrorPresentation.isGeneric(result))
                {
                    return result;
                }
                if (Objects.isNull(firstFallback))
                {
                    firstFallback = result;
                }
            }
        }
        catch (Exception e)
        {
            log.error("媒体扇入读取失败根因异常: parentTaskId={}", parentTaskId, e);
        }
        String submittedFailureMessage = getFirstFailureMessage(parentTaskId);
        if (StrUtil.isNotBlank(submittedFailureMessage))
        {
            TaskErrorResult submittedFailure = ErrorNormalizer.classify(
                    null, fallbackModelCode, -1, submittedFailureMessage);
            if (!TaskErrorPresentation.isGeneric(submittedFailure))
            {
                return submittedFailure;
            }
            if (Objects.isNull(firstFallback))
            {
                firstFallback = submittedFailure;
            }
        }
        if (Objects.nonNull(firstFallback))
        {
            return firstFallback;
        }
        return TaskErrorResult.of(TaskErrorCode.AI_GENERATION_FAILED, "子任务全部失败");
    }

    private String toFailureJson(Map<String, Object> item) {
        try { return OBJECT_MAPPER.writeValueAsString(item); }
        catch (Exception ex) { throw new IllegalArgumentException("任务错误无效", ex); }
    }

    /**
     * CAS 抢占收尾权：仅首个调用返回 true，保证并发事件下唯一一次收尾。
     */
    public boolean tryWinFinalize(Long taskId)
    {
        try
        {
            Boolean won = redisCache.redisTemplate.opsForValue()
                    .setIfAbsent(REDIS_FINAL_PREFIX + taskId, "1", FANIN_TTL_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(won);
        }
        catch (Exception e)
        {
            log.warn("媒体扇入收尾CAS异常(按未抢到): taskId={}", taskId, e);
            return false;
        }
    }

    /** 收尾完成后清理扇入键（失败计数 + 收尾标记一并清理，避免续生重跑时抢不到收尾权） */
    public void cleanup(Long taskId)
    {
        if (Objects.isNull(taskId)) { return; }
        try { redisCache.redisTemplate.delete(REDIS_FAIL_PREFIX + taskId); }
        catch (Exception ignore) { /* ignore */ }
        try { redisCache.redisTemplate.delete(REDIS_FAILURE_MESSAGE_PREFIX + taskId); }
        catch (Exception ignore) { /* ignore */ }
        try { redisCache.redisTemplate.delete(REDIS_FINAL_PREFIX + taskId); }
        catch (Exception ignore) { /* ignore */ }
    }

    /** 从父任务 input_snapshot 解析 storyboardIds。 */
    public List<Long> parseStoryboardIds(String inputSnapshot)
    {
        List<Long> ids = new ArrayList<>();
        if (StrUtil.isBlank(inputSnapshot)) { return ids; }
        try
        {
            JsonNode arr = OBJECT_MAPPER.readTree(inputSnapshot).path("storyboardIds");
            if (arr.isArray())
            {
                for (JsonNode n : arr) { if (n.canConvertToLong()) { ids.add(n.asLong()); } }
            }
        }
        catch (Exception e)
        {
            log.warn("媒体扇入解析 storyboardIds 失败: {}", e.getMessage());
        }
        return ids;
    }

    /** 镜头锁快照项：storyboardId + lockToken。 */
    public static final class ShotLockRef
    {
        public final long storyboardId;
        public final String lockToken;

        public ShotLockRef(long storyboardId, String lockToken)
        {
            this.storyboardId = storyboardId;
            this.lockToken = lockToken;
        }
    }

    /** 从父任务 input_snapshot 解析 shots[].(storyboardId, lockToken)，供收尾释放镜头锁。 */
    public List<ShotLockRef> parseShotLocks(String inputSnapshot)
    {
        List<ShotLockRef> refs = new ArrayList<>();
        if (StrUtil.isBlank(inputSnapshot)) { return refs; }
        try
        {
            JsonNode shots = OBJECT_MAPPER.readTree(inputSnapshot).path("shots");
            if (shots.isArray())
            {
                for (JsonNode s : shots)
                {
                    long sid = s.path("storyboardId").asLong(0L);
                    String token = s.path("lockToken").asText(null);
                    if (sid > 0 && StrUtil.isNotBlank(token))
                    {
                        refs.add(new ShotLockRef(sid, token));
                    }
                }
            }
        }
        catch (Exception e)
        {
            log.warn("媒体扇入解析 shots 镜头锁失败: {}", e.getMessage());
        }
        return refs;
    }
}
