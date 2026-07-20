package com.aid.storyboard.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aid.agent.AgentDefaultParamsApplier;
import com.aid.agent.AgentModelDefault;
import com.aid.agent.IAidAgentService;
import com.aid.aid.domain.AidAgent;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidGenRecordService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.provider.ReferenceImageLimiter;
import com.aid.media.service.IMediaGenerationService;
import com.aid.media.service.MediaTaskArchiveService;
import com.aid.model.service.IAiModelBusinessService;
import com.aid.model.vo.AiModelVO;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.rps.queue.MediaGenFanInSupport;
import com.aid.rps.resolver.StoryboardImageReferenceResolver;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.sse.AssetExtractSseManager;
import com.aid.service.IAiModelConfigService;
import com.aid.storyboard.dto.StoryboardImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardImageGenerateVO;
import com.aid.storyboard.service.IStoryboardImageGenerationService;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜图生成服务实现（批量 + 续生）。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class StoryboardImageGenerationServiceImpl implements IStoryboardImageGenerationService
{
    /** 默认智能体编码 */
    private static final String DEFAULT_AGENT_CODE = "aid_storyboard_image";

    /** 业务分类（biz_category_code），与 aid_ai_model_func_config.func_code 完全一致 */
    private static final String BIZ_CATEGORY = "main_storyboard_image";

    /** 任务类型（与 AssetExtractServiceImpl 常量保持一致） */
    private static final String TASK_TYPE_STORYBOARD_IMAGE_GENERATE = "storyboard_image_generate";

    /** 业务任务类型（写入 aid_media_task.biz_task_type） */
    private static final String BIZ_TASK_TYPE = "storyboard_image_generate";

    /** 任务状态枚举（与 AidExtractTask 字符串字段对齐） */
    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    /** 排队中（已入队等待并发名额） */
    private static final String TASK_STATUS_QUEUED = "QUEUED";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_CANCELLED = "CANCELLED";
    /** 部分镜头成功 + 至少一镜头/张失败的终态（支持续生） */
    private static final String TASK_STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";

    /** 期望模型类型 */
    private static final String MODEL_TYPE_IMAGE = "image";

    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** gen_type 常量：单图 */
    private static final String GEN_TYPE_IMAGE = "image";

    /** 提示词最大长度 */
    private static final int MAX_PROMPT_LENGTH = 8000;

    /** 用户补充文本最大长度（超出截断） */
    private static final int MAX_USER_INPUT_LENGTH = 500;

    /** 负向提示词最大长度（超出报错） */
    private static final int MAX_NEGATIVE_PROMPT_LENGTH = 1000;

    /** 出图数量上下限 */
    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 8;

    /** 单次批量出图最大镜头数 */
    private static final int MAX_BATCH_SHOTS = 20;

    /** 运行批次号上限（独占编码位 [0,1000)），用作续生重试超限守卫 */
    private static final int MAX_RUN_NO = 999;

    /** 参考图业务层兜底上限（仅当模型 capability_json.maxReferenceImages 未配置时生效） */
    private static final int MAX_REFERENCE_IMAGES = 8;

    /** @图片N[name] 占位正则（与 StoryboardImageReferenceResolver 完全一致） */
    private static final Pattern REF_PATTERN = Pattern.compile("@图片(\\d+)\\[([^\\]]+)\\]");

    /** 最终 prompt 中参考图映射段的分隔标记 */
    private static final String REFERENCE_MAPPING_HEADER = "\n---参考图映射---\n";

    /** Redis 防重锁 Key 前缀（与 AssetExtractServiceImpl.FORM_LOCK_PREFIX 同命名空间） */
    private static final String FORM_LOCK_PREFIX = "asset:form:lock:";

    /** 防重锁 TTL（秒） */
    private static final long FORM_LOCK_TTL_SECONDS = 30L * 60L;

    /** 锁「在建宽限期」（毫秒）：此期间锁视为并发在建而非泄漏，不予抢占。 */
    private static final long LOCK_INFLIGHT_GRACE_MS = 120_000L;

    /** 图片中间态白名单（异步在途判定用） */
    private static final Set<String> IMAGE_IN_PROGRESS_STATUSES = Set.of(
            "INIT", "PENDING", "QUEUED", "PROCESSING", "WAIT_POLL", "WAIT_CALLBACK");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 镜头锁 compare-and-delete Lua 脚本：锁值匹配才删除。 */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> SHOT_LOCK_RELEASE_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    /** 镜头锁 compare-and-expire Lua 脚本：锁值匹配才续租。 */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> SHOT_LOCK_RENEW_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], "
                            + FORM_LOCK_TTL_SECONDS + ") else return 0 end",
                    Long.class);

    /** 镜头锁持有凭证：key + token。 */
    private static final class ShotLock
    {
        final String key;
        final String token;

        ShotLock(String key, String token)
        {
            this.key = key;
            this.token = token;
        }
    }

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private IAidExtractTaskService extractTaskService;

    @Autowired
    private IAidStoryboardService aidStoryboardService;

    @Autowired
    private IAidAgentService aidAgentService;

    @Autowired
    private IMediaGenerationService mediaGenerationService;

    /** 媒体任务终态载荷二阶段压缩：业务上下文消费成功后再清理。 */
    @Autowired
    private MediaTaskArchiveService mediaTaskArchiveService;

    @Autowired
    private StoryboardImageReferenceResolver storyboardImageReferenceResolver;

    /** 引用即启用：生图前把"已存在但未启用"的被引用资产自动设为 is_use=1 */
    @Autowired
    private com.aid.rps.service.IRpsFormImageBusinessService rpsFormImageBusinessService;

    @Autowired
    private AgentDefaultParamsApplier agentDefaultParamsApplier;

    @Autowired
    private IAiModelConfigService aiModelConfigService;

    @Autowired
    private IAiModelBusinessService aiModelBusinessService;

    @Autowired
    private IAidGenRecordService aidGenRecordService;

    /** aid_media_task 无独立 Service，沿用直读 Mapper：按确定性 bizTaskId 反查已成功媒体任务。 */
    @Autowired
    private AidMediaTaskMapper aidMediaTaskMapper;

    @Autowired
    private IAssetExtractService assetExtractService;

    /** 任务排队 / 多维并发调度服务 */
    @Autowired
    private com.aid.rps.queue.TaskQueueService taskQueueService;

    @Autowired
    private AssetExtractSseManager sseManager;

    @Autowired
    private IWechatNotifyService wechatNotifyService;

    @Override
    public StoryboardImageGenerateVO generateImage(StoryboardImageGenerateRequest request, Long userId)
    {
        validateUserId(userId);
        List<Long> ids = validateBatchRequest(request);
        boolean single = ids.size() == 1;
        int perShotCount = single ? clampCount(request.getCount()) : 1;

        String effectiveAgentCode = StrUtil.blankToDefault(request.getAgentCode(), DEFAULT_AGENT_CODE);
        log.info("分镜图生成入口: userId={}, shotCount={}, single={}, perShotCount={}, agentCode={}, modelName={}, hasInputPrompt={}",
                userId, ids.size(), single, perShotCount, effectiveAgentCode, request.getModelName(),
                StrUtil.isNotBlank(request.getImagePrompt()));

        AidAgent agent = loadAndAssertAgent(effectiveAgentCode);
        AiModelConfigVo modelConfig = resolveModelConfig(request.getModelName(), agent);
        String modelCode = modelConfig.getModelCode();
        Long modelId = modelConfig.getId();

        // 能力守卫：请求档位/比例必须落在模型 capability 枚举内（如模型仅 1K/2K 时传 8K 直接拦截）
        com.aid.model.vo.CapabilityVO capability =
                com.aid.model.support.ModelCapabilityGuard.parseOrNull(modelConfig.getCapabilityJson());
        com.aid.model.support.ModelCapabilityGuard.assertSizeSupported(capability, request.getSize(), modelCode);
        com.aid.model.support.ModelCapabilityGuard.assertAspectRatioSupported(capability, request.getAspectRatio(), modelCode);

        final boolean singleFinal = single;
        final int perShotCountFinal = perShotCount;
        return submitBatch(userId, ids, single, perShotCount, effectiveAgentCode, modelCode, modelId, modelConfig,
                request.getAspectRatio(), request.getSize(), request.getScenario(),
                request.getNegativePrompt(), request.getUserInputText(),
                sb -> prepareShot(sb, singleFinal, request.getImagePrompt(), request.getUserInputText(),
                        userId, perShotCountFinal, modelConfig));
    }
    private void validateUserId(Long userId)
    {
        if (Objects.isNull(userId) || userId <= 0)
        {
            log.error("分镜图生成登录态缺失: userId={}", userId);
            throw new ServiceException("请先登录");
        }
    }

    /**
     * 批量入参校验：storyboardIds 非空去重 + count 规则 + 提示词/负向提示词/补充文本长度校验。
     *
     * @return 去重后的分镜 ID 列表
     */
    private List<Long> validateBatchRequest(StoryboardImageGenerateRequest request)
    {
        if (Objects.isNull(request))
        {
            log.error("分镜图生成入参为空");
            throw new ServiceException("参数错误");
        }
        List<Long> ids = distinctValidIds(request.getStoryboardIds());
        boolean single = ids.size() == 1;
        validateCountRule(single, request.getCount());
        // imagePrompt 仅单镜头生效，多镜头忽略，故仅单镜头时校验长度
        if (single && StrUtil.isNotBlank(request.getImagePrompt())
                && request.getImagePrompt().length() > MAX_PROMPT_LENGTH)
        {
            log.error("分镜图生成提示词过长: len={}", request.getImagePrompt().length());
            throw new ServiceException("提示词过长");
        }
        if (StrUtil.isNotBlank(request.getNegativePrompt())
                && request.getNegativePrompt().length() > MAX_NEGATIVE_PROMPT_LENGTH)
        {
            log.error("分镜图生成负向提示词过长: len={}", request.getNegativePrompt().length());
            throw new ServiceException("提示词过长");
        }
        if (StrUtil.isNotBlank(request.getUserInputText())
                && request.getUserInputText().length() > MAX_USER_INPUT_LENGTH)
        {
            log.warn("分镜图生成 userInputText 超长截断: originLen={}", request.getUserInputText().length());
            request.setUserInputText(request.getUserInputText().substring(0, MAX_USER_INPUT_LENGTH));
        }
        if (StrUtil.isNotBlank(request.getSize()) && StrUtil.isNotBlank(request.getAspectRatio()))
        {
            log.info("分镜图生成 size 与 aspectRatio 同时非空, size 优先");
            request.setAspectRatio(null);
        }
        return ids;
    }

    /** 分镜 ID 列表去重 + 基础非空校验 + 批量规模上限。 */
    private List<Long> distinctValidIds(List<Long> rawIds)
    {
        if (CollectionUtil.isEmpty(rawIds))
        {
            log.error("分镜图生成 storyboardIds 为空");
            throw new ServiceException("参数错误");
        }
        List<Long> ids = new ArrayList<>();
        for (Long id : rawIds)
        {
            if (Objects.nonNull(id) && id > 0 && !ids.contains(id))
            {
                ids.add(id);
            }
        }
        if (ids.isEmpty())
        {
            log.error("分镜图生成 storyboardIds 全部非法: raw={}", rawIds);
            throw new ServiceException("参数错误");
        }
        if (ids.size() > MAX_BATCH_SHOTS)
        {
            log.info("分镜批量出图镜头数超限: size={}, max={}", ids.size(), MAX_BATCH_SHOTS);
            throw new ServiceException("数量超限");
        }
        return ids;
    }

    /**
     * count 规则校验：多镜头禁止 count&gt;1；单镜头 count 必须在 [1,8]（为空按 1）。
     */
    private void validateCountRule(boolean single, Integer count)
    {
        if (Objects.isNull(count))
        {
            return;
        }
        if (!single)
        {
            if (count > MIN_COUNT)
            {
                log.info("分镜图生成多镜头批量禁止 count>1: count={}", count);
                throw new ServiceException("批量限1张");
            }
            return;
        }
        if (count < MIN_COUNT || count > MAX_COUNT)
        {
            log.error("分镜图生成 count 越界: count={}", count);
            throw new ServiceException("参数错误");
        }
    }

    /** 单镜头出图张数兜底：null→1，限制 [1,8]。 */
    private int clampCount(Integer count)
    {
        if (Objects.isNull(count) || count < MIN_COUNT)
        {
            return MIN_COUNT;
        }
        return Math.min(count, MAX_COUNT);
    }

    /** 把异常文案归一化为简短的镜头跳过原因（控制在 ~12 字内，供前端列表展示）。 */
    private String shortReason(String message)
    {
        String safe = StrUtil.blankToDefault(message, "生成失败");
        return StrUtil.sub(safe, 0, 12);
    }

    private AidStoryboard loadAndCheckStoryboard(Long storyboardId, Long userId)
    {
        AidStoryboard storyboard = aidStoryboardService.getOne(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId,
                                AidStoryboard::getProjectId,
                                AidStoryboard::getEpisodeId,
                                AidStoryboard::getUserId,
                                AidStoryboard::getImagePrompt,
                                AidStoryboard::getDelFlag)
                        .eq(AidStoryboard::getId, storyboardId)
                        .last("limit 1"),
                false);
        if (Objects.isNull(storyboard) || !DEL_FLAG_NORMAL.equals(storyboard.getDelFlag()))
        {
            log.error("分镜不存在或已删除: storyboardId={}, userId={}", storyboardId, userId);
            throw new ServiceException("分镜不存在");
        }
        if (!Objects.equals(userId, storyboard.getUserId()))
        {
            log.error("分镜归属校验失败: storyboardId={}, ownerUserId={}, requestUserId={}",
                    storyboardId, storyboard.getUserId(), userId);
            throw new ServiceException("无权访问");
        }
        return storyboard;
    }
    /** 单镜头解析结果（不可变快照）：finalPrompt + 参考图 + 富化清单 + 本镜头出图张数。 */
    private static final class PreparedShot
    {
        final AidStoryboard storyboard;
        final String finalPrompt;
        final String rawImagePrompt;
        final List<String> referenceImages;
        final String referenceManifestJson;
        final String userImagePromptInput;
        final int takeCount;

        PreparedShot(AidStoryboard storyboard, String finalPrompt, String rawImagePrompt,
                     List<String> referenceImages, String referenceManifestJson,
                     String userImagePromptInput, int takeCount)
        {
            this.storyboard = storyboard;
            this.finalPrompt = finalPrompt;
            this.rawImagePrompt = rawImagePrompt;
            this.referenceImages = referenceImages;
            this.referenceManifestJson = referenceManifestJson;
            this.userImagePromptInput = userImagePromptInput;
            this.takeCount = takeCount;
        }
    }

    /** 镜头解析器（函数式），由主入口 / 续生分别注入 single / 库内取值差异。 */
    @FunctionalInterface
    private interface ShotPreparer
    {
        PreparedShot prepare(AidStoryboard storyboard);
    }

    /**
     * 解析单个镜头：提示词来源 + 引用即启用 + 缺失文字降级 + finalPrompt 构建 + 参考图清单。
     *
     * @param inputImagePrompt  单镜头时前端临时提示词（多镜头忽略，传 null）
     * @param userInputText     用户补充文本（整批共用，拼进 finalPrompt）
     * @param perShotCount      本镜头出图张数
     */
    private PreparedShot prepareShot(AidStoryboard sb, boolean single, String inputImagePrompt,
                                     String userInputText, Long userId, int perShotCount, AiModelConfigVo modelConfig)
    {
        String imagePrompt = resolveImagePrompt(single ? inputImagePrompt : null, sb);
        Map<Integer, String> refNameByN = parseAtReferences(imagePrompt);
        if (CollectionUtil.isNotEmpty(refNameByN))
        {
            List<String> missingNames = rpsFormImageBusinessService.enableReferencesAndCollectMissing(
                    sb.getProjectId(), sb.getEpisodeId(), userId, refNameByN.values());
            if (CollectionUtil.isNotEmpty(missingNames))
            {
                log.warn("分镜图生成存在失效引用，将降级为文字描述: storyboardId={}, missing={}", sb.getId(),
                        formatMissingRefs(imagePrompt, missingNames));
                java.util.Set<String> missingSet = missingNames.stream()
                        .map(StoryboardImageReferenceResolver::normalizeAssetRefName)
                        .collect(java.util.stream.Collectors.toSet());
                List<String> availableNames = refNameByN.values().stream()
                        .filter(name -> !missingSet.contains(StoryboardImageReferenceResolver.normalizeAssetRefName(name)))
                        .collect(java.util.stream.Collectors.toList());
                if (CollectionUtil.isNotEmpty(availableNames))
                {
                    List<String> unexpectedMissing = rpsFormImageBusinessService.enableReferencesAndCollectMissing(
                            sb.getProjectId(), sb.getEpisodeId(), userId, availableNames);
                    if (CollectionUtil.isNotEmpty(unexpectedMissing))
                    {
                        log.warn("分镜图有效引用二次启用时状态变化，将继续文字降级: storyboardId={}, missing={}",
                                sb.getId(), unexpectedMissing);
                    }
                }
            }
        }
        int maxReferenceImages = ReferenceImageLimiter.resolveMax(modelConfig, MAX_REFERENCE_IMAGES);
        ImagePromptResolution promptResolution = compactImagePrompt(imagePrompt, sb, userId, maxReferenceImages);
        String effectivePrompt = promptResolution.prompt;
        StoryboardImageReferenceResolver.ResolveResult refResult = storyboardImageReferenceResolver.resolve(
                effectivePrompt, sb.getProjectId(), sb.getEpisodeId(), userId);
        if (CollectionUtil.isNotEmpty(refResult.getUnresolvedNames()))
        {
            // 并发删除等极小窗口的二次兜底：再次文字化，禁止把悬空编号交给 Provider。
            log.warn("分镜图生成引用解析期间状态变化，将再次文字降级: storyboardId={}, unresolved={}",
                    sb.getId(), formatMissingRefs(effectivePrompt, refResult.getUnresolvedNames()));
            promptResolution = compactImagePrompt(effectivePrompt, sb, userId, maxReferenceImages);
            effectivePrompt = promptResolution.prompt;
            refResult = storyboardImageReferenceResolver.resolve(
                    effectivePrompt, sb.getProjectId(), sb.getEpisodeId(), userId);
        }
        int minReferenceImages = ReferenceImageLimiter.readMinFromCapabilityJson(modelConfig.getCapabilityJson());
        if (refResult.getReferenceImageUrls().size() < minReferenceImages
                || (CollectionUtil.isEmpty(refResult.getReferenceImageUrls())
                    && Boolean.FALSE.equals(modelConfig.getSupportsTextInput())))
        {
            log.info("分镜图有效参考图不足: storyboardId={}, required={}, actual={}, supportsText={}",
                    sb.getId(), minReferenceImages, refResult.getReferenceImageUrls().size(),
                    modelConfig.getSupportsTextInput());
            throw new ServiceException("请选择参考图");
        }
        String finalPrompt = buildFinalPrompt(effectivePrompt, refResult, userInputText);
        List<String> referenceImages = ReferenceImageLimiter.limit(
                refResult.getReferenceImageUrls(), modelConfig, MAX_REFERENCE_IMAGES, "分镜图生成");
        String referenceManifestJson = buildReferenceManifestJson(effectivePrompt, sb, userId);
        String userImagePromptInput = single ? inputImagePrompt : null;
        return new PreparedShot(sb, finalPrompt, imagePrompt, referenceImages, referenceManifestJson,
                userImagePromptInput, perShotCount);
    }

    /** 把有图引用压缩为连续序号；无图引用只保留名称文本，让模型按描述自由生成。 */
    private ImagePromptResolution compactImagePrompt(String prompt, AidStoryboard storyboard, Long userId,
                                                      int maxReferenceImages)
    {
        List<StoryboardImageReferenceResolver.ResolvedImageReference> rich =
                storyboardImageReferenceResolver.resolveRich(prompt, storyboard.getProjectId(),
                        storyboard.getEpisodeId(), userId);
        Map<Integer, Integer> compactByOriginal = new LinkedHashMap<>();
        int compact = 0;
        for (StoryboardImageReferenceResolver.ResolvedImageReference ref : rich)
        {
            if (ref.getType() == StoryboardImageReferenceResolver.RefType.REFERENCE
                    && StrUtil.isNotBlank(ref.getUrl())
                    && compact < maxReferenceImages)
            {
                compactByOriginal.put(ref.getN(), ++compact);
            }
        }
        Matcher matcher = REF_PATTERN.matcher(StrUtil.nullToEmpty(prompt));
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find())
        {
            int originalN;
            try { originalN = Integer.parseInt(matcher.group(1)); }
            catch (NumberFormatException e) { matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group())); continue; }
            Integer compactN = compactByOriginal.get(originalN);
            String name = StrUtil.trimToEmpty(matcher.group(2));
            String replacement = Objects.nonNull(compactN)
                    ? "@图片" + compactN + "[" + name + "]"
                    : name;
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return new ImagePromptResolution(rewritten.toString());
    }

    /** 当前任务实际使用的图片提示词快照。 */
    private static final class ImagePromptResolution
    {
        final String prompt;

        ImagePromptResolution(String prompt)
        {
            this.prompt = prompt;
        }
    }

    private String resolveImagePrompt(String inputPrompt, AidStoryboard storyboard)
    {
        if (StrUtil.isNotBlank(inputPrompt))
        {
            return inputPrompt;
        }
        String dbPrompt = storyboard.getImagePrompt();
        if (StrUtil.isBlank(dbPrompt))
        {
            log.error("分镜图生成提示词为空: storyboardId={}, userId={}",
                    storyboard.getId(), storyboard.getUserId());
            throw new ServiceException("提示词不能为空");
        }
        return dbPrompt;
    }

    private AidAgent loadAndAssertAgent(String agentCode)
    {
        AidAgent agent;
        try
        {
            agent = aidAgentService.getByAgentCodeAndAssertBizCategory(agentCode, BIZ_CATEGORY);
        }
        catch (ServiceException ex)
        {
            log.error("智能体加载失败: agentCode={}, expectedBizCategory={}, reason={}",
                    agentCode, BIZ_CATEGORY, ex.getMessage());
            throw new ServiceException("智能体不可用");
        }
        if (Objects.isNull(agent) || !Objects.equals(1, agent.getStatus()))
        {
            log.error("智能体不可用: agentCode={}, agent={}", agentCode, agent);
            throw new ServiceException("智能体不可用");
        }
        return agent;
    }

    /**
     * 解析最终模型配置（用户传入 → 智能体默认 → 都没有报错），并完成类型 + 业务池强校验。
     */
    private AiModelConfigVo resolveModelConfig(String requestModelCode, AidAgent agent)
    {
        if (Objects.isNull(agent))
        {
            log.error("解析智能体模型失败: agent 为空");
            throw new ServiceException("智能体不可用");
        }
        if (StrUtil.isNotBlank(requestModelCode))
        {
            AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(requestModelCode);
            if (Objects.isNull(modelConfig))
            {
                log.info("用户指定模型不可用: agentCode={}, requestModelCode={}",
                        agent.getAgentCode(), requestModelCode);
                throw new ServiceException("模型不存在");
            }
            if (!Objects.equals(MODEL_TYPE_IMAGE, modelConfig.getModelType()))
            {
                log.info("用户指定模型类型不匹配: agentCode={}, requestModelCode={}, actualType={}",
                        agent.getAgentCode(), requestModelCode, modelConfig.getModelType());
                throw new ServiceException("模型不可用");
            }
            assertModelInBizCategoryPool(requestModelCode, modelConfig.getId(), agent);
            return modelConfig;
        }
        String agentModelCode = agent.getModelCode();
        if (StrUtil.isBlank(agentModelCode))
        {
            log.error("智能体未配置默认模型: agentCode={}", agent.getAgentCode());
            throw new ServiceException("模型未配置");
        }
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(agentModelCode);
        if (Objects.isNull(modelConfig))
        {
            log.error("智能体配置的模型不可用: agentCode={}, modelCode={}", agent.getAgentCode(), agentModelCode);
            throw new ServiceException("模型不存在");
        }
        if (!Objects.equals(MODEL_TYPE_IMAGE, modelConfig.getModelType()))
        {
            log.error("智能体配置的模型类型不匹配: agentCode={}, modelCode={}, actualType={}",
                    agent.getAgentCode(), agentModelCode, modelConfig.getModelType());
            throw new ServiceException("模型不可用");
        }
        assertModelInBizCategoryPool(agentModelCode, modelConfig.getId(), agent);
        return modelConfig;
    }

    private void assertModelInBizCategoryPool(String modelCode, Long modelId, AidAgent agent)
    {
        if (Objects.isNull(agent) || StrUtil.isBlank(agent.getBizCategoryCode()))
        {
            return;
        }
        String funcCode = agent.getBizCategoryCode();
        List<AiModelVO> pool = aiModelBusinessService.listAvailableModelsByFuncCode(funcCode);
        if (CollectionUtil.isEmpty(pool))
        {
            log.error("分镜图模型解析失败: 业务分类对应的功能配置不存在或可选模型为空, agentCode={}, bizCategoryCode={}, modelCode={}",
                    agent.getAgentCode(), funcCode, modelCode);
            throw new ServiceException("模型未配置");
        }
        boolean inPool = pool.stream().anyMatch(m -> Objects.equals(modelId, m.getId()));
        if (!inPool)
        {
            log.error("分镜图模型解析失败: 模型不在业务分类可选池内, agentCode={}, bizCategoryCode={}, modelCode={}, poolSize={}",
                    agent.getAgentCode(), funcCode, modelCode, pool.size());
            throw new ServiceException("模型不可用");
        }
    }

    /** 把缺失的参考占位格式化为"图片N[name]、图片M[name2]"（按 N 升序）。 */
    private String formatMissingRefs(String imagePrompt, List<String> unresolvedNames)
    {
        Map<Integer, String> nameByN = parseAtReferences(imagePrompt);
        Set<String> missing = new java.util.HashSet<>(unresolvedNames);
        List<Integer> ns = new ArrayList<>(nameByN.keySet());
        ns.sort(Integer::compareTo);
        StringBuilder sb = new StringBuilder();
        for (Integer n : ns)
        {
            String name = nameByN.get(n);
            if (missing.contains(name))
            {
                if (sb.length() > 0) { sb.append('、'); }
                sb.append("图片").append(n).append('[').append(name).append(']');
            }
        }
        return sb.toString();
    }

    private String buildFinalPrompt(String imagePrompt,
                                    StoryboardImageReferenceResolver.ResolveResult refResult,
                                    String userInputText)
    {
        Map<Integer, String> nameByN = parseAtReferences(imagePrompt);
        StringBuilder sb = new StringBuilder(imagePrompt);
        if (StrUtil.isNotBlank(userInputText))
        {
            sb.append('\n').append("用户补充：").append(userInputText);
        }
        if (CollectionUtil.isNotEmpty(nameByN))
        {
            appendReferenceMappingSection(sb, nameByN, refResult);
        }
        String finalPrompt = sb.toString();
        log.info("分镜图最终 prompt 构建完成: charLen={}, refTotal={}, refResolved={}, refUnresolved={}",
                finalPrompt.length(), nameByN.size(),
                refResult.getReferenceImageUrls().size(),
                refResult.getUnresolvedNames().size());
        return finalPrompt;
    }

    private void appendReferenceMappingSection(StringBuilder sb, Map<Integer, String> nameByN,
                                               StoryboardImageReferenceResolver.ResolveResult refResult)
    {
        List<Integer> orderedNs = new ArrayList<>(nameByN.keySet());
        orderedNs.sort(Integer::compareTo);
        List<String> unresolvedNames = refResult.getUnresolvedNames();
        List<String> resolvedUrls = refResult.getReferenceImageUrls();
        sb.append(REFERENCE_MAPPING_HEADER);
        int resolvedCursor = 0;
        for (Integer n : orderedNs)
        {
            String name = nameByN.get(n);
            boolean isUnresolved = unresolvedNames.contains(name);
            if (isUnresolved)
            {
                log.error("分镜图最终 prompt 构建发现未解析占位（前置校验应已拦截）: 图{}[{}]", n, name);
                throw new ServiceException("参考图缺失，请补齐");
            }
            String url = (resolvedCursor < resolvedUrls.size()) ? resolvedUrls.get(resolvedCursor) : null;
            resolvedCursor++;
            if (StrUtil.isBlank(url))
            {
                log.error("分镜图最终 prompt 构建命中却 URL 为空（前置校验应已拦截）: 图{}[{}]", n, name);
                throw new ServiceException("参考图缺失，请补齐");
            }
            sb.append("【图").append(n).append("】: ").append(url).append(' ').append(name).append('\n');
        }
    }

    private Map<Integer, String> parseAtReferences(String imagePrompt)
    {
        Map<Integer, String> nameByN = new LinkedHashMap<>();
        if (StrUtil.isBlank(imagePrompt))
        {
            return nameByN;
        }
        Matcher m = REF_PATTERN.matcher(imagePrompt);
        while (m.find())
        {
            int n;
            try
            {
                n = Integer.parseInt(m.group(1));
            }
            catch (NumberFormatException e)
            {
                continue;
            }
            String name = StrUtil.trimToEmpty(m.group(2));
            if (StrUtil.isBlank(name))
            {
                continue;
            }
            nameByN.putIfAbsent(n, name);
        }
        return nameByN;
    }

    /** 构建富化引用清单 JSON（供 Provider 层「厂商参考图渲染策略」消费）；解析失败/无引用返回 null。 */
    private String buildReferenceManifestJson(String imagePrompt, AidStoryboard storyboard, Long userId)
    {
        try
        {
            List<StoryboardImageReferenceResolver.ResolvedImageReference> manifest =
                    storyboardImageReferenceResolver.resolveRich(imagePrompt,
                            storyboard.getProjectId(), storyboard.getEpisodeId(), userId);
            if (CollectionUtil.isEmpty(manifest))
            {
                return null;
            }
            return OBJECT_MAPPER.writeValueAsString(manifest);
        }
        catch (Exception e)
        {
            log.warn("分镜图富化引用清单序列化失败(降级,不阻断): storyboardId={}, err={}",
                    storyboard.getId(), e.getMessage());
            return null;
        }
    }
    private String acquireShotLock(String lockKey, Long storyboardId)
    {
        String token = newLockToken();
        Boolean locked = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(lockKey, token, FORM_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(locked))
        {
            if (hasActiveTaskInDb(storyboardId))
            {
                releaseLockIfMine(lockKey, token);
                log.info("分镜图生成抢锁后发现父任务仍活跃(锁曾过期)，拒绝重复出图: storyboardId={}", storyboardId);
                return null;
            }
            return token;
        }
        if (hasActiveTaskInDb(storyboardId))
        {
            return null;
        }
        Object existing = redisCache.getCacheObject(lockKey);
        long lockTs = parseLeadingTs(existing);
        if (lockTs > 0 && System.currentTimeMillis() - lockTs < LOCK_INFLIGHT_GRACE_MS)
        {
            log.info("分镜图生成锁处于在建宽限期(疑似并发在建任务)，按处理中对待: storyboardId={}", storyboardId);
            return null;
        }
        log.warn("分镜图生成 Redis 锁疑似泄漏（DB 无活跃任务且超在建宽限期），CAS 清理重抢: lockKey={}", lockKey);
        releaseLockIfMine(lockKey, existing);
        String newToken = newLockToken();
        Boolean re = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(lockKey, newToken, FORM_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(re))
        {
            return null;
        }
        if (hasActiveTaskInDb(storyboardId))
        {
            releaseLockIfMine(lockKey, newToken);
            log.info("分镜图生成重抢后发现父任务已活跃，CAS 释放并拒绝: storyboardId={}", storyboardId);
            return null;
        }
        return newToken;
    }

    /** 锁值 token：毫秒时间戳:UUID。 */
    private String newLockToken()
    {
        return System.currentTimeMillis() + ":" + java.util.UUID.randomUUID();
    }

    /** 原子 compare-and-delete：仅当锁当前值 == 传入值时删除。 */
    private void releaseLockIfMine(String lockKey, Object expectedValue)
    {
        if (lockKey == null || expectedValue == null)
        {
            return;
        }
        try
        {
            redisCache.redisTemplate.execute(SHOT_LOCK_RELEASE_SCRIPT,
                    java.util.Collections.singletonList(lockKey), expectedValue);
        }
        catch (Exception ignore)
        {
            // CAS 删除失败不阻断主流程，锁会随 TTL 自然过期
        }
    }

    /** 原子 compare-and-expire：仅当锁当前值 == token 时续租 TTL。 */
    private void renewLockIfMine(String lockKey, String token)
    {
        if (lockKey == null || token == null)
        {
            return;
        }
        try
        {
            redisCache.redisTemplate.execute(SHOT_LOCK_RENEW_SCRIPT,
                    java.util.Collections.singletonList(lockKey), token);
        }
        catch (Exception ignore)
        {
            // 续租失败不阻断，acquireShotLock 已有 DB 兜底
        }
    }

    /** 解析锁值前导时间戳（token 形如 "时间戳:uuid"；旧值/非法返回 0）。 */
    private long parseLeadingTs(Object v)
    {
        if (v == null)
        {
            return 0L;
        }
        String s = String.valueOf(v).trim();
        int idx = s.indexOf(':');
        String head = idx >= 0 ? s.substring(0, idx) : s;
        try
        {
            return Long.parseLong(head);
        }
        catch (NumberFormatException e)
        {
            return 0L;
        }
    }
    /**
     * 逐镜头抢锁 + 解析 → 创建单一父任务并异步入队。
     */
    private StoryboardImageGenerateVO submitBatch(Long userId, List<Long> ids, boolean single, int perShotCount,
            String agentCode, String modelCode, Long modelId, AiModelConfigVo modelConfig, String aspectRatio,
            String size, String scenario, String negativePrompt, String userInputText, ShotPreparer preparer)
    {
        List<PreparedShot> prepared = new ArrayList<>();
        List<ShotLock> heldLocks = new ArrayList<>();
        List<StoryboardImageGenerateVO.ShotResult> rejected = new ArrayList<>();
        try
        {
            for (Long id : ids)
            {
                String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_IMAGE_GENERATE + ":" + id;
                ShotLock heldThis = null;
                try
                {
                    AidStoryboard sb = loadAndCheckStoryboard(id, userId);
                    String token = acquireShotLock(lockKey, id);
                    if (token == null)
                    {
                        if (single) { throw new ServiceException("任务处理中"); }
                        rejected.add(new StoryboardImageGenerateVO.ShotResult(id, false, "任务处理中"));
                        continue;
                    }
                    heldThis = new ShotLock(lockKey, token);
                    heldLocks.add(heldThis);
                    prepared.add(preparer.prepare(sb));
                }
                catch (RuntimeException e)
                {
                    if (heldThis != null)
                    {
                        releaseLockIfMine(heldThis.key, heldThis.token);
                        heldLocks.remove(heldThis);
                    }
                    if (single) { throw e; }
                    log.info("分镜批量出图跳过镜头: storyboardId={}, reason={}", id, e.getMessage());
                    rejected.add(new StoryboardImageGenerateVO.ShotResult(id, false, shortReason(e.getMessage())));
                }
            }
            if (prepared.isEmpty())
            {
                log.info("分镜批量出图无可受理镜头: ids={}, rejectedCount={}", ids, rejected.size());
                throw new ServiceException("操作失败，请重试");
            }
            return createBatchTaskAndEnqueue(userId, agentCode, modelCode, modelId, modelConfig, aspectRatio, size,
                    scenario, negativePrompt, userInputText, perShotCount, prepared, heldLocks, rejected);
        }
        catch (RuntimeException e)
        {
            for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
            throw e;
        }
    }

    /** 创建单一父任务（写 input_snapshot/totalCount）+ 构建每镜头 Job + 入队异步执行。 */
    private StoryboardImageGenerateVO createBatchTaskAndEnqueue(Long userId, String agentCode, String modelCode,
            Long modelId, AiModelConfigVo modelConfig, String aspectRatio, String size, String scenario,
            String negativePrompt, String userInputText, int perShotCount, List<PreparedShot> prepared,
            List<ShotLock> heldLocks, List<StoryboardImageGenerateVO.ShotResult> rejected)
    {
        AidStoryboard firstSb = prepared.get(0).storyboard;
        Long projectId = firstSb.getProjectId();
        Long episodeId = firstSb.getEpisodeId();

        int newSubtasks = 0;
        List<Long> acceptedIds = new ArrayList<>();
        List<Map<String, Object>> shotsSnapshot = new ArrayList<>();
        List<Map<String, Object>> allShotsSnapshot = new ArrayList<>();
        for (int i = 0; i < prepared.size(); i++)
        {
            PreparedShot ps = prepared.get(i);
            newSubtasks += ps.takeCount;
            acceptedIds.add(ps.storyboard.getId());
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("storyboardId", ps.storyboard.getId());
            s.put("takeCount", ps.takeCount);
            s.put("lockToken", heldLocks.get(i).token);
            shotsSnapshot.add(s);
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("storyboardId", ps.storyboard.getId());
            a.put("takeCount", ps.takeCount);
            allShotsSnapshot.add(a);
        }

        AidExtractTask task = new AidExtractTask();
        task.setProjectId(projectId);
        task.setEpisodeId(episodeId);
        task.setUserId(userId);
        task.setTaskType(TASK_TYPE_STORYBOARD_IMAGE_GENERATE);
        task.setModelCode(modelCode);
        Map<String, Object> inputMap = new LinkedHashMap<>();
        inputMap.put("storyboardIds", acceptedIds);
        inputMap.put("agentCode", agentCode);
        inputMap.put("modelCode", modelCode);
        inputMap.put("aspectRatio", aspectRatio);
        inputMap.put("size", size);
        inputMap.put("scenario", scenario);
        inputMap.put("negativePrompt", negativePrompt);
        inputMap.put("userInputText", userInputText);
        inputMap.put("countPerShot", perShotCount);
        inputMap.put("shots", shotsSnapshot);
        inputMap.put("allShots", allShotsSnapshot);
        inputMap.put("runNo", 0);
        try
        {
            task.setInputSnapshot(OBJECT_MAPPER.writeValueAsString(inputMap));
        }
        catch (Exception e)
        {
            log.warn("分镜批量出图 inputSnapshot 序列化失败, 降级", e);
            task.setInputSnapshot("{\"taskType\":\"" + TASK_TYPE_STORYBOARD_IMAGE_GENERATE + "\"}");
        }
        task.setStatus(TASK_STATUS_PENDING);
        task.setTotalCount(newSubtasks);
        task.setDelFlag(DEL_FLAG_NORMAL);
        task.setCreateTime(DateUtils.getNowDate());
        task.setCreateBy(String.valueOf(userId));
        extractTaskService.save(task);
        Long taskId = task.getId();

        boolean enqueued;
        try
        {
            Map<Long, Integer> shotOrdinalById = new LinkedHashMap<>();
            Map<Long, List<Integer>> takeSlotsByShot = new LinkedHashMap<>();
            for (int i = 0; i < prepared.size(); i++)
            {
                PreparedShot ps = prepared.get(i);
                Long sid = ps.storyboard.getId();
                shotOrdinalById.put(sid, i);
                List<Integer> slots = new ArrayList<>();
                for (int s = 0; s < ps.takeCount; s++) { slots.add(s); }
                takeSlotsByShot.put(sid, slots);
            }
            enqueued = buildJobsAndEnqueue(taskId, projectId, episodeId, userId, modelCode, modelId, modelConfig,
                    agentCode, aspectRatio, size, scenario, negativePrompt, userInputText, perShotCount, prepared, heldLocks,
                    newSubtasks, prepared.size(), 0, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    java.util.Collections.emptyMap(), 0, false, shotOrdinalById, takeSlotsByShot);
        }
        catch (RuntimeException ex)
        {
            log.error("分镜图生成批量入队异常: taskId={}", taskId, ex);
            updateTaskFailed(taskId, "提交失败");
            for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
            throw new ServiceException("提交失败，请重试");
        }
        if (!enqueued)
        {
            log.error("分镜图生成批量入队失败: taskId={}", taskId);
            updateTaskFailed(taskId, "提交失败");
            for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
            throw new ServiceException("提交失败，请重试");
        }

        StoryboardImageGenerateVO vo = new StoryboardImageGenerateVO();
        vo.setTaskId(taskId);
        vo.setStatus(TASK_STATUS_PENDING);
        vo.setModelName(modelCode);
        vo.setTotalShots(prepared.size());
        vo.setCountPerShot(perShotCount);
        vo.setTotalSubtasks(newSubtasks);
        List<StoryboardImageGenerateVO.ShotResult> items = new ArrayList<>();
        for (PreparedShot ps : prepared)
        {
            items.add(new StoryboardImageGenerateVO.ShotResult(ps.storyboard.getId(), true, null));
        }
        items.addAll(rejected);
        vo.setItems(items);
        return vo;
    }

    /** 构建每镜头 ImageGenJob（共享 taskId）+ 组装 ImageBatchJob 入队。返回是否入队成功。 */
    private boolean buildJobsAndEnqueue(Long taskId, Long projectId, Long episodeId, Long userId, String modelCode,
            Long modelId, AiModelConfigVo modelConfig, String agentCode, String aspectRatio, String size, String scenario,
            String negativePrompt, String userInputText, int perShotCount, List<PreparedShot> prepared,
            List<ShotLock> heldLocks, int newSubtasks, int totalShots, int seedSuccessCount, List<Long> seedRecordIds,
            List<Map<String, Object>> seedItems, List<Map<String, Object>> seedShotResults,
            Map<Long, Map<String, Object>> seedShotPrior, int runNo, boolean forcePartial,
            Map<Long, Integer> shotOrdinalById, Map<Long, List<Integer>> takeSlotsByShot)
    {
        // 入队前清理本任务的扇入计数/收尾标记，保证每轮从干净状态开始扇入
        fanInSupport.cleanup(taskId);
        List<ImageGenJob> shotJobs = new ArrayList<>();
        for (int i = 0; i < prepared.size(); i++)
        {
            PreparedShot ps = prepared.get(i);
            ShotLock lock = heldLocks.get(i);
            Long sid = ps.storyboard.getId();
            int ordinal = (shotOrdinalById != null && shotOrdinalById.get(sid) != null) ? shotOrdinalById.get(sid) : i;
            long bizSeqBase;
            try
            {
                bizSeqBase = Math.addExact(
                        Math.multiplyExact(taskId, MediaGenFanInSupport.BIZ_SEQ_PARENT_FACTOR),
                        (long) ordinal * 1000L);
            }
            catch (ArithmeticException overflow)
            {
                log.error("分镜图生成 bizSeqBase 编码溢出: taskId={}, ordinal={}", taskId, ordinal, overflow);
                throw new ServiceException("提交失败，请重试");
            }
            List<Integer> takeSlots = (takeSlotsByShot != null) ? takeSlotsByShot.get(sid) : null;
            if (CollectionUtil.isEmpty(takeSlots))
            {
                takeSlots = new ArrayList<>();
                for (int s = 0; s < ps.takeCount; s++) { takeSlots.add(s); }
            }
            shotJobs.add(new ImageGenJob(taskId, userId, ps.storyboard, modelCode, modelId, modelConfig,
                    ps.finalPrompt, ps.rawImagePrompt, ps.referenceImages, ps.referenceManifestJson, ps.takeCount,
                    aspectRatio, size, scenario, negativePrompt, userInputText, ps.userImagePromptInput,
                    agentCode, bizSeqBase, takeSlots, lock.key, lock.token));
        }
        ImageBatchJob batchJob = new ImageBatchJob(taskId, userId, modelCode, perShotCount, totalShots,
                seedSuccessCount + newSubtasks, seedSuccessCount, runNo, shotJobs, new ArrayList<>(heldLocks),
                seedRecordIds, seedItems, seedShotResults, seedShotPrior, forcePartial);
        return taskQueueService.submitLocalTask(taskId, projectId, episodeId, userId, modelCode,
                TASK_TYPE_STORYBOARD_IMAGE_GENERATE, () -> runAsyncBatch(batchJob));
    }

    /** 组装单镜头 shotResult，并合并续生携带的既有进度（累计口径）。 */
    private Map<String, Object> buildShotResultEntry(ImageBatchJob batch, ImageGenJob shot,
            List<Long> shotRecordIds, int shotSuccess, List<Integer> failedTakes)
    {
        Long sid = shot.storyboard.getId();
        Map<String, Object> prior = batch.seedShotPrior.get(sid);
        int origTakeCount = shot.imageCount;
        int baseSuccess = 0;
        List<Long> mergedRecordIds = new ArrayList<>();
        if (prior != null)
        {
            if (prior.get("takeCount") instanceof Number n) { origTakeCount = n.intValue(); }
            if (prior.get("successCount") instanceof Number n) { baseSuccess = n.intValue(); }
            if (prior.get("recordIds") instanceof List<?> list)
            {
                for (Object o : list) { if (o instanceof Number n) { mergedRecordIds.add(n.longValue()); } }
            }
        }
        mergedRecordIds.addAll(shotRecordIds);
        Map<String, Object> sr = new LinkedHashMap<>();
        sr.put("storyboardId", sid);
        sr.put("takeCount", origTakeCount);
        sr.put("successCount", baseSuccess + shotSuccess);
        sr.put("recordIds", mergedRecordIds);
        if (!failedTakes.isEmpty()) { sr.put("failedTakes", failedTakes); }
        return sr;
    }

    /** 组装父任务 resultData 结构（终态 / 增量共用）。 */
    private Map<String, Object> buildBatchResultMap(ImageBatchJob batch, int total, List<Long> recordIds,
            List<Map<String, Object>> items, List<Map<String, Object>> shotResults, List<Map<String, Object>> failedItems)
    {
        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("modelCode", batch.modelCode);
        resultMap.put("totalShots", batch.totalShots);
        resultMap.put("countPerShot", batch.countPerShot);
        resultMap.put("totalSubtasks", total);
        resultMap.put("successCount", recordIds.size());
        resultMap.put("failCount", failedItems.size());
        resultMap.put("recordIds", recordIds);
        resultMap.put("items", items);
        resultMap.put("shots", shotResults);
        if (!failedItems.isEmpty()) { resultMap.put("failedItems", failedItems); }
        return resultMap;
    }

    /** 组装单镜头单张 take 的 gen_params JSON 快照（落 aid_gen_record.gen_params NOT NULL 列）。 */
    private String buildShotGenParamsJson(Long parentTaskId, Long storyboardId, String modelCode, String agentCode,
            String aspectRatio, String size, int takeCount, String scenario, String negativePrompt,
            String userInputText, String imagePromptInput, int takeIndex, long bizSeq, String referenceManifestJson)
    {
        Map<String, Object> genParamsMap = new LinkedHashMap<>();
        genParamsMap.put("parentTaskId", parentTaskId);
        genParamsMap.put("storyboardId", storyboardId);
        genParamsMap.put("modelCode", modelCode);
        genParamsMap.put("agentCode", agentCode);
        genParamsMap.put("aspectRatio", aspectRatio);
        genParamsMap.put("size", size);
        genParamsMap.put("count", takeCount);
        genParamsMap.put("takeIndex", takeIndex);
        genParamsMap.put("bizSeq", bizSeq);
        genParamsMap.put("scenario", scenario);
        genParamsMap.put("negativePrompt", negativePrompt);
        genParamsMap.put("userInputText", userInputText);
        genParamsMap.put("imagePromptInput", imagePromptInput);
        // 参考图快照：把出图当时的富化引用清单(referenceManifest)随这条 take 落库，
        // 列表 / 明细接口直接读取，免实时重解析 image_prompt，且不受素材后续换图/删图影响。
        // referenceManifestJson 本身已是 JSON 数组字符串，先 readTree 成节点再放入，避免被当成字符串二次转义。
        if (StrUtil.isNotBlank(referenceManifestJson))
        {
            try
            {
                genParamsMap.put("referenceManifest", OBJECT_MAPPER.readTree(referenceManifestJson));
            }
            catch (Exception e)
            {
                log.warn("分镜图 referenceManifest 嵌入 genParams 失败(降级,不阻断): storyboardId={}, err={}",
                        storyboardId, e.getMessage());
            }
        }
        try
        {
            return OBJECT_MAPPER.writeValueAsString(genParamsMap);
        }
        catch (Exception e)
        {
            log.warn("分镜图生成 genParams 序列化失败, 降级最小快照: storyboardId={}", storyboardId, e);
            return "{\"parentTaskId\":" + parentTaskId + ",\"storyboardId\":" + storyboardId
                    + ",\"takeIndex\":" + takeIndex + ",\"bizSeq\":" + bizSeq + "}";
        }
    }
    /**
     * 非阻塞提交阶段：抢 PROCESSING → 逐镜逐张只提交不轮询 media 子任务 → 立即返回释放 worker 线程。
     * DIRECT 同步直出内联幂等落库；异步子任务由 media 调度中心轮询完成后发事件，
     * {@link #onMediaImageTaskTerminal} 扇入收尾。父任务租约在异步在途期间由 media 调度中心续租。
     */
    private void runAsyncBatch(ImageBatchJob batch)
    {
        Long taskId = batch.taskId;
        // 同步提交阶段是否已登记心跳续租：仅 PROCESSING 抢占成功后才登记，finally 据此决定是否移出心跳集合。
        boolean heartbeatActivated = false;
        try
        {
            if (assetExtractService.isTaskCancelled(taskId))
            {
                if (updateTaskCancelled(taskId)) { sseManager.sendCancelled(taskId, "用户取消"); }
                releaseBatchLocksAndSlots(batch);
                return;
            }
            if (!updateTaskStatus(taskId, TASK_STATUS_PROCESSING, null, TASK_STATUS_PENDING))
            {
                log.warn("分镜批量出图任务已被其他线程处理, 跳过: taskId={}", taskId);
                return; // 不释放：另一线程持有
            }
            // 同步提交阶段登记心跳常驻续租：Agnes 等同步直出单张可能耗时 > 租约 TTL(90s)，
            // 若只在每镜起点续租一次，长耗时同步生成期间租约会过期被僵尸回收误杀（现象：SSE "服务重启中断"
            // 但图片实际已生成）。转异步在途后于 finally 移出心跳，改由 media 轮询续租，避免名额泄漏。
            assetExtractService.markTaskProcessing(taskId);
            heartbeatActivated = true;
            if (assetExtractService.isTaskCancelled(taskId))
            {
                if (updateTaskCancelled(taskId)) { sseManager.sendCancelled(taskId, "用户取消"); }
                releaseBatchLocksAndSlots(batch);
                return;
            }

            int asyncPending = 0;
            boolean cancelledMid = false;
            outer:
            for (ImageGenJob shot : batch.shots)
            {
                renewLockIfMine(shot.lockKey, shot.lockToken);
                try { assetExtractService.touchTaskProcessing(taskId); }
                catch (Exception ignore) { /* 续租失败不阻断 */ }
                for (int idx = 0; idx < shot.takeSlots.size(); idx++)
                {
                    int slot = shot.takeSlots.get(idx);
                    if (assetExtractService.isTaskCancelled(taskId)) { cancelledMid = true; break outer; }
                    long bizSeq = Math.addExact(shot.bizSeqBase, slot);
                    String genParamsJson = buildShotGenParamsJson(shot.taskId, shot.storyboard.getId(),
                            shot.modelCode, shot.agentCode, shot.aspectRatio, shot.size, shot.imageCount,
                            shot.scenario, shot.negativePrompt, shot.userInputText, shot.userImagePromptInput,
                            slot, bizSeq, shot.referenceManifestJson);
                    try
                    {
                        String reused = reconcileExistingMediaUrl(bizSeq);
                        if (StrUtil.isNotBlank(reused))
                        {
                            persistGenRecordIdempotent(shot, bizSeq, reused, genParamsJson);
                            continue;
                        }
                        MediaTaskResponse resp = submitSingleImageMedia(shot, bizSeq, genParamsJson);
                        String st = Objects.isNull(resp) ? null : resp.getStatus();
                        if (TASK_STATUS_SUCCEEDED.equals(st))
                        {
                            if (Objects.nonNull(resp) && StrUtil.isNotBlank(resp.getOssUrl()))
                            {
                                // DIRECT 同步直出：内联幂等落库。落库失败不计失败——media 已成功且会发终态事件，
                                // 由事件监听幂等兜底落库；若此处误计 fail 会与事件成功落库形成「同一 take 既成功又失败」双计数。
                                try { persistGenRecordIdempotent(shot, bizSeq, resp.getOssUrl(), genParamsJson); }
                                catch (Exception pe) { log.error("分镜批量出图DIRECT内联落库失败,交由事件兜底: taskId={}, bizSeq={}", taskId, bizSeq, pe); }
                            }
                            else
                            {
                                // 成功但 OSS 未就绪 → 等 OSS 持久化事件扇入收尾，不可计失败（已计费）
                                asyncPending++;
                            }
                        }
                        else if (Objects.nonNull(st) && IMAGE_IN_PROGRESS_STATUSES.contains(st))
                        {
                            asyncPending++; // 异步在途：等事件扇入
                        }
                        else
                        {
                            log.error("分镜批量出图提交即终态非成功: taskId={}, storyboardId={}, status={}",
                                    taskId, shot.storyboard.getId(), st);
                            if (Objects.nonNull(resp) && Objects.nonNull(resp.getTaskId()))
                            {
                                // 统一走媒体终态消费入口，失败计数完成后同步清理已无用途的分镜上下文。
                                onMediaImageTaskTerminal(resp.getTaskId(), false, null);
                            }
                            else
                            {
                                fanInSupport.incrFail(taskId);
                            }
                        }
                    }
                    catch (ShotInFlightException inflight)
                    {
                        asyncPending++; // 在飞/待补偿：视为异步在途
                    }
                    catch (Exception perItemEx)
                    {
                        log.error("分镜批量出图单张提交失败: taskId={}, storyboardId={}, take={}, err={}",
                                taskId, shot.storyboard.getId(), slot + 1, perItemEx.getMessage());
                        fanInSupport.incrFail(taskId);
                    }
                }
            }

            if (cancelledMid || assetExtractService.isTaskCancelled(taskId))
            {
                if (cancelImageBatchAtCheckpoint(taskId))
                {
                    releaseBatchLocksAndSlots(batch);
                }
                return;
            }
            if (asyncPending > 0)
            {
                // 有异步在途：保持 PROCESSING，锁/名额不在此释放，由事件扇入 finalizeImageBatchIfDone 收尾；
                // 父任务租约在异步在途期间由 media 调度中心轮询子任务时续租（TaskDispatchServiceImpl 钩子）。
                // 转异步前再续一次租约，给 media 轮询接管留出完整 TTL 窗口。
                assetExtractService.touchTaskProcessing(taskId);
                log.info("分镜批量出图已提交,转异步等待扇入: taskId={}, asyncPending={}", taskId, asyncPending);
                return;
            }
            // 全部同步完成/复用/失败 → 立即收尾（finalize 内部释放锁/名额）
            finalizeImageBatchIfDone(taskId);
        }
        catch (Exception e)
        {
            log.error("分镜批量出图提交阶段失败: taskId={}", taskId, e);
            com.aid.common.error.TaskErrorResult errorResult = com.aid.common.error.ErrorNormalizer.normalize(e);
            if (updateTaskFailed(taskId, errorResult)) { sseManager.sendError(taskId, errorResult); }
            releaseBatchLocksAndSlots(batch);
        }
        finally
        {
            // 同步提交阶段结束：停止心跳续租（保留租约 key）。
            // 转异步→交 media 轮询续租接管；同步终态→租约已随收尾释放、剩余 TTL 自然过期，均不再需要心跳。
            if (heartbeatActivated)
            {
                assetExtractService.deactivateTaskProcessingHeartbeat(taskId);
            }
        }
    }

    /** 释放本批次镜头锁 + 取消标记 + 并发名额（取消/失败/提交异常路径用；正常异步路径由收尾释放）。 */
    private void releaseBatchLocksAndSlots(ImageBatchJob batch)
    {
        for (ShotLock l : batch.shotLocks) { releaseLockIfMine(l.key, l.token); }
        try { assetExtractService.clearCancelFlag(batch.taskId); } catch (Exception ignore) { /* ignore */ }
        try { assetExtractService.releaseTaskSlots(batch.taskId); } catch (Exception ignore) { /* ignore */ }
    }

    private boolean cancelImageBatchAtCheckpoint(Long taskId)
    {
        try
        {
            AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
            if (Objects.isNull(task) || !TASK_STATUS_PROCESSING.equals(task.getStatus()))
            {
                return false;
            }
            int total = task.getTotalCount() == null ? 0 : task.getTotalCount();
            List<Long> storyboardIds = fanInSupport.parseStoryboardIds(task.getInputSnapshot());
            if (total > 0 && CollectionUtil.isEmpty(storyboardIds))
            {
                log.error("分镜批量出图取消收尾缺少storyboardIds: taskId={}", taskId);
                return false;
            }
            List<AidGenRecord> succ = loadSucceededRecordsByParentTask(taskId, storyboardIds);
            int successCount = succ.size();
            int failCount = fanInSupport.getFailCount(taskId);
            int pendingCount = Math.max(total - successCount - failCount, 0);
            Map<String, Object> resultMap = buildFinalizeResultMap(task, succ, total, successCount, failCount);
            resultMap.put("pendingCount", pendingCount);
            String resultJson = null;
            try { resultJson = OBJECT_MAPPER.writeValueAsString(resultMap); }
            catch (Exception e) { log.warn("分镜批量出图取消结果序列化失败: taskId={}", taskId, e); }
            boolean updated = updateTaskCancelledWithResult(taskId, resultJson);
            if (updated)
            {
                try { sseManager.sendCancelled(taskId, "用户取消"); }
                catch (Exception e) { log.warn("分镜批量出图取消SSE发送失败: taskId={}", taskId, e); }
                try { fanInSupport.cleanup(taskId); }
                catch (Exception e) { log.warn("分镜批量出图取消清理扇入状态失败: taskId={}", taskId, e); }
                log.info("分镜批量出图在安全检查点取消: taskId={}, total={}, success={}, fail={}, pending={}",
                        taskId, total, successCount, failCount, pendingCount);
            }
            return updated;
        }
        catch (Exception e)
        {
            log.error("分镜批量出图取消收尾失败，保留PROCESSING等待租约回收: taskId={}", taskId, e);
            return false;
        }
    }
    @Override
    public StoryboardImageGenerateVO resumeImage(Long taskId, Long userId)
    {
        validateUserId(userId);
        if (Objects.isNull(taskId) || taskId <= 0)
        {
            log.error("分镜批量出图续生入参无效: taskId={}", taskId);
            throw new ServiceException("参数错误");
        }
        AidExtractTask task = extractTaskService.getById(taskId);
        if (Objects.isNull(task) || !DEL_FLAG_NORMAL.equals(task.getDelFlag()))
        {
            throw new ServiceException("任务不存在");
        }
        if (!Objects.equals(userId, task.getUserId()))
        {
            log.error("分镜批量出图续生归属校验失败: taskId={}, owner={}, req={}", taskId, task.getUserId(), userId);
            throw new ServiceException("无权访问");
        }
        if (!TASK_TYPE_STORYBOARD_IMAGE_GENERATE.equals(task.getTaskType()))
        {
            throw new ServiceException("类型不支持");
        }
        if (!TASK_STATUS_PARTIAL_FAILED.equals(task.getStatus())
                && !TASK_STATUS_FAILED.equals(task.getStatus())
                && !TASK_STATUS_CANCELLED.equals(task.getStatus()))
        {
            log.info("分镜批量出图续生拒绝：状态不可续: taskId={}, status={}", taskId, task.getStatus());
            throw new ServiceException("状态不支持");
        }
        String originalStatus = task.getStatus();
        String originalInputSnapshot = task.getInputSnapshot();

        JsonNode input;
        try
        {
            input = OBJECT_MAPPER.readTree(StrUtil.blankToDefault(task.getInputSnapshot(), "{}"));
        }
        catch (Exception e)
        {
            log.error("分镜批量出图续生 inputSnapshot 解析失败: taskId={}", taskId, e);
            throw new ServiceException("任务数据异常");
        }
        String agentCode = input.path("agentCode").asText(DEFAULT_AGENT_CODE);
        String modelCode = input.path("modelCode").asText(null);
        String aspectRatio = input.hasNonNull("aspectRatio") ? input.get("aspectRatio").asText() : null;
        String size = input.hasNonNull("size") ? input.get("size").asText() : null;
        String scenario = input.hasNonNull("scenario") ? input.get("scenario").asText() : null;
        String negativePrompt = input.hasNonNull("negativePrompt") ? input.get("negativePrompt").asText() : null;
        String userInputText = input.hasNonNull("userInputText") ? input.get("userInputText").asText() : null;
        int perShotCount = input.path("countPerShot").asInt(1);
        int priorRunNo = input.path("runNo").asInt(0);
        if (priorRunNo >= MAX_RUN_NO)
        {
            log.warn("分镜批量出图续生次数超限(编码位耗尽): taskId={}, priorRunNo={}", taskId, priorRunNo);
            throw new ServiceException("重试次数超限");
        }
        int newRunNo = priorRunNo + 1;
        Integer originalTotalCount = task.getTotalCount();

        // 镜头全集：以稳定的 allShots 为准，老快照回退 shots
        java.util.LinkedHashMap<Long, Integer> origTakeByShot = new java.util.LinkedHashMap<>();
        JsonNode allShotsNode = input.path("allShots");
        if (!allShotsNode.isArray() || allShotsNode.isEmpty())
        {
            allShotsNode = input.path("shots");
        }
        if (allShotsNode.isArray())
        {
            for (JsonNode an : allShotsNode)
            {
                long sid = an.path("storyboardId").asLong(0);
                int tc = an.path("takeCount").asInt(0);
                if (sid > 0 && tc > 0) { origTakeByShot.put(sid, tc); }
            }
        }
        if (origTakeByShot.isEmpty())
        {
            log.error("分镜批量出图续生全集为空(快照缺 allShots/shots): taskId={}", taskId);
            throw new ServiceException("任务数据异常");
        }
        int originalTotalShots = origTakeByShot.size();

        // 已成功进度以 aid_gen_record（durable 真源）为准：按 bizSeq 编码段反查 + 解析 takeIndex 槽位
        List<Long> seedRecordIds = new ArrayList<>();
        List<Map<String, Object>> seedItems = new ArrayList<>();
        List<Map<String, Object>> seedShotResults = new ArrayList<>();
        Map<Long, Map<String, Object>> seedShotPrior = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> priorShotFull = new LinkedHashMap<>();
        Map<Long, Integer> remainByShot = new LinkedHashMap<>();
        Map<Long, List<Long>> recordsByShot = new LinkedHashMap<>();
        Map<Long, TreeSet<Integer>> succeededSlotsByShot = new LinkedHashMap<>();
        List<AidGenRecord> doneRecords = loadSucceededRecordsByParentTask(taskId, origTakeByShot.keySet());
        for (AidGenRecord rec : doneRecords)
        {
            Long sid = rec.getStoryboardId();
            if (Objects.isNull(sid)) { continue; }
            int takeCount = origTakeByShot.getOrDefault(sid, 0);
            recordsByShot.computeIfAbsent(sid, k -> new ArrayList<>()).add(rec.getId());
            seedRecordIds.add(rec.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("storyboardId", sid);
            m.put("recordId", rec.getId());
            if (StrUtil.isNotBlank(rec.getFileUrl())) { m.put("imageUrl", rec.getFileUrl()); }
            seedItems.add(m);
            TreeSet<Integer> slots = succeededSlotsByShot.computeIfAbsent(sid, k -> new TreeSet<>());
            Integer slot = parseTakeIndexFromGenParams(rec.getGenParams());
            if (slot == null || slot < 0 || slot >= takeCount || slots.contains(slot))
            {
                slot = null;
                for (int j = 0; j < takeCount; j++) { if (!slots.contains(j)) { slot = j; break; } }
            }
            if (slot != null && slot >= 0 && slot < takeCount) { slots.add(slot); }
        }

        Map<Long, Integer> shotOrdinalById = new LinkedHashMap<>();
        int ordSeq = 0;
        for (Long sid : origTakeByShot.keySet()) { shotOrdinalById.put(sid, ordSeq++); }
        Map<Long, List<Integer>> missingSlotsByShot = new LinkedHashMap<>();

        for (Map.Entry<Long, Integer> en : origTakeByShot.entrySet())
        {
            Long sid = en.getKey();
            int takeCount = en.getValue();
            TreeSet<Integer> succeeded = succeededSlotsByShot.getOrDefault(sid, new TreeSet<>());
            List<Integer> missing = new ArrayList<>();
            for (int j = 0; j < takeCount; j++) { if (!succeeded.contains(j)) { missing.add(j); } }
            int successCount = takeCount - missing.size();
            List<Long> records = recordsByShot.getOrDefault(sid, java.util.Collections.emptyList());
            if (missing.isEmpty())
            {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("storyboardId", sid);
                m.put("takeCount", takeCount);
                m.put("successCount", successCount);
                m.put("recordIds", new ArrayList<>(records));
                seedShotResults.add(m);
                continue;
            }
            remainByShot.put(sid, missing.size());
            missingSlotsByShot.put(sid, missing);
            Map<String, Object> full = new LinkedHashMap<>();
            full.put("storyboardId", sid);
            full.put("takeCount", takeCount);
            full.put("successCount", successCount);
            full.put("recordIds", new ArrayList<>(records));
            priorShotFull.put(sid, full);
            if (successCount > 0)
            {
                Map<String, Object> prior = new LinkedHashMap<>();
                prior.put("takeCount", takeCount);
                prior.put("successCount", successCount);
                prior.put("recordIds", new ArrayList<>(records));
                seedShotPrior.put(sid, prior);
            }
        }
        int seedSuccessCount = seedRecordIds.size();

        if (remainByShot.isEmpty())
        {
            log.info("分镜批量出图续生无待补镜头: taskId={}", taskId);
            throw new ServiceException("无可续生项");
        }
        if (StrUtil.isBlank(modelCode))
        {
            throw new ServiceException("任务数据异常");
        }

        // 重新解析智能体 + 模型（沿用原 agentCode / modelCode，强校验池）
        AidAgent agent = loadAndAssertAgent(agentCode);
        AiModelConfigVo modelConfig = resolveModelConfig(modelCode, agent);
        String resolvedModelCode = modelConfig.getModelCode();
        Long modelId = modelConfig.getId();

        final String userInputF = userInputText;
        List<PreparedShot> prepared = new ArrayList<>();
        List<ShotLock> heldLocks = new ArrayList<>();
        boolean forcePartial = false;
        Long projectId = null;
        Long episodeId = null;
        try
        {
            for (Map.Entry<Long, Integer> en : remainByShot.entrySet())
            {
                Long id = en.getKey();
                int remain = en.getValue();
                String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_IMAGE_GENERATE + ":" + id;
                ShotLock heldThis = null;
                try
                {
                    AidStoryboard sb = loadAndCheckStoryboard(id, userId);
                    String token = acquireShotLock(lockKey, id);
                    if (token == null)
                    {
                        log.info("分镜批量出图续生跳过被占用镜头(保持待补): storyboardId={}", id);
                        forcePartial = true;
                        Map<String, Object> kept = priorShotFull.get(id);
                        if (kept != null) { seedShotResults.add(kept); }
                        continue;
                    }
                    heldThis = new ShotLock(lockKey, token);
                    heldLocks.add(heldThis);
                    // 续生统一走库内 image_prompt（single=false），用户补充沿用快照
                    PreparedShot ps = prepareShot(sb, false, null, userInputF, userId, remain, modelConfig);
                    prepared.add(ps);
                    if (projectId == null) { projectId = sb.getProjectId(); episodeId = sb.getEpisodeId(); }
                }
                catch (RuntimeException e)
                {
                    if (heldThis != null) { releaseLockIfMine(heldThis.key, heldThis.token); heldLocks.remove(heldThis); }
                    log.info("分镜批量出图续生跳过镜头(保持待补): storyboardId={}, reason={}", id, e.getMessage());
                    forcePartial = true;
                    Map<String, Object> kept = priorShotFull.get(id);
                    if (kept != null) { seedShotResults.add(kept); }
                }
            }
            if (prepared.isEmpty())
            {
                for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
                throw new ServiceException("无可续生项");
            }
            int newSubtasks = prepared.stream().mapToInt(p -> p.takeCount).sum();

            Map<String, Object> newSnap = new LinkedHashMap<>();
            List<Long> resumeIds = new ArrayList<>();
            List<Map<String, Object>> resumeShots = new ArrayList<>();
            for (int i = 0; i < prepared.size(); i++)
            {
                PreparedShot ps = prepared.get(i);
                resumeIds.add(ps.storyboard.getId());
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("storyboardId", ps.storyboard.getId());
                s.put("takeCount", ps.takeCount);
                s.put("lockToken", heldLocks.get(i).token);
                resumeShots.add(s);
            }
            newSnap.put("storyboardIds", resumeIds);
            newSnap.put("agentCode", agentCode);
            newSnap.put("modelCode", resolvedModelCode);
            newSnap.put("aspectRatio", aspectRatio);
            newSnap.put("size", size);
            newSnap.put("scenario", scenario);
            newSnap.put("negativePrompt", negativePrompt);
            newSnap.put("userInputText", userInputText);
            newSnap.put("countPerShot", perShotCount);
            newSnap.put("shots", resumeShots);
            List<Map<String, Object>> allShotsOut = new ArrayList<>();
            for (Map.Entry<Long, Integer> e : origTakeByShot.entrySet())
            {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("storyboardId", e.getKey());
                a.put("takeCount", e.getValue());
                allShotsOut.add(a);
            }
            newSnap.put("allShots", allShotsOut);
            newSnap.put("runNo", newRunNo);
            newSnap.put("priorTotalCount", originalTotalCount);
            String newSnapJson;
            try
            {
                newSnapJson = OBJECT_MAPPER.writeValueAsString(newSnap);
            }
            catch (Exception e)
            {
                for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
                log.error("分镜批量出图续生快照序列化失败: taskId={}", taskId, e);
                throw new ServiceException("提交失败，请重试");
            }

            LambdaUpdateWrapper<AidExtractTask> toPending = Wrappers.lambdaUpdate();
            toPending.eq(AidExtractTask::getId, taskId);
            toPending.in(AidExtractTask::getStatus,
                    TASK_STATUS_PARTIAL_FAILED, TASK_STATUS_FAILED, TASK_STATUS_CANCELLED);
            toPending.set(AidExtractTask::getStatus, TASK_STATUS_PENDING);
            toPending.set(AidExtractTask::getTotalCount, seedSuccessCount + newSubtasks);
            toPending.set(AidExtractTask::getInputSnapshot, newSnapJson);
            toPending.set(AidExtractTask::getErrorMessage, null);
            toPending.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            int casRows;
            try
            {
                casRows = extractTaskService.getBaseMapper().update(null, toPending);
            }
            catch (RuntimeException ex)
            {
                for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
                log.error("分镜批量出图续生状态CAS异常: taskId={}", taskId, ex);
                throw new ServiceException("提交失败，请重试");
            }
            if (casRows == 0)
            {
                for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
                log.warn("分镜批量出图续生 CAS 失败（状态已变）: taskId={}", taskId);
                throw new ServiceException("状态不支持");
            }

            boolean enqueued;
            try
            {
                try { assetExtractService.clearCancelFlag(taskId); } catch (Exception ignore) { /* ignore */ }
                enqueued = buildJobsAndEnqueue(taskId, projectId, episodeId, userId, resolvedModelCode, modelId,
                        modelConfig, agentCode, aspectRatio, size, scenario, negativePrompt, userInputF, perShotCount, prepared,
                        heldLocks, newSubtasks, originalTotalShots, seedSuccessCount, seedRecordIds, seedItems,
                        seedShotResults, seedShotPrior, newRunNo, forcePartial, shotOrdinalById, missingSlotsByShot);
            }
            catch (RuntimeException ex)
            {
                log.error("分镜批量出图续生入队异常，回滚原状态: taskId={}", taskId, ex);
                releaseLocksAndRollbackResume(taskId, heldLocks, originalTotalCount, originalStatus, originalInputSnapshot);
                throw new ServiceException("提交失败，请重试");
            }
            if (!enqueued)
            {
                releaseLocksAndRollbackResume(taskId, heldLocks, originalTotalCount, originalStatus, originalInputSnapshot);
                throw new ServiceException("提交失败，请重试");
            }

            StoryboardImageGenerateVO vo = new StoryboardImageGenerateVO();
            vo.setTaskId(taskId);
            vo.setStatus(TASK_STATUS_PENDING);
            vo.setModelName(resolvedModelCode);
            vo.setTotalShots(prepared.size());
            vo.setCountPerShot(perShotCount);
            vo.setTotalSubtasks(newSubtasks);
            List<StoryboardImageGenerateVO.ShotResult> voItems = new ArrayList<>();
            for (PreparedShot ps : prepared)
            {
                voItems.add(new StoryboardImageGenerateVO.ShotResult(ps.storyboard.getId(), true, null));
            }
            vo.setItems(voItems);
            log.info("分镜批量出图续生提交: taskId={}, resumeShots={}, newSubtasks={}, seedSuccess={}",
                    taskId, prepared.size(), newSubtasks, seedSuccessCount);
            return vo;
        }
        catch (RuntimeException e)
        {
            log.error("分镜批量出图续生受理失败: taskId={}", taskId, e);
            throw e;
        }
    }
    /**
     * Durable 复用 / 阻断决策：按确定性 bizTaskId 反查本逻辑槽位的既有媒体任务（窗口无关）。
     *
     *   - SUCCEEDED + ossUrl 就绪 → 返回 ossUrl 复用（跳过生成/扣费）；
     *   - SUCCEEDED 但 ossUrl 暂空 / 在飞 → fail-closed 抛错阻断不新建（槽位保持可续）；
     *   - 仅 FAILED / 无记录 → 返回 null，允许重新生成。
     *
     */
    private String reconcileExistingMediaUrl(long bizTaskId)
    {
        List<AidMediaTask> tasks;
        try
        {
            tasks = aidMediaTaskMapper.selectList(
                    Wrappers.<AidMediaTask>lambdaQuery()
                            .select(AidMediaTask::getId, AidMediaTask::getStatus, AidMediaTask::getOssUrl)
                            .eq(AidMediaTask::getBizTaskType, BIZ_TASK_TYPE)
                            .eq(AidMediaTask::getBizTaskId, bizTaskId));
        }
        catch (Exception e)
        {
            log.error("分镜批量出图 durable 复用查询失败(防重复扣费, fail-closed): bizTaskId={}", bizTaskId, e);
            throw new ServiceException("查询失败");
        }
        if (CollectionUtil.isEmpty(tasks))
        {
            return null;
        }
        boolean blockRegenerate = false;
        for (AidMediaTask t : tasks)
        {
            String st = t.getStatus();
            if (TASK_STATUS_SUCCEEDED.equals(st))
            {
                if (StrUtil.isNotBlank(t.getOssUrl())) { return t.getOssUrl(); }
                blockRegenerate = true;
            }
            else if (!TASK_STATUS_FAILED.equals(st))
            {
                blockRegenerate = true;
            }
        }
        if (blockRegenerate)
        {
            log.info("分镜批量出图同槽位存在在飞/待补偿媒体任务，阻断新建(保持可续，不计失败): bizTaskId={}", bizTaskId);
            throw new ShotInFlightException();
        }
        return null;
    }

    /**
     * 非阻塞提交单张图到 media 主链路：注入扇入上下文(sbzImageGenCtx)到 options，不轮询，直接返回响应。
     * 计费 / 排队 / 退款全部由媒体主链路处理。
     * DIRECT 同步直出 → 响应即终态(SUCCEEDED+ossUrl)，调用方内联落库；异步 → 响应为中间态，
     * 由 media 调度中心轮询到终态后发事件,{@code StoryboardImageGenEventListener} 扇入收尾。
     */
    private MediaTaskResponse submitSingleImageMedia(ImageGenJob job, long bizSeq, String genParamsJson)
    {
        AidStoryboard storyboard = job.storyboard;

        MediaImageGenerateRequest imageRequest = new MediaImageGenerateRequest();
        imageRequest.setPrompt(job.finalPrompt);
        imageRequest.setModelName(job.modelCode);
        // 关键：异步线程内 SecurityContext 丢失，必须显式透传 userId 否则计费跳过
        imageRequest.setUserId(job.userId);
        imageRequest.setProjectId(storyboard.getProjectId());
        imageRequest.setEpisodeId(storyboard.getEpisodeId());
        imageRequest.setBizTaskType(BIZ_TASK_TYPE);
        // bizTaskId 确定性编码：续生重跑同槽位 → 同 bizTaskId → 媒体层幂等复用；同时是扇入反解父任务/落库幂等的依据
        imageRequest.setBizTaskId(bizSeq);

        if (StrUtil.isNotBlank(job.negativePrompt))
        {
            imageRequest.setNegativePrompt(job.negativePrompt);
        }
        if (StrUtil.isNotBlank(job.size))
        {
            imageRequest.setSize(job.size);
        }
        imageRequest.setExpectedImageCount(1);

        Map<String, Object> options = new HashMap<>();
        if (CollectionUtil.isNotEmpty(job.referenceImages))
        {
            if (job.referenceImages.size() == 1)
            {
                imageRequest.setReferenceImageUrl(job.referenceImages.get(0));
            }
            options.put("referenceImages", new ArrayList<>(job.referenceImages));
        }
        if (StrUtil.isNotBlank(job.referenceManifestJson))
        {
            options.put("referenceManifest", job.referenceManifestJson);
        }
        if (StrUtil.isNotBlank(job.aspectRatio))
        {
            options.put("aspect_ratio", job.aspectRatio);
        }
        options.put("force_single", true);
        // 扇入上下文：随 request_json 落库，事件回调时由 listener 反读重建 gen_record（厂商 Provider 只取已知键，不下发此键）
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("storyboardId", storyboard.getId());
        ctx.put("modelId", job.modelId);
        ctx.put("finalPrompt", job.finalPrompt);
        ctx.put("userInputText", StrUtil.blankToDefault(job.userImagePromptInput, job.rawImagePrompt));
        ctx.put("genParams", genParamsJson);
        ctx.put("bizSeq", bizSeq);
        options.put(OPT_KEY_CTX, ctx);
        imageRequest.setOptions(options);

        AgentModelDefault agentModel = new AgentModelDefault(job.modelCode);
        agentDefaultParamsApplier.applyToImage(agentModel, imageRequest, job.modelConfig);

        if (StrUtil.isNotBlank(job.scenario))
        {
            mediaGenerationService.applyImageScenarioOverrides(imageRequest, job.scenario);
        }

        return mediaGenerationService.generateImage(imageRequest);
    }

    /**
     * 同槽位存在「在飞 / 待补偿」媒体任务的信号异常（非失败）。
     * 由 {@link #reconcileExistingMediaUrl} 在 fail-closed 阻断重生时抛出，{@code runAsyncBatch} 单独捕获，
     * 把该 take 计为「待补/进行中」而非「失败」，任务进 PARTIAL_FAILED 保留续生入口，避免把"暂时在飞"误展示为"失败"。
     */
    private static final class ShotInFlightException extends RuntimeException
    {
        ShotInFlightException()
        {
            super("处理中");
        }
    }

    /** 异步执行所需的全部入参快照（不可变）。 */
    private static final class ImageGenJob
    {
        final Long taskId;
        final Long userId;
        final AidStoryboard storyboard;
        final String modelCode;
        final Long modelId;
        final AiModelConfigVo modelConfig;
        final String finalPrompt;
        final String rawImagePrompt;
        final List<String> referenceImages; // unmodifiable copy
        final String referenceManifestJson;
        final int imageCount;
        final String aspectRatio;
        final String size;
        final String scenario;
        final String negativePrompt;
        final String userInputText;
        final String userImagePromptInput;
        final String agentCode;
        /** 该镜头 bizTaskId 基址 = 父任务编码段 + 稳定镜头序号*1000（不含 take 槽位 / runNo） */
        final long bizSeqBase;
        /** 本轮要生成的逻辑 take 槽位（0 基）：fresh=[0..takeCount-1]，续生=缺失槽位 */
        final List<Integer> takeSlots;
        final String lockKey;
        final String lockToken;

        ImageGenJob(Long taskId, Long userId, AidStoryboard storyboard, String modelCode, Long modelId,
                    AiModelConfigVo modelConfig, String finalPrompt, String rawImagePrompt, List<String> referenceImages,
                    String referenceManifestJson, int imageCount, String aspectRatio, String size, String scenario,
                    String negativePrompt, String userInputText, String userImagePromptInput, String agentCode,
                    long bizSeqBase, List<Integer> takeSlots, String lockKey, String lockToken)
        {
            this.taskId = taskId;
            this.userId = userId;
            this.storyboard = storyboard;
            this.modelCode = modelCode;
            this.modelId = modelId;
            this.modelConfig = modelConfig;
            this.finalPrompt = finalPrompt;
            this.rawImagePrompt = rawImagePrompt;
            this.referenceImages = (referenceImages == null)
                    ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(referenceImages));
            this.referenceManifestJson = referenceManifestJson;
            this.imageCount = imageCount;
            this.aspectRatio = aspectRatio;
            this.size = size;
            this.scenario = scenario;
            this.negativePrompt = negativePrompt;
            this.userInputText = userInputText;
            this.userImagePromptInput = userImagePromptInput;
            this.agentCode = agentCode;
            this.bizSeqBase = bizSeqBase;
            this.takeSlots = (takeSlots == null)
                    ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(takeSlots));
            this.lockKey = lockKey;
            this.lockToken = lockToken;
        }
    }

    /** 批量异步执行容器：一个父任务下的多镜头 Job 列表 + 续生携带的既有成功快照（不可变）。 */
    private static final class ImageBatchJob
    {
        final Long taskId;
        final Long userId;
        final String modelCode;
        final int countPerShot;
        final int totalShots;
        final int totalSubtasks;
        final int seedSuccessCount;
        final int runNo;
        final List<ImageGenJob> shots;
        final List<ShotLock> shotLocks;
        final List<Long> seedRecordIds;
        final List<Map<String, Object>> seedItems;
        final List<Map<String, Object>> seedShotResults;
        final Map<Long, Map<String, Object>> seedShotPrior;
        final boolean forcePartial;

        ImageBatchJob(Long taskId, Long userId, String modelCode, int countPerShot, int totalShots,
                      int totalSubtasks, int seedSuccessCount, int runNo, List<ImageGenJob> shots,
                      List<ShotLock> shotLocks, List<Long> seedRecordIds, List<Map<String, Object>> seedItems,
                      List<Map<String, Object>> seedShotResults, Map<Long, Map<String, Object>> seedShotPrior,
                      boolean forcePartial)
        {
            this.taskId = taskId;
            this.userId = userId;
            this.modelCode = modelCode;
            this.countPerShot = countPerShot;
            this.totalShots = totalShots;
            this.totalSubtasks = totalSubtasks;
            this.seedSuccessCount = seedSuccessCount;
            this.runNo = runNo;
            this.shots = (shots == null) ? java.util.Collections.emptyList() : shots;
            this.shotLocks = (shotLocks == null) ? new ArrayList<>() : shotLocks;
            this.seedRecordIds = (seedRecordIds == null) ? new ArrayList<>() : seedRecordIds;
            this.seedItems = (seedItems == null) ? new ArrayList<>() : seedItems;
            this.seedShotResults = (seedShotResults == null) ? new ArrayList<>() : seedShotResults;
            this.seedShotPrior = (seedShotPrior == null) ? java.util.Collections.emptyMap() : seedShotPrior;
            this.forcePartial = forcePartial;
        }
    }

    /** 落地一条 {@code aid_gen_record}（gen_type=image, status=1, file_url=ossUrl, del_flag=0）。 */
    private Long persistGenRecord(ImageGenJob job, long bizSeq, String imageUrl, String genParamsJson)
    {
        AidStoryboard storyboard = job.storyboard;
        AidGenRecord record = new AidGenRecord();
        record.setUserId(job.userId);
        record.setProjectId(storyboard.getProjectId());
        record.setEpisodeId(storyboard.getEpisodeId());
        record.setStoryboardId(storyboard.getId());
        record.setGenType(GEN_TYPE_IMAGE);
        record.setModelId(job.modelId);
        record.setPromptText(job.finalPrompt);
        record.setUserInputText(StrUtil.blankToDefault(job.userImagePromptInput, job.rawImagePrompt));
        record.setFileUrl(imageUrl);
        record.setGenParams(genParamsJson);
        record.setBizSeq(bizSeq); // 幂等唯一键：由唯一索引 uk_gen_record_biz_seq 兜底防重复落库
        record.setStatus(1); // 1=成功
        record.setIsSelected(1);
        record.setDelFlag(DEL_FLAG_NORMAL);
        record.setCreateTime(DateUtils.getNowDate());
        record.setCreateBy(String.valueOf(job.userId));
        try
        {
            aidGenRecordService.save(record);
        }
        catch (DuplicateKeyException dup)
        {
            // DIRECT 内联与事件扇入并发竞态：唯一键冲突说明同一 take 已落库，幂等忽略
            log.info("分镜图 gen_record 已存在(biz_seq 唯一键冲突,幂等忽略): bizSeq={}, storyboardId={}",
                    bizSeq, storyboard.getId());
            markExistingImageRecordAsFinal(bizSeq, job.userId);
            return null;
        }
        markStoryboardFinalImage(storyboard.getId(), record.getId(), job.userId);
        return record.getId();
    }
    /** 注入 aid_media_task.request_json.options 的单一命名空间上下文键（厂商 Provider 只取已知键，不会下发上游）。 */
    private static final String OPT_KEY_CTX = "sbzImageGenCtx";

    /** 通用扇入支撑（失败计数 / 收尾CAS / bizSeq反解 / 快照解析），与出视频共用，避免重复实现。 */
    @Autowired
    private MediaGenFanInSupport fanInSupport;

    /** 幂等落 gen_record：先按 bizSeq 查重，已存在则跳过（事件重投 / DIRECT 与事件竞态双保险）。 */
    private void persistGenRecordIdempotent(ImageGenJob shot, long bizSeq, String imageUrl, String genParamsJson)
    {
        AidGenRecord existing = loadGenRecordByBizSeq(bizSeq);
        if (Objects.nonNull(existing))
        {
            Long recordUserId = Objects.nonNull(existing.getUserId()) ? existing.getUserId() : shot.userId;
            markStoryboardFinalImage(existing.getStoryboardId(), existing.getId(), recordUserId);
            return;
        }
        persistGenRecord(shot, bizSeq, imageUrl, genParamsJson);
    }

    /** 按 biz_seq 唯一键加载该 take 已落库记录（与唯一索引 uk_gen_record_biz_seq 语义一致）。 */
    private AidGenRecord loadGenRecordByBizSeq(long bizSeq)
    {
        try
        {
            return aidGenRecordService.getOne(
                    Wrappers.<AidGenRecord>lambdaQuery()
                            .select(AidGenRecord::getId, AidGenRecord::getStoryboardId, AidGenRecord::getUserId)
                            .eq(AidGenRecord::getBizSeq, bizSeq)
                            .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL)
                            .last("limit 1"), false);
        }
        catch (Exception e)
        {
            log.warn("分镜图 bizSeq 查重异常(按不存在处理): bizSeq={}", bizSeq, e);
            return null;
        }
    }

    private void markExistingImageRecordAsFinal(long bizSeq, Long fallbackUserId)
    {
        AidGenRecord existing = loadGenRecordByBizSeq(bizSeq);
        if (Objects.isNull(existing))
        {
            return;
        }
        Long userId = Objects.nonNull(existing.getUserId()) ? existing.getUserId() : fallbackUserId;
        markStoryboardFinalImage(existing.getStoryboardId(), existing.getId(), userId);
    }

    /**
     * 媒体任务终态回调（由 {@code StoryboardImageGenEventListener} 调用）：
     * 成功则幂等落 gen_record，失败则计数，随后尝试收尾。
     */
    @Override
    public void onMediaImageTaskTerminal(Long mediaTaskId, boolean success, String ossUrl)
    {
        AidMediaTask mt = aidMediaTaskMapper.selectById(mediaTaskId);
        if (Objects.isNull(mt) || Objects.isNull(mt.getBizTaskId()))
        {
            return;
        }
        long bizSeq = mt.getBizTaskId();
        Long parentTaskId = fanInSupport.decodeParentTaskId(bizSeq);
        boolean contextConsumed = false;
        try
        {
            if (success && StrUtil.isNotBlank(ossUrl))
            {
                contextConsumed = persistGenRecordFromCtx(mt, bizSeq, ossUrl);
            }
            else if (!success)
            {
                fanInSupport.incrFail(parentTaskId);
                contextConsumed = true;
            }
        }
        catch (Exception e)
        {
            log.error("分镜图事件落库异常: mediaTaskId={}, bizSeq={}", mediaTaskId, bizSeq, e);
            // 落库异常按失败计数，避免父任务永远等不齐
            fanInSupport.incrFail(parentTaskId);
        }
        finalizeImageBatchIfDone(parentTaskId);
        if (contextConsumed)
        {
            mediaTaskArchiveService.removeConsumedFanInContext(mediaTaskId, OPT_KEY_CTX);
        }
    }

    /** 从 aid_media_task.request_json.options.sbzImageGenCtx 反序列化上下文，幂等落 gen_record。 */
    private boolean persistGenRecordFromCtx(AidMediaTask mt, long bizSeq, String ossUrl)
    {
        AidGenRecord existing = loadGenRecordByBizSeq(bizSeq);
        if (Objects.nonNull(existing))
        {
            Long recordUserId = Objects.nonNull(existing.getUserId()) ? existing.getUserId() : mt.getUserId();
            markStoryboardFinalImage(existing.getStoryboardId(), existing.getId(), recordUserId);
            return true; // 幂等：已落库时不再依赖 request_json 上下文，兼容上下文压缩后的重复事件
        }
        Map<String, Object> ctx = extractCtxFromMediaTask(mt);
        if (CollectionUtil.isEmpty(ctx))
        {
            log.error("分镜图事件落库缺少上下文(跳过,计失败防卡死): mediaTaskId={}, bizSeq={}", mt.getId(), bizSeq);
            fanInSupport.incrFail(fanInSupport.decodeParentTaskId(bizSeq));
            return false;
        }
        Long storyboardId = ctx.get("storyboardId") instanceof Number n ? n.longValue() : null;
        if (Objects.isNull(storyboardId))
        {
            // 上下文缺 storyboardId 无法落库：计失败防卡死，与上方"缺上下文"分支保持一致。
            // 否则该 take 既不计成功也不计失败，扇入永远凑不齐 total → 父任务永卡 PROCESSING（仅能靠僵尸对账兜底）。
            log.error("分镜图事件落库缺 storyboardId(跳过,计失败防卡死): mediaTaskId={}, bizSeq={}", mt.getId(), bizSeq);
            fanInSupport.incrFail(fanInSupport.decodeParentTaskId(bizSeq));
            return false;
        }
        AidGenRecord record = new AidGenRecord();
        record.setUserId(mt.getUserId());
        record.setProjectId(mt.getProjectId());
        record.setEpisodeId(mt.getEpisodeId());
        record.setStoryboardId(storyboardId);
        record.setGenType(GEN_TYPE_IMAGE);
        record.setModelId(ctx.get("modelId") instanceof Number n ? n.longValue() : null);
        record.setPromptText((String) ctx.get("finalPrompt"));
        record.setUserInputText((String) ctx.get("userInputText"));
        record.setFileUrl(ossUrl);
        record.setGenParams((String) ctx.get("genParams"));
        record.setBizSeq(bizSeq); // 幂等唯一键：由唯一索引 uk_gen_record_biz_seq 兜底防重复落库
        record.setStatus(1);
        record.setIsSelected(1);
        record.setDelFlag(DEL_FLAG_NORMAL);
        record.setCreateTime(DateUtils.getNowDate());
        record.setCreateBy(String.valueOf(mt.getUserId()));
        try
        {
            aidGenRecordService.save(record);
        }
        catch (DuplicateKeyException dup)
        {
            // DIRECT 内联与事件扇入并发竞态：唯一键冲突说明同一 take 已落库，幂等忽略
            log.info("分镜图事件落库 gen_record 已存在(biz_seq 唯一键冲突,幂等忽略): bizSeq={}, storyboardId={}",
                    bizSeq, storyboardId);
            markExistingImageRecordAsFinal(bizSeq, mt.getUserId());
            return true;
        }
        markStoryboardFinalImage(storyboardId, record.getId(), mt.getUserId());
        return true;
    }

    /** 自动生成成功后同步分镜主图字段；失败只记录日志，不影响已成功产物落库与扇入收尾。 */
    private void markStoryboardFinalImage(Long storyboardId, Long recordId, Long userId)
    {
        if (Objects.isNull(storyboardId) || Objects.isNull(recordId) || Objects.isNull(userId))
        {
            return;
        }
        try
        {
            LambdaUpdateWrapper<AidStoryboard> update = Wrappers.lambdaUpdate();
            update.eq(AidStoryboard::getId, storyboardId);
            update.eq(AidStoryboard::getUserId, userId);
            update.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
            update.set(AidStoryboard::getFinalImageId, recordId);
            update.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
            update.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
            boolean updated = aidStoryboardService.update(update);
            if (!updated)
            {
                log.warn("分镜图自动设为主图失败(分镜不存在或无权): storyboardId={}, recordId={}, userId={}",
                        storyboardId, recordId, userId);
            }
        }
        catch (Exception e)
        {
            log.warn("分镜图自动设为主图异常(不阻断): storyboardId={}, recordId={}", storyboardId, recordId, e);
        }
    }

    /** 解析 request_json → options.sbzImageGenCtx（Map）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractCtxFromMediaTask(AidMediaTask mt)
    {
        if (Objects.isNull(mt) || StrUtil.isBlank(mt.getRequestJson()))
        {
            return java.util.Collections.emptyMap();
        }
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(mt.getRequestJson());
            JsonNode opt = root.path("options").path(OPT_KEY_CTX);
            if (opt.isMissingNode() || opt.isNull())
            {
                return java.util.Collections.emptyMap();
            }
            return OBJECT_MAPPER.convertValue(opt, Map.class);
        }
        catch (Exception e)
        {
            log.warn("分镜图解析媒体任务上下文失败: mediaTaskId={}", mt.getId(), e);
            return java.util.Collections.emptyMap();
        }
    }

    /**
     * 扇入收尾（幂等）：当 成功 gen_record 数 + 失败计数 >= 总子任务数 时，CAS 一次性收尾——
     * 置终态(SUCCEEDED/PARTIAL_FAILED/FAILED) + SSE + 释放镜头锁 + 释放并发名额 + 清理扇入键。
     * 未到齐则直接返回，等待后续事件。
     */
    @Override
    public void finalizeImageBatchIfDone(Long taskId)
    {
        if (Objects.isNull(taskId))
        {
            return;
        }
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || !TASK_STATUS_PROCESSING.equals(task.getStatus()))
        {
            return; // 非 PROCESSING（已终态/取消）不收尾
        }
        int total = task.getTotalCount() == null ? 0 : task.getTotalCount();
        List<Long> storyboardIds = fanInSupport.parseStoryboardIds(task.getInputSnapshot());
        List<AidGenRecord> succ = loadSucceededRecordsByParentTask(taskId, storyboardIds);
        int successCount = succ.size();
        int failCount = fanInSupport.getFailCount(taskId);
        if (successCount + failCount < total)
        {
            return; // 未到齐
        }
        // CAS 收尾标记：只有一个线程/事件真正收尾
        if (!fanInSupport.tryWinFinalize(taskId))
        {
            return; // 别的事件已收尾
        }
        try
        {
            Map<String, Object> resultMap = buildFinalizeResultMap(task, succ, total, successCount, failCount);
            String resultJson;
            try { resultJson = OBJECT_MAPPER.writeValueAsString(resultMap); }
            catch (Exception e) { resultJson = null; }

            if (assetExtractService.isTaskCancelled(taskId))
            {
                if (updateTaskCancelledWithResult(taskId, resultJson))
                {
                    sseManager.sendCancelled(taskId, "用户取消");
                }
                log.info("分镜批量出图收尾检测到取消，结果已保留: taskId={}, total={}, success={}, fail={}",
                        taskId, total, successCount, failCount);
                return;
            }

            if (successCount == 0)
            {
                if (updateTaskFailed(taskId, "生成失败"))
                {
                    sseManager.sendError(taskId,
                            com.aid.common.error.ErrorNormalizer.normalize(new RuntimeException("生成失败")));
                }
            }
            else if (failCount > 0)
            {
                if (updateTaskPartialFailed(taskId, total, resultJson)) { sseManager.sendPartialFailed(taskId, resultMap, "部分完成"); }
            }
            else
            {
                if (updateTaskSuccess(taskId, total, resultJson)) { sseManager.sendComplete(taskId, resultMap); }
            }
            log.info("分镜批量出图收尾: taskId={}, total={}, success={}, fail={}", taskId, total, successCount, failCount);
        }
        finally
        {
            releaseShotLocksFromSnapshot(task);
            try { assetExtractService.clearCancelFlag(taskId); } catch (Exception ignore) { /* ignore */ }
            try { assetExtractService.releaseTaskSlots(taskId); } catch (Exception ignore) { /* ignore */ }
            fanInSupport.cleanup(taskId);
        }
    }

    /** 释放父任务 input_snapshot.shots[].lockToken 持有的镜头锁（CAS，复用通用快照解析）。 */
    private void releaseShotLocksFromSnapshot(AidExtractTask task)
    {
        if (Objects.isNull(task)) { return; }
        for (MediaGenFanInSupport.ShotLockRef ref : fanInSupport.parseShotLocks(task.getInputSnapshot()))
        {
            releaseLockIfMine(FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_IMAGE_GENERATE + ":" + ref.storyboardId, ref.lockToken);
        }
    }

    /** 收尾结果 resultData：按已成功 gen_record 聚合 items/shots，结构与同步版兼容。 */
    private Map<String, Object> buildFinalizeResultMap(AidExtractTask task, List<AidGenRecord> succ,
            int total, int successCount, int failCount)
    {
        List<Map<String, Object>> items = new ArrayList<>();
        List<Long> recordIds = new ArrayList<>();
        Map<Long, List<Long>> byShot = new LinkedHashMap<>();
        for (AidGenRecord r : succ)
        {
            recordIds.add(r.getId());
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("storyboardId", r.getStoryboardId());
            it.put("recordId", r.getId());
            it.put("imageUrl", r.getFileUrl());
            items.add(it);
            byShot.computeIfAbsent(r.getStoryboardId(), k -> new ArrayList<>()).add(r.getId());
        }
        List<Map<String, Object>> shots = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> e : byShot.entrySet())
        {
            Map<String, Object> sr = new LinkedHashMap<>();
            sr.put("storyboardId", e.getKey());
            sr.put("successCount", e.getValue().size());
            sr.put("recordIds", e.getValue());
            shots.add(sr);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("modelCode", task.getModelCode());
        map.put("totalSubtasks", total);
        map.put("successCount", successCount);
        map.put("failCount", failCount);
        map.put("recordIds", recordIds);
        map.put("items", items);
        map.put("shots", shots);
        return map;
    }

    /** 续生反查：按 bizSeq 父任务编码段 + storyboardId 取本父任务已成功落库的图片记录。 */
    private List<AidGenRecord> loadSucceededRecordsByParentTask(Long taskId, java.util.Collection<Long> storyboardIds)
    {
        if (Objects.isNull(taskId) || CollectionUtil.isEmpty(storyboardIds))
        {
            return new ArrayList<>();
        }
        try
        {
            long lowerBizSeq = Math.multiplyExact(taskId, MediaGenFanInSupport.BIZ_SEQ_PARENT_FACTOR);
            long upperBizSeq = Math.addExact(lowerBizSeq, MediaGenFanInSupport.BIZ_SEQ_PARENT_FACTOR);
            return aidGenRecordService.list(
                    Wrappers.<AidGenRecord>lambdaQuery()
                            .select(AidGenRecord::getId, AidGenRecord::getStoryboardId,
                                    AidGenRecord::getFileUrl, AidGenRecord::getGenParams, AidGenRecord::getCreateTime)
                            .in(AidGenRecord::getStoryboardId, storyboardIds)
                            .eq(AidGenRecord::getStatus, 1)
                            .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL)
                            .ge(AidGenRecord::getBizSeq, lowerBizSeq)
                            .lt(AidGenRecord::getBizSeq, upperBizSeq)
                            .orderByAsc(AidGenRecord::getBizSeq));
        }
        catch (Exception e)
        {
            log.error("分镜批量出图续生反查 aid_gen_record 失败(fail-closed): taskId={}", taskId, e);
            throw new ServiceException("任务数据异常");
        }
    }

    /** 从 aid_gen_record.gen_params 解析逻辑 take 槽位（takeIndex）；无法解析返回 null。 */
    private Integer parseTakeIndexFromGenParams(String genParams)
    {
        if (StrUtil.isBlank(genParams)) { return null; }
        try
        {
            JsonNode node = OBJECT_MAPPER.readTree(genParams);
            JsonNode ti = node.get("takeIndex");
            if (ti != null && ti.canConvertToInt()) { return ti.asInt(); }
            return null;
        }
        catch (Exception e)
        {
            log.warn("分镜批量出图续生解析 takeIndex 失败: {}", e.getMessage());
            return null;
        }
    }
    /**
     * DB 兜底：检查指定 storyboardId 是否真的有未结束（PENDING / QUEUED / PROCESSING）的 storyboard_image_generate 任务。
     * 父任务 input_snapshot 内 storyboardId 落在 {@code storyboardIds:[...]} 数组，无独立列。
     * 为避免对整串 inputSnapshot 做 LIKE 与 {@code countPerShot/takeCount/runNo} 等小数值误命中，
     * 这里先用 LIKE 粗筛候选（缩小集合），再解析 JSON 的 storyboardIds 数组做精确比对，杜绝假阳性拒绝合法请求。
     */
    private boolean hasActiveTaskInDb(Long storyboardId)
    {
        if (Objects.isNull(storyboardId))
        {
            return false;
        }
        // 粗筛：含该数字的活跃任务（仍可能因 countPerShot 等误命中，下面精确比对兜底）
        String coarse = String.valueOf(storyboardId);
        List<AidExtractTask> candidates = extractTaskService.list(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getInputSnapshot)
                        .eq(AidExtractTask::getTaskType, TASK_TYPE_STORYBOARD_IMAGE_GENERATE)
                        .in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_QUEUED, TASK_STATUS_PROCESSING)
                        .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)
                        .like(AidExtractTask::getInputSnapshot, coarse));
        if (CollectionUtil.isEmpty(candidates))
        {
            return false;
        }
        for (AidExtractTask t : candidates)
        {
            if (snapshotContainsStoryboard(t.getInputSnapshot(), storyboardId))
            {
                return true;
            }
        }
        return false;
    }

    /** 精确解析 inputSnapshot.storyboardIds 数组，判断是否包含指定 storyboardId。 */
    private boolean snapshotContainsStoryboard(String inputSnapshot, Long storyboardId)
    {
        if (StrUtil.isBlank(inputSnapshot) || Objects.isNull(storyboardId))
        {
            return false;
        }
        try
        {
            JsonNode node = OBJECT_MAPPER.readTree(inputSnapshot);
            JsonNode ids = node.path("storyboardIds");
            if (ids.isArray())
            {
                for (JsonNode n : ids)
                {
                    if (n.canConvertToLong() && n.asLong() == storyboardId)
                    {
                        return true;
                    }
                }
            }
            return false;
        }
        catch (Exception e)
        {
            // 解析失败时保守判活（fail-safe，宁可拒绝重复提交也不重复出图/扣费）
            log.warn("分镜图生成活跃任务快照解析失败, 保守判活: storyboardId={}, err={}", storyboardId, e.getMessage());
            return true;
        }
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
            log.warn("分镜图生成成功CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
            return false;
        }
        wechatNotifyService.notifyTaskTerminal(taskId);
        return true;
    }

    private boolean updateTaskPartialFailed(Long taskId, int totalCount, String resultJson)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.eq(AidExtractTask::getStatus, TASK_STATUS_PROCESSING);
        update.set(AidExtractTask::getStatus, TASK_STATUS_PARTIAL_FAILED);
        update.set(AidExtractTask::getTotalCount, totalCount);
        if (StrUtil.isNotBlank(resultJson))
        {
            update.set(AidExtractTask::getResultData, resultJson);
        }
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, update);
        if (rows == 0)
        {
            log.warn("分镜图生成部分失败CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
            return false;
        }
        wechatNotifyService.notifyTaskTerminal(taskId);
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
            log.warn("分镜图生成失败CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
            return false;
        }
        wechatNotifyService.notifyTaskTerminal(taskId);
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
            log.warn("分镜图生成取消CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
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
            log.warn("分镜图生成取消(保留结果)CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
            return false;
        }
        return true;
    }

    /**
     * 续生入队失败回滚：把任务从 PENDING 还原到原终态（PARTIAL_FAILED/FAILED）+ 恢复 totalCount + 原 inputSnapshot，
     * 并释放本轮持有的镜头锁，保留续生入口。
     */
    private void releaseLocksAndRollbackResume(Long taskId, List<ShotLock> heldLocks, Integer originalTotalCount,
            String originalStatus, String originalInputSnapshot)
    {
        try
        {
            LambdaUpdateWrapper<AidExtractTask> rollback = Wrappers.lambdaUpdate();
            rollback.eq(AidExtractTask::getId, taskId);
            rollback.in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_QUEUED);
            rollback.set(AidExtractTask::getStatus, originalStatus);
            if (Objects.nonNull(originalTotalCount)) { rollback.set(AidExtractTask::getTotalCount, originalTotalCount); }
            if (StrUtil.isNotBlank(originalInputSnapshot)) { rollback.set(AidExtractTask::getInputSnapshot, originalInputSnapshot); }
            rollback.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            extractTaskService.getBaseMapper().update(null, rollback);
        }
        catch (Exception e)
        {
            log.error("分镜批量出图续生回滚状态异常(锁仍会释放): taskId={}", taskId, e);
        }
        if (CollectionUtil.isNotEmpty(heldLocks))
        {
            for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
        }
    }
}
