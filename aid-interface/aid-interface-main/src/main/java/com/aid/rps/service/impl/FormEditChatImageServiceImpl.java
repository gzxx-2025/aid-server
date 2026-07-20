package com.aid.rps.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiModelFuncConfig;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.service.IAidAiModelFuncConfigService;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.agent.AgentDefaultParamsApplier;
import com.aid.agent.AgentModelDefault;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.image.ImageUrlValidator;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.service.IMediaGenerationService;
import com.aid.model.vo.CapabilityVO;
import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.dto.FormEditChatImageGenerateRequest;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.service.IFormEditChatImageService;
import com.aid.rps.sse.AssetExtractSseManager;
import com.aid.service.IAiModelConfigService;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 编辑弹窗生图 / 对话作图 Service 实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class FormEditChatImageServiceImpl implements IFormEditChatImageService
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String STATUS_NORMAL = "0";

    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_CANCELLED = "CANCELLED";

    /** 任务类型：编辑弹窗生图 / 对话作图（与 AssetExtractServiceImpl 常量保持一致） */
    private static final String TASK_TYPE_FORM_EDIT_CHAT = "form_edit_chat";

    /** 生成模式：编辑图片（必须参考图） */
    private static final String GEN_MODE_EDIT = "edit";
    /** 生成模式：对话作图（参考图可选，可纯文生图 / 图生图 / 多图融合） */
    private static final String GEN_MODE_CHAT = "chat";

    /**
     * 功能编码：aid_ai_model_func_config.func_code 必须匹配该值。
     * 统一收敛到 {@code image_edit}（与 {@code /api/user/model/listByFunc} 场景编码一致），
     * 与分镜编辑图接口共享同一份模型池配置。
     */
    private static final String FUNC_CODE_IMAGE_EDIT = "image_edit";

    private static final String MODEL_TYPE_IMAGE = "image";

    /** form image.source_type：编辑弹窗独立来源，与 ai_auto / ai_builder / ai_multi_view 区分 */
    private static final String FORM_IMAGE_SOURCE_TYPE_EDIT_CHAT = "ai_edit_chat";

    // 含回调优先模式的 WAIT_CALLBACK，防止把中间态误判为失败。
    private static final Set<String> IMAGE_IN_PROGRESS_STATUSES = Set.of(
            "INIT", "PENDING", "QUEUED", "PROCESSING", "WAIT_POLL", "WAIT_CALLBACK");

    /** Redis 防重锁 Key 前缀（与 AssetExtractServiceImpl.FORM_LOCK_PREFIX 同命名空间） */
    private static final String FORM_LOCK_PREFIX = "asset:form:lock:";

    /**
     * 防重锁 TTL（秒）。
     * 必须覆盖单次任务最坏耗时：imageCount 上限 4 × 单张轮询超时 {@link #IMAGE_POLL_TIMEOUT_SECONDS}（180s）
     * + 模型同步耗时 + 计费 / 落库 + 安全余量，按 30 分钟兜底。
     * TTL 过短会在 4 张连发场景下出现"任务还在跑、锁先过期"，导致下一次提交直接 SETNX 成功并发起平行任务。
     */
    private static final long FORM_LOCK_TTL_SECONDS = 30L * 60L;

    /**
     * 锁"刚抢到但还没落 DB"的宽限期（毫秒）。
     * 锁值里编码了抢锁时间戳；若现存锁的年龄小于该值，无论 DB 是否查到活跃任务，都视为锁仍合法
     * （持有者大概率正在 SETNX 与 INSERT 之间的窗口）。一旦超过该值还查不到活跃任务，才能认定为僵尸锁清理。
     * 典型 INSERT 路径耗时 < 2s，60s 留足容错冗余。
     */
    private static final long FORM_LOCK_STALE_GRACE_MS = 60L * 1000L;

    /** Lua 脚本：仅当 GET key == ARGV[1] 才 DEL，防止自动过期后被他人复用又被本请求误删 */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    /** 张数上限：接口层 1~4，service 侧再做一次保险 */
    private static final int IMAGE_COUNT_MIN = 1;
    private static final int IMAGE_COUNT_MAX = 4;

    /** 图片轮询参数：与形态生图保持一致 */
    private static final long IMAGE_POLL_TIMEOUT_SECONDS = 180L;
    private static final long IMAGE_POLL_INTERVAL_SECONDS = 5L;

    // 忽略 capability_json 等反序列化时的未知字段，避免后续新增能力字段（如 maxReferenceImages）导致解析失败误判"模型不符"
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 异步线程池：与多机位 / 形态生图保持相同的执行语义 */
    private final ExecutorService editChatExecutor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "form-edit-chat-worker");
        t.setDaemon(true);
        return t;
    });
    @Autowired
    private RedisCache redisCache;

    @Autowired
    private IAidExtractTaskService extractTaskService;

    @Autowired
    private IAidComicProjectService projectService;

    @Autowired
    private IAidRolePropSceneService rpsService;

    @Autowired
    private IAidRolePropSceneFormService rpsFormService;

    @Autowired
    private IAidRolePropSceneFormImageService rpsFormImageService;

    @Autowired
    private IAidAiModelService aidAiModelService;

    @Autowired
    private IAidAiModelFuncConfigService aidAiModelFuncConfigService;

    @Autowired
    private IAiModelConfigService aiModelConfigService;

    @Autowired
    private IMediaGenerationService mediaGenerationService;

    /** 媒体URL统一解析器：本站校验 + 相对路径拼完整URL */
    @Autowired
    private MediaUrlResolver mediaUrlResolver;

    @Autowired
    private AgentDefaultParamsApplier agentDefaultParamsApplier;

    @Autowired
    private IAssetExtractService assetExtractService;

    /** 任务排队 / 多维并发调度服务 */
    @Autowired
    private com.aid.rps.queue.TaskQueueService taskQueueService;

    @Autowired
    private AssetExtractSseManager sseManager;

    @PreDestroy
    public void shutdown()
    {
        log.info("关闭编辑弹窗生图线程池...");
        editChatExecutor.shutdown();
        try
        {
            if (!editChatExecutor.awaitTermination(30, TimeUnit.SECONDS))
            {
                editChatExecutor.shutdownNow();
                log.warn("编辑弹窗生图线程池强制关闭");
            }
        }
        catch (InterruptedException e)
        {
            editChatExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    @Override
    public AssetExtractTaskVO generateEditChatImage(FormEditChatImageGenerateRequest request, Long userId)
    {
        validateBasicRequest(request, userId);

        EditChatContext ctx = loadAndValidateOwnership(request.getFormId(), userId);

        AidAiModel model = validateEditChatModel(request.getModelCode());
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(model.getModelCode());
        if (Objects.isNull(modelConfig))
        {
            log.error("编辑弹窗生图模型配置缺失: modelCode={}", model.getModelCode());
            throw new RuntimeException("模型无效");
        }
        validateModelCapability(modelConfig, request, userId);

        //    参考图统一拼成完整URL，避免相对路径进 prompt 对下游模型无意义
        String finalPrompt = buildFinalPrompt(request.getPrompt(), request.getAspectRatio(),
                mediaUrlResolver.toFullUrls(request.referenceImagesAsList()));

        //
        //    设计要点：
        //         a. 现存锁年龄 > FORM_LOCK_STALE_GRACE_MS（60s）—— 给真实持有者从 SETNX 到 INSERT 留宽限期，
        //            杜绝"持有者刚 SETNX 成功还没 INSERT，被并发请求误删了真实锁并重抢"的破坏防重核心语义的窗口；
        //         b. DB 中无活跃任务 —— 真正确认任务记录没落库。
        //       发现已有活跃任务 → 立即 CAS 释放本次抢到的锁并拒绝。
        String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_FORM_EDIT_CHAT + ":" + request.getFormId();
        String lockToken = buildLockToken();
        boolean lockHeldByMe = tryAcquireLock(lockKey, lockToken);

        if (!lockHeldByMe)
        {
            // 抢锁失败：先看 DB
            if (hasActiveEditChatTaskInDb(request.getFormId()))
            {
                log.info("编辑弹窗生图并发拦截: formId={}, lockKey={}", request.getFormId(), lockKey);
                throw new RuntimeException("任务处理中");
            }
            // DB 无活跃任务 → 还需检查现存锁的年龄；锁太新意味着持有者大概率正在 SETNX→INSERT 窗口里，不能误清。
            Object existing = redisCache.getCacheObject(lockKey);
            if (Objects.isNull(existing))
            {
                // 现存锁刚刚自然过期 → 直接重抢一次；抢不到说明同一瞬间被另一请求抢走，按真并发处理
                lockHeldByMe = tryAcquireLock(lockKey, lockToken);
                if (!lockHeldByMe)
                {
                    log.info("[FormEditChat] 锁过期后重抢被同瞬抢占: lockKey={}", lockKey);
                    throw new RuntimeException("任务处理中");
                }
            }
            else
            {
                String existingToken = String.valueOf(existing);
                if (!isLockStaleByAge(existingToken))
                {
                    log.info("编辑弹窗生图抢锁失败但锁年龄未过宽限期, 视为真并发: formId={}, lockKey={}",
                            request.getFormId(), lockKey);
                    throw new RuntimeException("任务处理中");
                }
                // 锁年龄已超过宽限期且 DB 无活跃任务 → 真正的僵尸锁；
                // 用 CAS 删除"刚才读到的那条 token"，避免在 get 与 del 之间该锁自然过期 / 被他人重新占用导致裸删误杀。
                log.warn("[FormEditChat] 检测到 Redis 锁泄漏（年龄超限且 DB 无活跃任务），CAS 清理: lockKey={}, formId={}",
                        lockKey, request.getFormId());
                if (!casDeleteIfMatch(lockKey, existingToken))
                {
                    // CAS 失败：要么锁已经被持有者主动释放、要么已被他人重新占用 → 一律按真并发处理，不再尝试
                    log.info("[FormEditChat] 僵尸锁 CAS 清理失败（锁已变化）, 视为真并发: lockKey={}", lockKey);
                    throw new RuntimeException("任务处理中");
                }
                lockHeldByMe = tryAcquireLock(lockKey, lockToken);
                if (!lockHeldByMe)
                {
                    log.info("[FormEditChat] 僵尸锁清理后再次被抢占: lockKey={}", lockKey);
                    throw new RuntimeException("任务处理中");
                }
            }
        }

        //    若发现已有活跃任务 → 立即 CAS 释放本次抢到的锁并拒绝，避免造成并发任务
        if (hasActiveEditChatTaskInDb(request.getFormId()))
        {
            safeReleaseLock(lockKey, lockToken);
            log.info("编辑弹窗生图抢锁后 DB 复核命中活跃任务, 拒绝并发: formId={}", request.getFormId());
            throw new RuntimeException("任务处理中");
        }

        try
        {
            return submitTask(request, userId, ctx, model, finalPrompt, lockKey, lockToken);
        }
        catch (RuntimeException e)
        {
            safeReleaseLock(lockKey, lockToken);
            throw e;
        }
    }

    /** 构造锁值：uuid|epochMillis；释放走 CAS，僵尸判定靠时间戳 */
    private String buildLockToken()
    {
        return java.util.UUID.randomUUID().toString() + "|" + System.currentTimeMillis();
    }

    /**
     * 判断给定锁值是否已超过宽限期（"刚抢到但还没落 DB"的合理时间窗）。
     * 解析失败一律视为过期（脏数据，按可清理处理）。
     */
    private boolean isLockStaleByAge(String tokenWithTs)
    {
        if (StrUtil.isBlank(tokenWithTs))
        {
            return true;
        }
        int sep = tokenWithTs.lastIndexOf('|');
        if (sep < 0 || sep >= tokenWithTs.length() - 1)
        {
            // 老格式 / 异常 token：按可清理处理
            return true;
        }
        try
        {
            long acquiredAt = Long.parseLong(tokenWithTs.substring(sep + 1));
            return System.currentTimeMillis() - acquiredAt > FORM_LOCK_STALE_GRACE_MS;
        }
        catch (NumberFormatException ignored)
        {
            return true;
        }
    }

    /**
     * SETNX + EX 抢锁。
     * @return true 表示本请求拿到锁，锁值即 token；false 表示锁已被占用
     */
    @SuppressWarnings("unchecked")
    private boolean tryAcquireLock(String key, String token)
    {
        Boolean ok = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(key, token, FORM_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 安全释放锁：仅当锁值仍等于本请求当初写入的 token 才删除。
     * 锁已自然过期 / 已被另一请求重新占用时，本调用一律不删别人的锁。
     */
    @SuppressWarnings("unchecked")
    private void safeReleaseLock(String key, String token)
    {
        try
        {
            redisCache.redisTemplate.execute(
                    RELEASE_LOCK_SCRIPT,
                    java.util.Collections.singletonList(key),
                    token);
        }
        catch (Exception e)
        {
            // 释放失败仅记日志：锁会按 TTL 自然过期，不影响业务
            log.warn("编辑弹窗生图锁释放失败: key={}, msg={}", key, e.getMessage());
        }
    }

    /**
     * 僵尸锁清理用 CAS：仅当锁值仍等于"刚才读到的 existingToken"时才 DEL，
     * 防止在 GET → DEL 之间锁自然过期被他人重抢导致裸删误杀。
     *
     * @return true = 成功删除（确认是当初读到的同一把脏锁）；
     *         false = 锁值已变（被持有者主动释放、过期被他人重抢、或本身已不存在），不删
     */
    @SuppressWarnings("unchecked")
    private boolean casDeleteIfMatch(String key, String existingToken)
    {
        if (StrUtil.isBlank(existingToken))
        {
            return false;
        }
        try
        {
            Object ret = redisCache.redisTemplate.execute(
                    RELEASE_LOCK_SCRIPT,
                    java.util.Collections.singletonList(key),
                    existingToken);
            return Objects.nonNull(ret) && ((Number) ret).longValue() > 0L;
        }
        catch (Exception e)
        {
            log.warn("编辑弹窗生图僵尸锁 CAS 清理失败: key={}, msg={}", key, e.getMessage());
            return false;
        }
    }

    /**
     * DB 兜底：检查指定 formId 是否真的有未结束（PENDING / PROCESSING）的 form_edit_chat 任务。
     */
    private boolean hasActiveEditChatTaskInDb(Long formId)
    {
        if (Objects.isNull(formId))
        {
            return false;
        }
        // 两个边界匹配：JSON 中 formId 后接 "," （还有后续字段）或 "}"（formId 是最后一个字段）
        String boundaryComma = "\"formId\":" + formId + ",";
        String boundaryEnd = "\"formId\":" + formId + "}";
        LambdaQueryWrapper<AidExtractTask> w = Wrappers.lambdaQuery();
        w.eq(AidExtractTask::getTaskType, TASK_TYPE_FORM_EDIT_CHAT)
                .in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING)
                .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)
                .and(q -> q.like(AidExtractTask::getInputSnapshot, boundaryComma)
                        .or()
                        .like(AidExtractTask::getInputSnapshot, boundaryEnd));
        Long cnt = extractTaskService.getBaseMapper().selectCount(w);
        return Objects.nonNull(cnt) && cnt > 0;
    }

    /** 编辑弹窗生图任务上下文快照：校验通过后打包传给异步线程，避免重复读库 */
    private static class EditChatContext
    {
        AidRolePropSceneForm form;
        AidRolePropScene asset;
        AidComicProject project;
    }

    /**
     * 同一批次的排序 / 名称基线：
     * 在进入"逐张生成 + 逐张落库"循环之前一次性统计当前 form 已有图片数和是否已有使用中的图；
     * 循环内只按"基线 + 循环 index"递增，避免每轮重新查库时把已写入的新图也计入 existingCount，
     * 造成名称序号 / sortOrder 跳号（1,3,5,7 …）。
     */
    private static class FormImageSortBaseline
    {
        int baseCount;
        boolean anyInUse;
        /** 该 form 下已有图片名字以 _编辑_N 结尾的最大 N（无则 0），本批次按 maxEditSeq + 1 起递增 */
        int maxEditSeq;
    }

    /** 批次开始前读取一次 form 的图片基线，后续逐张落库复用，不再每轮 getCount */
    private FormImageSortBaseline resolveFormImageBaseline(Long formId)
    {
        // baseCount / anyInUse 只看未删除的图（用于 sort_order / is_use 计算）
        LambdaQueryWrapper<AidRolePropSceneFormImage> existsQuery = Wrappers.lambdaQuery();
        existsQuery.eq(AidRolePropSceneFormImage::getFormId, formId);
        existsQuery.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        existsQuery.select(AidRolePropSceneFormImage::getId, AidRolePropSceneFormImage::getIsUse,
                AidRolePropSceneFormImage::getName);
        List<AidRolePropSceneFormImage> existing = rpsFormImageService.list(existsQuery);
        FormImageSortBaseline baseline = new FormImageSortBaseline();
        baseline.baseCount = CollectionUtil.isNotEmpty(existing) ? existing.size() : 0;
        baseline.anyInUse = false;
        baseline.maxEditSeq = 0;
        if (CollectionUtil.isNotEmpty(existing))
        {
            for (AidRolePropSceneFormImage row : existing)
            {
                if (Objects.nonNull(row.getIsUse()) && row.getIsUse() == 1)
                {
                    baseline.anyInUse = true;
                    break;
                }
            }
        }

        // maxEditSeq 必须扫描所有记录（含 del_flag='2' 软删），保证序号单调递增不被复用，
        // 避免被删除图片的旧名与新生成图片重复。
        LambdaQueryWrapper<AidRolePropSceneFormImage> allQuery = Wrappers.lambdaQuery();
        allQuery.eq(AidRolePropSceneFormImage::getFormId, formId);
        allQuery.select(AidRolePropSceneFormImage::getName);
        List<AidRolePropSceneFormImage> allRows = rpsFormImageService.list(allQuery);
        if (CollectionUtil.isNotEmpty(allRows))
        {
            java.util.regex.Pattern editSuffix = java.util.regex.Pattern.compile("_编辑_(\\d+)$");
            for (AidRolePropSceneFormImage row : allRows)
            {
                String n = row.getName();
                if (StrUtil.isBlank(n))
                {
                    continue;
                }
                java.util.regex.Matcher m = editSuffix.matcher(n);
                if (m.find())
                {
                    try
                    {
                        int seq = Integer.parseInt(m.group(1));
                        if (seq > baseline.maxEditSeq)
                        {
                            baseline.maxEditSeq = seq;
                        }
                    }
                    catch (NumberFormatException ignored)
                    {
                    }
                }
            }
        }
        return baseline;
    }

    /**
     * 批次"全部失败"内部信号：用于和取消 / 通用异常区分，日志 / result_data / SSE 行为不同。
     */
    private static final class BatchAllFailedException extends RuntimeException
    {
        BatchAllFailedException(String message)
        {
            super(message);
        }
    }

    /** 从 failedItems 里挑第一条失败文案作为对外错误提示；为空时回退到默认。 */
    @SuppressWarnings("unchecked")
    private String pickFirstFailMessage(List<Map<String, Object>> failedItems, String fallback)
    {
        if (CollectionUtil.isEmpty(failedItems))
        {
            return fallback;
        }
        Object msg = failedItems.get(0).get("message");
        if (msg instanceof String s && StrUtil.isNotBlank(s))
        {
            return s;
        }
        return fallback;
    }

    /**
     * 推一次"逐张进度"SSE 事件：。
     *
     * @param taskId          任务ID
     * @param formId          对应 form
     * @param totalCount      批次总张数（= 前端请求 imageCount）
     * @param processedCount  已处理张数（成功 + 失败）：每处理一张就 +1
     * @param items           已成功图片列表 [{imageId, imageUrl}]
     * @param failedItems     已累计失败项 [{index, message}]
     */
    private void pushEditChatStepProgress(Long taskId, Long formId, int totalCount,
                                           int processedCount,
                                           List<Map<String, Object>> items,
                                           List<Map<String, Object>> failedItems)
    {
        if (Objects.isNull(taskId) || totalCount <= 0)
        {
            return;
        }
        // 进度百分比：按"已处理数"计算，留 1% 给最终 complete 事件，避免中间态 progress 抢占 100
        int progress = (int) Math.floor(processedCount * 100.0 / totalCount);
        if (progress < 0)
        {
            progress = 0;
        }
        if (progress > 99)
        {
            progress = 99;
        }
        String progressText = processedCount + "/" + totalCount;
        // stepId 基于已处理张数编号：两次事件（如"第2张成功"和"第3张失败"）stepId 不会冲突
        String stepId = "edit_chat_" + processedCount + "_of_" + totalCount;
        // 文案同样走"已处理"，不再强调"已生成"——失败也是一种处理结果
        String stepTitle = "已处理 " + progressText;

        int successCount = CollectionUtil.isEmpty(items) ? 0 : items.size();
        int failCount = CollectionUtil.isEmpty(failedItems) ? 0 : failedItems.size();

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("taskType", TASK_TYPE_FORM_EDIT_CHAT);
        extras.put("status", TASK_STATUS_PROCESSING);
        extras.put("formId", formId);
        // 进度核心分子：已处理张数（成功+失败），保证单调递增
        extras.put("processedCount", processedCount);
        // 实际成功张数：独立字段，前端如需展示"已生成 X 张"可用此字段
        extras.put("successCount", successCount);
        extras.put("totalCount", totalCount);
        extras.put("progressText", progressText);
        // 兼容老字段 currentCount：语义统一为"已处理张数" = processedCount
        extras.put("currentCount", processedCount);
        if (CollectionUtil.isNotEmpty(items))
        {
            extras.put("items", items);
        }
        extras.put("failCount", failCount);
        if (failCount > 0)
        {
            extras.put("failedItems", failedItems);
        }

        try
        {
            sseManager.sendStepProgressWithData(taskId, "edit_chat_gen", progress,
                    stepId, stepTitle, processedCount, totalCount, extras);
        }
        catch (Exception e)
        {
            // 进度推送失败不影响主流程
            log.warn("编辑弹窗生图progress推送异常: taskId={}, formId={}, {}/{}",
                    taskId, formId, processedCount, totalCount);
        }
    }

    /**
     * 把 failedItems 里第一条错误原文归一化为"对前端可见的 ≤6 字短文案"。
     * {@link #pickFirstFailMessage(List, String)} 返回的是落盘用的原文（80 字截断），
     * 但规范要求：用户可见异常 ≤ 6 字，且不能透出底层英文 / 长异常；
     * 本方法从短文案关键词白名单里命中首个；全都不命中时返回安全兜底。
     */
    private String pickFirstUserFacingMessage(List<Map<String, Object>> failedItems, String fallback)
    {
        String raw = pickFirstFailMessage(failedItems, null);
        return sanitizeUserFacingError(raw, fallback);
    }

    /**
     * 归一化异常文案：命中业务短文案白名单 → 返回该短文案；其他情况 → 返回 {@code fallback}。
     * 关键词对照的都是服务内已用过的 ≤6 字短文案（见各业务 throw 位置），不会让底层 provider 的英文原文 / 长异常直接暴露。
     */
    private String sanitizeUserFacingError(String raw, String fallback)
    {
        String safe = StrUtil.isNotBlank(fallback) ? fallback : "生成失败";
        if (StrUtil.isBlank(raw))
        {
            return safe;
        }
        // 白名单短文案：与本服务 / 媒体主链路里抛出的业务短文案对齐，不引入新词汇
        String[] knownShort = new String[] {
                "图片生成失败", "图片生成超时", "图片生成被中断",
                "模型无效", "模型不符", "比例不符", "清晰度不符", "张数不合法",
                "参考图超限", "参考图缺失", "图片无效",
                "存储失败", "功能未开放", "任务处理中", "模板异常",
                "生成失败"
        };
        for (String keyword : knownShort)
        {
            if (raw.contains(keyword))
            {
                return keyword;
            }
        }
        return safe;
    }
    /**
     * 基础入参校验：参考图非必填（0~4 张），传时逐张做远程合法性校验；
     * 原始 prompt / modelCode / 比例 / 清晰度 / 张数非空。
     */
    private void validateBasicRequest(FormEditChatImageGenerateRequest request, Long userId)
    {
        if (Objects.isNull(request))
        {
            log.info("编辑弹窗生图失败，请求为空");
            throw new RuntimeException("参数异常");
        }
        if (Objects.isNull(request.getFormId()))
        {
            log.info("编辑弹窗生图失败，formId为空: userId={}", userId);
            throw new RuntimeException("形态不存在");
        }
        // 生成模式校验：必须是 edit（编辑图片）/ chat（对话作图）之一
        String genMode = normalizeGenMode(request.getGenMode());
        List<String> referenceImages = request.referenceImagesAsList();
        // edit 模式（编辑图片）：必须有参考图；chat 模式（对话作图）：参考图可空
        if (GEN_MODE_EDIT.equals(genMode) && CollectionUtil.isEmpty(referenceImages))
        {
            log.info("编辑图片失败，编辑模式必须有参考图: formId={}, userId={}", request.getFormId(), userId);
            throw new RuntimeException("参考图不能空");
        }
        // 远程合法性校验：非法 → 整批拒绝，不进任务系统 / 计费 / 媒体主链路
        // 张数上限由模型 capability_json.maxReferenceImages 决定，超出部分由 Provider 层统一截断，此处不做上限拦截
        for (String url : referenceImages)
        {
            if (StrUtil.isBlank(url))
            {
                log.info("形态图片创作失败，参考图URL为空: formId={}, userId={}",
                        request.getFormId(), userId);
                throw new RuntimeException("图片无效");
            }
            String trimmed = url.trim();
            // 仅允许本站资源（相对路径或本站域名完整URL），拒绝站外外链
            if (!mediaUrlResolver.isSiteImageUrl(trimmed))
            {
                log.info("形态图片创作参考图非本站资源: scene=form_edit_chat, genMode={}, formId={}, userId={}, imageUrl={}",
                        genMode, request.getFormId(), userId, trimmed);
                throw new RuntimeException("图片无效");
            }
            // 相对路径拼完整URL后再做远程可达性 + Content-Type 校验
            if (!ImageUrlValidator.isValidRemoteImageUrl(mediaUrlResolver.toFullUrl(trimmed)))
            {
                log.info("形态图片创作参考图校验失败: scene=form_edit_chat, genMode={}, formId={}, userId={}, imageUrl={}",
                        genMode, request.getFormId(), userId, trimmed);
                throw new RuntimeException("图片无效");
            }
        }

        if (StrUtil.isBlank(request.getPrompt()))
        {
            log.info("编辑弹窗生图失败，prompt为空: formId={}, userId={}", request.getFormId(), userId);
            throw new RuntimeException("提示词为空");
        }
        if (StrUtil.isBlank(request.getModelCode()))
        {
            log.info("编辑弹窗生图失败，modelCode为空: formId={}, userId={}", request.getFormId(), userId);
            throw new RuntimeException("模型不能空");
        }
        if (StrUtil.isBlank(request.getAspectRatio()))
        {
            log.info("编辑弹窗生图失败，aspectRatio为空: formId={}, userId={}", request.getFormId(), userId);
            throw new RuntimeException("比例不能空");
        }
        if (StrUtil.isBlank(request.getSize()))
        {
            log.info("编辑弹窗生图失败，size为空: formId={}, userId={}", request.getFormId(), userId);
            throw new RuntimeException("清晰度为空");
        }
        if (Objects.isNull(request.getImageCount())
                || request.getImageCount() < IMAGE_COUNT_MIN
                || request.getImageCount() > IMAGE_COUNT_MAX)
        {
            log.info("编辑弹窗生图失败，imageCount不合法: formId={}, userId={}, imageCount={}",
                    request.getFormId(), userId, request.getImageCount());
            throw new RuntimeException("张数不合法");
        }
    }

    /**
     * 归一化并校验生成模式：仅允许 {@code edit}（编辑图片）/ {@code chat}（对话作图）。
     * 大小写 / 前后空白不敏感；非法值直接拒绝。
     *
     * @param rawGenMode 入参原始模式值
     * @return 归一化后的模式（edit / chat）
     */
    private String normalizeGenMode(String rawGenMode)
    {
        String mode = StrUtil.trimToEmpty(rawGenMode).toLowerCase();
        if (GEN_MODE_EDIT.equals(mode) || GEN_MODE_CHAT.equals(mode))
        {
            return mode;
        }
        log.info("形态图片创作失败，模式不合法: genMode={}", rawGenMode);
        throw new RuntimeException("模式不合法");
    }

    /**
     * form 归属校验 + 加载主资产 / 项目。
     * 校验项：form 存在 / 未删除 / 属于当前用户；主资产存在；项目存在。
     */
    private EditChatContext loadAndValidateOwnership(Long formId, Long userId)
    {
        LambdaQueryWrapper<AidRolePropSceneForm> formQuery = Wrappers.lambdaQuery();
        formQuery.select(AidRolePropSceneForm::getId, AidRolePropSceneForm::getAssetId,
                AidRolePropSceneForm::getProjectId, AidRolePropSceneForm::getEpisodeId,
                AidRolePropSceneForm::getUserId, AidRolePropSceneForm::getName,
                AidRolePropSceneForm::getPromptText, AidRolePropSceneForm::getDelFlag);
        formQuery.eq(AidRolePropSceneForm::getId, formId);
        AidRolePropSceneForm form = rpsFormService.getOne(formQuery, false);
        if (Objects.isNull(form) || !Objects.equals(DEL_FLAG_NORMAL, form.getDelFlag()))
        {
            log.info("编辑弹窗生图失败，形态不存在: formId={}", formId);
            throw new RuntimeException("形态不存在");
        }
        if (!Objects.equals(userId, form.getUserId()))
        {
            log.info("编辑弹窗生图失败，形态不属于当前用户: formId={}, userId={}", formId, userId);
            throw new RuntimeException("形态不存在");
        }
        AidRolePropScene asset = rpsService.getById(form.getAssetId());
        if (Objects.isNull(asset))
        {
            log.info("编辑弹窗生图失败，主资产不存在: assetId={}", form.getAssetId());
            throw new RuntimeException("资产不存在");
        }
        AidComicProject project = projectService.selectAidComicProjectById(asset.getProjectId());
        if (Objects.isNull(project))
        {
            log.info("编辑弹窗生图失败，项目不存在: projectId={}", asset.getProjectId());
            throw new RuntimeException("项目不存在");
        }
        EditChatContext ctx = new EditChatContext();
        ctx.form = form;
        ctx.asset = asset;
        ctx.project = project;
        return ctx;
    }

    /**
     * 模型可用范围校验：modelCode 必须存在 + 启用 + model_type=image，且 ID 在
     * {@code aid_ai_model_func_config.func_code = image_edit} 的 modelIds 列表里
     * （与 {@code /api/user/model/listByFunc} 共享同一份"图片编辑"模型池）。
     */
    private AidAiModel validateEditChatModel(String modelCode)
    {
        LambdaQueryWrapper<AidAiModelFuncConfig> cfgQuery = Wrappers.lambdaQuery();
        cfgQuery.select(AidAiModelFuncConfig::getId, AidAiModelFuncConfig::getFuncCode,
                AidAiModelFuncConfig::getModelIds, AidAiModelFuncConfig::getStatus,
                AidAiModelFuncConfig::getDelFlag);
        cfgQuery.eq(AidAiModelFuncConfig::getFuncCode, FUNC_CODE_IMAGE_EDIT);
        cfgQuery.eq(AidAiModelFuncConfig::getStatus, STATUS_NORMAL);
        cfgQuery.eq(AidAiModelFuncConfig::getDelFlag, DEL_FLAG_NORMAL);
        cfgQuery.last("limit 1");
        AidAiModelFuncConfig cfg = aidAiModelFuncConfigService.getOne(cfgQuery, false);
        if (Objects.isNull(cfg))
        {
            log.error("编辑弹窗生图失败，未配置功能池: funcCode={}", FUNC_CODE_IMAGE_EDIT);
            throw new RuntimeException("功能未开放");
        }
        List<Long> allowedIds = parseModelIdsJson(cfg.getModelIds());
        if (CollectionUtil.isEmpty(allowedIds))
        {
            log.error("编辑弹窗生图失败，功能池为空: funcCode={}", FUNC_CODE_IMAGE_EDIT);
            throw new RuntimeException("功能未开放");
        }

        LambdaQueryWrapper<AidAiModel> modelQuery = Wrappers.lambdaQuery();
        modelQuery.select(AidAiModel::getId, AidAiModel::getModelCode,
                AidAiModel::getModelName, AidAiModel::getModelType,
                AidAiModel::getStatus, AidAiModel::getDelFlag);
        modelQuery.eq(AidAiModel::getModelCode, modelCode);
        modelQuery.eq(AidAiModel::getStatus, STATUS_NORMAL);
        modelQuery.eq(AidAiModel::getDelFlag, DEL_FLAG_NORMAL);
        modelQuery.last("limit 1");
        AidAiModel model = aidAiModelService.getOne(modelQuery, false);
        if (Objects.isNull(model))
        {
            log.info("编辑弹窗生图失败，模型不存在或已停用: modelCode={}", modelCode);
            throw new RuntimeException("模型无效");
        }
        if (!Objects.equals(MODEL_TYPE_IMAGE, model.getModelType()))
        {
            log.info("编辑弹窗生图失败，模型类型不匹配: modelCode={}, modelType={}", modelCode, model.getModelType());
            throw new RuntimeException("模型不符");
        }
        if (!allowedIds.contains(model.getId()))
        {
            log.info("编辑弹窗生图失败，模型不在功能池: modelCode={}, modelId={}, pool={}",
                    modelCode, model.getId(), allowedIds);
            throw new RuntimeException("模型不符");
        }
        return model;
    }

    /**
     * 模型能力校验（严格模式）：。
     */
    private void validateModelCapability(AiModelConfigVo modelConfig,
                                          FormEditChatImageGenerateRequest request, Long userId)
    {
        String modelCode = modelConfig.getModelCode();
        Long formId = request.getFormId();

        CapabilityVO capability = parseCapabilityJsonStrict(modelConfig.getCapabilityJson(), modelCode, formId, userId);

        List<String> aspectOptions = capability.getAspectRatioOptions();
        if (CollectionUtil.isEmpty(aspectOptions))
        {
            log.info("编辑弹窗生图比例能力缺失: formId={}, userId={}, modelCode={}", formId, userId, modelCode);
            throw new RuntimeException("比例不符");
        }
        String requestedAspect = request.getAspectRatio().trim();
        boolean aspectMatched = aspectOptions.stream()
                .filter(StrUtil::isNotBlank)
                .anyMatch(opt -> opt.trim().equalsIgnoreCase(requestedAspect));
        if (!aspectMatched)
        {
            log.info("编辑弹窗生图比例不支持: formId={}, userId={}, modelCode={}, aspectRatio={}, supported={}",
                    formId, userId, modelCode, requestedAspect, aspectOptions);
            throw new RuntimeException("比例不符");
        }

        List<String> sizeOptions = capability.getSizeOptions();
        if (CollectionUtil.isEmpty(sizeOptions))
        {
            log.info("编辑弹窗生图清晰度能力缺失: formId={}, userId={}, modelCode={}", formId, userId, modelCode);
            throw new RuntimeException("清晰度不符");
        }
        String requestedSize = request.getSize().trim();
        boolean sizeMatched = sizeOptions.stream()
                .filter(StrUtil::isNotBlank)
                .anyMatch(opt -> opt.trim().equalsIgnoreCase(requestedSize));
        if (!sizeMatched)
        {
            log.info("编辑弹窗生图清晰度不支持: formId={}, userId={}, modelCode={}, size={}, supported={}",
                    formId, userId, modelCode, requestedSize, sizeOptions);
            throw new RuntimeException("清晰度不符");
        }

        Integer maxOutput = modelConfig.getMaxOutputCount();
        if (Objects.isNull(maxOutput) || maxOutput <= 0)
        {
            log.info("编辑弹窗生图模型输出上限未配置: formId={}, userId={}, modelCode={}", formId, userId, modelCode);
            throw new RuntimeException("张数不合法");
        }
        int requestedCount = request.getImageCount();
        if (requestedCount < IMAGE_COUNT_MIN || requestedCount > IMAGE_COUNT_MAX)
        {
            log.info("编辑弹窗生图张数越界: formId={}, userId={}, modelCode={}, imageCount={}",
                    formId, userId, modelCode, requestedCount);
            throw new RuntimeException("张数不合法");
        }
        if (requestedCount > maxOutput)
        {
            log.info("编辑弹窗生图张数超出模型上限: formId={}, userId={}, modelCode={}, imageCount={}, maxOutput={}",
                    formId, userId, modelCode, requestedCount, maxOutput);
            throw new RuntimeException("张数不合法");
        }

        //    - refCount == 0：走文生图路径（仅 chat 模式可能为 0），模型必须 supportsTextInput=true
        //    - refCount >= 1：图生图 / 多图融合，模型必须 supportsImageInput=true
        //    注：参考图张数上限由模型 capability_json.maxReferenceImages 决定，超出由 Provider 层统一截断，此处不做上限拦截
        int refCount = CollectionUtil.isEmpty(request.referenceImagesAsList()) ? 0 : request.referenceImagesAsList().size();
        Boolean supportsText = modelConfig.getSupportsTextInput();
        Boolean supportsImage = modelConfig.getSupportsImageInput();
        if (refCount == 0)
        {
            if (!Boolean.TRUE.equals(supportsText))
            {
                log.info("编辑弹窗生图文生图输入能力缺失: scene=form_edit_chat, formId={}, userId={}, modelCode={}, referenceImageCount=0, supportsTextInput={}",
                        formId, userId, modelCode, supportsText);
                throw new RuntimeException("模型不符");
            }
        }
        // 图生图 / 多图融合：模型必须支持图片输入
        else
        {
            if (!Boolean.TRUE.equals(supportsImage))
            {
                log.info("形态图片创作图片输入能力缺失: scene=form_edit_chat, formId={}, userId={}, modelCode={}, referenceImageCount={}, supportsImageInput={}",
                        formId, userId, modelCode, refCount, supportsImage);
                throw new RuntimeException("模型不符");
            }
        }
    }

    /**
     * 严格解析 capabilityJson → CapabilityVO。
     * 解析失败 / 入参为空 一律视为模型能力不可确认，直接拒绝。
     * 日志带 scene / formId / userId / modelCode 便于定位。
     */
    private CapabilityVO parseCapabilityJsonStrict(String json, String modelCode, Long formId, Long userId)
    {
        if (StrUtil.isBlank(json))
        {
            log.info("编辑弹窗生图capabilityJson为空, 无法确认模型能力: scene=form_edit_chat, formId={}, userId={}, modelCode={}",
                    formId, userId, modelCode);
            throw new RuntimeException("模型不符");
        }
        try
        {
            CapabilityVO capability = OBJECT_MAPPER.readValue(json, CapabilityVO.class);
            if (Objects.isNull(capability))
            {
                log.info("编辑弹窗生图capabilityJson解析为空: scene=form_edit_chat, formId={}, userId={}, modelCode={}",
                        formId, userId, modelCode);
                throw new RuntimeException("模型不符");
            }
            return capability;
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.info("编辑弹窗生图capabilityJson解析失败: scene=form_edit_chat, formId={}, userId={}, modelCode={}, err={}",
                    formId, userId, modelCode, e.getMessage());
            throw new RuntimeException("模型不符");
        }
    }

    /** 解析 {@code aid_ai_model_func_config.model_ids} JSON 数组字符串（复用多机位相同实现）。 */
    private List<Long> parseModelIdsJson(String modelIdsJson)
    {
        List<Long> ordered = new ArrayList<>();
        if (StrUtil.isBlank(modelIdsJson))
        {
            return ordered;
        }
        try
        {
            List<?> raw = JSONUtil.parseArray(modelIdsJson).toList(Object.class);
            for (Object item : raw)
            {
                if (Objects.isNull(item))
                {
                    continue;
                }
                Long id = null;
                if (item instanceof Number)
                {
                    id = ((Number) item).longValue();
                }
                else
                {
                    String s = item.toString().trim();
                    if (StrUtil.isBlank(s))
                    {
                        continue;
                    }
                    try
                    {
                        id = Long.parseLong(s);
                    }
                    catch (NumberFormatException ignore)
                    {
                        // 非数字元素跳过
                    }
                }
                if (Objects.nonNull(id) && id > 0L && !ordered.contains(id))
                {
                    ordered.add(id);
                }
            }
        }
        catch (Exception e)
        {
            log.error("解析编辑弹窗生图功能池 modelIds 失败: raw={}, err={}", modelIdsJson, e.getMessage());
        }
        return ordered;
    }

    /** 最终 prompt 拼装：
     * 原文保留 + 图片比例 + 参考图 URL 列表一起拼进最终 prompt，让下游模型能拿到完整上下文。
     * 原始前端 prompt 另存 {@code input_snapshot.rawPrompt}；参考图 URL 另存 {@code input_snapshot.referenceImages}
     * 和 {@code form_image.reference_images}；最终完整 prompt 同时落 {@code form_image.prompt_snapshot}。
     */
    private String buildFinalPrompt(String rawPrompt, String aspectRatio, List<String> referenceImages)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(StrUtil.trimToEmpty(rawPrompt));
        if (StrUtil.isNotBlank(aspectRatio))
        {
            if (sb.length() > 0 && !sb.toString().endsWith("\n"))
            {
                sb.append("\n");
            }
            sb.append("图片比例：").append(aspectRatio.trim());
        }
        // 参考图：不仅写数量，完整把 URL 列表拼进来（下游 provider 不一定识别，但对排查 + 提示词保真有价值）
        if (CollectionUtil.isNotEmpty(referenceImages))
        {
            if (sb.length() > 0 && !sb.toString().endsWith("\n"))
            {
                sb.append("\n");
            }
            sb.append("参考图数量：").append(referenceImages.size()).append("\n");
            sb.append("参考图URL：").append("\n");
            int idx = 1;
            for (String url : referenceImages)
            {
                if (StrUtil.isBlank(url))
                {
                    continue;
                }
                sb.append("[").append(idx).append("] ").append(url.trim()).append("\n");
                idx++;
            }
        }
        return sb.toString();
    }
    /**
     * 写任务记录 + 本地线程池异步执行（与多机位 / form_image 链路保持相同骨架）。
     */
    private AssetExtractTaskVO submitTask(FormEditChatImageGenerateRequest request, Long userId,
                                           EditChatContext ctx, AidAiModel model,
                                           String finalPrompt, String lockKey, String lockToken)
    {
        AidRolePropSceneForm form = ctx.form;
        AidRolePropScene asset = ctx.asset;
        String modelCode = model.getModelCode();
        String aspectRatio = request.getAspectRatio().trim();
        String size = request.getSize().trim();
        int imageCount = request.getImageCount();
        List<String> referenceImages = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(request.referenceImagesAsList()))
        {
            for (String url : request.referenceImagesAsList())
            {
                if (StrUtil.isNotBlank(url))
                {
                    // DB 统一存相对路径：完整URL剥域名，相对路径原样保留
                    referenceImages.add(mediaUrlResolver.toRelativePath(url.trim()));
                }
            }
        }

        AidExtractTask task = new AidExtractTask();
        task.setProjectId(form.getProjectId());
        task.setEpisodeId(form.getEpisodeId());
        task.setUserId(userId);
        task.setTaskType(TASK_TYPE_FORM_EDIT_CHAT);
        task.setModelCode(modelCode);
        try
        {
            Map<String, Object> inputMap = new LinkedHashMap<>();
            inputMap.put("formId", form.getId());
            inputMap.put("assetId", asset.getId());
            inputMap.put("assetType", asset.getAssetType());
            inputMap.put("genMode", normalizeGenMode(request.getGenMode()));
            inputMap.put("modelCode", modelCode);
            inputMap.put("aspectRatio", aspectRatio);
            inputMap.put("size", size);
            inputMap.put("imageCount", imageCount);
            inputMap.put("referenceImages", referenceImages);
            // 原始 prompt 原文保留（全量）+ 最终 prompt 摘要（200 字内）
            inputMap.put("rawPrompt", request.getPrompt());
            inputMap.put("finalPromptSummary", StrUtil.sub(finalPrompt, 0, 200));
            inputMap.put("finalPromptLen", finalPrompt.length());
            task.setInputSnapshot(OBJECT_MAPPER.writeValueAsString(inputMap));
        }
        catch (Exception e)
        {
            task.setInputSnapshot("{\"formId\":" + form.getId() + "}");
        }
        task.setStatus(TASK_STATUS_PENDING);
        task.setTotalCount(0);
        task.setDelFlag(DEL_FLAG_NORMAL);
        task.setCreateTime(DateUtils.getNowDate());
        task.setCreateBy(String.valueOf(userId));
        extractTaskService.save(task);
        Long taskId = task.getId();

        // 入队 + 多维并发调度（LOCAL 派发），名额放行后由本地派发执行器执行此 job。
        Runnable editChatJob = () ->
        {
            try
            {
                if (assetExtractService.isTaskCancelled(taskId))
                {
                    log.info("编辑弹窗生图启动前检测到取消, 跳过执行: taskId={}, formId={}", taskId, form.getId());
                    if (updateTaskCancelled(taskId))
                    {
                        sseManager.sendCancelled(taskId, "用户取消");
                    }
                    return;
                }
                if (!updateTaskStatus(taskId, TASK_STATUS_PROCESSING, null, TASK_STATUS_PENDING))
                {
                    log.warn("编辑弹窗生图任务已被其他线程处理, 跳过: taskId={}", taskId);
                    return;
                }
                // 登记执行租约（重启自愈据租约判活）
                assetExtractService.markTaskProcessing(taskId);
                if (assetExtractService.isTaskCancelled(taskId))
                {
                    log.info("编辑弹窗生图进入PROCESSING后检测到取消, 跳过生成: taskId={}, formId={}", taskId, form.getId());
                    if (updateTaskCancelled(taskId))
                    {
                        sseManager.sendCancelled(taskId, "用户取消");
                    }
                    return;
                }

                // 真实的多张输出语义：按用户请求的 imageCount 循环调用 generateImage，
                // 每次单张；这样能真正拿到 N 个 ossUrl、N 条 form_image、N 条计费记录，
                // 避免"前端传 4、最后只拿到 1 张"的伪支持。取消检查点穿插在每轮生成之间 / 落库前。
                // 补丁说明：
                //   ① baseSortOrder 在循环外一次性读取，避免"实时查询 + index"叠加导致的名称 / sortOrder 跳号；
                //   ② 单轮异常不再整体失败 —— 已成功的图继续保留，批次末尾再按"部分成功 / 全部成功 / 全部失败"统一收尾。
                List<Long> imageIds = new ArrayList<>();
                List<Map<String, Object>> items = new ArrayList<>();
                // 批次内"累计失败"收集：每轮失败仅记录，不再中断循环；末尾统一决定终态
                List<Map<String, Object>> failedItems = new ArrayList<>();
                FormImageSortBaseline baseline = resolveFormImageBaseline(form.getId());
                for (int i = 0; i < imageCount; i++)
                {
                    // 逐张检查取消：尚未调生成前命中取消 → 终止循环，已经落库的图保留
                    if (assetExtractService.isTaskCancelled(taskId))
                    {
                        log.info("编辑弹窗生图批次中途检测到取消: taskId={}, formId={}, done={}/{}",
                                taskId, form.getId(), i, imageCount);
                        break;
                    }
                    try
                    {
                        String imageUrl = generateSingleImage(taskId, userId, form, modelCode, finalPrompt,
                                referenceImages, aspectRatio, size, i + 1, imageCount);
                        if (StrUtil.isBlank(imageUrl))
                        {
                            log.error("编辑弹窗生图单张为空: taskId={}, formId={}, index={}", taskId, form.getId(), i);
                            throw new RuntimeException("图片生成失败");
                        }
                        Long imageId = persistEditChatFormImage(form, asset, imageUrl, finalPrompt,
                                referenceImages, taskId, userId, baseline, i);
                        imageIds.add(imageId);
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("imageId", imageId);
                        item.put("imageUrl", imageUrl);
                        items.add(item);
                        // 逐张成功：推一次 progress（已处理/总数 + items + 已累计失败项）；
                        // 最终 complete 仍然由批次结束后的统一分支推，不被这里替代
                        pushEditChatStepProgress(taskId, form.getId(), imageCount,
                                i + 1, items, failedItems);
                    }
                    catch (TaskCancelledException cancel)
                    {
                        // 轮询期间探测到取消：交给外层 TaskCancelledException 分支处理终态
                        throw cancel;
                    }
                    catch (Exception perItemEx)
                    {
                        // 单张失败不中断批次：记录失败项，继续跑后续，保证已成功的图保留
                        log.error("编辑弹窗生图单张失败: taskId={}, formId={}, index={}, err={}",
                                taskId, form.getId(), i, perItemEx.getMessage());
                        Map<String, Object> failItem = new LinkedHashMap<>();
                        failItem.put("index", i + 1);
                        // 原始异常文案（截断 80 字）仅落 result_data.failedItems 用于内部排查；
                        // 对前端的错误提示走 pickFirstUserFacingMessage 归一化为短文案
                        failItem.put("message", StrUtil.sub(
                                StrUtil.blankToDefault(perItemEx.getMessage(), "生成失败"), 0, 80));
                        failedItems.add(failItem);
                        // 单张失败也推一次 progress：分子走"已处理张数"而非"成功张数"，
                        // 保证前端始终能看到"1/4 → 2/4 → 3/4 → 4/4"单调递增，不会卡在同一分子
                        pushEditChatStepProgress(taskId, form.getId(), imageCount,
                                i + 1, items, failedItems);
                    }
                }

                // 已拿到最终 imageUrl 列表：与 image_upscale / 多机位对齐，走"已完成结果保留"语义 —— 即便已取消也落库
                if (CollectionUtil.isEmpty(imageIds))
                {
                    // 0 张结果 + 未检测到取消 → 视为失败
                    if (!assetExtractService.isTaskCancelled(taskId))
                    {
                        log.error("编辑弹窗生图无任何结果: taskId={}, formId={}, failedItems={}",
                                taskId, form.getId(), failedItems);
                        // 对前端只透出归一化后的短文案（≤6字）；原始底层异常保留在 result_data.failedItems + 日志
                        String userFacing = pickFirstUserFacingMessage(failedItems, "生成失败");
                        throw new BatchAllFailedException(userFacing);
                    }
                }

                Map<String, Object> resultMap = new LinkedHashMap<>();
                resultMap.put("formId", form.getId());
                resultMap.put("imageCount", imageIds.size());
                resultMap.put("imageIds", imageIds);
                resultMap.put("items", items);
                resultMap.put("aspectRatio", aspectRatio);
                resultMap.put("size", size);
                // 部分成功：把失败明细一并写到 resultData，前端详情 / 排查都能看见
                if (CollectionUtil.isNotEmpty(failedItems))
                {
                    resultMap.put("failCount", failedItems.size());
                    resultMap.put("failedItems", failedItems);
                }
                String resultJson = OBJECT_MAPPER.writeValueAsString(resultMap);

                if (assetExtractService.isTaskCancelled(taskId))
                {
                    if (updateTaskCancelledWithResult(taskId, resultJson))
                    {
                        sseManager.sendCancelled(taskId, "用户取消");
                        log.info("编辑弹窗生图完成但检测到取消(resultData已保留): taskId={}, formId={}, imageIds={}",
                                taskId, form.getId(), imageIds);
                    }
                    else
                    {
                        log.info("编辑弹窗生图取消分支 CAS 未命中, 放弃发送 cancelled: taskId={}, formId={}",
                                taskId, form.getId());
                    }
                    return;
                }

                if (updateTaskSuccess(taskId, imageIds.size(), resultJson))
                {
                    sseManager.sendComplete(taskId, resultMap);
                    log.info("编辑弹窗生图完成: taskId={}, formId={}, imageIds={}",
                            taskId, form.getId(), imageIds);
                }
                else
                {
                    log.info("编辑弹窗生图成功分支 CAS 未命中, 放弃发送 complete: taskId={}, formId={}",
                            taskId, form.getId());
                }
            }
            catch (TaskCancelledException e)
            {
                log.info("编辑弹窗生图任务执行中被取消: taskId={}, formId={}", taskId, form.getId());
                if (updateTaskCancelled(taskId))
                {
                    sseManager.sendCancelled(taskId, "用户取消");
                }
            }
            catch (Exception e)
            {
                log.error("编辑弹窗生图任务失败: taskId={}, formId={}", taskId, form.getId(), e);
                // 对外只透出短文案：任务 errorMessage（任务查询接口会读）和 SSE error 文案都走统一归一化
                com.aid.common.error.TaskErrorResult errorResult = com.aid.common.error.ErrorNormalizer.normalize(e);
                if (updateTaskFailed(taskId, errorResult))
                {
                    sseManager.sendError(taskId, errorResult);
                }
            }
            finally
            {
                // CAS 释放：仅当锁值仍是本请求当初写入的 token 才删，避免锁自然过期后被他人重新占用又被这里误删
                safeReleaseLock(lockKey, lockToken);
                try
                {
                    assetExtractService.clearCancelFlag(taskId);
                }
                catch (Exception ignore)
                {
                    // 清标记失败仅告警，不影响主流程
                }
                // 释放多维并发名额 + 执行租约（幂等）
                try
                {
                    assetExtractService.releaseTaskSlots(taskId);
                }
                catch (Exception ignore)
                {
                    // ignore
                }
            }
        };
        // 入队（LOCAL 派发）；CAS 未命中（极少见）则回滚任务 + 释放锁
        boolean enqueued = taskQueueService.submitLocalTask(taskId, form.getProjectId(),
                form.getEpisodeId(), userId, modelCode, TASK_TYPE_FORM_EDIT_CHAT, editChatJob);
        if (!enqueued)
        {
            log.error("编辑弹窗生图入队失败: taskId={}, formId={}", taskId, form.getId());
            updateTaskFailed(taskId, "提交失败");
            safeReleaseLock(lockKey, lockToken);
            throw new RuntimeException("提交失败");
        }

        return AssetExtractTaskVO.builder()
                .taskId(taskId)
                .status(TASK_STATUS_PENDING)
                .build();
    }

    /**
     * 调用统一图片生成主链路 —— 单次一张：。
     */
    private String generateSingleImage(Long taskId, Long userId, AidRolePropSceneForm form,
                                        String modelCode, String finalPrompt,
                                        List<String> referenceImages, String aspectRatio,
                                        String size, int indexFrom1, int totalCount)
    {
        MediaImageGenerateRequest imageRequest = new MediaImageGenerateRequest();
        imageRequest.setModelName(modelCode);
        imageRequest.setUserId(userId);
        imageRequest.setPrompt(finalPrompt);
        imageRequest.setProjectId(form.getProjectId());
        imageRequest.setEpisodeId(form.getEpisodeId());

        Map<String, Object> options = new HashMap<>();
        // 参考图：顶层 referenceImageUrl 固定带首张（兼容仅识别单图字段的 Provider），
        // 完整多图列表通过 options.referenceImages 下发；具体可用张数由各 Provider 按 capability_json.maxReferenceImages 截断。
        if (CollectionUtil.isNotEmpty(referenceImages))
        {
            // DB 侧存相对路径，下游 provider 需完整可访问 URL，这里统一拼成完整URL
            List<String> fullRefs = mediaUrlResolver.toFullUrls(referenceImages);
            imageRequest.setReferenceImageUrl(fullRefs.get(0));
            options.put("referenceImages", new ArrayList<>(fullRefs));
        }
        // 用户显式传了 aspect_ratio → 写入 options（options 优先级高于 applier 的默认兜底）
        options.put("aspect_ratio", aspectRatio);
        // 强制单图：即便后续有人在 options 里传 n/expectedImageCount 也会被压回 1，避免误预扣
        options.put("force_single", true);
        imageRequest.setOptions(options);

        imageRequest.setSize(size);
        imageRequest.setExpectedImageCount(1);
        // 业务任务关联：bizTaskType 保持稳定（= form_edit_chat）以便媒体任务审计 / 汇总统一；
        // bizTaskId 按 "父 taskId * 100 + indexFrom1" 编码差异化，保证 N 次调用的 requestHash 天然不同，
        // 避免同 prompt / 同参考图 / 同参数被媒体层幂等复用合并。父 taskId 可通过 bizTaskId/100 反推。
        imageRequest.setBizTaskId(taskId * 100L + indexFrom1);
        imageRequest.setBizTaskType(TASK_TYPE_FORM_EDIT_CHAT);

        AiModelConfigVo defaultModelConfig = aiModelConfigService.selectByModelCode(modelCode);
        if (Objects.isNull(defaultModelConfig))
        {
            log.error("编辑弹窗生图模型配置缺失: modelCode={}", modelCode);
            throw new RuntimeException("模型无效");
        }
        // 应用模型能力校验与默认参数。
        AgentModelDefault agentModel = new AgentModelDefault(modelCode);
        agentDefaultParamsApplier.applyToImage(agentModel, imageRequest, defaultModelConfig);

        MediaTaskResponse imageResponse = mediaGenerationService.generateImage(imageRequest);
        return resolveSingleImageUrl(taskId, imageResponse);
    }

    /**
     * 内部受检信号：异步执行期间探测到用户取消；走独立 catch 以免被当作 FAILED 覆盖任务状态。
     */
    private static final class TaskCancelledException extends RuntimeException
    {
        TaskCancelledException()
        {
            super("用户取消");
        }
    }

    /**
     * 解析单次图片生成响应：支持同步 / 异步，异步情况下轮询期间可响应用户取消。
     * 本服务走"每次一张"语义，故返回单一 ossUrl；空 / 失败 / 超时直接抛异常。
     */
    private String resolveSingleImageUrl(Long taskId, MediaTaskResponse imageResponse)
    {
        if (Objects.isNull(imageResponse))
        {
            throw new RuntimeException("图片生成失败");
        }

        if (Objects.equals(TASK_STATUS_SUCCEEDED, imageResponse.getStatus()))
        {
            if (StrUtil.isBlank(imageResponse.getOssUrl()))
            {
                log.error("编辑弹窗生图同步成功但 ossUrl 为空: mediaTaskId={}", imageResponse.getTaskId());
                throw new RuntimeException("存储失败");
            }
            return imageResponse.getOssUrl();
        }
        if (!IMAGE_IN_PROGRESS_STATUSES.contains(imageResponse.getStatus()))
        {
            String errorMsg = imageResponse.getErrorMessage();
            log.error("编辑弹窗生图失败: mediaTaskId={}, status={}, error={}",
                    imageResponse.getTaskId(), imageResponse.getStatus(), errorMsg);
            throw new RuntimeException(StrUtil.isNotBlank(errorMsg) ? errorMsg : "图片生成失败");
        }

        Long mediaTaskId = imageResponse.getTaskId();
        if (Objects.isNull(mediaTaskId))
        {
            log.error("编辑弹窗生图异步任务缺少 taskId");
            throw new RuntimeException("图片生成失败");
        }

        long deadline = System.currentTimeMillis() + IMAGE_POLL_TIMEOUT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                Thread.sleep(IMAGE_POLL_INTERVAL_SECONDS * 1000L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new RuntimeException("图片生成被中断");
            }

            if (assetExtractService.isTaskCancelled(taskId))
            {
                log.info("编辑弹窗生图轮询期间检测到取消, 停止等待: taskId={}, mediaTaskId={}", taskId, mediaTaskId);
                throw new TaskCancelledException();
            }

            MediaTaskResponse polled = mediaGenerationService.queryTaskRefresh(mediaTaskId);
            if (Objects.isNull(polled))
            {
                log.error("编辑弹窗生图轮询返回空: mediaTaskId={}", mediaTaskId);
                throw new RuntimeException("图片生成失败");
            }
            if (Objects.equals(TASK_STATUS_SUCCEEDED, polled.getStatus()))
            {
                if (StrUtil.isBlank(polled.getOssUrl()))
                {
                    log.warn("编辑弹窗生图成功但 ossUrl 暂空，等待下一轮持久化: mediaTaskId={}", mediaTaskId);
                    continue;
                }
                return polled.getOssUrl();
            }
            if (TASK_STATUS_FAILED.equals(polled.getStatus()))
            {
                String errorMsg = polled.getErrorMessage();
                log.error("编辑弹窗生图异步失败: mediaTaskId={}, error={}", mediaTaskId, errorMsg);
                throw new RuntimeException(StrUtil.isNotBlank(errorMsg) ? errorMsg : "图片生成失败");
            }
        }
        log.error("编辑弹窗生图异步超时: mediaTaskId={}, timeout={}s", mediaTaskId, IMAGE_POLL_TIMEOUT_SECONDS);
        throw new RuntimeException("图片生成超时");
    }
    /**
     * 落地一条 {@code aid_role_prop_scene_form_image}（source_type = ai_edit_chat）。
     *
     * @param baseline 批次开始前一次性采集到的基线（已有张数 + 是否已有使用中），循环内按 baseCount + index 计算，
     * @param index    第几张图（0 基）：仅影响图片名称序号和 sortOrder
     */
    private Long persistEditChatFormImage(AidRolePropSceneForm form, AidRolePropScene asset,
                                           String imageUrl, String finalPrompt,
                                           List<String> referenceImages,
                                           Long taskId, Long userId,
                                           FormImageSortBaseline baseline, int index)
    {
        int baseCount = Objects.nonNull(baseline) ? baseline.baseCount : 0;
        boolean anyInUse = Objects.nonNull(baseline) && baseline.anyInUse;

        // reference_images 列：仍用 List<String> 兼容格式，只落参考图 URL，追溯信息走 input_snapshot
        String referenceImagesJson = null;
        if (CollectionUtil.isNotEmpty(referenceImages))
        {
            try
            {
                referenceImagesJson = OBJECT_MAPPER.writeValueAsString(referenceImages);
            }
            catch (Exception e)
            {
                log.warn("编辑弹窗生图参考图序列化失败: formId={}, taskId={}, err={}",
                        form.getId(), taskId, e.getMessage());
            }
        }

        AidRolePropSceneFormImage img = new AidRolePropSceneFormImage();
        img.setFormId(form.getId());
        img.setAssetId(form.getAssetId());
        img.setProjectId(form.getProjectId());
        img.setEpisodeId(form.getEpisodeId());
        img.setUserId(userId);
        // name 命名：取该 form 当前 form_image.name（基线快照已含），追加 "_编辑_N"。
        // N = baseline.maxEditSeq + 1 + index，保证全局递增（与历史 _编辑_X 不重复）。
        // 基础名优先用 form.name + "_" + form.change_reason；缺失时降级。
        // name 命名：编辑作图永远需要 "_编辑_N" 后缀做版本区分。
        // 基底直接用 form.name（已包含"资产名_变更原因"完整语义），不再额外拼 changeReason，
        // 避免出现 "林深_初始形象_初始形象_编辑_1" 这种复读名。
        String baseName = StrUtil.isNotBlank(form.getName()) ? form.getName() : "形态";
        int editSeq = (Objects.nonNull(baseline) ? baseline.maxEditSeq : 0) + 1 + index;
        img.setName(baseName + "_编辑_" + editSeq);
        img.setImageUrl(imageUrl);
        img.setSourceType(FORM_IMAGE_SOURCE_TYPE_EDIT_CHAT);
        img.setDescriptionIndex(0);
        img.setPromptSnapshot(finalPrompt);
        img.setReferenceImages(referenceImagesJson);
        img.setBatchNo(Objects.nonNull(taskId) ? String.valueOf(taskId) : null);
        img.setSortOrder(baseCount + index);
        // 全局"默认未引用"规则：编辑/对话作图新生图不再自动接管 is_use=1，
        // 保持与 AI 生图 / 多机位 / 设定卡 / 上传 / 拆分等所有"生成路径"一致。
        img.setIsUse(0);
        img.setImageStatus("completed");
        img.setDelFlag(DEL_FLAG_NORMAL);
        img.setCreateTime(DateUtils.getNowDate());
        img.setCreateBy(String.valueOf(userId));
        rpsFormImageService.save(img);
        return img.getId();
    }
    /** CAS 更新任务状态：仅在 {@code expectedStatus} 命中时更新为 {@code newStatus} */
    private boolean updateTaskStatus(Long taskId, String newStatus, String errorMessage, String expectedStatus)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.eq(AidExtractTask::getStatus, expectedStatus);
        update.set(AidExtractTask::getStatus, newStatus);
        if (StrUtil.isNotBlank(errorMessage))
        {
            update.set(AidExtractTask::getErrorMessage, errorMessage);
        }
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, update);
        return rows > 0;
    }

    private boolean updateTaskSuccess(Long taskId, int totalCount, String resultJson)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.eq(AidExtractTask::getStatus, TASK_STATUS_PROCESSING);
        update.set(AidExtractTask::getStatus, TASK_STATUS_SUCCEEDED);
        update.set(AidExtractTask::getTotalCount, totalCount);
        if (StrUtil.isNotBlank(resultJson))
        {
            update.set(AidExtractTask::getResultData, resultJson);
        }
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, update);
        if (rows == 0)
        {
            log.warn("编辑弹窗生图成功CAS未命中, 终态已被其他分支接管, 不再发送complete: taskId={}", taskId);
            return false;
        }
        return true;
    }

    private boolean updateTaskFailed(Long taskId, String errorMessage)
    {
        String safeMsg = StrUtil.isNotBlank(errorMessage) ? errorMessage : "生成失败";
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING);
        update.set(AidExtractTask::getStatus, TASK_STATUS_FAILED);
        update.set(AidExtractTask::getErrorMessage, StrUtil.sub(safeMsg, 0, 255));
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, update);
        if (rows == 0)
        {
            log.warn("编辑弹窗生图失败CAS未命中, 终态已被其他分支接管, 不再发送error: taskId={}", taskId);
            return false;
        }
        return true;
    }

    /**
     * CAS 标记任务失败（结构化版本兼容入口）：保留此重载避免调用点大改,
     * 但内部只写 errorMessage 到 DB。
     * 注意：DB 存的是原始上游错误文案（rawMessage），而非友好文案。
     * 这样运行时 ErrorNormalizer.normalizeByMessage(task.getErrorMessage()) 才能正确归一化。
     */
    private boolean updateTaskFailed(Long taskId, com.aid.common.error.TaskErrorResult errorResult)
    {
        // 优先存 rawMessage（上游原文），fallback 到 userMessage（友好文案）
        String dbMessage = errorResult.getRawMessage() != null ? errorResult.getRawMessage() : errorResult.getUserMessage();
        return updateTaskFailed(taskId, dbMessage);
    }

    private boolean updateTaskCancelled(Long taskId)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING);
        update.set(AidExtractTask::getStatus, TASK_STATUS_CANCELLED);
        update.set(AidExtractTask::getErrorMessage, "用户取消");
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, update);
        if (rows == 0)
        {
            log.warn("编辑弹窗生图取消CAS未命中, 终态已被其他分支接管, 不再发送cancelled: taskId={}", taskId);
            return false;
        }
        return true;
    }

    private boolean updateTaskCancelledWithResult(Long taskId, String resultJson)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING);
        update.set(AidExtractTask::getStatus, TASK_STATUS_CANCELLED);
        update.set(AidExtractTask::getErrorMessage, "用户取消");
        if (StrUtil.isNotBlank(resultJson))
        {
            update.set(AidExtractTask::getResultData, resultJson);
        }
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, update);
        if (rows == 0)
        {
            log.warn("编辑弹窗生图取消(保留结果)CAS未命中, 终态已被其他分支接管, 不再发送cancelled: taskId={}", taskId);
            return false;
        }
        return true;
    }
}
