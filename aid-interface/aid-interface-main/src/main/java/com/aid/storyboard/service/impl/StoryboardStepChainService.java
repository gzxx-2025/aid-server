package com.aid.storyboard.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.core.redis.RedisCache;
import com.aid.rps.queue.BatchPromptTerminalEvent;
import com.aid.storyboard.dto.ChainTriggerResult;
import com.aid.storyboard.dto.StoryboardImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardImageGenerateVO;
import com.aid.storyboard.dto.StoryboardVideoFromImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoGenerateVO;
import com.aid.storyboard.dto.StoryboardVideoGridGenerateRequest;
import com.aid.storyboard.service.IStoryboardImageGenerationService;
import com.aid.storyboard.service.IStoryboardVideoGenerationService;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜「提示词 + 自动出图/出片」合并接口的链式触发器：提示词批量任务终态后自动发起下一步生成。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class StoryboardStepChainService
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
    private static final String TASK_TYPE_IMAGE_PROMPT = "storyboard_image_prompt_batch";
    private static final String TASK_TYPE_VIDEO_PROMPT = "storyboard_video_prompt_batch";
    private static final String CHAIN_TYPE_IMAGE = "image";
    private static final String CHAIN_TYPE_VIDEO = "video";
    /** 链路类型：图生视频 i2v（走图生池 main_storyboard_video_image，读 video_prompt_image）。 */
    private static final String CHAIN_TYPE_VIDEO_IMAGE = "video_image";
    /** 链路类型：宫格出片（走宫格专属视频池 main_storyboard_video_grid，读 video_prompt_image）。 */
    private static final String CHAIN_TYPE_VIDEO_GRID = "video_grid";

    /** 子任务类型：出图父任务（前端第二段 SSE 订阅用） */
    private static final String CHILD_TASK_TYPE_IMAGE = "storyboard_image_generate";
    /** 子任务类型：出片父任务（前端第二段 SSE 订阅用） */
    private static final String CHILD_TASK_TYPE_VIDEO = "storyboard_video_generate";

    /** 触发幂等 Redis key 前缀（Set：成员为已触发出图/出片的分镜 ID，镜头级去重）。 */
    private static final String REDIS_CHAIN_SHOTS_PREFIX = "sb:chain:shots:";
    /** 幂等 Set 保留时长：覆盖提示词续生窗口（24h，长于续生 RESUME_WINDOW_HOURS）。 */
    private static final long CHAIN_ONCE_TTL_SECONDS = 24L * 3600L;
    /** 已触发出图的 child 父任务 ID 记录前缀（List，仅供追踪/前端可选查询，不参与状态机）。 */
    private static final String REDIS_CHAIN_CHILD_IMAGE_PREFIX = "sb:chain:childImage:";
    /** 链式下一步使用底层出图/出片服务的单批上限，避免触发数量超限。 */
    private static final int CHAIN_CHILD_BATCH_SIZE = 20;
    private static final String CHAIN_IMAGE_FAILED_MESSAGE = "出图提交失败";
    private static final String CHAIN_VIDEO_FAILED_MESSAGE = "视频提交失败";

    @Resource
    private IAidExtractTaskService extractTaskService;

    @Resource
    private IAidStoryboardService aidStoryboardService;

    @Resource
    private IStoryboardImageGenerationService storyboardImageGenerationService;

    @Resource
    private IStoryboardVideoGenerationService storyboardVideoGenerationService;

    @Resource
    private RedisCache redisCache;

    /**
     * 监听本地派发路径的提示词批量终态事件（{@code BatchTaskLocalOrchestrator} 发布），触发下一步。
     * MQ 路径由 {@code AssetExtractConsumer} 直接调用 {@link #onPromptBatchTerminal}；本地路径走事件解耦，
     * 避免低层编排器反向依赖本业务触发器形成循环。
     */
    @EventListener
    public void onBatchPromptTerminalEvent(BatchPromptTerminalEvent event)
    {
        if (event == null) { return; }
        // 同步事件：触发下一步并把子任务信息回填到 event，供发布方（本地编排器）在 publishEvent 返回后读取
        ChainTriggerResult result = onPromptBatchTerminal(event.getTaskId(), event.getTaskType(), event.getStatus());
        event.setChainChildTaskId(result.getChildTaskId());
        event.setChainChildTaskIds(result.getChildTaskIds());
        event.setChainChildTaskType(result.getChildTaskType());
        event.setChainRequested(result.isChainRequested());
        event.setChainFailed(result.isChainFailed());
        event.setChainMessage(result.getMessage());
    }

    /**
     * 提示词批量任务终态回调：若该任务带 chainNext，则自动发起下一步出图/出视频。
     *
     * @param taskId   提示词父任务 ID
     * @param taskType 任务类型（仅 image_prompt / video_prompt 处理）
     * @param status   终态（仅 SUCCEEDED / PARTIAL_FAILED 触发）
     * @return 链式触发结果（含子任务 ID + 类型）；非合并任务 / 无待触发分镜 / 触发失败时字段为 null
     */
    public ChainTriggerResult onPromptBatchTerminal(Long taskId, String taskType, String status)
    {
        if (Objects.isNull(taskId) || taskType == null) { return ChainTriggerResult.empty(); }
        if (!TASK_TYPE_IMAGE_PROMPT.equals(taskType) && !TASK_TYPE_VIDEO_PROMPT.equals(taskType)) { return ChainTriggerResult.empty(); }
        if (!STATUS_SUCCEEDED.equals(status) && !STATUS_PARTIAL_FAILED.equals(status)) { return ChainTriggerResult.empty(); }

        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || StrUtil.isBlank(task.getInputSnapshot())) { return ChainTriggerResult.empty(); }

        Map<String, Object> chainNext;
        List<Long> storyboardIds;
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(task.getInputSnapshot());
            JsonNode chainNode = root.path("chainNext");
            if (chainNode.isMissingNode() || chainNode.isNull()) { return ChainTriggerResult.empty(); } // 非合并任务
            chainNext = OBJECT_MAPPER.convertValue(chainNode, Map.class);
            storyboardIds = new ArrayList<>();
            JsonNode arr = root.path("storyboardIds");
            if (arr.isArray()) { for (JsonNode n : arr) { if (n.canConvertToLong()) { storyboardIds.add(n.asLong()); } } }
        }
        catch (Exception e)
        {
            log.warn("分镜合并链路解析 chainNext 失败: taskId={}", taskId, e);
            return ChainTriggerResult.failed(chainChildTypeByTaskType(taskType), chainFailureMessageByTaskType(taskType));
        }
        if (CollectionUtil.isEmpty(storyboardIds) || CollectionUtil.isEmpty(chainNext))
        {
            log.warn("分镜合并链路参数缺失: taskId={}, storyboardIds={}, chainNext={}", taskId, storyboardIds, chainNext);
            return ChainTriggerResult.failed(chainChildTypeByTaskType(taskType), chainFailureMessageByTaskType(taskType));
        }

        Long userId = task.getUserId();
        String type = strVal(chainNext.get("type"));
        try
        {
            // 镜头级幂等：去重判定下沉到 trigger 内（按 taskId×storyboardId 的 Redis Set），
            // 既保证重复终态事件不重复触发，又支持续生补齐后仅触发新分镜。
            if (CHAIN_TYPE_IMAGE.equals(type))
            {
                List<Long> childIds = triggerImage(taskId, storyboardIds, userId, chainNext);
                return CollectionUtil.isEmpty(childIds) ? ChainTriggerResult.empty()
                        : ChainTriggerResult.success(childIds, CHILD_TASK_TYPE_IMAGE);
            }
            else if (CHAIN_TYPE_VIDEO_IMAGE.equals(type))
            {
                List<Long> childIds = triggerVideoFromImage(taskId, storyboardIds, userId, chainNext);
                return CollectionUtil.isEmpty(childIds) ? ChainTriggerResult.empty()
                        : ChainTriggerResult.success(childIds, CHILD_TASK_TYPE_VIDEO);
            }
            else if (CHAIN_TYPE_VIDEO_GRID.equals(type))
            {
                List<Long> childIds = triggerVideoGrid(taskId, storyboardIds, userId, chainNext);
                return CollectionUtil.isEmpty(childIds) ? ChainTriggerResult.empty()
                        : ChainTriggerResult.success(childIds, CHILD_TASK_TYPE_VIDEO);
            }
            else if (CHAIN_TYPE_VIDEO.equals(type))
            {
                List<Long> childIds = triggerVideo(taskId, storyboardIds, userId, chainNext);
                return CollectionUtil.isEmpty(childIds) ? ChainTriggerResult.empty()
                        : ChainTriggerResult.success(childIds, CHILD_TASK_TYPE_VIDEO);
            }
        }
        catch (ChainSubmitException e)
        {
            log.error("分镜合并链路触发部分失败: taskId={}, type={}, childTaskIds={}",
                    taskId, type, e.getChildTaskIds(), e);
            return ChainTriggerResult.failed(e.getChildTaskType(), e.getChildTaskIds(), chainFailureMessage(type));
        }
        catch (Exception e)
        {
            // 下一步触发失败不回滚已生成的提示词；记录便于排查（用户可手动发起出图/出片）
            log.error("分镜合并链路触发下一步失败(提示词已生成,不影响): taskId={}, type={}", taskId, type, e);
            return ChainTriggerResult.failed(chainChildType(type), chainFailureMessage(type));
        }
        log.warn("分镜合并链路类型不支持: taskId={}, type={}", taskId, type);
        return ChainTriggerResult.failed(chainChildType(type), chainFailureMessage(type));
    }

    private String chainChildType(String type)
    {
        return CHAIN_TYPE_IMAGE.equals(type) ? CHILD_TASK_TYPE_IMAGE : CHILD_TASK_TYPE_VIDEO;
    }

    private String chainChildTypeByTaskType(String taskType)
    {
        return TASK_TYPE_IMAGE_PROMPT.equals(taskType) ? CHILD_TASK_TYPE_IMAGE : CHILD_TASK_TYPE_VIDEO;
    }

    private String chainFailureMessage(String type)
    {
        return CHAIN_TYPE_IMAGE.equals(type) ? CHAIN_IMAGE_FAILED_MESSAGE : CHAIN_VIDEO_FAILED_MESSAGE;
    }

    private String chainFailureMessageByTaskType(String taskType)
    {
        return TASK_TYPE_IMAGE_PROMPT.equals(taskType) ? CHAIN_IMAGE_FAILED_MESSAGE : CHAIN_VIDEO_FAILED_MESSAGE;
    }

    /**
     * 触发出图：仅对本批中 image_prompt 非空、且本任务尚未触发过出图的分镜（镜头级幂等）。
     *
     * @return 出图子任务 ID 列表；无待触发分镜时返回空列表
     */
    private List<Long> triggerImage(Long promptTaskId, List<Long> storyboardIds, Long userId, Map<String, Object> chainNext)
    {
        List<Long> ready = filterByPrompt(storyboardIds, AidStoryboard::getImagePrompt);
        // 镜头级去重：仅保留本任务此前未触发过出图的分镜
        List<Long> toTrigger = claimUntriggeredShots(promptTaskId, ready);
        if (CollectionUtil.isEmpty(toTrigger))
        {
            log.info("分镜合并出图：无待触发分镜(均无提示词或已触发)，跳过, taskId={}, userId={}", promptTaskId, userId);
            return new ArrayList<>();
        }
        List<Long> childTaskIds = new ArrayList<>();
        List<Long> submittedShots = new ArrayList<>();
        try
        {
            for (List<Long> batchIds : splitBatches(toTrigger))
            {
                StoryboardImageGenerateRequest req = new StoryboardImageGenerateRequest();
                req.setStoryboardIds(batchIds);
                req.setAgentCode(strVal(chainNext.get("agentCode")));
                req.setModelName(strVal(chainNext.get("modelName")));
                req.setAspectRatio(strVal(chainNext.get("aspectRatio")));
                req.setSize(strVal(chainNext.get("size")));
                req.setScenario(strVal(chainNext.get("scenario")));
                req.setNegativePrompt(strVal(chainNext.get("negativePrompt")));
                // 多镜头强制每镜 1 张，不传 count
                StoryboardImageGenerateVO vo = storyboardImageGenerationService.generateImage(req, userId);
                Long childTaskId = Objects.isNull(vo) ? null : vo.getTaskId();
                ensureChildTaskCreated(promptTaskId, batchIds, childTaskId);
                childTaskIds.add(childTaskId);
                submittedShots.addAll(batchIds);
                recordChildImageTask(promptTaskId, vo);
            }
        }
        catch (RuntimeException e)
        {
            // 触发失败：释放本次占位，让后续终态事件（如提示词续生）可重新触发这些分镜
            releaseShots(promptTaskId, remainingShots(toTrigger, submittedShots));
            throw new ChainSubmitException(CHILD_TASK_TYPE_IMAGE, childTaskIds, e);
        }
        log.info("分镜合并出图已触发: promptTaskId={}, shotCount={}, childImageTaskIds={}, userId={}",
                promptTaskId, toTrigger.size(), childTaskIds, userId);
        return childTaskIds;
    }

    /**
     * 触发出视频（多参方向）：仅对本批中 video_prompt 非空、且本任务尚未触发过出片的分镜（镜头级幂等）。
     *
     * @return 出片子任务 ID 列表；无待触发分镜时返回空列表
     */
    private List<Long> triggerVideo(Long promptTaskId, List<Long> storyboardIds, Long userId, Map<String, Object> chainNext)
    {
        List<Long> ready = filterByPrompt(storyboardIds, AidStoryboard::getVideoPrompt);
        List<Long> toTrigger = claimUntriggeredShots(promptTaskId, ready);
        if (CollectionUtil.isEmpty(toTrigger))
        {
            log.info("分镜合并出片：无待触发分镜(均无提示词或已触发)，跳过, taskId={}, userId={}", promptTaskId, userId);
            return new ArrayList<>();
        }
        List<Long> childTaskIds = new ArrayList<>();
        List<Long> submittedShots = new ArrayList<>();
        try
        {
            for (List<Long> batchIds : splitBatches(toTrigger))
            {
                StoryboardVideoGenerateRequest req = new StoryboardVideoGenerateRequest();
                req.setStoryboardIds(batchIds);
                req.setModelName(strVal(chainNext.get("modelName")));
                req.setAspectRatio(strVal(chainNext.get("aspectRatio")));
                req.setResolution(strVal(chainNext.get("resolution")));
                if (chainNext.get("durationSeconds") instanceof Number n) { req.setDurationSeconds(n.intValue()); }
                if (chainNext.get("generateAudio") instanceof Boolean b) { req.setGenerateAudio(b); }
                StoryboardVideoGenerateVO vo = storyboardVideoGenerationService.generateVideo(req, userId);
                Long childTaskId = Objects.isNull(vo) ? null : vo.getTaskId();
                ensureChildTaskCreated(promptTaskId, batchIds, childTaskId);
                childTaskIds.add(childTaskId);
                submittedShots.addAll(batchIds);
            }
        }
        catch (RuntimeException e)
        {
            // 触发失败：释放本次占位，让后续终态事件可重新触发这些分镜
            releaseShots(promptTaskId, remainingShots(toTrigger, submittedShots));
            throw new ChainSubmitException(CHILD_TASK_TYPE_VIDEO, childTaskIds, e);
        }
        log.info("分镜合并出片已触发: promptTaskId={}, shotCount={}, childVideoTaskIds={}, userId={}",
                promptTaskId, toTrigger.size(), childTaskIds, userId);
        return childTaskIds;
    }

    /**
     * 触发图生视频 i2v：仅对本批中 video_prompt_image 非空、且本任务尚未触发过出片的分镜（镜头级幂等）。
     *
     * @return 出片子任务 ID 列表；无待触发分镜时返回空列表
     */
    private List<Long> triggerVideoFromImage(Long promptTaskId, List<Long> storyboardIds, Long userId, Map<String, Object> chainNext)
    {
        // 图生方向视频提示词回填在 video_prompt_image 列
        List<Long> ready = filterByPrompt(storyboardIds, AidStoryboard::getVideoPromptImage);
        List<Long> toTrigger = claimUntriggeredShots(promptTaskId, ready);
        if (CollectionUtil.isEmpty(toTrigger))
        {
            log.info("分镜合并图生出片：无待触发分镜(均无提示词或已触发)，跳过, taskId={}, userId={}", promptTaskId, userId);
            return new ArrayList<>();
        }
        List<Long> childTaskIds = new ArrayList<>();
        List<Long> submittedShots = new ArrayList<>();
        try
        {
            for (List<Long> batchIds : splitBatches(toTrigger))
            {
                StoryboardVideoFromImageGenerateRequest req = new StoryboardVideoFromImageGenerateRequest();
                req.setStoryboardIds(batchIds);
                req.setModelName(strVal(chainNext.get("modelName")));
                req.setAspectRatio(strVal(chainNext.get("aspectRatio")));
                req.setResolution(strVal(chainNext.get("resolution")));
                if (chainNext.get("durationSeconds") instanceof Number n) { req.setDurationSeconds(n.intValue()); }
                if (chainNext.get("generateAudio") instanceof Boolean b) { req.setGenerateAudio(b); }
                // 不传 images：多镜头各自回落分镜主图 final_image_id 作参考；多镜头每镜 1 条，不传 count
                StoryboardVideoGenerateVO vo = storyboardVideoGenerationService.generateVideoFromImage(req, userId);
                Long childTaskId = Objects.isNull(vo) ? null : vo.getTaskId();
                ensureChildTaskCreated(promptTaskId, batchIds, childTaskId);
                childTaskIds.add(childTaskId);
                submittedShots.addAll(batchIds);
            }
        }
        catch (RuntimeException e)
        {
            // 触发失败：释放本次占位，让后续终态事件（如提示词续生）可重新触发这些分镜
            releaseShots(promptTaskId, remainingShots(toTrigger, submittedShots));
            throw new ChainSubmitException(CHILD_TASK_TYPE_VIDEO, childTaskIds, e);
        }
        log.info("分镜合并图生出片已触发: promptTaskId={}, shotCount={}, childVideoTaskIds={}, userId={}",
                promptTaskId, toTrigger.size(), childTaskIds, userId);
        return childTaskIds;
    }

    /**
     * 触发宫格出片：仅对本批中 video_prompt_image 非空、且本任务尚未触发过出片的分镜（镜头级幂等）。
     *
     * @return 出片子任务 ID 列表；无待触发分镜时返回空列表
     */
    private List<Long> triggerVideoGrid(Long promptTaskId, List<Long> storyboardIds, Long userId, Map<String, Object> chainNext)
    {
        // 宫格视频提示词回填在 video_prompt_image 列（复用图生列）
        List<Long> ready = filterByPrompt(storyboardIds, AidStoryboard::getVideoPromptImage);
        List<Long> toTrigger = claimUntriggeredShots(promptTaskId, ready);
        if (CollectionUtil.isEmpty(toTrigger))
        {
            log.info("分镜合并宫格出片：无待触发分镜(均无提示词或已触发)，跳过, taskId={}, userId={}", promptTaskId, userId);
            return new ArrayList<>();
        }
        List<Long> childTaskIds = new ArrayList<>();
        List<Long> submittedShots = new ArrayList<>();
        try
        {
            for (List<Long> batchIds : splitBatches(toTrigger))
            {
                StoryboardVideoGridGenerateRequest req = new StoryboardVideoGridGenerateRequest();
                req.setStoryboardIds(batchIds);
                req.setModelName(strVal(chainNext.get("modelName")));
                req.setAspectRatio(strVal(chainNext.get("aspectRatio")));
                req.setResolution(strVal(chainNext.get("resolution")));
                if (chainNext.get("durationSeconds") instanceof Number n) { req.setDurationSeconds(n.intValue()); }
                if (chainNext.get("generateAudio") instanceof Boolean b) { req.setGenerateAudio(b); }
                // 多镜头每镜 1 条，不传 count
                StoryboardVideoGenerateVO vo = storyboardVideoGenerationService.generateVideoFromGrid(req, userId);
                Long childTaskId = Objects.isNull(vo) ? null : vo.getTaskId();
                ensureChildTaskCreated(promptTaskId, batchIds, childTaskId);
                childTaskIds.add(childTaskId);
                submittedShots.addAll(batchIds);
            }
        }
        catch (RuntimeException e)
        {
            // 触发失败：释放本次占位，让后续终态事件（如提示词续生）可重新触发这些分镜
            releaseShots(promptTaskId, remainingShots(toTrigger, submittedShots));
            throw new ChainSubmitException(CHILD_TASK_TYPE_VIDEO, childTaskIds, e);
        }
        log.info("分镜合并宫格出片已触发: promptTaskId={}, shotCount={}, childVideoTaskIds={}, userId={}",
                promptTaskId, toTrigger.size(), childTaskIds, userId);
        return childTaskIds;
    }

    private List<List<Long>> splitBatches(List<Long> ids)
    {
        List<List<Long>> batches = new ArrayList<>();
        if (CollectionUtil.isEmpty(ids))
        {
            return batches;
        }
        for (int i = 0; i < ids.size(); i += CHAIN_CHILD_BATCH_SIZE)
        {
            int end = Math.min(i + CHAIN_CHILD_BATCH_SIZE, ids.size());
            batches.add(new ArrayList<>(ids.subList(i, end)));
        }
        return batches;
    }

    private List<Long> remainingShots(List<Long> allShots, List<Long> submittedShots)
    {
        List<Long> remaining = new ArrayList<>();
        if (CollectionUtil.isEmpty(allShots))
        {
            return remaining;
        }
        for (Long shotId : allShots)
        {
            if (Objects.nonNull(shotId) && (submittedShots == null || !submittedShots.contains(shotId)))
            {
                remaining.add(shotId);
            }
        }
        return remaining;
    }

    /**
     * 镜头级幂等占位：对候选分镜逐个 {@code SADD sb:chain:shots:{taskId}}，仅返回"本次新加入"（此前未触发过）的分镜。
     * SADD 原子，保证并发终态事件下同一分镜只被一个调用拿到触发权；首次设置后刷新 24h TTL，覆盖续生窗口。
     */
    private List<Long> claimUntriggeredShots(Long promptTaskId, List<Long> readyShots)
    {
        if (CollectionUtil.isEmpty(readyShots)) { return new ArrayList<>(); }
        String key = REDIS_CHAIN_SHOTS_PREFIX + promptTaskId;
        List<Long> claimed = new ArrayList<>();
        for (Long sid : readyShots)
        {
            if (Objects.isNull(sid)) { continue; }
            Long added = redisCache.redisTemplate.opsForSet().add(key, String.valueOf(sid)); // 1=新加入 0=已存在
            if (added != null && added > 0L) { claimed.add(sid); }
        }
        // 刷新 TTL（幂等集合保留 24h，覆盖提示词续生窗口）
        redisCache.redisTemplate.expire(key, CHAIN_ONCE_TTL_SECONDS, TimeUnit.SECONDS);
        return claimed;
    }

    /** 校验子任务已创建：childTaskId 为空视为触发失败，释放本批占位并抛错。 */
    private void ensureChildTaskCreated(Long promptTaskId, List<Long> shots, Long childTaskId)
    {
        if (Objects.nonNull(childTaskId))
        {
            return;
        }
        releaseShots(promptTaskId, shots);
        throw new IllegalStateException("子任务创建失败");
    }

    /** 释放占位：触发出图/出片失败时把本次已占位的分镜移出幂等集合，使后续终态事件可重试。 */
    private void releaseShots(Long promptTaskId, List<Long> shots)
    {
        if (CollectionUtil.isEmpty(shots)) { return; }
        try
        {
            String key = REDIS_CHAIN_SHOTS_PREFIX + promptTaskId;
            redisCache.redisTemplate.opsForSet().remove(key, shots.stream().map(String::valueOf).toArray());
        }
        catch (Exception e)
        {
            // 释放失败仅影响这些分镜后续不再自动触发（用户可手动出图），不影响主流程
            log.warn("释放链路占位失败(不影响,可手动出图): promptTaskId={}, shots={}", promptTaskId, shots);
        }
    }

    /** 记录 child 出图父任务 ID（List，TTL 24h）：仅供追踪/前端可选定位出图阶段，不参与任何状态机。 */
    private void recordChildImageTask(Long promptTaskId, StoryboardImageGenerateVO vo)
    {
        if (Objects.isNull(vo) || Objects.isNull(vo.getTaskId())) { return; }
        try
        {
            String key = REDIS_CHAIN_CHILD_IMAGE_PREFIX + promptTaskId;
            redisCache.redisTemplate.opsForList().rightPush(key, String.valueOf(vo.getTaskId()));
            redisCache.redisTemplate.expire(key, CHAIN_ONCE_TTL_SECONDS, TimeUnit.SECONDS);
        }
        catch (Exception e)
        {
            // 仅追踪用途，记录失败不影响主流程
            log.warn("记录 child 出图 taskId 失败(不影响): promptTaskId={}, childTaskId={}", promptTaskId, vo.getTaskId());
        }
    }

    /**
     * 过滤出本批中"指定提示词列非空"的分镜，保持入参顺序。
     * 一次 in 查询批量取回（仅 select 三个提示词列），避免逐镜全字段 N+1 查询。
     *
     * @param promptGetter 提示词列取值器（image_prompt / video_prompt / video_prompt_image）
     */
    private List<Long> filterByPrompt(List<Long> storyboardIds, Function<AidStoryboard, String> promptGetter)
    {
        List<Long> ready = new ArrayList<>();
        if (CollectionUtil.isEmpty(storyboardIds))
        {
            return ready;
        }
        // 查询字段精简：仅 id + 三个提示词列（promptGetter 只会取其中之一，新增提示词列时同步增列）
        List<AidStoryboard> storyboards = aidStoryboardService.list(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getImagePrompt,
                                AidStoryboard::getVideoPrompt, AidStoryboard::getVideoPromptImage)
                        .in(AidStoryboard::getId, storyboardIds));
        Map<Long, AidStoryboard> byId = new java.util.HashMap<>();
        for (AidStoryboard sb : storyboards)
        {
            byId.put(sb.getId(), sb);
        }
        for (Long sid : storyboardIds)
        {
            AidStoryboard sb = byId.get(sid);
            if (Objects.isNull(sb)) { continue; }
            if (StrUtil.isNotBlank(promptGetter.apply(sb))) { ready.add(sid); }
        }
        return ready;
    }

    private static class ChainSubmitException extends RuntimeException
    {
        private final String childTaskType;
        private final List<Long> childTaskIds;

        ChainSubmitException(String childTaskType, List<Long> childTaskIds, Throwable cause)
        {
            super(cause);
            this.childTaskType = childTaskType;
            this.childTaskIds = new ArrayList<>();
            if (CollectionUtil.isNotEmpty(childTaskIds))
            {
                this.childTaskIds.addAll(childTaskIds);
            }
        }

        String getChildTaskType()
        {
            return childTaskType;
        }

        List<Long> getChildTaskIds()
        {
            return childTaskIds;
        }
    }

    private String strVal(Object v)
    {
        return v == null ? null : String.valueOf(v);
    }
}
