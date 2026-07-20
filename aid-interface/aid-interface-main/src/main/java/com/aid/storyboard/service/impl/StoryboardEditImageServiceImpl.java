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
import com.aid.common.utils.image.ImageUrlValidator;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.service.IMediaGenerationService;
import com.aid.model.vo.CapabilityVO;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.sse.AssetExtractSseManager;
import com.aid.service.IAiModelConfigService;
import com.aid.storyboard.dto.StoryboardEditImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardEditImageGenerateVO;
import com.aid.storyboard.service.IStoryboardEditImageService;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜编辑图生成服务实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class StoryboardEditImageServiceImpl implements IStoryboardEditImageService
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String STATUS_NORMAL = "0";
    private static final String MODEL_TYPE_IMAGE = "image";

    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_CANCELLED = "CANCELLED";

    /** 任务类型：分镜编辑图（与 AssetExtractServiceImpl 常量保持一致） */
    private static final String TASK_TYPE_STORYBOARD_EDIT_IMAGE = "storyboard_edit_image";

    /** 功能编码：与 /api/user/model/listByFunc?funcCode=image_edit 一致 */
    private static final String FUNC_CODE_IMAGE_EDIT = "image_edit";

    /** gen_type 常量：单图 */
    private static final String GEN_TYPE_IMAGE = "image";

    /** 张数上下限 */
    private static final int IMAGE_COUNT_MIN = 1;
    private static final int IMAGE_COUNT_MAX = 4;

    /** 图片中间态白名单（轮询用）：含回调优先模式的 WAIT_CALLBACK，防止把中间态误判为失败 */
    private static final Set<String> IMAGE_IN_PROGRESS_STATUSES = Set.of(
            "INIT", "PENDING", "QUEUED", "PROCESSING", "WAIT_POLL", "WAIT_CALLBACK");

    /** Redis 防重锁 Key 前缀（与 AssetExtractServiceImpl.FORM_LOCK_PREFIX 同命名空间） */
    private static final String FORM_LOCK_PREFIX = "asset:form:lock:";

    /** 防重锁 TTL（秒）：30 分钟兜底，覆盖 4 张 × 180s 轮询 + 余量 */
    private static final long FORM_LOCK_TTL_SECONDS = 30L * 60L;

    /** 锁"刚抢到但还没落 DB"的宽限期（毫秒）：60s 留足容错 */
    private static final long FORM_LOCK_STALE_GRACE_MS = 60L * 1000L;

    /** Lua 脚本：仅当 GET key == ARGV[1] 才 DEL，防止自动过期后被他人复用又被本请求误删 */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    /** 图片轮询参数 */
    private static final long IMAGE_POLL_TIMEOUT_SECONDS = 180L;
    private static final long IMAGE_POLL_INTERVAL_SECONDS = 5L;

    // 忽略 capability_json 等反序列化时的未知字段，避免后续新增能力字段（如 maxReferenceImages）导致解析失败误判"模型不符"
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private IAidExtractTaskService extractTaskService;

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

    /** 媒体URL统一解析器：本站校验 + 相对路径拼完整URL */
    @Autowired
    private MediaUrlResolver mediaUrlResolver;

    @Autowired
    private IAidGenRecordService aidGenRecordService;

    @Autowired
    private AgentDefaultParamsApplier agentDefaultParamsApplier;

    @Autowired
    private IAssetExtractService assetExtractService;

    /** 任务排队 / 多维并发调度服务 */
    @Autowired
    private com.aid.rps.queue.TaskQueueService taskQueueService;

    @Autowired
    private AssetExtractSseManager sseManager;

    @Override
    public StoryboardEditImageGenerateVO generateEditImage(StoryboardEditImageGenerateRequest request, Long userId)
    {
        validateUserId(userId);
        validateBasicRequest(request, userId);

        AidStoryboard storyboard = loadAndCheckStoryboard(request.getStoryboardId(), userId);

        AidAiModel model = validateModelInPool(request.getModelCode());
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(model.getModelCode());
        if (Objects.isNull(modelConfig))
        {
            log.error("分镜编辑图模型配置缺失: modelCode={}", model.getModelCode());
            throw new RuntimeException("模型无效");
        }
        validateModelCapability(modelConfig, request, userId);

        String finalPrompt = buildFinalPrompt(request.getPrompt(), request.getAspectRatio(),
                mediaUrlResolver.toFullUrls(request.referenceImagesAsList()));

        String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_EDIT_IMAGE + ":" + request.getStoryboardId();
        String lockToken = buildLockToken();
        boolean lockHeldByMe = tryAcquireLock(lockKey, lockToken);
        if (!lockHeldByMe)
        {
            if (hasActiveTaskInDb(request.getStoryboardId()))
            {
                log.info("分镜编辑图并发拦截: storyboardId={}, lockKey={}",
                        request.getStoryboardId(), lockKey);
                throw new RuntimeException("任务处理中");
            }
            Object existing = redisCache.getCacheObject(lockKey);
            if (Objects.isNull(existing))
            {
                lockHeldByMe = tryAcquireLock(lockKey, lockToken);
                if (!lockHeldByMe)
                {
                    log.info("[StoryboardEditImage] 锁过期后重抢被同瞬抢占: lockKey={}", lockKey);
                    throw new RuntimeException("任务处理中");
                }
            }
            else
            {
                String existingToken = String.valueOf(existing);
                if (!isLockStaleByAge(existingToken))
                {
                    log.info("分镜编辑图抢锁失败但锁年龄未过宽限期, 视为真并发: storyboardId={}, lockKey={}",
                            request.getStoryboardId(), lockKey);
                    throw new RuntimeException("任务处理中");
                }
                log.warn("[StoryboardEditImage] 检测到 Redis 锁泄漏（年龄超限且 DB 无活跃任务），CAS 清理: lockKey={}, storyboardId={}",
                        lockKey, request.getStoryboardId());
                if (!casDeleteIfMatch(lockKey, existingToken))
                {
                    log.info("[StoryboardEditImage] 僵尸锁 CAS 清理失败（锁已变化）, 视为真并发: lockKey={}", lockKey);
                    throw new RuntimeException("任务处理中");
                }
                lockHeldByMe = tryAcquireLock(lockKey, lockToken);
                if (!lockHeldByMe)
                {
                    log.info("[StoryboardEditImage] 僵尸锁清理后再次被抢占: lockKey={}", lockKey);
                    throw new RuntimeException("任务处理中");
                }
            }
        }

        if (hasActiveTaskInDb(request.getStoryboardId()))
        {
            safeReleaseLock(lockKey, lockToken);
            log.info("分镜编辑图抢锁后 DB 复核命中活跃任务, 拒绝并发: storyboardId={}", request.getStoryboardId());
            throw new RuntimeException("任务处理中");
        }

        try
        {
            return submitTask(request, userId, storyboard, model, finalPrompt, lockKey, lockToken);
        }
        catch (RuntimeException e)
        {
            safeReleaseLock(lockKey, lockToken);
            throw e;
        }
    }
    private String buildLockToken()
    {
        return java.util.UUID.randomUUID().toString() + "|" + System.currentTimeMillis();
    }

    private boolean isLockStaleByAge(String tokenWithTs)
    {
        if (StrUtil.isBlank(tokenWithTs))
        {
            return true;
        }
        int sep = tokenWithTs.lastIndexOf('|');
        if (sep < 0 || sep >= tokenWithTs.length() - 1)
        {
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

    @SuppressWarnings("unchecked")
    private boolean tryAcquireLock(String key, String token)
    {
        Boolean ok = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(key, token, FORM_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(ok);
    }

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
            log.warn("分镜编辑图锁释放失败: key={}, msg={}", key, e.getMessage());
        }
    }

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
            log.warn("分镜编辑图僵尸锁 CAS 清理失败: key={}, msg={}", key, e.getMessage());
            return false;
        }
    }

    /**
     * DB 兜底：检查指定 storyboardId 是否真的有未结束（PENDING / PROCESSING）的 storyboard_edit_image 任务。
     * 因 storyboardId 落在 input_snapshot JSON 内，没有独立列。
     * 用 LIKE 时考虑 JSON 边界字符（紧跟 {@code ,} 或 {@code }} 之一），
     * 否则 storyboardId=12 会误命中 storyboardId=123 / 1234 等更长 ID。
     */
    private boolean hasActiveTaskInDb(Long storyboardId)
    {
        if (Objects.isNull(storyboardId))
        {
            return false;
        }
        String boundaryComma = "\"storyboardId\":" + storyboardId + ",";
        String boundaryEnd = "\"storyboardId\":" + storyboardId + "}";
        LambdaQueryWrapper<AidExtractTask> w = Wrappers.lambdaQuery();
        w.eq(AidExtractTask::getTaskType, TASK_TYPE_STORYBOARD_EDIT_IMAGE)
                .in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING)
                .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)
                .and(q -> q.like(AidExtractTask::getInputSnapshot, boundaryComma)
                        .or()
                        .like(AidExtractTask::getInputSnapshot, boundaryEnd));
        Long cnt = extractTaskService.getBaseMapper().selectCount(w);
        return Objects.nonNull(cnt) && cnt > 0;
    }
    private void validateUserId(Long userId)
    {
        if (Objects.isNull(userId) || userId <= 0)
        {
            log.error("分镜编辑图登录态缺失: userId={}", userId);
            throw new RuntimeException("未登录");
        }
    }

    private void validateBasicRequest(StoryboardEditImageGenerateRequest request, Long userId)
    {
        if (Objects.isNull(request))
        {
            log.info("分镜编辑图入参为空: userId={}", userId);
            throw new RuntimeException("参数异常");
        }
        if (Objects.isNull(request.getStoryboardId()))
        {
            log.info("分镜编辑图storyboardId为空: userId={}", userId);
            throw new RuntimeException("分镜不存在");
        }
        List<String> refs = request.referenceImagesAsList();
        if (CollectionUtil.isEmpty(refs) || refs.size() != 1)
        {
            log.info("分镜编辑图参考图数量不合法: storyboardId={}, count={}",
                    request.getStoryboardId(), refs == null ? 0 : refs.size());
            throw new RuntimeException("参考图缺失");
        }
        String url = refs.get(0).trim();
        // 仅允许本站资源（相对路径或本站域名完整URL），拒绝站外外链
        if (StrUtil.isBlank(url) || !mediaUrlResolver.isSiteImageUrl(url))
        {
            log.info("分镜编辑图参考图非本站资源: storyboardId={}, url={}", request.getStoryboardId(), url);
            throw new RuntimeException("图片无效");
        }
        // 相对路径拼完整URL后再做远程可达性 + Content-Type 校验
        String fullUrl = mediaUrlResolver.toFullUrl(url);
        if (!ImageUrlValidator.isValidRemoteImageUrl(fullUrl))
        {
            log.info("分镜编辑图参考图校验失败: storyboardId={}, url={}", request.getStoryboardId(), fullUrl);
            throw new RuntimeException("图片无效");
        }
        if (StrUtil.isBlank(request.getPrompt()))
        {
            log.info("分镜编辑图prompt为空: storyboardId={}", request.getStoryboardId());
            throw new RuntimeException("提示词为空");
        }
        if (StrUtil.isBlank(request.getModelCode()))
        {
            log.info("分镜编辑图modelCode为空: storyboardId={}", request.getStoryboardId());
            throw new RuntimeException("模型不能空");
        }
        if (StrUtil.isBlank(request.getAspectRatio()))
        {
            log.info("分镜编辑图aspectRatio为空: storyboardId={}", request.getStoryboardId());
            throw new RuntimeException("比例不能空");
        }
        if (StrUtil.isBlank(request.getSize()))
        {
            log.info("分镜编辑图size为空: storyboardId={}", request.getStoryboardId());
            throw new RuntimeException("清晰度为空");
        }
        if (Objects.isNull(request.getImageCount())
                || request.getImageCount() < IMAGE_COUNT_MIN
                || request.getImageCount() > IMAGE_COUNT_MAX)
        {
            log.info("分镜编辑图imageCount不合法: storyboardId={}, imageCount={}",
                    request.getStoryboardId(), request.getImageCount());
            throw new RuntimeException("张数不合法");
        }
    }

    private AidStoryboard loadAndCheckStoryboard(Long storyboardId, Long userId)
    {
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
            log.info("分镜编辑图分镜不存在: storyboardId={}", storyboardId);
            throw new RuntimeException("分镜不存在");
        }
        if (!Objects.equals(userId, storyboard.getUserId()))
        {
            log.info("分镜编辑图归属校验失败: storyboardId={}, owner={}, request={}",
                    storyboardId, storyboard.getUserId(), userId);
            throw new RuntimeException("无权访问");
        }
        return storyboard;
    }

    /**
     * 模型可用范围校验：modelCode 必须存在 + 启用 + model_type=image，
     * 且 ID 在 {@code aid_ai_model_func_config.func_code = image_edit} 的 modelIds 列表里。
     */
    private AidAiModel validateModelInPool(String modelCode)
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
            log.error("分镜编辑图功能池未配置: funcCode={}", FUNC_CODE_IMAGE_EDIT);
            throw new RuntimeException("功能未开放");
        }
        List<Long> allowedIds = parseModelIdsJson(cfg.getModelIds());
        if (CollectionUtil.isEmpty(allowedIds))
        {
            log.error("分镜编辑图功能池为空: funcCode={}", FUNC_CODE_IMAGE_EDIT);
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
            log.info("分镜编辑图模型不存在或已停用: modelCode={}", modelCode);
            throw new RuntimeException("模型无效");
        }
        if (!Objects.equals(MODEL_TYPE_IMAGE, model.getModelType()))
        {
            log.info("分镜编辑图模型类型不匹配: modelCode={}, type={}", modelCode, model.getModelType());
            throw new RuntimeException("模型不符");
        }
        if (!allowedIds.contains(model.getId()))
        {
            log.info("分镜编辑图模型不在功能池: modelCode={}, modelId={}, pool={}",
                    modelCode, model.getId(), allowedIds);
            throw new RuntimeException("模型不符");
        }
        return model;
    }

    /** 模型能力校验（比例 / 清晰度 / 张数 / 输入能力 严格模式） */
    private void validateModelCapability(AiModelConfigVo modelConfig,
                                          StoryboardEditImageGenerateRequest request, Long userId)
    {
        String modelCode = modelConfig.getModelCode();
        Long storyboardId = request.getStoryboardId();

        CapabilityVO capability = parseCapabilityJsonStrict(modelConfig.getCapabilityJson(),
                modelCode, storyboardId, userId);

        List<String> aspectOptions = capability.getAspectRatioOptions();
        if (CollectionUtil.isEmpty(aspectOptions))
        {
            log.info("分镜编辑图比例能力缺失: storyboardId={}, modelCode={}", storyboardId, modelCode);
            throw new RuntimeException("比例不符");
        }
        String requestedAspect = request.getAspectRatio().trim();
        boolean aspectMatched = aspectOptions.stream()
                .filter(StrUtil::isNotBlank)
                .anyMatch(opt -> opt.trim().equalsIgnoreCase(requestedAspect));
        if (!aspectMatched)
        {
            log.info("分镜编辑图比例不支持: storyboardId={}, modelCode={}, aspect={}, supported={}",
                    storyboardId, modelCode, requestedAspect, aspectOptions);
            throw new RuntimeException("比例不符");
        }

        List<String> sizeOptions = capability.getSizeOptions();
        if (CollectionUtil.isEmpty(sizeOptions))
        {
            log.info("分镜编辑图清晰度能力缺失: storyboardId={}, modelCode={}", storyboardId, modelCode);
            throw new RuntimeException("清晰度不符");
        }
        String requestedSize = request.getSize().trim();
        boolean sizeMatched = sizeOptions.stream()
                .filter(StrUtil::isNotBlank)
                .anyMatch(opt -> opt.trim().equalsIgnoreCase(requestedSize));
        if (!sizeMatched)
        {
            log.info("分镜编辑图清晰度不支持: storyboardId={}, modelCode={}, size={}, supported={}",
                    storyboardId, modelCode, requestedSize, sizeOptions);
            throw new RuntimeException("清晰度不符");
        }

        Integer maxOutput = modelConfig.getMaxOutputCount();
        if (Objects.isNull(maxOutput) || maxOutput <= 0)
        {
            log.info("分镜编辑图模型输出上限未配置: storyboardId={}, modelCode={}", storyboardId, modelCode);
            throw new RuntimeException("张数不合法");
        }
        if (request.getImageCount() > maxOutput)
        {
            log.info("分镜编辑图张数超出模型上限: storyboardId={}, modelCode={}, count={}, max={}",
                    storyboardId, modelCode, request.getImageCount(), maxOutput);
            throw new RuntimeException("张数不合法");
        }

        // 输入能力校验：本接口必须有 1 张参考图，模型必须 supportsImageInput=true
        Boolean supportsImage = modelConfig.getSupportsImageInput();
        if (!Boolean.TRUE.equals(supportsImage))
        {
            log.info("分镜编辑图模型不支持图片输入: storyboardId={}, modelCode={}", storyboardId, modelCode);
            throw new RuntimeException("模型不符");
        }
    }

    private CapabilityVO parseCapabilityJsonStrict(String json, String modelCode, Long storyboardId, Long userId)
    {
        if (StrUtil.isBlank(json))
        {
            log.info("分镜编辑图capabilityJson为空: storyboardId={}, userId={}, modelCode={}",
                    storyboardId, userId, modelCode);
            throw new RuntimeException("模型不符");
        }
        try
        {
            CapabilityVO capability = OBJECT_MAPPER.readValue(json, CapabilityVO.class);
            if (Objects.isNull(capability))
            {
                log.info("分镜编辑图capabilityJson解析为空: storyboardId={}, userId={}, modelCode={}",
                        storyboardId, userId, modelCode);
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
            log.info("分镜编辑图capabilityJson解析失败: storyboardId={}, userId={}, modelCode={}, err={}",
                    storyboardId, userId, modelCode, e.getMessage());
            throw new RuntimeException("模型不符");
        }
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
            log.error("解析分镜编辑图功能池modelIds失败: raw={}, err={}", modelIdsJson, e.getMessage());
        }
        return ordered;
    }

    /** 最终 prompt 拼装（原文 + 比例 + 参考图 URL 清单） */
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
     * 写任务记录 + 本地线程池异步执行（与 form_edit_chat 相同骨架）。
     */
    private StoryboardEditImageGenerateVO submitTask(StoryboardEditImageGenerateRequest request, Long userId,
                                                     AidStoryboard storyboard, AidAiModel model,
                                                     String finalPrompt, String lockKey, String lockToken)
    {
        String modelCode = model.getModelCode();
        String aspectRatio = request.getAspectRatio().trim();
        String size = request.getSize().trim();
        int imageCount = request.getImageCount();
        List<String> referenceImages = new ArrayList<>();
        for (String url : request.referenceImagesAsList())
        {
            if (StrUtil.isNotBlank(url))
            {
                // 相对路径拼成完整URL，下游 provider 需可访问地址
                referenceImages.add(mediaUrlResolver.toFullUrl(url.trim()));
            }
        }

        AidExtractTask task = new AidExtractTask();
        task.setProjectId(storyboard.getProjectId());
        task.setEpisodeId(storyboard.getEpisodeId());
        task.setUserId(userId);
        task.setTaskType(TASK_TYPE_STORYBOARD_EDIT_IMAGE);
        task.setModelCode(modelCode);
        try
        {
            Map<String, Object> inputMap = new LinkedHashMap<>();
            inputMap.put("storyboardId", storyboard.getId());
            inputMap.put("modelCode", modelCode);
            inputMap.put("aspectRatio", aspectRatio);
            inputMap.put("size", size);
            inputMap.put("imageCount", imageCount);
            inputMap.put("referenceImages", referenceImages);
            inputMap.put("rawPrompt", request.getPrompt());
            inputMap.put("finalPromptSummary", StrUtil.sub(finalPrompt, 0, 200));
            inputMap.put("finalPromptLen", finalPrompt.length());
            task.setInputSnapshot(OBJECT_MAPPER.writeValueAsString(inputMap));
        }
        catch (Exception e)
        {
            task.setInputSnapshot("{\"storyboardId\":" + storyboard.getId() + "}");
        }
        task.setStatus(TASK_STATUS_PENDING);
        task.setTotalCount(0);
        task.setDelFlag(DEL_FLAG_NORMAL);
        task.setCreateTime(DateUtils.getNowDate());
        task.setCreateBy(String.valueOf(userId));
        extractTaskService.save(task);
        Long taskId = task.getId();

        // gen_params：精简 JSON 快照，落 aid_gen_record.gen_params NOT NULL 列
        String genParamsJson;
        try
        {
            Map<String, Object> genParamsMap = new LinkedHashMap<>();
            genParamsMap.put("storyboardId", storyboard.getId());
            genParamsMap.put("modelCode", modelCode);
            genParamsMap.put("aspectRatio", aspectRatio);
            genParamsMap.put("size", size);
            genParamsMap.put("imageCount", imageCount);
            genParamsMap.put("referenceImages", referenceImages);
            genParamsMap.put("rawPrompt", request.getPrompt());
            genParamsJson = OBJECT_MAPPER.writeValueAsString(genParamsMap);
        }
        catch (Exception e)
        {
            log.warn("分镜编辑图 genParams 序列化失败, 降级最小快照: storyboardId={}", storyboard.getId(), e);
            genParamsJson = "{\"storyboardId\":" + storyboard.getId() + "}";
        }
        final String genParamsJsonFinal = genParamsJson;

        Long modelId = model.getId();
        // 入队 + 多维并发调度（LOCAL 派发）。
        boolean enqueued = taskQueueService.submitLocalTask(taskId, storyboard.getProjectId(),
                storyboard.getEpisodeId(), userId, modelCode, TASK_TYPE_STORYBOARD_EDIT_IMAGE,
                () -> runAsync(
                        taskId, userId, storyboard, modelCode, modelId, finalPrompt,
                        referenceImages, aspectRatio, size, imageCount,
                        request.getPrompt(), lockKey, lockToken, genParamsJsonFinal));
        if (!enqueued)
        {
            log.error("分镜编辑图入队失败: taskId={}, storyboardId={}", taskId, storyboard.getId());
            updateTaskFailed(taskId, "提交失败");
            safeReleaseLock(lockKey, lockToken);
            throw new RuntimeException("提交失败");
        }

        return StoryboardEditImageGenerateVO.builder()
                .taskId(taskId)
                .status(TASK_STATUS_PENDING)
                .build();
    }

    /**
     * 执行分镜编辑图生成。
     */
    private void runAsync(Long taskId, Long userId, AidStoryboard storyboard,
                          String modelCode, Long modelId, String finalPrompt,
                          List<String> referenceImages, String aspectRatio, String size,
                          int imageCount, String rawPrompt,
                          String lockKey, String lockToken, String genParamsJson)
    {
        try
        {
            // 取消检查点①：异步启动后
            if (assetExtractService.isTaskCancelled(taskId))
            {
                log.info("分镜编辑图启动前检测到取消, 跳过执行: taskId={}, storyboardId={}", taskId, storyboard.getId());
                if (updateTaskCancelled(taskId))
                {
                    sseManager.sendCancelled(taskId, "用户取消");
                }
                return;
            }
            if (!updateTaskStatus(taskId, TASK_STATUS_PROCESSING, null, TASK_STATUS_PENDING))
            {
                log.warn("分镜编辑图任务已被其他线程处理, 跳过: taskId={}", taskId);
                return;
            }
            // 登记执行租约（重启自愈据租约判活）
            assetExtractService.markTaskProcessing(taskId);
            // 取消检查点②：CAS 进 PROCESSING 后
            if (assetExtractService.isTaskCancelled(taskId))
            {
                log.info("分镜编辑图进入PROCESSING后检测到取消: taskId={}, storyboardId={}", taskId, storyboard.getId());
                if (updateTaskCancelled(taskId))
                {
                    sseManager.sendCancelled(taskId, "用户取消");
                }
                return;
            }

            // 真实多张输出语义：按 imageCount 循环单图调用
            List<Long> recordIds = new ArrayList<>();
            List<Map<String, Object>> items = new ArrayList<>();
            List<Map<String, Object>> failedItems = new ArrayList<>();

            for (int i = 0; i < imageCount; i++)
            {
                if (assetExtractService.isTaskCancelled(taskId))
                {
                    log.info("分镜编辑图批次中途检测到取消: taskId={}, storyboardId={}, done={}/{}",
                            taskId, storyboard.getId(), i, imageCount);
                    break;
                }
                try
                {
                    String imageUrl = generateSingleImage(taskId, userId, storyboard, modelCode,
                            finalPrompt, referenceImages, aspectRatio, size, i + 1);
                    if (StrUtil.isBlank(imageUrl))
                    {
                        log.error("分镜编辑图单张为空: taskId={}, storyboardId={}, index={}",
                                taskId, storyboard.getId(), i);
                        throw new RuntimeException("图片生成失败");
                    }
                    Long recordId = persistGenRecord(storyboard, userId, modelId,
                            rawPrompt, finalPrompt, imageUrl, genParamsJson);
                    recordIds.add(recordId);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("recordId", recordId);
                    item.put("imageUrl", imageUrl);
                    items.add(item);
                    pushStepProgress(taskId, storyboard.getId(), imageCount, i + 1, items, failedItems);
                }
                catch (TaskCancelledException cancel)
                {
                    throw cancel;
                }
                catch (Exception perItemEx)
                {
                    log.error("分镜编辑图单张失败: taskId={}, storyboardId={}, index={}, err={}",
                            taskId, storyboard.getId(), i, perItemEx.getMessage());
                    Map<String, Object> failItem = new LinkedHashMap<>();
                    failItem.put("index", i + 1);
                    failItem.put("message", StrUtil.sub(
                            StrUtil.blankToDefault(perItemEx.getMessage(), "生成失败"), 0, 80));
                    failedItems.add(failItem);
                    pushStepProgress(taskId, storyboard.getId(), imageCount, i + 1, items, failedItems);
                }
            }

            if (CollectionUtil.isEmpty(recordIds))
            {
                if (!assetExtractService.isTaskCancelled(taskId))
                {
                    log.error("分镜编辑图无任何结果: taskId={}, storyboardId={}, failedItems={}",
                            taskId, storyboard.getId(), failedItems);
                    String userFacing = pickFirstUserFacingMessage(failedItems, "生成失败");
                    throw new BatchAllFailedException(userFacing);
                }
            }

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("storyboardId", storyboard.getId());
            resultMap.put("imageCount", recordIds.size());
            resultMap.put("recordIds", recordIds);
            resultMap.put("items", items);
            resultMap.put("aspectRatio", aspectRatio);
            resultMap.put("size", size);
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
                    log.info("分镜编辑图完成但检测到取消(resultData已保留): taskId={}, storyboardId={}, recordIds={}",
                            taskId, storyboard.getId(), recordIds);
                }
                return;
            }

            if (updateTaskSuccess(taskId, recordIds.size(), resultJson))
            {
                sseManager.sendComplete(taskId, resultMap);
                log.info("分镜编辑图完成: taskId={}, storyboardId={}, recordIds={}",
                        taskId, storyboard.getId(), recordIds);
            }
        }
        catch (TaskCancelledException e)
        {
            log.info("分镜编辑图任务执行中被取消: taskId={}, storyboardId={}", taskId, storyboard.getId());
            if (updateTaskCancelled(taskId))
            {
                sseManager.sendCancelled(taskId, "用户取消");
            }
        }
        catch (Exception e)
        {
            log.error("分镜编辑图任务失败: taskId={}, storyboardId={}", taskId, storyboard.getId(), e);
            com.aid.common.error.TaskErrorResult errorResult = com.aid.common.error.ErrorNormalizer.normalize(e);
            if (updateTaskFailed(taskId, errorResult))
            {
                sseManager.sendError(taskId, errorResult);
            }
        }
        finally
        {
            safeReleaseLock(lockKey, lockToken);
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

    /** 调用统一图片生成主链路 —— 单次一张；计费完全由媒体主链路处理。 */
    private String generateSingleImage(Long taskId, Long userId, AidStoryboard storyboard,
                                        String modelCode, String finalPrompt,
                                        List<String> referenceImages, String aspectRatio,
                                        String size, int indexFrom1)
    {
        MediaImageGenerateRequest imageRequest = new MediaImageGenerateRequest();
        imageRequest.setModelName(modelCode);
        imageRequest.setUserId(userId);
        imageRequest.setPrompt(finalPrompt);
        imageRequest.setProjectId(storyboard.getProjectId());
        imageRequest.setEpisodeId(storyboard.getEpisodeId());

        Map<String, Object> options = new HashMap<>();
        if (CollectionUtil.isNotEmpty(referenceImages))
        {
            imageRequest.setReferenceImageUrl(referenceImages.get(0));
        }
        options.put("aspect_ratio", aspectRatio);
        options.put("force_single", true);
        imageRequest.setOptions(options);
        imageRequest.setSize(size);
        imageRequest.setExpectedImageCount(1);
        // bizTaskId 差异化：父 taskId * 100 + index，避免媒体层 requestHash 把 N 次请求幂等合并成 1 次
        imageRequest.setBizTaskId(taskId * 100L + indexFrom1);
        imageRequest.setBizTaskType(TASK_TYPE_STORYBOARD_EDIT_IMAGE);

        AiModelConfigVo defaultModelConfig = aiModelConfigService.selectByModelCode(modelCode);
        if (Objects.isNull(defaultModelConfig))
        {
            log.error("分镜编辑图模型配置缺失: modelCode={}", modelCode);
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

    private static final class BatchAllFailedException extends RuntimeException
    {
        BatchAllFailedException(String message)
        {
            super(message);
        }
    }

    /** 解析单次图片生成响应：支持同步 / 异步，异步轮询期间响应取消。 */
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
                log.error("分镜编辑图同步成功但 ossUrl 为空: mediaTaskId={}", imageResponse.getTaskId());
                throw new RuntimeException("存储失败");
            }
            return imageResponse.getOssUrl();
        }
        if (!IMAGE_IN_PROGRESS_STATUSES.contains(imageResponse.getStatus()))
        {
            String errorMsg = imageResponse.getErrorMessage();
            log.error("分镜编辑图失败: mediaTaskId={}, status={}, error={}",
                    imageResponse.getTaskId(), imageResponse.getStatus(), errorMsg);
            throw new RuntimeException(StrUtil.isNotBlank(errorMsg) ? errorMsg : "图片生成失败");
        }

        Long mediaTaskId = imageResponse.getTaskId();
        if (Objects.isNull(mediaTaskId))
        {
            log.error("分镜编辑图异步任务缺少 taskId");
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
                log.info("分镜编辑图轮询期间检测到取消, 停止等待: taskId={}, mediaTaskId={}", taskId, mediaTaskId);
                throw new TaskCancelledException();
            }
            MediaTaskResponse polled = mediaGenerationService.queryTaskRefresh(mediaTaskId);
            if (Objects.isNull(polled))
            {
                log.error("分镜编辑图轮询返回空: mediaTaskId={}", mediaTaskId);
                throw new RuntimeException("图片生成失败");
            }
            if (Objects.equals(TASK_STATUS_SUCCEEDED, polled.getStatus()))
            {
                if (StrUtil.isBlank(polled.getOssUrl()))
                {
                    log.warn("分镜编辑图成功但 ossUrl 暂空，等待下一轮持久化: mediaTaskId={}", mediaTaskId);
                    continue;
                }
                return polled.getOssUrl();
            }
            if (TASK_STATUS_FAILED.equals(polled.getStatus()))
            {
                String errorMsg = polled.getErrorMessage();
                log.error("分镜编辑图异步失败: mediaTaskId={}, error={}", mediaTaskId, errorMsg);
                throw new RuntimeException(StrUtil.isNotBlank(errorMsg) ? errorMsg : "图片生成失败");
            }
        }
        log.error("分镜编辑图异步超时: mediaTaskId={}, timeout={}s", mediaTaskId, IMAGE_POLL_TIMEOUT_SECONDS);
        throw new RuntimeException("图片生成超时");
    }
    /** 落地一条 {@code aid_gen_record}（gen_type=image）。 */
    private Long persistGenRecord(AidStoryboard storyboard, Long userId, Long modelId,
                                   String rawPrompt, String finalPrompt, String imageUrl,
                                   String genParamsJson)
    {
        AidGenRecord record = new AidGenRecord();
        record.setUserId(userId);
        // 冗余存项目 / 剧集，便于按 (project, episode) 维度反查 aid_gen_record
        record.setProjectId(storyboard.getProjectId());
        record.setEpisodeId(storyboard.getEpisodeId());
        record.setStoryboardId(storyboard.getId());
        record.setGenType(GEN_TYPE_IMAGE);
        record.setModelId(modelId);
        record.setPromptText(finalPrompt);
        record.setUserInputText(rawPrompt);
        record.setFileUrl(imageUrl);
        // gen_params：精简 JSON 快照，符合 aid_gen_record.gen_params NOT NULL 列约束
        record.setGenParams(genParamsJson);
        record.setStatus(1); // 1=成功
        record.setIsSelected(0);
        record.setDelFlag(DEL_FLAG_NORMAL);
        record.setCreateTime(DateUtils.getNowDate());
        record.setCreateBy(String.valueOf(userId));
        aidGenRecordService.save(record);
        return record.getId();
    }
    private void pushStepProgress(Long taskId, Long storyboardId, int totalCount, int processedCount,
                                   List<Map<String, Object>> items, List<Map<String, Object>> failedItems)
    {
        if (Objects.isNull(taskId) || totalCount <= 0)
        {
            return;
        }
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
        String stepId = "edit_image_" + processedCount + "_of_" + totalCount;
        String stepTitle = "已处理 " + progressText;

        int successCount = CollectionUtil.isEmpty(items) ? 0 : items.size();
        int failCount = CollectionUtil.isEmpty(failedItems) ? 0 : failedItems.size();

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("taskType", TASK_TYPE_STORYBOARD_EDIT_IMAGE);
        extras.put("status", TASK_STATUS_PROCESSING);
        extras.put("storyboardId", storyboardId);
        extras.put("processedCount", processedCount);
        extras.put("successCount", successCount);
        extras.put("totalCount", totalCount);
        extras.put("progressText", progressText);
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
            sseManager.sendStepProgressWithData(taskId, "edit_image_gen", progress,
                    stepId, stepTitle, processedCount, totalCount, extras);
        }
        catch (Exception e)
        {
            log.warn("分镜编辑图progress推送异常: taskId={}, storyboardId={}, {}/{}",
                    taskId, storyboardId, processedCount, totalCount);
        }
    }

    @SuppressWarnings("unchecked")
    private String pickFirstUserFacingMessage(List<Map<String, Object>> failedItems, String fallback)
    {
        String safe = StrUtil.isNotBlank(fallback) ? fallback : "生成失败";
        if (CollectionUtil.isEmpty(failedItems))
        {
            return safe;
        }
        Object msg = failedItems.get(0).get("message");
        if (!(msg instanceof String s) || StrUtil.isBlank(s))
        {
            return safe;
        }
        // 关键词白名单归一化：避免上游英文 / 长异常透出
        String[] knownShort = new String[] {
                "图片生成失败", "图片生成超时", "图片生成被中断",
                "模型无效", "模型不符", "比例不符", "清晰度不符", "张数不合法",
                "参考图缺失", "图片无效",
                "存储失败", "功能未开放", "任务处理中",
                "生成失败"
        };
        for (String keyword : knownShort)
        {
            if (s.contains(keyword))
            {
                return keyword;
            }
        }
        return safe;
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
            log.warn("分镜编辑图成功CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
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
            log.warn("分镜编辑图失败CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
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
            log.warn("分镜编辑图取消CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
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
            log.warn("分镜编辑图取消(保留结果)CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
            return false;
        }
        return true;
    }
}
