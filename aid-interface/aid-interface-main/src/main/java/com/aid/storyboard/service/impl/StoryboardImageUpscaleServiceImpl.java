package com.aid.storyboard.service.impl;

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

import com.aid.agent.AgentDefaultParamsApplier;
import com.aid.agent.AgentModelDefault;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiModelFuncConfig;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.service.IAidAiModelFuncConfigService;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidGenRecordService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.utils.DateUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.service.IMediaGenerationService;
import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.sse.AssetExtractSseManager;
import com.aid.service.IAiModelConfigService;
import com.aid.storyboard.dto.StoryboardImageUpscaleRequest;
import com.aid.storyboard.service.IStoryboardImageUpscaleService;

import org.springframework.data.redis.core.script.DefaultRedisScript;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜图高清服务实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class StoryboardImageUpscaleServiceImpl implements IStoryboardImageUpscaleService
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String STATUS_NORMAL = "0";
    private static final String MODEL_TYPE_IMAGE = "image";

    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_CANCELLED = "CANCELLED";

    /** 任务类型（与 AssetExtractServiceImpl 常量保持一致） */
    private static final String TASK_TYPE_STORYBOARD_IMAGE_UPSCALE = "storyboard_image_upscale";

    /** 功能编码：与 /api/user/model/listByFunc?funcCode=image_upscale 一致 */
    private static final String FUNC_CODE_IMAGE_UPSCALE = "image_upscale";

    private static final String GEN_TYPE_IMAGE = "image";
    private static final String GEN_TYPE_GRID = "grid";

    /** 默认 prompt（与形态图高清保持一致：内容不变只放大） */
    private static final String UPSCALE_FALLBACK_PROMPT = "高清增强，保持原图内容不变";

    // 含回调优先模式的 WAIT_CALLBACK，防止把中间态误判为失败。
    private static final Set<String> IMAGE_IN_PROGRESS_STATUSES = Set.of(
            "INIT", "PENDING", "QUEUED", "PROCESSING", "WAIT_POLL", "WAIT_CALLBACK");

    private static final String FORM_LOCK_PREFIX = "asset:form:lock:";
    private static final long FORM_LOCK_TTL_SECONDS = 10L * 60L;

    private static final long IMAGE_POLL_TIMEOUT_SECONDS = 180L;
    private static final long IMAGE_POLL_INTERVAL_SECONDS = 5L;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private IAidExtractTaskService extractTaskService;

    @Autowired
    private IAidGenRecordService aidGenRecordService;

    @Autowired
    private IAidStoryboardService aidStoryboardService;

    @Autowired
    private IAidAiModelService aidAiModelService;

    @Autowired
    private IAidAiModelFuncConfigService aidAiModelFuncConfigService;

    @Autowired
    private IAiModelConfigService aiModelConfigService;

    @Autowired
    private IMediaGenerationService mediaGenerationService;

    @Autowired
    private AgentDefaultParamsApplier agentDefaultParamsApplier;

    @Autowired
    private IAssetExtractService assetExtractService;

    /** 任务排队 / 多维并发调度服务 */
    @Autowired
    private com.aid.rps.queue.TaskQueueService taskQueueService;

    @Autowired
    private AssetExtractSseManager sseManager;

    /** 相对路径拼回完整 URL（gen_record.file_url 落库为相对路径） */
    @Autowired
    private com.aid.common.aid.oss.util.MediaUrlResolver mediaUrlResolver;

    @Override
    public AssetExtractTaskVO upscaleStoryboardImage(StoryboardImageUpscaleRequest request, Long userId)
    {
        if (Objects.isNull(userId) || userId <= 0)
        {
            log.error("分镜图高清登录态缺失: userId={}", userId);
            throw new RuntimeException("未登录");
        }
        if (Objects.isNull(request) || Objects.isNull(request.getGenRecordId()))
        {
            log.info("分镜图高清入参缺失");
            throw new RuntimeException("参数异常");
        }
        if (StrUtil.isBlank(request.getModelCode()))
        {
            log.info("分镜图高清modelCode为空: recordId={}", request.getGenRecordId());
            throw new RuntimeException("模型不能空");
        }

        AidGenRecord record = loadAndCheckRecord(request.getGenRecordId(), userId);

        AidStoryboard storyboard = loadAndCheckStoryboard(record.getStoryboardId(), userId);

        AidAiModel model = validateModelInPool(request.getModelCode());

        // 能力守卫：清晰度档位必须落在模型 capability.sizeOptions 内（如 ultra 仅 4K/8K）
        AiModelConfigVo guardConfig = aiModelConfigService.selectByModelCode(model.getModelCode());
        if (Objects.nonNull(guardConfig))
        {
            com.aid.model.vo.CapabilityVO capability =
                    com.aid.model.support.ModelCapabilityGuard.parseOrNull(guardConfig.getCapabilityJson());
            com.aid.model.support.ModelCapabilityGuard.assertSizeSupported(
                    capability, request.getResolution(), model.getModelCode());
        }

        String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_IMAGE_UPSCALE + ":" + record.getId();
        // 锁值用 token：释放时 CAS 校验，任务超过锁 TTL 后旧任务收尾不会误删新任务的锁
        String lockToken = IdUtil.fastSimpleUUID();
        Boolean locked = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockToken, FORM_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (locked == null || !locked)
        {
            throw new RuntimeException("任务处理中");
        }

        Long taskId = null;
        try
        {
            taskId = createExtractTask(record, storyboard, userId, model.getModelCode(),
                    request.getResolution());

            // gen_record.file_url 落库为相对路径，必须拼回完整 URL 再下发（即梦等上游按 URL 下载图片）
            String referenceUrl = mediaUrlResolver.toFullUrl(record.getFileUrl());
            String resolution = request.getResolution();
            String modelCode = model.getModelCode();
            Long modelId = model.getId();
            Long finalTaskId = taskId;
            // 入队 + 多维并发调度（LOCAL 派发）
            boolean enqueued = taskQueueService.submitLocalTask(finalTaskId, storyboard.getProjectId(),
                    storyboard.getEpisodeId(), userId, modelCode, TASK_TYPE_STORYBOARD_IMAGE_UPSCALE,
                    () -> runAsync(finalTaskId, userId, storyboard, record,
                            modelCode, modelId, referenceUrl, resolution, lockKey, lockToken));
            if (!enqueued)
            {
                log.error("分镜图高清入队失败: taskId={}", finalTaskId);
                updateTaskFailed(finalTaskId, "提交失败");
                releaseLockSafely(lockKey, lockToken);
                throw new RuntimeException("提交失败");
            }

            return AssetExtractTaskVO.builder()
                    .taskId(taskId)
                    .status(TASK_STATUS_PENDING)
                    .build();
        }
        catch (RuntimeException e)
        {
            // 任务入库失败 / 发起异步前抛错：释放锁
            releaseLockSafely(lockKey, lockToken);
            throw e;
        }
    }

    /**
     * CAS 释放防重锁：token 一致才删除，锁已过期被新任务持有时不误删。
     *
     * @param key   锁键
     * @param token 持锁标识
     */
    private void releaseLockSafely(String key, String token)
    {
        try
        {
            String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
            redisCache.redisTemplate.execute(
                    new DefaultRedisScript<>(script, Long.class),
                    java.util.Collections.singletonList(key), token);
        }
        catch (Exception e)
        {
            log.warn("分镜图高清锁释放失败: key={}, msg={}", key, e.getMessage());
        }
    }
    /** 加载 + 校验 aid_gen_record。 */
    private AidGenRecord loadAndCheckRecord(Long recordId, Long userId)
    {
        LambdaQueryWrapper<AidGenRecord> q = Wrappers.lambdaQuery();
        q.select(AidGenRecord::getId, AidGenRecord::getUserId, AidGenRecord::getStoryboardId,
                AidGenRecord::getGenType, AidGenRecord::getFileUrl, AidGenRecord::getStatus,
                AidGenRecord::getDelFlag);
        q.eq(AidGenRecord::getId, recordId);
        q.eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL);
        q.last("limit 1");
        AidGenRecord record = aidGenRecordService.getOne(q, false);
        if (Objects.isNull(record))
        {
            log.info("分镜图高清记录不存在: recordId={}", recordId);
            throw new RuntimeException("记录不存在");
        }
        if (!Objects.equals(userId, record.getUserId()))
        {
            log.info("分镜图高清记录归属校验失败: recordId={}, owner={}, request={}",
                    recordId, record.getUserId(), userId);
            throw new RuntimeException("无权访问");
        }
        // 仅图片类支持高清
        if (!GEN_TYPE_IMAGE.equals(record.getGenType()) && !GEN_TYPE_GRID.equals(record.getGenType())) {
            log.info("分镜图高清记录类型不支持: recordId={}, genType={}", recordId, record.getGenType());
            throw new RuntimeException("类型不合法");
        }
        // 必须有 fileUrl 作为参考图
        if (StrUtil.isBlank(record.getFileUrl()))
        {
            log.info("分镜图高清记录无fileUrl: recordId={}", recordId);
            throw new RuntimeException("图片无内容");
        }
        // 必须是成功记录（status=1）才能做高清
        if (!Objects.equals(1, record.getStatus()))
        {
            log.info("分镜图高清记录状态非成功: recordId={}, status={}", recordId, record.getStatus());
            throw new RuntimeException("图片未生成");
        }
        return record;
    }

    /** 通过分镜 ID 校验归属，并加载所需字段。 */
    private AidStoryboard loadAndCheckStoryboard(Long storyboardId, Long userId)
    {
        if (Objects.isNull(storyboardId))
        {
            log.info("分镜图高清记录无关联分镜: userId={}", userId);
            throw new RuntimeException("分镜不存在");
        }
        AidStoryboard storyboard = aidStoryboardService.getOne(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getProjectId,
                                AidStoryboard::getEpisodeId, AidStoryboard::getUserId,
                                AidStoryboard::getDelFlag)
                        .eq(AidStoryboard::getId, storyboardId)
                        .last("limit 1"),
                false);
        if (Objects.isNull(storyboard) || !DEL_FLAG_NORMAL.equals(storyboard.getDelFlag()))
        {
            log.info("分镜图高清分镜不存在: storyboardId={}", storyboardId);
            throw new RuntimeException("分镜不存在");
        }
        if (!Objects.equals(userId, storyboard.getUserId()))
        {
            log.info("分镜图高清分镜归属校验失败: storyboardId={}, owner={}, request={}",
                    storyboardId, storyboard.getUserId(), userId);
            throw new RuntimeException("无权访问");
        }
        return storyboard;
    }

    /**
     * 模型可用范围校验：modelCode 必须存在 + 启用 + model_type=image，
     * 且 ID 在 {@code aid_ai_model_func_config.func_code = image_upscale} 的 modelIds 列表里。
     * 额外校验：模型 capabilityJson.imageRefine 必须为 3（高清/超分辨率），
     * 与现有 {@code RpsFormImageBusinessServiceImpl.validateUpscaleCapability} 同口径，
     * 双重防御避免运营误把非高清模型加入功能池。
     */
    private AidAiModel validateModelInPool(String modelCode)
    {
        LambdaQueryWrapper<AidAiModelFuncConfig> cfgQuery = Wrappers.lambdaQuery();
        cfgQuery.select(AidAiModelFuncConfig::getId, AidAiModelFuncConfig::getFuncCode,
                AidAiModelFuncConfig::getModelIds, AidAiModelFuncConfig::getStatus,
                AidAiModelFuncConfig::getDelFlag);
        cfgQuery.eq(AidAiModelFuncConfig::getFuncCode, FUNC_CODE_IMAGE_UPSCALE);
        cfgQuery.eq(AidAiModelFuncConfig::getStatus, STATUS_NORMAL);
        cfgQuery.eq(AidAiModelFuncConfig::getDelFlag, DEL_FLAG_NORMAL);
        cfgQuery.last("limit 1");
        AidAiModelFuncConfig cfg = aidAiModelFuncConfigService.getOne(cfgQuery, false);
        if (Objects.isNull(cfg))
        {
            log.error("分镜图高清功能池未配置: funcCode={}", FUNC_CODE_IMAGE_UPSCALE);
            throw new RuntimeException("功能未开放");
        }
        List<Long> allowedIds = parseModelIdsJson(cfg.getModelIds());
        if (CollectionUtil.isEmpty(allowedIds))
        {
            log.error("分镜图高清功能池为空: funcCode={}", FUNC_CODE_IMAGE_UPSCALE);
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
            log.info("分镜图高清模型不存在或已停用: modelCode={}", modelCode);
            throw new RuntimeException("模型无效");
        }
        if (!Objects.equals(MODEL_TYPE_IMAGE, model.getModelType()))
        {
            log.info("分镜图高清模型类型不匹配: modelCode={}, type={}", modelCode, model.getModelType());
            throw new RuntimeException("模型不符");
        }
        if (!allowedIds.contains(model.getId()))
        {
            log.info("分镜图高清模型不在功能池: modelCode={}, modelId={}, pool={}",
                    modelCode, model.getId(), allowedIds);
            throw new RuntimeException("模型不符");
        }
        // 高清"能力"由 func_code=image_upscale 功能池统一治理（运营把能做高清的模型加入池即可），
        // 不再额外卡 imageRefine=3，与形态图高清 / 其它图片生成口径保持一致。
        return model;
    }

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
            log.error("解析分镜图高清功能池modelIds失败: raw={}, err={}", modelIdsJson, e.getMessage());
        }
        return ordered;
    }
    private Long createExtractTask(AidGenRecord record, AidStoryboard storyboard, Long userId,
                                    String modelCode, String resolution)
    {
        AidExtractTask task = new AidExtractTask();
        task.setProjectId(storyboard.getProjectId());
        task.setEpisodeId(storyboard.getEpisodeId());
        task.setUserId(userId);
        task.setTaskType(TASK_TYPE_STORYBOARD_IMAGE_UPSCALE);
        task.setModelCode(modelCode);
        try
        {
            Map<String, Object> inputMap = new LinkedHashMap<>();
            inputMap.put("genRecordId", record.getId());
            inputMap.put("storyboardId", storyboard.getId());
            inputMap.put("modelCode", modelCode);
            inputMap.put("resolution", resolution);
            inputMap.put("referenceUrl", record.getFileUrl());
            task.setInputSnapshot(OBJECT_MAPPER.writeValueAsString(inputMap));
        }
        catch (Exception e)
        {
            task.setInputSnapshot("{\"genRecordId\":" + record.getId() + "}");
        }
        task.setStatus(TASK_STATUS_PENDING);
        task.setTotalCount(0);
        task.setDelFlag(DEL_FLAG_NORMAL);
        task.setCreateTime(DateUtils.getNowDate());
        task.setCreateBy(String.valueOf(userId));
        extractTaskService.save(task);
        return task.getId();
    }

    /** 执行分镜图高清生成。 */
    private void runAsync(Long taskId, Long userId, AidStoryboard storyboard, AidGenRecord sourceRecord,
                          String modelCode, Long modelId, String referenceUrl, String resolution,
                          String lockKey, String lockToken)
    {
        try
        {
            if (assetExtractService.isTaskCancelled(taskId))
            {
                log.info("分镜图高清启动前检测到取消: taskId={}, recordId={}", taskId, sourceRecord.getId());
                if (updateTaskCancelled(taskId))
                {
                    sseManager.sendCancelled(taskId, "用户取消");
                }
                return;
            }
            if (!updateTaskStatus(taskId, TASK_STATUS_PROCESSING, null, TASK_STATUS_PENDING))
            {
                log.warn("分镜图高清任务已被其他线程处理: taskId={}", taskId);
                return;
            }
            // 登记执行租约（重启自愈据租约判活）
            assetExtractService.markTaskProcessing(taskId);
            if (assetExtractService.isTaskCancelled(taskId))
            {
                log.info("分镜图高清进入PROCESSING后检测到取消: taskId={}, recordId={}", taskId, sourceRecord.getId());
                if (updateTaskCancelled(taskId))
                {
                    sseManager.sendCancelled(taskId, "用户取消");
                }
                return;
            }

            String newImageUrl = generateUpscaledImage(taskId, userId, storyboard, modelCode,
                    referenceUrl, resolution);
            if (StrUtil.isBlank(newImageUrl))
            {
                log.error("分镜图高清结果URL为空: taskId={}, recordId={}", taskId, sourceRecord.getId());
                throw new RuntimeException("生成失败");
            }

            // 落库新行 aid_gen_record（gen_type=image），与原记录共存
            Long newRecordId = persistUpscaledRecord(storyboard, sourceRecord, userId, modelId,
                    newImageUrl, resolution);

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("storyboardId", storyboard.getId());
            resultMap.put("sourceRecordId", sourceRecord.getId());
            resultMap.put("recordId", newRecordId);
            resultMap.put("imageUrl", newImageUrl);
            resultMap.put("modelCode", modelCode);
            resultMap.put("resolution", resolution);
            String resultJson = OBJECT_MAPPER.writeValueAsString(resultMap);

            // 取消检查点（保留结果型）：与 image_upscale / 多机位对齐
            if (assetExtractService.isTaskCancelled(taskId))
            {
                if (updateTaskCancelledWithResult(taskId, resultJson))
                {
                    sseManager.sendCancelled(taskId, "用户取消");
                    log.info("分镜图高清完成但检测到取消(resultData已保留): taskId={}, recordId={}",
                            taskId, newRecordId);
                }
                return;
            }

            if (updateTaskSuccess(taskId, 1, resultJson))
            {
                sseManager.sendComplete(taskId, resultMap);
                log.info("分镜图高清完成: taskId={}, sourceRecordId={}, newRecordId={}, imageUrl={}",
                        taskId, sourceRecord.getId(), newRecordId, newImageUrl);
            }
        }
        catch (TaskCancelledException e)
        {
            log.info("分镜图高清任务执行中被取消: taskId={}, recordId={}", taskId, sourceRecord.getId());
            if (updateTaskCancelled(taskId))
            {
                sseManager.sendCancelled(taskId, "用户取消");
            }
        }
        catch (Exception e)
        {
            log.error("分镜图高清任务失败: taskId={}, recordId={}", taskId, sourceRecord.getId(), e);
            com.aid.common.error.TaskErrorResult errorResult = com.aid.common.error.ErrorNormalizer.normalize(e);
            if (updateTaskFailed(taskId, errorResult))
            {
                sseManager.sendError(taskId, errorResult);
            }
        }
        finally
        {
            releaseLockSafely(lockKey, lockToken);
            try
            {
                assetExtractService.clearCancelFlag(taskId);
            }
            catch (Exception ignore)
            {
            }
            // 释放多维并发名额 + 执行租约（幂等）
            try
            {
                assetExtractService.releaseTaskSlots(taskId);
            }
            catch (Exception ignore)
            {
            }
        }
    }

    /** 调用统一图片生成主链路 —— 单次一张高清出图 */
    private String generateUpscaledImage(Long taskId, Long userId, AidStoryboard storyboard,
                                          String modelCode, String referenceUrl, String resolution)
    {
        MediaImageGenerateRequest imageRequest = new MediaImageGenerateRequest();
        imageRequest.setModelName(modelCode);
        imageRequest.setUserId(userId);
        imageRequest.setPrompt(UPSCALE_FALLBACK_PROMPT);
        imageRequest.setProjectId(storyboard.getProjectId());
        imageRequest.setEpisodeId(storyboard.getEpisodeId());
        imageRequest.setReferenceImageUrl(referenceUrl);
        imageRequest.setExpectedImageCount(1);

        Map<String, Object> options = new HashMap<>();
        if (StrUtil.isNotBlank(resolution))
        {
            options.put("resolution", resolution);
        }
        options.put("force_single", true);
        imageRequest.setOptions(options);

        imageRequest.setBizTaskId(taskId);
        imageRequest.setBizTaskType(TASK_TYPE_STORYBOARD_IMAGE_UPSCALE);

        AiModelConfigVo defaultModelConfig = aiModelConfigService.selectByModelCode(modelCode);
        if (Objects.isNull(defaultModelConfig))
        {
            log.error("分镜图高清模型配置缺失: modelCode={}", modelCode);
            throw new RuntimeException("模型无效");
        }
        AgentModelDefault agentModel = new AgentModelDefault(modelCode);
        agentDefaultParamsApplier.applyToImage(agentModel, imageRequest, defaultModelConfig);

        MediaTaskResponse imageResponse = mediaGenerationService.generateImage(imageRequest);
        return resolveSingleImageUrl(taskId, imageResponse);
    }

    private static final class TaskCancelledException extends RuntimeException
    {
        TaskCancelledException()
        {
            super("用户取消");
        }
    }

    /** 解析单次图片生成响应，与 form_edit_chat / 多机位轮询行为一致。 */
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
                log.error("分镜图高清同步成功但 ossUrl 为空: mediaTaskId={}", imageResponse.getTaskId());
                throw new RuntimeException("存储失败");
            }
            return imageResponse.getOssUrl();
        }
        if (!IMAGE_IN_PROGRESS_STATUSES.contains(imageResponse.getStatus()))
        {
            String errorMsg = imageResponse.getErrorMessage();
            log.error("分镜图高清失败: mediaTaskId={}, status={}, error={}",
                    imageResponse.getTaskId(), imageResponse.getStatus(), errorMsg);
            throw new RuntimeException(StrUtil.isNotBlank(errorMsg) ? errorMsg : "图片生成失败");
        }

        Long mediaTaskId = imageResponse.getTaskId();
        if (Objects.isNull(mediaTaskId))
        {
            log.error("分镜图高清异步任务缺少 taskId");
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
                log.info("分镜图高清轮询期间检测到取消, 停止等待: taskId={}, mediaTaskId={}", taskId, mediaTaskId);
                throw new TaskCancelledException();
            }
            MediaTaskResponse polled = mediaGenerationService.queryTaskRefresh(mediaTaskId);
            if (Objects.isNull(polled))
            {
                log.error("分镜图高清轮询返回空: mediaTaskId={}", mediaTaskId);
                throw new RuntimeException("图片生成失败");
            }
            if (Objects.equals(TASK_STATUS_SUCCEEDED, polled.getStatus()))
            {
                if (StrUtil.isBlank(polled.getOssUrl()))
                {
                    log.warn("分镜图高清成功但 ossUrl 暂空: mediaTaskId={}", mediaTaskId);
                    continue;
                }
                return polled.getOssUrl();
            }
            if (TASK_STATUS_FAILED.equals(polled.getStatus()))
            {
                String errorMsg = polled.getErrorMessage();
                log.error("分镜图高清异步失败: mediaTaskId={}, error={}", mediaTaskId, errorMsg);
                throw new RuntimeException(StrUtil.isNotBlank(errorMsg) ? errorMsg : "图片生成失败");
            }
        }
        log.error("分镜图高清异步超时: mediaTaskId={}, timeout={}s", mediaTaskId, IMAGE_POLL_TIMEOUT_SECONDS);
        throw new RuntimeException("图片生成超时");
    }
    /** 落新行 aid_gen_record（与原记录共存，用户可自行选择哪一版作为最终图） */
    private Long persistUpscaledRecord(AidStoryboard storyboard, AidGenRecord source, Long userId,
                                        Long modelId, String newImageUrl, String resolution)
    {
        AidGenRecord record = new AidGenRecord();
        record.setUserId(userId);
        // 冗余存项目 / 剧集，便于按 (project, episode) 维度反查 aid_gen_record
        record.setProjectId(storyboard.getProjectId());
        record.setEpisodeId(storyboard.getEpisodeId());
        record.setStoryboardId(storyboard.getId());
        record.setGenType(GEN_TYPE_IMAGE);
        record.setModelId(modelId);
        record.setPromptText(StrUtil.isNotBlank(resolution)
                ? UPSCALE_FALLBACK_PROMPT + "（清晰度：" + resolution + "）"
                : UPSCALE_FALLBACK_PROMPT);
        record.setUserInputText("分镜图高清-基于记录" + source.getId());
        record.setBaseImageId(source.getId()); // 记录原始图片 id 便于追溯
        record.setFileUrl(newImageUrl);
        // gen_params：精简 JSON 快照，符合 aid_gen_record.gen_params NOT NULL 列约束
        String genParamsJson;
        try
        {
            Map<String, Object> genParamsMap = new LinkedHashMap<>();
            genParamsMap.put("storyboardId", storyboard.getId());
            genParamsMap.put("sourceRecordId", source.getId());
            genParamsMap.put("modelId", modelId);
            genParamsMap.put("resolution", resolution);
            genParamsMap.put("scene", "storyboard_image_upscale");
            genParamsJson = OBJECT_MAPPER.writeValueAsString(genParamsMap);
        }
        catch (Exception e)
        {
            log.warn("分镜图高清 genParams 序列化失败, 降级最小快照: storyboardId={}", storyboard.getId(), e);
            genParamsJson = "{\"storyboardId\":" + storyboard.getId() + ",\"sourceRecordId\":" + source.getId() + "}";
        }
        record.setGenParams(genParamsJson);
        record.setStatus(1);
        record.setIsSelected(0);
        record.setDelFlag(DEL_FLAG_NORMAL);
        record.setCreateTime(DateUtils.getNowDate());
        record.setCreateBy(String.valueOf(userId));
        aidGenRecordService.save(record);
        return record.getId();
    }
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
            log.warn("分镜图高清成功CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
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
            log.warn("分镜图高清失败CAS未命中: taskId={}", taskId);
            return false;
        }
        return true;
    }

    private boolean updateTaskFailed(Long taskId, com.aid.common.error.TaskErrorResult errorResult)
    {
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
            log.warn("分镜图高清取消CAS未命中: taskId={}", taskId);
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
            log.warn("分镜图高清取消(保留结果)CAS未命中: taskId={}", taskId);
            return false;
        }
        return true;
    }
}
