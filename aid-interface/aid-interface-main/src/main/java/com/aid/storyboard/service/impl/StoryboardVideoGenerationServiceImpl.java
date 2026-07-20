package com.aid.storyboard.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidGenRecordService;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.image.ImageUrlValidator;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.enums.CreationModeEnum;
import com.aid.enums.GenTypeEnum;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.service.IMediaGenerationService;
import com.aid.media.service.MediaTaskArchiveService;
import com.aid.model.service.IAiModelBusinessService;
import com.aid.model.vo.AiModelVO;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.rps.queue.MediaGenFanInSupport;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.sse.AssetExtractSseManager;
import com.aid.service.IAiModelConfigService;
import com.aid.storyboard.dto.StoryboardVideoGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoFromImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoEdgeGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoGridGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoGenerateVO;
import com.aid.storyboard.service.IStoryboardVideoGenerationService;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;
import com.aid.storyboard.video.VideoReferencePlanner;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜图生视频服务实现（父任务异步）。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class StoryboardVideoGenerationServiceImpl implements IStoryboardVideoGenerationService
{
    /** 模型池功能编码（aid_ai_model_func_config.func_code），独立于智能体 */
    private static final String FUNC_CODE_STORYBOARD_VIDEO = "main_storyboard_video";

    /** 图生方向出片专用视频模型池：仅放支持图片输入的视频模型，从源头约束图生视频出片模型 */
    private static final String FUNC_CODE_STORYBOARD_VIDEO_IMAGE = "main_storyboard_video_image";

    /** 多参方向 pro（专业版）专属模型池：仅 creation_mode=pro 的多参出片走此池，其余创作模式仍走通用池 */
    private static final String FUNC_CODE_STORYBOARD_VIDEO_MULTI_PRO = "main_storyboard_video_multi_pro";

    /** 首尾帧出片专用视频模型池：仅放支持尾帧输入的视频模型，不按创作模式分流 */
    private static final String FUNC_CODE_STORYBOARD_VIDEO_EDGE = "main_storyboard_video_edge";

    /** 宫格出片专用视频模型池：仅 creation_mode=auto_grid 的宫格出片走此池 */
    private static final String FUNC_CODE_STORYBOARD_VIDEO_GRID = "main_storyboard_video_grid";

    /** 任务类型（必须与 AssetExtractServiceImpl 常量保持一致） */
    private static final String TASK_TYPE_STORYBOARD_VIDEO_GENERATE = "storyboard_video_generate";

    /** 业务任务类型（写入 aid_media_task.biz_task_type） */
    private static final String BIZ_TASK_TYPE = "storyboard_video_generate";

    /** 任务状态枚举（与 AidExtractTask 字符串字段对齐） */
    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    /** 排队中（已入队等待并发名额）：与 PENDING/PROCESSING 同属"活跃"，重复出片检测必须纳入 */
    private static final String TASK_STATUS_QUEUED = "QUEUED";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_CANCELLED = "CANCELLED";
    /** 部分镜头成功 + 至少一镜头/条失败的终态（支持续生） */
    private static final String TASK_STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";

    /** 出片方向标识（写入 input_snapshot，续生时据此选 prepare 链路） */
    private static final String DIRECTION_MULTI = "multi";
    private static final String DIRECTION_IMAGE = "image";
    /** 首尾帧出片方向：首图 + 尾图生成视频 */
    private static final String DIRECTION_EDGE = "edge";
    /** 宫格出片方向：以分镜宫格图为参考的图生视频（仅 auto_grid） */
    private static final String DIRECTION_GRID = "grid";

    /** 期望模型类型 */
    private static final String MODEL_TYPE_VIDEO = "video";

    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** gen_type 常量：图生视频 */
    private static final String GEN_TYPE_I2V = "i2v";

    /** gen_type 常量：首尾帧视频（与 GenTypeEnum.EDGE 对齐） */
    private static final String GEN_TYPE_EDGE = "edge";

    /**
     * 分镜视频（video 类）单选互斥范围：出片自动设为主视频时，同分镜其余分镜视频记录的选中一并重置，
     * 保证 video 类只有一个主视频；配音视频（compose 类）独立互斥，不在本范围内、不受出片影响。
     */
    private static final List<String> VIDEO_MUTEX_GEN_TYPES = List.of(
            GenTypeEnum.I2V.getValue(), GenTypeEnum.MULTI.getValue(), GenTypeEnum.EDGE.getValue(),
            GenTypeEnum.UPLOAD_VIDEO.getValue());

    /** 提示词最大长度（视频提示词以 800 字节为常见上限，此处给出业务侧软上限） */
    private static final int MAX_PROMPT_LENGTH = 4000;

    /** 用户补充文本最大长度（超出截断） */
    private static final int MAX_USER_INPUT_LENGTH = 500;

    /** 出片数量上下限 */
    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 4;

    /** 单次批量出片最大镜头数：限制批量规模，避免父任务顺序执行总时长过长超出执行租约 */
    private static final int MAX_BATCH_SHOTS = 20;

    /** 首尾帧配音参考：最多段数 */
    private static final int MAX_EDGE_AUDIOS = 7;

    /** 首尾帧配音参考：单段最大时长（秒） */
    private static final int MAX_EDGE_AUDIO_SECONDS = 10;

    /**
     * 运行批次号上限（独占编码位 [0,1000)）：bizTaskId 按父任务编码段 + 稳定镜头序号*1000 + 逻辑槽位确定性编码
     * （不含 runNo）。runNo 仅持久化于 input_snapshot 作「重试超限」守卫——续生到该上限直接拒绝，不 clamp。
     */
    private static final int MAX_RUN_NO = 999;

    /** 参考图业务层统一上限（厂商内部仍会做二次裁剪） */
    private static final int MAX_REFERENCE_IMAGES = 8;

    /** {@code @图片N[name]} 占位正则（与 {@link } 完全一致） */
    private static final Pattern REF_PATTERN = Pattern.compile("@图片(\\d+)\\[([^\\]]+)\\]");

    /** Redis 防重锁 Key 前缀（与 AssetExtractServiceImpl.FORM_LOCK_PREFIX 同命名空间） */
    private static final String FORM_LOCK_PREFIX = "asset:form:lock:";

    /** 防重锁 TTL（秒）：90 分钟，覆盖 count 最大 4 条 × 1200s 单条视频轮询 + 余量 */
    private static final long FORM_LOCK_TTL_SECONDS = 90L * 60L;

    /**
     * 锁「在建宽限期」（毫秒）：SETNX 成功后到父任务入库前有一段加载/解析窗口，
     * 此期间锁值时间戳新于宽限期的，视为「并发在建」而非泄漏，不予抢占，避免删掉对方有效锁导致重复受理。
     */
    private static final long LOCK_INFLIGHT_GRACE_MS = 120_000L;

    /** 视频中间态白名单（用于异步首响应判活：非此白名单且非 SUCCEEDED 才判失败）。
     *  必须覆盖媒体调度的全部在飞态——含 WAIT_CALLBACK（callback-first 模型仅返回 providerTaskId 时进入），
     *  否则 callback-first 视频模型首响应会被误判为失败、过早记失败。轮询循环靠"非 SUCCEEDED/FAILED 即继续"驱动，不依赖本白名单。 */
    private static final Set<String> VIDEO_IN_PROGRESS_STATUSES = Set.of(
            "INIT", "PENDING", "QUEUED", "PROCESSING", "WAIT_POLL", "WAIT_CALLBACK");

    /** 批量出片 SSE 进度分段：排队 admitted 固定 5，提交阶段 5→30，扇入等待阶段 30→99，保证整体单调不回退 */
    private static final int PROGRESS_SUBMIT_BASE = 5;
    private static final int PROGRESS_SUBMIT_SPAN = 25;
    private static final int PROGRESS_FANIN_BASE = 30;
    private static final int PROGRESS_FANIN_SPAN = 69;
    private static final int PROGRESS_CAP = 99;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 镜头锁 compare-and-delete Lua 脚本：仅当锁当前值 == ARGV[1] 才删除，原子，杜绝误删他人/新锁。
     * 走 {@code redisCache.redisTemplate.execute(...)}，ARGV 与存值经同一序列化器，比较一致（与 EXTRACT 锁同套路）。
     */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> SHOT_LOCK_RELEASE_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    /**
     * 镜头锁 compare-and-expire Lua 脚本：仅当锁当前值 == ARGV[1] 才续租。
     * TTL 直接写进脚本（整数字面量），不经 ARGV 传入——本类用项目默认 RedisTemplate（FastJson2 value 序列化器），
     * 若把 TTL 作为 ARGV 传入会被序列化成带引号字符串（如 {@code "5400"}），导致 {@code EXPIRE} 报
     * "value is not an integer"。ARGV[1] 的 token 比较不受影响（存值与 ARGV 经同一序列化器，比较一致）。
     * TTL 取 {@link #FORM_LOCK_TTL_SECONDS}（在本字段之前声明，拼接时已完成初始化），随常量自动同步。
     */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> SHOT_LOCK_RENEW_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], "
                            + FORM_LOCK_TTL_SECONDS + ") else return 0 end",
                    Long.class);

    /** 镜头锁持有凭证：key + token，所有释放/续租必须基于 token 走原子 CAS，杜绝误删/误续他人锁。 */
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
    private IMediaGenerationService mediaGenerationService;

    /** 媒体任务终态载荷二阶段压缩：业务上下文消费成功后再清理。 */
    @Autowired
    private MediaTaskArchiveService mediaTaskArchiveService;

    /** 参考素材富化解析所需：form_image（@图片N 命中行）/ form（形态→主资产）/ 主资产（名称/类型）。 */
    @Autowired
    private IAidRolePropSceneFormImageService rolePropSceneFormImageService;

    @Autowired
    private IAidRolePropSceneFormService rolePropSceneFormService;

    @Autowired
    private IAidRolePropSceneService rolePropSceneService;

    /** 分厂商 / 分模型参考装配策略路由器（多模态差异化处理 + 可扩展）。 */
    @Autowired
    private VideoReferencePlanner videoReferencePlanner;

    @Autowired
    private IAiModelConfigService aiModelConfigService;

    @Autowired
    private IAiModelBusinessService aiModelBusinessService;

    @Autowired
    private IAidGenRecordService aidGenRecordService;

    /** aid_media_task 无独立 Service，沿用现有直读 Mapper 的统一做法。
     *  续生 durable 复用用：按确定性 bizTaskId 反查已成功媒体任务，不受媒体层 1 小时幂等窗限制。 */
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
    private com.aid.common.aid.oss.util.MediaUrlResolver mediaUrlResolver;

    /** 引用即启用：生视频前把"已存在但未启用"的被引用资产自动设为 is_use=1 */
    @Autowired
    private com.aid.rps.service.IRpsFormImageBusinessService rpsFormImageBusinessService;

    /** 创作模式解析：剧集 creation_mode 优先，回退项目 default_creation_mode（多参 pro 分流用）。 */
    @Autowired
    private IAidComicEpisodeService aidComicEpisodeService;

    @Autowired
    private IAidComicProjectService aidComicProjectService;

    @Autowired
    private IWechatNotifyService wechatNotifyService;

    /** 计费金额计算器（建任务前余额预检的金额预估，冻结仍走统一 prepareBilling） */
    @Autowired
    private com.aid.billing.service.BillingAmountCalculator billingAmountCalculator;

    /** 账户服务（建任务前的余额只读预检） */
    @Autowired
    private com.aid.billing.service.IAccountUpdateService accountUpdateService;

    @Override
    public StoryboardVideoGenerateVO generateVideo(StoryboardVideoGenerateRequest request, Long userId)
    {
        validateUserId(userId);
        List<Long> ids = validateBatchRequestMulti(request);
        boolean single = ids.size() == 1;
        int perShotCount = single ? clampCount(request.getCount()) : 1;

        log.info("分镜图生视频(多参方向)入口: userId={}, shotCount={}, single={}, perShotCount={}, modelName={}, hasInputPrompt={}",
                userId, ids.size(), single, perShotCount, request.getModelName(),
                StrUtil.isNotBlank(request.getVideoPrompt()));

        String creationMode = resolveCreationModeForBatch(ids, userId);
        String funcCode = CreationModeEnum.PRO.getValue().equals(creationMode)
                ? FUNC_CODE_STORYBOARD_VIDEO_MULTI_PRO : FUNC_CODE_STORYBOARD_VIDEO; // pro 专属多参池 / 通用多参池
        String modelCode = resolveModelCode(request.getModelName(), funcCode);
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(modelCode);
        if (Objects.isNull(modelConfig))
        {
            log.error("分镜图生视频模型配置缺失: modelCode={}", modelCode);
            throw new ServiceException("模型不存在");
        }
        Long modelId = modelConfig.getId();
        Integer resolvedDuration = Boolean.TRUE.equals(modelConfig.getSupportsDuration())
                ? resolveDurationSeconds(request.getDurationSeconds(), modelConfig) : null;
        String resolvedAspectRatio = Boolean.TRUE.equals(modelConfig.getSupportsAspectRatio())
                ? resolveAspectRatio(request.getAspectRatio(), modelConfig) : null;
        String resolvedResolution = resolveResolution(request.getResolution(), modelConfig);

        final boolean singleFinal = single;
        final int perShotCountFinal = perShotCount;
        return submitBatch(userId, ids, single, perShotCount, modelCode, modelConfig, modelId,
                resolvedAspectRatio, resolvedResolution, resolvedDuration, request.getGenerateAudio(), request.getUserInputText(),
                funcCode, DIRECTION_MULTI,
                sb -> prepareMultiShot(sb, singleFinal, request, modelConfig, userId, perShotCountFinal));
    }

    /**
     * 解析创作模式（剧集 creation_mode 优先 → 项目 default_creation_mode 兜底 → 空串）。
     * 口径与 {@code ProjectGenConfigServiceImpl} 一致；仅 select 必要字段。
     */
    private String resolveCreationMode(Long projectId, Long episodeId)
    {
        if (Objects.nonNull(episodeId) && episodeId > 0)
        {
            AidComicEpisode episode = aidComicEpisodeService.getOne(
                    Wrappers.<AidComicEpisode>lambdaQuery()
                            .select(AidComicEpisode::getId, AidComicEpisode::getCreationMode)
                            .eq(AidComicEpisode::getId, episodeId)
                            .last("limit 1"), false);
            if (Objects.nonNull(episode) && StrUtil.isNotBlank(episode.getCreationMode()))
            {
                return episode.getCreationMode().trim();
            }
        }
        if (Objects.nonNull(projectId) && projectId > 0)
        {
            AidComicProject project = aidComicProjectService.getOne(
                    Wrappers.<AidComicProject>lambdaQuery()
                            .select(AidComicProject::getId, AidComicProject::getDefaultCreationMode)
                            .eq(AidComicProject::getId, projectId)
                            .last("limit 1"), false);
            if (Objects.nonNull(project) && StrUtil.isNotBlank(project.getDefaultCreationMode()))
            {
                return project.getDefaultCreationMode().trim();
            }
        }
        return "";
    }

    /**
     * 批量出片解析创作模式：扫描 ids 取第一个可加载分镜的创作模式（逐镜头真正的归属校验在 submitBatch 内进行）。
     * 避免首个 id 非法/越权直接整批失败——同批分镜本属同一 project/episode，取首个可加载者即可代表；
     * 全部不可加载时返回空串（后续 submitBatch 会以「全部失败」终结）。
     */
    private String resolveCreationModeForBatch(List<Long> ids, Long userId)
    {
        for (Long id : ids)
        {
            try
            {
                AidStoryboard sb = loadAndCheckStoryboard(id, userId);
                return resolveCreationMode(sb.getProjectId(), sb.getEpisodeId());
            }
            catch (RuntimeException ignore)
            {
                // 该镜头不可加载，尝试下一个
            }
        }
        return "";
    }

    /**
     * 按出片方向兜底解析 func_code（仅用于无 funcCode 字段的老快照续生）。
     * 多参 pro 分流的 func_code 已持久化到快照，老快照按 direction 回落到通用池：
     * image/grid → 各自池、edge → 首尾帧池、其余（multi）→ 通用多参池。
     */
    private String resolveFuncCodeByDirection(String direction)
    {
        if (DIRECTION_IMAGE.equals(direction)) { return FUNC_CODE_STORYBOARD_VIDEO_IMAGE; }
        if (DIRECTION_GRID.equals(direction)) { return FUNC_CODE_STORYBOARD_VIDEO_GRID; }
        if (DIRECTION_EDGE.equals(direction)) { return FUNC_CODE_STORYBOARD_VIDEO_EDGE; }
        return FUNC_CODE_STORYBOARD_VIDEO;
    }
    private void validateUserId(Long userId)
    {
        if (Objects.isNull(userId) || userId <= 0)
        {
            log.error("分镜图生视频登录态缺失: userId={}", userId);
            throw new ServiceException("请先登录");
        }
    }
    @Override
    public StoryboardVideoGenerateVO generateVideoFromImage(StoryboardVideoFromImageGenerateRequest request, Long userId)
    {
        validateUserId(userId);
        List<Long> ids = validateBatchRequestImage(request);
        boolean single = ids.size() == 1;
        int perShotCount = single ? clampCount(request.getCount()) : 1;

        log.info("分镜图生视频(图生方向)入口: userId={}, shotCount={}, single={}, perShotCount={}, modelName={}, imageCount={}",
                userId, ids.size(), single, perShotCount, request.getModelName(),
                request.getImages() == null ? 0 : request.getImages().size());

        String modelCode = resolveModelCode(request.getModelName(), FUNC_CODE_STORYBOARD_VIDEO_IMAGE);
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(modelCode);
        if (Objects.isNull(modelConfig))
        {
            log.error("分镜图生视频模型配置缺失: modelCode={}", modelCode);
            throw new ServiceException("模型不存在");
        }
        // 图生视频出片硬要求：模型必须支持图片输入（池已收敛，此处再兜底一层）
        if (!Boolean.TRUE.equals(modelConfig.getSupportsImageInput()))
        {
            log.info("图生视频出片模型不支持图片输入: modelCode={}", modelCode);
            throw new ServiceException("该模型不支持图片");
        }
        Long modelId = modelConfig.getId();
        Integer resolvedDuration = Boolean.TRUE.equals(modelConfig.getSupportsDuration())
                ? resolveDurationSeconds(request.getDurationSeconds(), modelConfig) : null;
        String resolvedAspectRatio = Boolean.TRUE.equals(modelConfig.getSupportsAspectRatio())
                ? resolveAspectRatio(request.getAspectRatio(), modelConfig) : null;
        String resolvedResolution = resolveResolution(request.getResolution(), modelConfig);

        final boolean singleFinal = single;
        final int perShotCountFinal = perShotCount;
        return submitBatch(userId, ids, single, perShotCount, modelCode, modelConfig, modelId,
                resolvedAspectRatio, resolvedResolution, resolvedDuration, request.getGenerateAudio(), request.getUserInputText(),
                FUNC_CODE_STORYBOARD_VIDEO_IMAGE, DIRECTION_IMAGE,
                sb -> prepareImageShot(sb, singleFinal, request, modelConfig, userId, perShotCountFinal));
    }
    @Override
    public StoryboardVideoGenerateVO generateVideoFromGrid(StoryboardVideoGridGenerateRequest request, Long userId)
    {
        validateUserId(userId);
        List<Long> ids = validateBatchRequestGrid(request);
        boolean single = ids.size() == 1;
        int perShotCount = single ? clampCount(request.getCount()) : 1;

        log.info("分镜宫格生视频入口: userId={}, shotCount={}, single={}, perShotCount={}, modelName={}",
                userId, ids.size(), single, perShotCount, request.getModelName());

        String creationMode = resolveCreationModeForBatch(ids, userId);
        if (!CreationModeEnum.AUTO_GRID.getValue().equals(creationMode))
        {
            log.info("宫格生视频拒绝：非 auto_grid 创作模式: creationMode={}", creationMode);
            throw new ServiceException("仅宫格模式可用");
        }

        String modelCode = resolveModelCode(request.getModelName(), FUNC_CODE_STORYBOARD_VIDEO_GRID);
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(modelCode);
        if (Objects.isNull(modelConfig))
        {
            log.error("宫格生视频模型配置缺失: modelCode={}", modelCode);
            throw new ServiceException("模型不存在");
        }
        if (!Boolean.TRUE.equals(modelConfig.getSupportsImageInput()))
        {
            log.info("宫格生视频模型不支持图片输入: modelCode={}", modelCode);
            throw new ServiceException("该模型不支持图片");
        }
        Long modelId = modelConfig.getId();
        Integer resolvedDuration = Boolean.TRUE.equals(modelConfig.getSupportsDuration())
                ? resolveDurationSeconds(request.getDurationSeconds(), modelConfig) : null;
        String resolvedAspectRatio = Boolean.TRUE.equals(modelConfig.getSupportsAspectRatio())
                ? resolveAspectRatio(request.getAspectRatio(), modelConfig) : null;
        String resolvedResolution = resolveResolution(request.getResolution(), modelConfig);

        final boolean singleFinal = single;
        final int perShotCountFinal = perShotCount;
        StoryboardVideoFromImageGenerateRequest imageReq = new StoryboardVideoFromImageGenerateRequest();
        imageReq.setVideoPrompt(single ? request.getVideoPrompt() : null);
        imageReq.setGenerateAudio(request.getGenerateAudio());
        imageReq.setUserInputText(request.getUserInputText());
        return submitBatch(userId, ids, single, perShotCount, modelCode, modelConfig, modelId,
                resolvedAspectRatio, resolvedResolution, resolvedDuration, request.getGenerateAudio(), request.getUserInputText(),
                FUNC_CODE_STORYBOARD_VIDEO_GRID, DIRECTION_GRID,
                sb -> prepareImageShot(sb, singleFinal, imageReq, modelConfig, userId, perShotCountFinal));
    }
    @Override
    public StoryboardVideoGenerateVO generateVideoFromEdge(StoryboardVideoEdgeGenerateRequest request, Long userId)
    {
        validateUserId(userId);
        List<Long> ids = validateBatchRequestEdge(request);
        boolean single = ids.size() == 1;
        int perShotCount = single ? clampCount(request.getCount()) : 1;

        log.info("分镜首尾帧生视频入口: userId={}, shotCount={}, single={}, perShotCount={}, modelName={}",
                userId, ids.size(), single, perShotCount, request.getModelName());

        String modelCode = resolveModelCode(request.getModelName(), FUNC_CODE_STORYBOARD_VIDEO_EDGE);
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(modelCode);
        if (Objects.isNull(modelConfig))
        {
            log.error("首尾帧生视频模型配置缺失: modelCode={}", modelCode);
            throw new ServiceException("模型不存在");
        }
        if (!Boolean.TRUE.equals(modelConfig.getSupportsLastFrame()))
        {
            log.info("首尾帧生视频模型不支持尾帧输入: modelCode={}", modelCode);
            throw new ServiceException("不支持尾帧");
        }
        Long modelId = modelConfig.getId();
        Integer resolvedDuration = Boolean.TRUE.equals(modelConfig.getSupportsDuration())
                ? resolveDurationSeconds(request.getDurationSeconds(), modelConfig) : null;
        String resolvedAspectRatio = Boolean.TRUE.equals(modelConfig.getSupportsAspectRatio())
                ? resolveAspectRatio(request.getAspectRatio(), modelConfig) : null;
        String resolvedResolution = resolveResolution(request.getResolution(), modelConfig);

        Map<Long, EdgeResolved> edgeMap = buildEdgeResolvedMap(request);
        final boolean singleFinal = single;
        final int perShotCountFinal = perShotCount;
        return submitBatch(userId, ids, single, perShotCount, modelCode, modelConfig, modelId,
                resolvedAspectRatio, resolvedResolution, resolvedDuration, request.getGenerateAudio(), request.getUserInputText(),
                FUNC_CODE_STORYBOARD_VIDEO_EDGE, DIRECTION_EDGE,
                sb -> prepareEdgeShot(sb, singleFinal, request, edgeMap, modelConfig, userId, perShotCountFinal));
    }

    /**
     * 图生方向批量出片入参校验：storyboardIds 非空去重 + count 规则（多镜头禁止 count&gt;1）+ 长度/时长基础校验。
     *
     * @return 去重后的分镜 ID 列表
     */
    private List<Long> validateBatchRequestImage(StoryboardVideoFromImageGenerateRequest request)
    {
        if (Objects.isNull(request))
        {
            log.error("分镜图生视频(图生方向)入参为空");
            throw new ServiceException("参数错误");
        }
        List<Long> ids = distinctValidIds(request.getStoryboardIds());
        boolean single = ids.size() == 1;
        validateCountRule(single, request.getCount());
        if (StrUtil.isNotBlank(request.getVideoPrompt()) && request.getVideoPrompt().length() > MAX_PROMPT_LENGTH)
        {
            log.error("分镜图生视频(图生方向)提示词过长: len={}", request.getVideoPrompt().length());
            throw new ServiceException("提示词过长");
        }
        if (StrUtil.isNotBlank(request.getUserInputText()) && request.getUserInputText().length() > MAX_USER_INPUT_LENGTH)
        {
            log.warn("分镜图生视频(图生方向) userInputText 超长截断: originLen={}", request.getUserInputText().length());
            request.setUserInputText(request.getUserInputText().substring(0, MAX_USER_INPUT_LENGTH));
        }
        if (Objects.nonNull(request.getDurationSeconds()) && request.getDurationSeconds() <= 0)
        {
            log.error("分镜图生视频(图生方向) durationSeconds 非法: val={}", request.getDurationSeconds());
            throw new ServiceException("时长有误");
        }
        return ids;
    }

    /** 将前端传入的图生提示词落库到 aid_storyboard.video_prompt_image（建任务前调用，见 generateVideoFromImage 设计说明）。 */
    private void persistVideoPromptImage(Long storyboardId, Long userId, String videoPrompt)
    {
        LambdaUpdateWrapper<AidStoryboard> upd = Wrappers.lambdaUpdate();
        upd.eq(AidStoryboard::getId, storyboardId);
        upd.eq(AidStoryboard::getUserId, userId);
        upd.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
        upd.set(AidStoryboard::getVideoPromptImage, videoPrompt);
        upd.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
        upd.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
        aidStoryboardService.update(upd);
    }

    /** 将前端传入的（多参方向）提示词落库到 aid_storyboard.video_prompt（建任务前调用，与图生方向口径一致）。 */
    private void persistVideoPrompt(Long storyboardId, Long userId, String videoPrompt)
    {
        LambdaUpdateWrapper<AidStoryboard> upd = Wrappers.lambdaUpdate();
        upd.eq(AidStoryboard::getId, storyboardId);
        upd.eq(AidStoryboard::getUserId, userId);
        upd.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
        upd.set(AidStoryboard::getVideoPrompt, videoPrompt);
        upd.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
        upd.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
        aidStoryboardService.update(upd);
    }

    /**
     * 多参方向批量出片入参校验：storyboardIds 非空去重 + count 规则 + 长度/时长基础校验。
     *
     * @return 去重后的分镜 ID 列表
     */
    private List<Long> validateBatchRequestMulti(StoryboardVideoGenerateRequest request)
    {
        if (Objects.isNull(request))
        {
            log.error("分镜图生视频入参为空");
            throw new ServiceException("参数错误");
        }
        List<Long> ids = distinctValidIds(request.getStoryboardIds());
        boolean single = ids.size() == 1;
        validateCountRule(single, request.getCount());
        if (StrUtil.isNotBlank(request.getVideoPrompt())
                && request.getVideoPrompt().length() > MAX_PROMPT_LENGTH)
        {
            log.error("分镜图生视频提示词过长: len={}", request.getVideoPrompt().length());
            throw new ServiceException("提示词过长");
        }
        if (StrUtil.isNotBlank(request.getUserInputText())
                && request.getUserInputText().length() > MAX_USER_INPUT_LENGTH)
        {
            log.warn("分镜图生视频 userInputText 超长截断: originLen={}", request.getUserInputText().length());
            request.setUserInputText(request.getUserInputText().substring(0, MAX_USER_INPUT_LENGTH));
        }
        if (Objects.nonNull(request.getDurationSeconds()) && request.getDurationSeconds() <= 0)
        {
            log.error("分镜图生视频 durationSeconds 非法: val={}", request.getDurationSeconds());
            throw new ServiceException("时长有误");
        }
        return ids;
    }

    /**
     * 宫格方向批量出片入参校验：storyboardIds 非空去重 + count 规则 + 长度/时长基础校验。
     *
     * @return 去重后的分镜 ID 列表
     */
    private List<Long> validateBatchRequestGrid(StoryboardVideoGridGenerateRequest request)
    {
        if (Objects.isNull(request))
        {
            log.error("分镜宫格生视频入参为空");
            throw new ServiceException("参数错误");
        }
        List<Long> ids = distinctValidIds(request.getStoryboardIds());
        boolean single = ids.size() == 1;
        validateCountRule(single, request.getCount());
        if (StrUtil.isNotBlank(request.getVideoPrompt()) && request.getVideoPrompt().length() > MAX_PROMPT_LENGTH)
        {
            log.error("分镜宫格生视频提示词过长: len={}", request.getVideoPrompt().length());
            throw new ServiceException("提示词过长");
        }
        if (StrUtil.isNotBlank(request.getUserInputText()) && request.getUserInputText().length() > MAX_USER_INPUT_LENGTH)
        {
            log.warn("分镜宫格生视频 userInputText 超长截断: originLen={}", request.getUserInputText().length());
            request.setUserInputText(request.getUserInputText().substring(0, MAX_USER_INPUT_LENGTH));
        }
        if (Objects.nonNull(request.getDurationSeconds()) && request.getDurationSeconds() <= 0)
        {
            log.error("分镜宫格生视频 durationSeconds 非法: val={}", request.getDurationSeconds());
            throw new ServiceException("时长有误");
        }
        return ids;
    }

    /**
     * 首尾帧方向批量出片入参校验：storyboardIds 非空去重 + count 规则 + 长度/时长基础校验。
     * 首尾图记录的存在性按镜头在 {@link #prepareEdgeShot} 中校验（多镜头单镜头失败仅跳过，不阻断整批）。
     *
     * @return 去重后的分镜 ID 列表
     */
    private List<Long> validateBatchRequestEdge(StoryboardVideoEdgeGenerateRequest request)
    {
        if (Objects.isNull(request))
        {
            log.error("分镜首尾帧生视频入参为空");
            throw new ServiceException("参数错误");
        }
        List<Long> ids = distinctValidIds(request.getStoryboardIds());
        boolean single = ids.size() == 1;
        validateCountRule(single, request.getCount());
        if (StrUtil.isNotBlank(request.getVideoPrompt()) && request.getVideoPrompt().length() > MAX_PROMPT_LENGTH)
        {
            log.error("分镜首尾帧生视频提示词过长: len={}", request.getVideoPrompt().length());
            throw new ServiceException("提示词过长");
        }
        if (StrUtil.isNotBlank(request.getUserInputText()) && request.getUserInputText().length() > MAX_USER_INPUT_LENGTH)
        {
            log.warn("分镜首尾帧生视频 userInputText 超长截断: originLen={}", request.getUserInputText().length());
            request.setUserInputText(request.getUserInputText().substring(0, MAX_USER_INPUT_LENGTH));
        }
        if (Objects.nonNull(request.getDurationSeconds()) && request.getDurationSeconds() <= 0)
        {
            log.error("分镜首尾帧生视频 durationSeconds 非法: val={}", request.getDurationSeconds());
            throw new ServiceException("时长有误");
        }
        return ids;
    }

    /** 组装首尾帧逐镜头解析项：storyboardId → {首图来源, 尾图来源, 配音项}（仅做原始映射，图片/配音校验在 prepareEdgeShot 内逐镜头进行）。 */
    private Map<Long, EdgeResolved> buildEdgeResolvedMap(StoryboardVideoEdgeGenerateRequest request)
    {
        Map<Long, EdgeResolved> map = new LinkedHashMap<>();
        if (Objects.isNull(request) || CollectionUtil.isEmpty(request.getItems()))
        {
            return map;
        }
        for (StoryboardVideoEdgeGenerateRequest.EdgeShotItem item : request.getItems())
        {
            if (Objects.isNull(item) || Objects.isNull(item.getStoryboardId())) { continue; }
            map.put(item.getStoryboardId(), new EdgeResolved(
                    StrUtil.trimToNull(item.getFirstImageUrl()), item.getFirstImageRecordId(),
                    StrUtil.trimToNull(item.getLastImageUrl()), item.getLastImageRecordId(),
                    item.getAudios()));
        }
        return map;
    }

    /** 校验配音参考（逐镜头）：最多 7 段、每段时长 (0,10]、URL 非空；非法直接抛错（≤12 字）。 */
    private void validateEdgeAudios(List<StoryboardVideoEdgeGenerateRequest.EdgeAudioItem> audios)
    {
        if (CollectionUtil.isEmpty(audios))
        {
            return;
        }
        if (audios.size() > MAX_EDGE_AUDIOS)
        {
            log.info("首尾帧配音段数超限: size={}, max={}", audios.size(), MAX_EDGE_AUDIOS);
            throw new ServiceException("配音最多7个");
        }
        for (StoryboardVideoEdgeGenerateRequest.EdgeAudioItem a : audios)
        {
            if (Objects.isNull(a)) { continue; }
            if (StrUtil.isBlank(a.getAudioUrl()))
            {
                log.info("首尾帧配音 URL 为空");
                throw new ServiceException("配音不可用");
            }
            Integer d = a.getDurationSeconds();
            if (Objects.isNull(d) || d <= 0 || d > MAX_EDGE_AUDIO_SECONDS)
            {
                log.info("首尾帧配音时长非法: durationSeconds={}, max={}", d, MAX_EDGE_AUDIO_SECONDS);
                throw new ServiceException("配音超10秒");
            }
        }
    }

    /** 抽取配音有序 URL 列表（已校验项 → 裸 URL，供透传 options.referenceAudios）。 */
    private List<String> extractAudioUrls(List<StoryboardVideoEdgeGenerateRequest.EdgeAudioItem> audios)
    {
        List<String> urls = new ArrayList<>();
        if (CollectionUtil.isEmpty(audios)) { return urls; }
        for (StoryboardVideoEdgeGenerateRequest.EdgeAudioItem a : audios)
        {
            if (Objects.nonNull(a) && StrUtil.isNotBlank(a.getAudioUrl())) { urls.add(StrUtil.trim(a.getAudioUrl())); }
        }
        return urls;
    }

    /** 把首尾帧来源（上传 URL / 记录 ID）+ 配音项写入快照镜头条目（仅 edge 方向有值），供续生重解析。 */
    private void putEdgeSnapshot(Map<String, Object> target, PreparedShot ps)
    {
        if (StrUtil.isNotBlank(ps.firstImageUrl)) { target.put("firstImageUrl", ps.firstImageUrl); }
        if (Objects.nonNull(ps.firstImageRecordId)) { target.put("firstImageRecordId", ps.firstImageRecordId); }
        if (StrUtil.isNotBlank(ps.lastImageUrl)) { target.put("lastImageUrl", ps.lastImageUrl); }
        if (Objects.nonNull(ps.lastImageRecordId)) { target.put("lastImageRecordId", ps.lastImageRecordId); }
        putAudiosSnapshot(target, ps.audioItems);
    }

    /** 把配音项序列化进快照（[{audioUrl, durationSeconds}]）；空则不写。 */
    private void putAudiosSnapshot(Map<String, Object> target,
            List<StoryboardVideoEdgeGenerateRequest.EdgeAudioItem> audioItems)
    {
        if (CollectionUtil.isEmpty(audioItems)) { return; }
        List<Map<String, Object>> arr = new ArrayList<>();
        for (StoryboardVideoEdgeGenerateRequest.EdgeAudioItem a : audioItems)
        {
            if (Objects.isNull(a) || StrUtil.isBlank(a.getAudioUrl())) { continue; }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("audioUrl", a.getAudioUrl());
            m.put("durationSeconds", a.getDurationSeconds());
            arr.add(m);
        }
        if (!arr.isEmpty()) { target.put("audios", arr); }
    }

    /** 把快照中的 audios 段反序列化为配音项列表（续生重建用）。 */
    private List<StoryboardVideoEdgeGenerateRequest.EdgeAudioItem> parseAudiosSnapshot(JsonNode shotNode)
    {
        List<StoryboardVideoEdgeGenerateRequest.EdgeAudioItem> items = new ArrayList<>();
        JsonNode audiosNode = shotNode.path("audios");
        if (!audiosNode.isArray()) { return items; }
        for (JsonNode au : audiosNode)
        {
            String url = au.path("audioUrl").asText(null);
            if (StrUtil.isBlank(url)) { continue; }
            StoryboardVideoEdgeGenerateRequest.EdgeAudioItem item =
                    new StoryboardVideoEdgeGenerateRequest.EdgeAudioItem();
            item.setAudioUrl(url);
            item.setDurationSeconds(au.path("durationSeconds").isInt() ? au.get("durationSeconds").asInt() : null);
            items.add(item);
        }
        return items;
    }

    /**
     * 解析首尾帧单帧来源 URL：上传图 URL 优先（本站图片校验），否则库内记录（须属本分镜）；两者皆无返回 null。
     *
     * @return 完整可下发的图片 URL；无来源返回 null（由调用方决定是否报错/单帧降级）
     */
    private String resolveEdgeFrameUrl(String uploadUrl, Long recordId, AidStoryboard sb, Long userId)
    {
        if (StrUtil.isNotBlank(uploadUrl))
        {
            return validateUploadedImageUrl(uploadUrl, sb);
        }
        if (Objects.nonNull(recordId) && recordId > 0)
        {
            return resolveBaseImageUrl(recordId, sb, userId);
        }
        return null;
    }

    /** 校验前端上传的首尾帧图片 URL（仅本站资源 + 远程可达 + 图片格式），返回完整 URL；非法抛「图片无效」。 */
    private String validateUploadedImageUrl(String url, AidStoryboard sb)
    {
        String u = StrUtil.trim(url);
        // 仅允许本站资源（相对路径或本站域名完整URL），拒绝站外外链
        if (!mediaUrlResolver.isSiteImageUrl(u))
        {
            log.info("首尾帧上传图非本站资源: storyboardId={}, url={}", sb.getId(), u);
            throw new ServiceException("图片不可用");
        }
        // 相对路径拼完整URL，并据此做远程可达性 + Content-Type 校验
        String full = mediaUrlResolver.toFullUrl(u);
        if (!ImageUrlValidator.isValidRemoteImageUrl(full))
        {
            log.info("首尾帧上传图非法(格式/不可达/非图片): storyboardId={}, url={}", sb.getId(), full);
            throw new ServiceException("图片不可用");
        }
        return full;
    }

    /** 首尾帧逐镜头解析项：首/尾图来源（上传 URL 或记录 ID，上传优先）、配音项（原始，未校验）。 */
    private static final class EdgeResolved
    {
        final String firstImageUrl;
        final Long firstImageRecordId;
        final String lastImageUrl;
        final Long lastImageRecordId;
        final List<StoryboardVideoEdgeGenerateRequest.EdgeAudioItem> audioItems;

        EdgeResolved(String firstImageUrl, Long firstImageRecordId, String lastImageUrl, Long lastImageRecordId,
                     List<StoryboardVideoEdgeGenerateRequest.EdgeAudioItem> audioItems)
        {
            this.firstImageUrl = firstImageUrl;
            this.firstImageRecordId = firstImageRecordId;
            this.lastImageUrl = lastImageUrl;
            this.lastImageRecordId = lastImageRecordId;
            this.audioItems = (audioItems == null) ? new ArrayList<>() : audioItems;
        }
    }

    /** 分镜 ID 列表去重 + 基础非空校验。 */
    private List<Long> distinctValidIds(List<Long> rawIds)
    {
        if (CollectionUtil.isEmpty(rawIds))
        {
            log.error("分镜图生视频 storyboardIds 为空");
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
            log.error("分镜图生视频 storyboardIds 全部非法: raw={}", rawIds);
            throw new ServiceException("参数错误");
        }
        if (ids.size() > MAX_BATCH_SHOTS)
        {
            log.info("分镜批量出片镜头数超限: size={}, max={}", ids.size(), MAX_BATCH_SHOTS);
            throw new ServiceException("数量超限");
        }
        return ids;
    }

    /**
     * count 规则校验：
     *
     *   - 多镜头（single=false）：不允许 count&gt;1，违者抛"批量限1条"；
     *   - 单镜头（single=true）：count 必须在 [1,4]（为空按 1）。
     *
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
                log.info("分镜图生视频多镜头批量禁止 count>1: count={}", count);
                throw new ServiceException("批量限1条");
            }
            return;
        }
        if (count < MIN_COUNT || count > MAX_COUNT)
        {
            log.error("分镜图生视频 count 越界: count={}", count);
            throw new ServiceException("参数错误");
        }
    }

    /** 单镜头出片条数兜底：null→1，限制 [1,4]。 */
    private int clampCount(Integer count)
    {
        if (Objects.isNull(count))
        {
            return MIN_COUNT;
        }
        if (count < MIN_COUNT)
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
                                AidStoryboard::getVideoPrompt,
                                AidStoryboard::getVideoPromptImage,
                                AidStoryboard::getImagePrompt,
                                AidStoryboard::getFinalImageId,
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

    private String resolveVideoPrompt(String inputPrompt, AidStoryboard storyboard)
    {
        if (StrUtil.isNotBlank(inputPrompt))
        {
            return inputPrompt;
        }
        String dbPrompt = storyboard.getVideoPrompt();
        if (StrUtil.isBlank(dbPrompt))
        {
            // 视频提示词为空说明还没生成视频提示词，引导用户先去生成提示词再来生成视频
            log.error("分镜图生视频提示词为空（视频提示词未生成）: storyboardId={}, userId={}",
                    storyboard.getId(), storyboard.getUserId());
            throw new ServiceException("请先生成提示词");
        }
        return dbPrompt;
    }

    /**
     * 模型解析：按指定 func_code 池校验。
     *
     *   - 用户传入 modelCode → 必须存在、{@code model_type=video} 且在池内
     *   - 用户未传 → 取池内第一个可用模型兜底
     *
     */
    private String resolveModelCode(String requestModelCode, String funcCode)
    {
        List<AiModelVO> pool = aiModelBusinessService.listAvailableModelsByFuncCode(funcCode);
        if (CollectionUtil.isEmpty(pool))
        {
            log.error("分镜图生视频模型池未配置或全部失效: funcCode={}", funcCode);
            throw new ServiceException("功能未配置");
        }

        if (StrUtil.isNotBlank(requestModelCode))
        {
            AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(requestModelCode);
            if (Objects.isNull(modelConfig))
            {
                log.info("用户指定视频模型不可用: requestModelCode={}", requestModelCode);
                throw new ServiceException("模型不存在");
            }
            if (!Objects.equals(MODEL_TYPE_VIDEO, modelConfig.getModelType()))
            {
                log.info("用户指定模型类型不匹配: requestModelCode={}, actualType={}",
                        requestModelCode, modelConfig.getModelType());
                throw new ServiceException("模型不可用");
            }
            boolean inPool = pool.stream().anyMatch(m -> Objects.equals(modelConfig.getId(), m.getId()));
            if (!inPool)
            {
                log.error("分镜图生视频模型不在 {} 池内: modelCode={}, poolSize={}",
                        funcCode, requestModelCode, pool.size());
                throw new ServiceException("模型不可用");
            }
            return requestModelCode;
        }

        String fallback = pool.get(0).getModelCode();
        log.info("分镜图生视频未指定模型，使用池内默认: funcCode={}, modelCode={}", funcCode, fallback);
        return fallback;
    }
    /**
     * 解析本镜参考素材：从最终下发的 {@code video_prompt}（入参覆盖优先，否则库值）的
     * {@code @图片N[name]} 占位出发，解析出参考图 URL 并压缩重排编号。
     *
     * @param referenceOverrides 临时 name→URL 映射（单镜头前端直传，可空）；命中即优先于库内资产
     * @return 按 N 升序、去重、仅含命中且 URL 非空的参考素材；无引用返回空列表
     */
    private ReferenceResolution resolveReferences(String videoPrompt, AidStoryboard storyboard, Long userId,
            Map<String, String> referenceOverrides)
    {
        // 从传入的最终 video_prompt 解析 @图片N（与实际下发 prompt 一致）；入参覆盖时也能正确对齐参考图。
        Map<Integer, String> nameByN = parseAtReferences(videoPrompt);
        if (CollectionUtil.isEmpty(nameByN))
        {
            return new ReferenceResolution(videoPrompt, new ArrayList<>());
        }
        // @图片N 名称分隔符归一化（连字符 → 下划线，与 form_image.name 命名口径一致）：
        // 兼容历史/偶发把 `角色名_形态` 写成 `角色名-形态` 的引用，避免下游"引用即启用/缺失校验"误报缺失。
        // 仅作匹配用途，归一后的名称用于后续库内查找；资产库 name 统一以下划线为分隔符，故连字符→下划线安全。
        Map<Integer, String> normalizedByN = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : nameByN.entrySet())
        {
            normalizedByN.put(e.getKey(), normalizeAssetRefName(e.getValue()));
        }
        nameByN = normalizedByN;

        // 临时参考图覆盖（name→URL，单镜头前端直传）：命中且 URL 非空的 name 走临时外部图，优先于库内资产，
        // 这些 name 不参与库内"引用即启用 / 缺失校验"、也不查 form_image；其余 name 仍走库内解析。
        // key/value 统一 trim 归一化：prompt 里的 @图片N[name] 的 name 已 trim，前端 map key 若带空格须对齐，避免漏命中。
        Map<String, String> overrides = new java.util.HashMap<>();
        if (referenceOverrides != null)
        {
            for (Map.Entry<String, String> e : referenceOverrides.entrySet())
            {
                String k = normalizeAssetRefName(e.getKey());
                String v = StrUtil.trim(e.getValue());
                if (StrUtil.isNotBlank(k) && StrUtil.isNotBlank(v)) { overrides.put(k, v); }
            }
        }
        // 需走库内解析的 name（未被临时映射覆盖的）；与 overrides 互斥，保证临时图不被当作"库内缺失"误报
        List<String> libraryNames = new ArrayList<>();
        for (String nm : nameByN.values())
        {
            if (StrUtil.isBlank(overrides.get(nm))) { libraryNames.add(nm); }
        }

        // 库内解析所需：name → form_image、assetId → 主资产；仅当存在库内 name 时才查
        Map<String, AidRolePropSceneFormImage> byName = new LinkedHashMap<>();
        Map<Long, AidRolePropScene> assetById = new java.util.HashMap<>();
        if (CollectionUtil.isNotEmpty(libraryNames))
        {
            // 第一次调用只做有效性分组；该方法在存在缺失时不会启用任何图片，因此需要把有效引用
            // 单独再调用一次，保证历史上未启用但仍可用的图片可以正常参与本次生成。
            java.util.List<String> missingNames = rpsFormImageBusinessService.enableReferencesAndCollectMissing(
                    storyboard.getProjectId(), storyboard.getEpisodeId(), userId, libraryNames);
            if (CollectionUtil.isNotEmpty(missingNames))
            {
                log.warn("分镜图生视频存在失效引用，将降级为文字描述: storyboardId={}, missing={}",
                        storyboard.getId(), formatMissingRefs(nameByN, missingNames));
                java.util.Set<String> missingSet = missingNames.stream()
                        .map(StoryboardVideoGenerationServiceImpl::normalizeAssetRefName)
                        .collect(java.util.stream.Collectors.toSet());
                libraryNames = libraryNames.stream()
                        .filter(name -> !missingSet.contains(normalizeAssetRefName(name)))
                        .collect(java.util.stream.Collectors.toList());
                if (CollectionUtil.isNotEmpty(libraryNames))
                {
                    java.util.List<String> unexpectedMissing = rpsFormImageBusinessService.enableReferencesAndCollectMissing(
                            storyboard.getProjectId(), storyboard.getEpisodeId(), userId, libraryNames);
                    if (CollectionUtil.isNotEmpty(unexpectedMissing))
                    {
                        log.warn("分镜图生视频有效引用二次启用时状态变化，将继续文字降级: storyboardId={}, missing={}",
                                storyboard.getId(), unexpectedMissing);
                    }
                }
            }

            List<AidRolePropSceneFormImage> imgs = CollectionUtil.isEmpty(libraryNames)
                    ? java.util.Collections.emptyList()
                    : rolePropSceneFormImageService.list(Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                            .select(AidRolePropSceneFormImage::getId,
                                    AidRolePropSceneFormImage::getName,
                                    AidRolePropSceneFormImage::getImageUrl,
                                    AidRolePropSceneFormImage::getAssetId,
                                    AidRolePropSceneFormImage::getSourceType)
                            .eq(AidRolePropSceneFormImage::getProjectId, storyboard.getProjectId())
                            .eq(AidRolePropSceneFormImage::getEpisodeId, storyboard.getEpisodeId())
                            .eq(AidRolePropSceneFormImage::getUserId, userId)
                            .eq(AidRolePropSceneFormImage::getIsUse, 1)
                            .eq(AidRolePropSceneFormImage::getIsSplitSource, 0)
                            .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL));

            // key 统一 trim：DB name 可能带首尾空格，而占位 name 已 trim，不归一化会漏命中
            for (AidRolePropSceneFormImage img : imgs)
            {
                if (StrUtil.isNotBlank(img.getImageUrl()))
                {
                    byName.putIfAbsent(normalizeAssetRefName(img.getName()), img);
                }
            }

            Set<Long> assetIds = new java.util.LinkedHashSet<>();
            for (AidRolePropSceneFormImage img : byName.values())
            {
                if (Objects.nonNull(img.getAssetId()))
                {
                    assetIds.add(img.getAssetId());
                }
            }
            if (CollectionUtil.isNotEmpty(assetIds))
            {
                List<AidRolePropScene> assets = rolePropSceneService.list(
                        Wrappers.<AidRolePropScene>lambdaQuery()
                                .select(AidRolePropScene::getId,
                                        AidRolePropScene::getName,
                                        AidRolePropScene::getAssetType)
                                .in(AidRolePropScene::getId, assetIds)
                                .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL));
                for (AidRolePropScene a : assets)
                {
                    assetById.put(a.getId(), a);
                }
            }
        }

        List<Integer> orderedNs = new ArrayList<>(nameByN.keySet());
        orderedNs.sort(Integer::compareTo);
        List<ResolvedReference> refs = new ArrayList<>();
        // 收集解析不到的库内占位名称：@图片N 引用了资产库里不存在/未启用/无图的素材时，
        // 不能静默跳过（会导致参考图变少、@图片N 与 referenceImages 错位、张冠李戴），必须硬报错。
        List<String> unresolvedNames = new ArrayList<>();
        for (Integer n : orderedNs)
        {
            String formName = nameByN.get(n);
            String ovUrl = StrUtil.trim(overrides.get(formName));
            // 临时映射优先：命中即用前端直传 URL（不入库），N 槽位对齐由占位顺序保证
            if (StrUtil.isNotBlank(ovUrl))
            {
                // 仅允许本站资源（相对路径或本站域名完整URL），拒绝站外外链
                if (!mediaUrlResolver.isSiteImageUrl(ovUrl))
                {
                    log.info("多参图生视频临时参考图非本站资源: storyboardId={}, name={}, url={}",
                            storyboard.getId(), formName, ovUrl);
                    throw new ServiceException("图片不可用");
                }
                // 相对路径拼完整URL后再做远程可达性 + Content-Type 校验
                String ovFull = mediaUrlResolver.toFullUrl(ovUrl);
                if (!ImageUrlValidator.isValidRemoteImageUrl(ovFull))
                {
                    log.info("多参图生视频临时参考图非法(格式/不可达/非图片): storyboardId={}, name={}, url={}",
                            storyboard.getId(), formName, ovFull);
                    throw new ServiceException("图片不可用");
                }
                // 临时图：无库内资产元数据（assetName/assetType 置空、非设定卡）；URL 拼成完整地址下发
                refs.add(new ResolvedReference(n, formName, formName, null, false, ovFull));
                continue;
            }
            AidRolePropSceneFormImage hit = null;
            for (String candidate : com.aid.rps.resolver.StoryboardImageReferenceResolver.candidateLookupKeys(formName))
            {
                hit = byName.get(candidate);
                if (Objects.nonNull(hit)) { break; }
            }
            if (Objects.isNull(hit) || StrUtil.isBlank(hit.getImageUrl()))
            {
                unresolvedNames.add(formName);
                continue;
            }
            AidRolePropScene asset = hit.getAssetId() == null ? null : assetById.get(hit.getAssetId());
            String assetName = asset == null ? null : asset.getName();
            String assetType = asset == null ? null : asset.getAssetType();
            boolean settingCard = isSettingCard(hit, formName);
            // DB 存相对路径，下游 provider 需完整 URL
            String fullUrl = mediaUrlResolver.toFullUrl(hit.getImageUrl());
            refs.add(new ResolvedReference(n, formName, assetName, assetType, settingCard, fullUrl));
        }
        // 资产删除/改名属于可恢复的历史数据变化：缺失占位改成普通文字，不再作为图片槽位下发。
        if (CollectionUtil.isNotEmpty(unresolvedNames))
        {
            log.warn("分镜图生视频引用文字降级: storyboardId={}, unresolved={}, formatted={}, refTotal={}",
                    storyboard.getId(), unresolvedNames, formatMissingRefs(nameByN, unresolvedNames), nameByN.size());
        }
        ReferenceResolution resolution = compactResolvedReferences(videoPrompt, refs);
        log.info("分镜图生视频参考解析完成: storyboardId={}, refTotal={}, resolved={}, degraded={}, tempOverride={}",
                storyboard.getId(), nameByN.size(), resolution.references.size(),
                nameByN.size() - resolution.references.size(), nameByN.size() - libraryNames.size());
        return resolution;
    }

    /**
     * 把有效引用压缩成连续的图片1..N；缺失引用剥掉私有占位壳，仅保留名称作为自然语言描述。
     */
    private ReferenceResolution compactResolvedReferences(String prompt, List<ResolvedReference> resolved)
    {
        List<ResolvedReference> ordered = new ArrayList<>(resolved);
        ordered.sort(java.util.Comparator.comparingInt(ResolvedReference::getOriginalN));
        Map<Integer, ResolvedReference> compactByOriginal = new LinkedHashMap<>();
        List<ResolvedReference> compactRefs = new ArrayList<>();
        for (ResolvedReference ref : ordered)
        {
            int compactN = compactRefs.size() + 1;
            ResolvedReference compact = new ResolvedReference(compactN, ref.getFormName(), ref.getAssetName(),
                    ref.getAssetType(), ref.isSettingCard(), ref.getUrl());
            compactByOriginal.put(ref.getOriginalN(), compact);
            compactRefs.add(compact);
        }
        Matcher matcher = REF_PATTERN.matcher(StrUtil.nullToEmpty(prompt));
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find())
        {
            int originalN;
            try { originalN = Integer.parseInt(matcher.group(1)); }
            catch (NumberFormatException e) { matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group())); continue; }
            ResolvedReference compact = compactByOriginal.get(originalN);
            String rawName = StrUtil.trimToEmpty(matcher.group(2));
            String replacement = Objects.nonNull(compact)
                    ? "@图片" + compact.getOriginalN() + "[" + compact.getFormName() + "]"
                    : rawName;
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return new ReferenceResolution(rewritten.toString(), compactRefs);
    }

    /** 单镜头参考解析结果：实际下发提示词与图片数组必须作为同一份快照使用。 */
    private static final class ReferenceResolution
    {
        final String prompt;
        final List<ResolvedReference> references;

        ReferenceResolution(String prompt, List<ResolvedReference> references)
        {
            this.prompt = prompt;
            this.references = references;
        }
    }

    /** 判定 form_image 是否角色多方位设定卡：source_type=ai_builder 或 name 以 _角色设定 结尾。 */
    private boolean isSettingCard(AidRolePropSceneFormImage img, String formName)
    {
        if (img != null && "ai_builder".equalsIgnoreCase(StrUtil.trimToEmpty(img.getSourceType())))
        {
            return true;
        }
        return StrUtil.endWith(formName, "_角色设定");
    }

    /** 把缺失的参考占位格式化为"图片N[name]、图片M[name2]"（按 N 升序），用于建任务前同步硬失败的精确提示。 */
    private String formatMissingRefs(Map<Integer, String> nameByN, java.util.Collection<String> missingNames)
    {
        java.util.Set<String> missing = new java.util.HashSet<>(missingNames);
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

    /**
     * 资产引用名归一化：把名称结构分隔符的"连字符 / 全角横线"统一成下划线后再比对。
     * 资产库 {@code aid_role_prop_scene_form_image.name} 以下划线 {@code _} 作为「角色名_形态」「场景名_方位」
     * 分隔符；上游 video_prompt 的 {@code @图片N[name]} 偶发/历史把 {@code _} 写成 {@code -}，精确匹配会被
     * 「引用即启用 / 缺失校验」误报缺失。本方法仅用于匹配比对，把常见横线变体统一为下划线消除该类误判。
     */
    private static String normalizeAssetRefName(String s)
    {
        if (s == null) { return ""; }
        String t = StrUtil.trim(s);
        return t.replace('-', '_').replace('－', '_').replace('‐', '_').replace('–', '_');
    }

    /** 解析 image_prompt 中 @图片N[name] 占位（按 N 升序去重）。 */
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

    /** 首帧垫图：baseImageRecordId 优先 → 分镜 final_image_id 兜底 → 都为空返回 null。 */
    private String resolveBaseImageUrl(Long baseImageRecordId, AidStoryboard storyboard, Long userId)
    {
        Long recordId = Objects.nonNull(baseImageRecordId) ? baseImageRecordId : storyboard.getFinalImageId();
        if (Objects.isNull(recordId))
        {
            return null;
        }
        AidGenRecord rec = aidGenRecordService.getOne(
                Wrappers.<AidGenRecord>lambdaQuery()
                        .select(AidGenRecord::getId, AidGenRecord::getUserId,
                                AidGenRecord::getStoryboardId, AidGenRecord::getFileUrl,
                                AidGenRecord::getDelFlag)
                        .eq(AidGenRecord::getId, recordId)
                        .last("limit 1"),
                false);
        if (Objects.isNull(rec) || !DEL_FLAG_NORMAL.equals(rec.getDelFlag()))
        {
            log.error("首帧底图记录不存在或已删除: recordId={}, storyboardId={}", recordId, storyboard.getId());
            throw new ServiceException("底图不可用");
        }
        if (!Objects.equals(userId, rec.getUserId()))
        {
            log.error("首帧底图归属校验失败: recordId={}, ownerUserId={}, requestUserId={}",
                    recordId, rec.getUserId(), userId);
            throw new ServiceException("无权访问");
        }
        if (!Objects.equals(storyboard.getId(), rec.getStoryboardId()))
        {
            log.error("首帧底图与分镜不匹配: recordId={}, recStoryboardId={}, requestStoryboardId={}",
                    recordId, rec.getStoryboardId(), storyboard.getId());
            throw new ServiceException("底图不可用");
        }
        if (StrUtil.isBlank(rec.getFileUrl()))
        {
            log.error("首帧底图 fileUrl 为空: recordId={}", recordId);
            throw new ServiceException("底图未就绪");
        }
        // DB 存相对路径，下游 provider 需完整 URL（与图生图链路 / 资产提取链路保持一致）
        return mediaUrlResolver.toFullUrl(rec.getFileUrl());
    }
    /** 单镜头准备结果（解析后的不可变快照，taskId 在建任务后再绑定）。 */
    private static final class PreparedShot
    {
        final AidStoryboard storyboard;
        final String finalPrompt;
        final String rawVideoPrompt;
        final List<String> referenceImages;
        final String baseImageUrl;
        final String userVideoPromptInput;
        final int takeCount;
        /** 首尾帧出片专用：尾帧图 URL（其余方向为 null） */
        final String lastFrameImageUrl;
        /** 首尾帧出片专用：首图记录 ID（走库内记录时落 aid_gen_record.first_image_id；上传图为 null） */
        final Long firstImageRecordId;
        /** 首尾帧出片专用：尾图记录 ID（走库内记录时落 aid_gen_record.last_image_id；上传图为 null） */
        final Long lastImageRecordId;
        /** 首尾帧出片专用：首图上传 URL 原始值（续生快照持久化用；走记录时为 null） */
        final String firstImageUrl;
        /** 首尾帧出片专用：尾图上传 URL 原始值（续生快照持久化用；走记录/无尾帧时为 null） */
        final String lastImageUrl;
        /** 首尾帧出片专用：配音参考项（url+时长，已校验；续生快照持久化用；其余方向为空） */
        final List<StoryboardVideoEdgeGenerateRequest.EdgeAudioItem> audioItems;
        /** 厂商专属扩展参数（装配策略产出，如 Vidu 主体调用 subjects），提交时并入 options */
        final Map<String, Object> providerExtraOptions;

        PreparedShot(AidStoryboard storyboard, String finalPrompt, String rawVideoPrompt,
                     List<String> referenceImages, String baseImageUrl, String userVideoPromptInput, int takeCount)
        {
            this(storyboard, finalPrompt, rawVideoPrompt, referenceImages, baseImageUrl, userVideoPromptInput,
                    takeCount, null, null, null, null, null, null, null);
        }

        PreparedShot(AidStoryboard storyboard, String finalPrompt, String rawVideoPrompt,
                     List<String> referenceImages, String baseImageUrl, String userVideoPromptInput, int takeCount,
                     Map<String, Object> providerExtraOptions)
        {
            this(storyboard, finalPrompt, rawVideoPrompt, referenceImages, baseImageUrl, userVideoPromptInput,
                    takeCount, null, null, null, null, null, null, providerExtraOptions);
        }

        PreparedShot(AidStoryboard storyboard, String finalPrompt, String rawVideoPrompt,
                     List<String> referenceImages, String baseImageUrl, String userVideoPromptInput, int takeCount,
                     String lastFrameImageUrl, Long firstImageRecordId, Long lastImageRecordId,
                     String firstImageUrl, String lastImageUrl,
                     List<StoryboardVideoEdgeGenerateRequest.EdgeAudioItem> audioItems)
        {
            this(storyboard, finalPrompt, rawVideoPrompt, referenceImages, baseImageUrl, userVideoPromptInput,
                    takeCount, lastFrameImageUrl, firstImageRecordId, lastImageRecordId,
                    firstImageUrl, lastImageUrl, audioItems, null);
        }

        PreparedShot(AidStoryboard storyboard, String finalPrompt, String rawVideoPrompt,
                     List<String> referenceImages, String baseImageUrl, String userVideoPromptInput, int takeCount,
                     String lastFrameImageUrl, Long firstImageRecordId, Long lastImageRecordId,
                     String firstImageUrl, String lastImageUrl,
                     List<StoryboardVideoEdgeGenerateRequest.EdgeAudioItem> audioItems,
                     Map<String, Object> providerExtraOptions)
        {
            this.storyboard = storyboard;
            this.finalPrompt = finalPrompt;
            this.rawVideoPrompt = rawVideoPrompt;
            this.referenceImages = (referenceImages == null)
                    ? java.util.Collections.emptyList() : referenceImages;
            this.baseImageUrl = baseImageUrl;
            this.userVideoPromptInput = userVideoPromptInput;
            this.takeCount = takeCount;
            this.lastFrameImageUrl = lastFrameImageUrl;
            this.firstImageRecordId = firstImageRecordId;
            this.lastImageRecordId = lastImageRecordId;
            this.firstImageUrl = firstImageUrl;
            this.lastImageUrl = lastImageUrl;
            this.audioItems = (audioItems == null) ? java.util.Collections.emptyList() : audioItems;
            this.providerExtraOptions = (providerExtraOptions == null)
                    ? java.util.Collections.emptyMap() : providerExtraOptions;
        }
    }

    /** 单镜头准备回调（锁内执行；解析失败抛 ServiceException）。 */
    @FunctionalInterface
    private interface ShotPreparer
    {
        PreparedShot prepare(AidStoryboard storyboard);
    }

    /** 多参方向单镜头准备：提示词来源 + 参考图解析 + 厂商装配。 */
    private PreparedShot prepareMultiShot(AidStoryboard sb, boolean single,
            StoryboardVideoGenerateRequest request, AiModelConfigVo modelConfig, Long userId, int takeCount)
    {
        String overridePrompt = single ? request.getVideoPrompt() : null;
        String videoPrompt = resolveVideoPrompt(overridePrompt, sb);
        if (single && StrUtil.isNotBlank(overridePrompt))
        {
            persistVideoPrompt(sb.getId(), userId, videoPrompt);
        }
        ReferenceResolution resolution = resolveReferences(videoPrompt, sb, userId,
                single ? request.getReferenceOverrides() : null);
        String baseImageUrl = resolveBaseImageUrl(single ? request.getBaseImageRecordId() : null, sb, userId);
        int maxRefImages = com.aid.media.provider.ReferenceImageLimiter.resolveMax(modelConfig, MAX_REFERENCE_IMAGES);
        if (maxRefImages == com.aid.media.provider.ReferenceImageLimiter.FORBID)
        {
            resolution = compactResolvedReferences(resolution.prompt, java.util.Collections.emptyList());
        }
        else if (resolution.references.size() > maxRefImages)
        {
            resolution = compactResolvedReferences(resolution.prompt,
                    new ArrayList<>(resolution.references.subList(0, maxRefImages)));
        }
        int minReferenceImages = com.aid.media.provider.ReferenceImageLimiter.readMinFromCapabilityJson(
                modelConfig.getCapabilityJson());
        if (resolution.references.size() < minReferenceImages)
        {
            log.info("多参视频有效参考图不足: storyboardId={}, required={}, actual={}",
                    sb.getId(), minReferenceImages, resolution.references.size());
            throw new ServiceException("请选择参考图");
        }
        if (CollectionUtil.isEmpty(resolution.references)
                && StrUtil.isBlank(baseImageUrl)
                && Boolean.FALSE.equals(modelConfig.getSupportsTextInput()))
        {
            log.info("多参视频零引用且模型不支持纯文本: storyboardId={}, modelCode={}",
                    sb.getId(), modelConfig.getModelCode());
            throw new ServiceException("请选择参考图");
        }
        VideoReferenceContext ctx = new VideoReferenceContext(resolution.prompt, request.getUserInputText(),
                resolution.references, baseImageUrl, modelConfig, request.getGenerateAudio(), maxRefImages);
        VideoReferencePlan plan = videoReferencePlanner.plan(ctx);
        String finalPrompt = plan.getFinalPrompt();
        if (StrUtil.length(finalPrompt) > MAX_PROMPT_LENGTH)
        {
            log.info("多参图生视频最终提示词过长: storyboardId={}, len={}", sb.getId(), StrUtil.length(finalPrompt));
            throw new ServiceException("提示词过长");
        }
        return new PreparedShot(sb, finalPrompt, videoPrompt, plan.getReferenceImageUrls(),
                plan.getFirstFrameImageUrl(), overridePrompt, takeCount, plan.getExtraOptions());
    }

    /** 图生方向单镜头准备：提示词回落 video_prompt_image + 单张参考图（前端直传或回落分镜主图）。 */
    private PreparedShot prepareImageShot(AidStoryboard sb, boolean single,
            StoryboardVideoFromImageGenerateRequest request, AiModelConfigVo modelConfig, Long userId, int takeCount)
    {
        boolean useFrontPrompt = single && StrUtil.isNotBlank(request.getVideoPrompt());
        String videoPrompt = useFrontPrompt ? request.getVideoPrompt() : sb.getVideoPromptImage();
        if (StrUtil.isBlank(videoPrompt))
        {
            log.info("图生视频提示词为空(video_prompt_image 未生成且未传入): storyboardId={}", sb.getId());
            throw new ServiceException("请先生成提示词");
        }
        if (videoPrompt.length() > MAX_PROMPT_LENGTH)
        {
            log.info("图生视频提示词过长(含库回落): storyboardId={}, len={}", sb.getId(), videoPrompt.length());
            throw new ServiceException("提示词过长");
        }
        int maxRef = com.aid.media.provider.ReferenceImageLimiter.resolveMax(modelConfig, MAX_REFERENCE_IMAGES);
        if (maxRef == com.aid.media.provider.ReferenceImageLimiter.FORBID)
        {
            log.info("图生视频模型禁用参考图: storyboardId={}", sb.getId());
            throw new ServiceException("该模型不支持图片");
        }
        // 参考图：单镜头前端直传取首张有效；否则（含多镜头）回落分镜主图(final_image)
        String refImageUrl = null;
        boolean refFromUpload = false;
        if (single && CollectionUtil.isNotEmpty(request.getImages()))
        {
            for (String u : request.getImages())
            {
                String url = StrUtil.trim(u);
                if (StrUtil.isNotBlank(url)) { refImageUrl = url; refFromUpload = true; break; }
            }
        }
        if (!refFromUpload)
        {
            refImageUrl = resolveBaseImageUrl(null, sb, userId);
        }
        if (StrUtil.isBlank(refImageUrl))
        {
            log.info("图生视频无参考图且无分镜主图: storyboardId={}", sb.getId());
            throw new ServiceException("请选择参考图");
        }
        if (refFromUpload)
        {
            // 仅允许本站资源（相对路径或本站域名完整URL），拒绝站外外链
            if (!mediaUrlResolver.isSiteImageUrl(refImageUrl))
            {
                log.info("图生视频参考图非本站资源: storyboardId={}, url={}", sb.getId(), refImageUrl);
                throw new ServiceException("图片不可用");
            }
            // 相对路径拼完整URL，并据此做远程可达性 + Content-Type 校验
            refImageUrl = mediaUrlResolver.toFullUrl(refImageUrl);
            if (!ImageUrlValidator.isValidRemoteImageUrl(refImageUrl))
            {
                log.info("图生视频参考图非法(格式/不可达/非图片): storyboardId={}, url={}", sb.getId(), refImageUrl);
                throw new ServiceException("图片不可用");
            }
        }
        if (useFrontPrompt)
        {
            persistVideoPromptImage(sb.getId(), userId, videoPrompt);
        }
        List<ResolvedReference> references = new ArrayList<>();
        references.add(new ResolvedReference(1, "参考图1", "参考图1", null, false, refImageUrl));
        String resolvedBaseImageUrl = (single && Objects.nonNull(request.getBaseImageRecordId()))
                ? resolveBaseImageUrl(request.getBaseImageRecordId(), sb, userId) : null;
        VideoReferenceContext ctx = new VideoReferenceContext(videoPrompt, request.getUserInputText(),
                references, resolvedBaseImageUrl, modelConfig, request.getGenerateAudio(), maxRef);
        VideoReferencePlan plan = videoReferencePlanner.plan(ctx);
        String finalPrompt = plan.getFinalPrompt();
        if (StrUtil.length(finalPrompt) > MAX_PROMPT_LENGTH)
        {
            log.info("图生视频最终提示词过长: storyboardId={}, len={}", sb.getId(), StrUtil.length(finalPrompt));
            throw new ServiceException("提示词过长");
        }
        return new PreparedShot(sb, finalPrompt, videoPrompt, plan.getReferenceImageUrls(),
                plan.getFirstFrameImageUrl(), useFrontPrompt ? request.getVideoPrompt() : null, takeCount);
    }

    /**
     * 首尾帧方向单镜头准备：解析首图(首帧)+可选尾图(尾帧)+配音参考+提示词。
     * 首/尾帧来源二选一(上传 URL 优先于库内记录 ID)：上传 URL 走本站图片校验,记录 ID 走 {@code resolveBaseImageUrl}(须属本分镜)。
     * 首帧必填(两种都缺报「请选首帧」);尾帧可选——传则关键帧动画(首→尾),不传则仅首帧单帧图生视频。
     * 提示词:单镜头入参优先 → 库回落 {@code aid_storyboard.video_prompt} → 双空报「请先生成提示词」。
     * 配音(逐镜头校验 ≤7 段/≤10 秒,多镜头单镜头失败仅跳过)透传到 {@link PreparedShot}。
     */
    private PreparedShot prepareEdgeShot(AidStoryboard sb, boolean single,
            StoryboardVideoEdgeGenerateRequest request, Map<Long, EdgeResolved> edgeMap,
            AiModelConfigVo modelConfig, Long userId, int takeCount)
    {
        EdgeResolved er = edgeMap.get(sb.getId());
        boolean firstFromUpload = er != null && StrUtil.isNotBlank(er.firstImageUrl);
        String firstUrl = (er == null) ? null : resolveEdgeFrameUrl(er.firstImageUrl, er.firstImageRecordId, sb, userId);
        if (StrUtil.isBlank(firstUrl))
        {
            log.info("首尾帧生视频缺少首帧来源(上传图/记录ID 均无): storyboardId={}", sb.getId());
            throw new ServiceException("请选择首帧");
        }
        // 上传图无对应 gen_record，记录 ID 仅在走库内记录时落库 first_image_id
        Long firstRecId = firstFromUpload ? null : (er != null ? er.firstImageRecordId : null);
        boolean lastFromUpload = er != null && StrUtil.isNotBlank(er.lastImageUrl);
        String lastUrl = (er == null) ? null : resolveEdgeFrameUrl(er.lastImageUrl, er.lastImageRecordId, sb, userId);
        Long lastRecId = lastFromUpload ? null : (er != null ? er.lastImageRecordId : null);
        List<StoryboardVideoEdgeGenerateRequest.EdgeAudioItem> audioItems =
                (er != null) ? er.audioItems : new ArrayList<>();
        validateEdgeAudios(audioItems);
        String overridePrompt = single ? request.getVideoPrompt() : null;
        String videoPrompt = resolveVideoPrompt(overridePrompt, sb);
        if (single && StrUtil.isNotBlank(overridePrompt))
        {
            persistVideoPrompt(sb.getId(), userId, videoPrompt);
        }
        VideoReferenceContext ctx = new VideoReferenceContext(videoPrompt, request.getUserInputText(),
                new ArrayList<>(), firstUrl, modelConfig, request.getGenerateAudio(), 0);
        VideoReferencePlan plan = videoReferencePlanner.plan(ctx);
        String finalPrompt = plan.getFinalPrompt();
        if (StrUtil.length(finalPrompt) > MAX_PROMPT_LENGTH)
        {
            log.info("首尾帧生视频最终提示词过长: storyboardId={}, len={}", sb.getId(), StrUtil.length(finalPrompt));
            throw new ServiceException("提示词过长");
        }
        return new PreparedShot(sb, finalPrompt, videoPrompt, new ArrayList<>(),
                plan.getFirstFrameImageUrl(), overridePrompt, takeCount, lastUrl,
                firstRecId, lastRecId,
                firstFromUpload ? er.firstImageUrl : null, lastFromUpload ? er.lastImageUrl : null,
                audioItems);
    }

    /** 抢镜头级 Redis 防重锁（token + 原子 CAS + 在建宽限 + 重抢后二次查 DB）：成功返回持有的 token，失败返回 null。 */
    private String acquireShotLock(String lockKey, Long storyboardId)
    {
        // 锁值 = 时间戳:uuid，时间戳用于「在建宽限期」判定，uuid 保证全局唯一、CAS 只删/续自己的锁
        String token = newLockToken();
        Boolean locked = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(lockKey, token, FORM_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(locked))
        {
            // 抢到锁后再查 DB：批量顺序执行可能让靠后镜头锁超 TTL 过期，避免对仍活跃父任务重复出片/扣费
            if (hasActiveTaskInDb(storyboardId))
            {
                releaseLockIfMine(lockKey, token);
                log.info("分镜图生视频抢锁后发现父任务仍活跃(锁曾过期)，拒绝重复出片: storyboardId={}", storyboardId);
                return null;
            }
            return token;
        }
        // SETNX 失败：DB 有活跃任务 → 真冲突，处理中
        if (hasActiveTaskInDb(storyboardId))
        {
            return null;
        }
        // DB 暂无活跃任务但锁存在：可能 (a) 并发请求刚 SETNX 成功、父任务尚未入库；(b) 进程崩溃残留泄漏锁。
        Object existing = redisCache.getCacheObject(lockKey);
        long lockTs = parseLeadingTs(existing);
        // (a) 锁仍在「在建宽限期」内 → 视为并发在建，按处理中对待，绝不抢占（避免删对方有效锁导致重复受理）
        if (lockTs > 0 && System.currentTimeMillis() - lockTs < LOCK_INFLIGHT_GRACE_MS)
        {
            log.info("分镜图生视频锁处于在建宽限期(疑似并发在建任务)，按处理中对待: storyboardId={}", storyboardId);
            return null;
        }
        // (b) 超宽限期 → 视为泄漏：原子 compare-and-delete（仅当锁仍是我读到的那个值才删，避免删掉期间被他人重抢的新锁）
        log.warn("分镜图生视频 Redis 锁疑似泄漏（DB 无活跃任务且超在建宽限期），CAS 清理重抢: lockKey={}", lockKey);
        releaseLockIfMine(lockKey, existing);
        String newToken = newLockToken();
        Boolean re = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(lockKey, newToken, FORM_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(re))
        {
            // 清理后未抢到 → 期间被他人抢走，按处理中对待
            return null;
        }
        // 关键：重抢成功后再查一次 DB，闭合「A 刚插入父任务、B 已删锁重抢」的 TOCTOU 窗口
        if (hasActiveTaskInDb(storyboardId))
        {
            releaseLockIfMine(lockKey, newToken);
            log.info("分镜图生视频重抢后发现父任务已活跃，CAS 释放并拒绝: storyboardId={}", storyboardId);
            return null;
        }
        return newToken;
    }

    /** 锁值 token：毫秒时间戳:UUID。 */
    private String newLockToken()
    {
        return System.currentTimeMillis() + ":" + java.util.UUID.randomUUID();
    }

    /** 原子 compare-and-delete：仅当锁当前值 == 传入值时删除（只删自己/指定值的锁）。 */
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

    /** 原子 compare-and-expire：仅当锁当前值 == token 时续租 TTL（避免续到他人锁）。 */
    private void renewLockIfMine(String lockKey, String token)
    {
        if (lockKey == null || token == null)
        {
            return;
        }
        try
        {
            // TTL 已写死在脚本中（避免 ARGV 被序列化成带引号字符串导致 EXPIRE 失败），此处只传 token 比较
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
     * 单镜头（single=true）任一步失败直接抛错；多镜头时单镜头失败仅跳过并记 ShotResult，不阻断其余。
     */
    private StoryboardVideoGenerateVO submitBatch(Long userId, List<Long> ids, boolean single, int perShotCount,
            String modelCode, AiModelConfigVo modelConfig, Long modelId, String aspectRatio, String resolution,
            Integer durationSeconds, Boolean generateAudio, String userInputText, String funcCode, String direction,
            ShotPreparer preparer)
    {
        List<PreparedShot> prepared = new ArrayList<>();
        List<ShotLock> heldLocks = new ArrayList<>();
        List<StoryboardVideoGenerateVO.ShotResult> rejected = new ArrayList<>();
        try
        {
            for (Long id : ids)
            {
                String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_VIDEO_GENERATE + ":" + id;
                ShotLock heldThis = null;
                try
                {
                    AidStoryboard sb = loadAndCheckStoryboard(id, userId);
                    String token = acquireShotLock(lockKey, id);
                    if (token == null)
                    {
                        if (single) { throw new ServiceException("任务处理中"); }
                        rejected.add(new StoryboardVideoGenerateVO.ShotResult(id, false, "任务处理中"));
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
                    log.info("分镜批量出片跳过镜头: storyboardId={}, reason={}", id, e.getMessage());
                    rejected.add(new StoryboardVideoGenerateVO.ShotResult(id, false, shortReason(e.getMessage())));
                }
            }
            if (prepared.isEmpty())
            {
                log.info("分镜批量出片无可受理镜头: ids={}, rejectedCount={}", ids, rejected.size());
                throw new ServiceException("操作失败，请重试");
            }
            return createBatchTaskAndEnqueue(userId, modelCode, modelId, modelConfig, aspectRatio, resolution,
                    durationSeconds, generateAudio, userInputText, perShotCount, funcCode, direction, prepared,
                    heldLocks, rejected);
        }
        catch (RuntimeException e)
        {
            for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
            throw e;
        }
    }

    /** 创建单一父任务（写 input_snapshot/totalCount）+ 构建每镜头 Job + 入队异步执行。 */
    private StoryboardVideoGenerateVO createBatchTaskAndEnqueue(Long userId, String modelCode, Long modelId,
            AiModelConfigVo modelConfig, String aspectRatio, String resolution, Integer durationSeconds,
            Boolean generateAudio, String userInputText, int perShotCount, String funcCode, String direction,
            List<PreparedShot> prepared, List<ShotLock> heldLocks, List<StoryboardVideoGenerateVO.ShotResult> rejected)
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
            // 持久化锁 token：外部（取消/队列收尾）释放锁时按 token 走 compare-and-delete，不裸删
            s.put("lockToken", heldLocks.get(i).token);
            // 首尾帧出片：持久化首/尾帧来源（上传 URL / 记录 ID）+ 配音项，续生时据此重解析（首尾帧/配音入参不可由分镜单图还原）
            putEdgeSnapshot(s, ps);
            shotsSnapshot.add(s);
            // 稳定全集（不含 token，续生时原样保留）：用于续生时兜底"未开始 / 无成功快照"的镜头全集
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("storyboardId", ps.storyboard.getId());
            a.put("takeCount", ps.takeCount);
            putEdgeSnapshot(a, ps);
            allShotsSnapshot.add(a);
        }

        AidExtractTask task = new AidExtractTask();
        task.setProjectId(projectId);
        task.setEpisodeId(episodeId);
        task.setUserId(userId);
        task.setTaskType(TASK_TYPE_STORYBOARD_VIDEO_GENERATE);
        task.setModelCode(modelCode);
        Map<String, Object> inputMap = new LinkedHashMap<>();
        inputMap.put("direction", direction);
        // 持久化解析定型的模型池编码：续生直接据此还原 func_code（多参 pro 分流仅凭 direction 无法还原）
        inputMap.put("funcCode", funcCode);
        inputMap.put("storyboardIds", acceptedIds);
        inputMap.put("modelCode", modelCode);
        inputMap.put("aspectRatio", aspectRatio);
        inputMap.put("resolution", resolution);
        inputMap.put("durationSeconds", durationSeconds);
        inputMap.put("countPerShot", perShotCount);
        inputMap.put("generateAudio", generateAudio);
        inputMap.put("userInputText", userInputText);
        inputMap.put("shots", shotsSnapshot);
        // 稳定全集：续生时据此兜底全部镜头（含未开始/无成功快照的），不依赖增量 resultData.shots
        inputMap.put("allShots", allShotsSnapshot);
        // 运行批次号：fresh 提交固定 0；续生在 resumeVideo 内 +1 并回写
        inputMap.put("runNo", 0);
        try
        {
            task.setInputSnapshot(OBJECT_MAPPER.writeValueAsString(inputMap));
        }
        catch (Exception e)
        {
            log.warn("分镜批量出片 inputSnapshot 序列化失败, 降级: direction={}", direction, e);
            task.setInputSnapshot("{\"direction\":\"" + direction + "\"}");
        }
        task.setStatus(TASK_STATUS_PENDING);
        task.setTotalCount(newSubtasks);
        task.setDelFlag(DEL_FLAG_NORMAL);
        task.setCreateTime(DateUtils.getNowDate());
        task.setCreateBy(String.valueOf(userId));
        // 校验前置：余额只读预检提前到建任务之前（预估 = 单条视频预扣 × 子任务数），
        // 余额不足直接拦截，不再产生「先建任务再媒体冻结失败」的废任务；最终以媒体任务锁内冻结为准
        precheckVideoBatchBalance(userId, modelConfig, aspectRatio, resolution, durationSeconds, generateAudio, newSubtasks);
        extractTaskService.save(task);
        Long taskId = task.getId();

        boolean enqueued;
        try
        {
            // 稳定镜头序号（fresh：prepared 顺序 == allShots 顺序）+ 本轮全槽位 [0..takeCount-1]
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
                    aspectRatio, resolution, durationSeconds, generateAudio, userInputText, perShotCount, direction,
                    prepared, heldLocks,
                    newSubtasks, prepared.size(), 0, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    java.util.Collections.emptyMap(), 0, false, shotOrdinalById, takeSlotsByShot);
        }
        catch (RuntimeException ex)
        {
            // 入队过程抛异常（非返回 false）：父任务已落库，须置 FAILED + 释放锁，避免遗留 PENDING 僵尸
            log.error("分镜图生视频批量入队异常: taskId={}", taskId, ex);
            updateTaskFailed(taskId, "提交失败");
            for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
            throw new ServiceException("提交失败，请重试");
        }
        if (!enqueued)
        {
            log.error("分镜图生视频批量入队失败: taskId={}", taskId);
            updateTaskFailed(taskId, "提交失败");
            for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
            throw new ServiceException("提交失败，请重试");
        }

        StoryboardVideoGenerateVO vo = new StoryboardVideoGenerateVO();
        vo.setTaskId(taskId);
        vo.setStatus(TASK_STATUS_PENDING);
        vo.setModelName(modelCode);
        vo.setTotalShots(prepared.size());
        vo.setCountPerShot(perShotCount);
        vo.setTotalSubtasks(newSubtasks);
        List<StoryboardVideoGenerateVO.ShotResult> items = new ArrayList<>();
        for (PreparedShot ps : prepared)
        {
            items.add(new StoryboardVideoGenerateVO.ShotResult(ps.storyboard.getId(), true, null));
        }
        items.addAll(rejected);
        vo.setItems(items);
        return vo;
    }

    /**
     * 出片批量余额前置预检（只读，不冻结）：按主要 SKU 维度（时长/分辨率/音频）预估单条视频预扣额 × 总条数，
     * 余额不足直接抛「余额不足」；预估不出（模型未配规则等）时放行，交由媒体任务统一冻结兜底。
     *
     * @param userId          用户ID
     * @param modelConfig     出片模型配置
     * @param aspectRatio     宽高比（分辨率缺省时用于推断档位）
     * @param resolution      已解析的清晰度档位（可空；与下发/冻结同源）
     * @param durationSeconds 单条时长（秒）
     * @param generateAudio   是否音画同出
     * @param totalTakes      本批总生成条数
     */
    private void precheckVideoBatchBalance(Long userId, AiModelConfigVo modelConfig, String aspectRatio,
            String resolution, Integer durationSeconds, Boolean generateAudio, int totalTakes)
    {
        try
        {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("duration", (Objects.nonNull(durationSeconds) && durationSeconds > 0) ? durationSeconds : 5);
            // 档位与真实下发同源（归一化口径与 BillingInputExtractor 一致）；未解析出档位时退回按宽高比推断
            if (StrUtil.isNotBlank(resolution))
            {
                String tier = com.aid.billing.util.ResolutionUtil.parseTier(resolution);
                params.put("resolution", StrUtil.isNotBlank(tier) ? tier : resolution.trim());
            }
            else
            {
                params.put("resolution", com.aid.billing.util.ResolutionUtil.inferVideoResolution(aspectRatio));
            }
            params.put("audio", Boolean.TRUE.equals(generateAudio));
            com.aid.billing.dto.BillingCalcResult calc = billingAmountCalculator.calculatePreHoldAmount(
                    modelConfig, new com.aid.billing.dto.BillingInput("VIDEO", params));
            if (Objects.isNull(calc) || !calc.isMatched() || Objects.isNull(calc.getAmount()))
            {
                // 预估不出时不拦截：预检只是提前失败的轻量防线，冻结环节仍会硬校验
                return;
            }
            java.math.BigDecimal total = calc.getAmount()
                    .multiply(java.math.BigDecimal.valueOf(Math.max(totalTakes, 1)));
            accountUpdateService.precheckBalance(userId, total);
        }
        catch (ServiceException e)
        {
            // 余额不足等业务异常原样上抛（建任务前拦截）
            throw e;
        }
        catch (Exception e)
        {
            log.warn("分镜出片余额预检异常(忽略, 交由统一冻结兜底): userId={}", userId, e);
        }
    }

    /** 构建每镜头 VideoGenJob（共享 taskId）+ 组装 VideoBatchJob 入队。返回是否入队成功。
     *  @param totalShots 原批次镜头总数（累计口径，写入 resultData.totalShots；续生时为原始全集大小，非本轮补跑数）
     *  @param shotOrdinalById 镜头稳定序号（allShots 位置，0 基）：用于 bizTaskId 基址，保证跨 fresh/续生同一镜头同一基址
     *  @param takeSlotsByShot 每镜头本轮要生成的逻辑 take 槽位（0 基）：fresh=[0..takeCount-1]，续生=缺失槽位 */
    private boolean buildJobsAndEnqueue(Long taskId, Long projectId, Long episodeId, Long userId, String modelCode,
            Long modelId, AiModelConfigVo modelConfig, String aspectRatio, String resolution, Integer durationSeconds,
            Boolean generateAudio, String userInputText, int perShotCount, String direction, List<PreparedShot> prepared,
            List<ShotLock> heldLocks, int newSubtasks, int totalShots, int seedSuccessCount, List<Long> seedRecordIds,
            List<Map<String, Object>> seedItems, List<Map<String, Object>> seedShotResults,
            Map<Long, Map<String, Object>> seedShotPrior, int runNo, boolean forcePartial,
            Map<Long, Integer> shotOrdinalById, Map<Long, List<Integer>> takeSlotsByShot)
    {
        // 入队前清理扇入计数/收尾标记：每轮（首跑/续生）从干净状态开始，兜底上一轮被僵尸回收未 cleanup 的残留
        fanInSupport.cleanup(taskId);
        // 出片产物类型：首尾帧方向落 gen_type=edge，其余方向沿用 i2v
        String genType = DIRECTION_EDGE.equals(direction) ? GEN_TYPE_EDGE : GEN_TYPE_I2V;
        List<VideoGenJob> shotJobs = new ArrayList<>();
        for (int i = 0; i < prepared.size(); i++)
        {
            PreparedShot ps = prepared.get(i);
            ShotLock lock = heldLocks.get(i);
            Long sid = ps.storyboard.getId();
            // 稳定镜头序号（allShots 位置）→ bizTaskId 基址；续生时同一镜头取同一 ordinal，保证同槽位同 bizTaskId
            int ordinal = (shotOrdinalById != null && shotOrdinalById.get(sid) != null) ? shotOrdinalById.get(sid) : i;
            long bizSeqBase;
            try
            {
                // bizSeqBase = 父任务编码段 + ordinal*1000；ordinal<20、槽位<count(≤4) → 不跨父任务编码空间
                bizSeqBase = Math.addExact(
                        Math.multiplyExact(taskId, MediaGenFanInSupport.BIZ_SEQ_PARENT_FACTOR),
                        (long) ordinal * 1000L);
            }
            catch (ArithmeticException overflow)
            {
                // taskId 异常大导致编码溢出：直接判提交失败，避免 bizTaskId 冲突误判幂等
                log.error("分镜图生视频 bizSeqBase 编码溢出: taskId={}, ordinal={}", taskId, ordinal, overflow);
                throw new ServiceException("提交失败，请重试");
            }
            List<Integer> takeSlots = (takeSlotsByShot != null) ? takeSlotsByShot.get(sid) : null;
            if (CollectionUtil.isEmpty(takeSlots))
            {
                // 兜底：无显式槽位（理论不出现）→ 按本镜头条数取前 N 槽位
                takeSlots = new ArrayList<>();
                for (int s = 0; s < ps.takeCount; s++) { takeSlots.add(s); }
            }
            shotJobs.add(new VideoGenJob(taskId, userId, ps.storyboard, modelCode, modelId, modelConfig,
                    ps.finalPrompt, ps.rawVideoPrompt, ps.referenceImages, ps.baseImageUrl, ps.takeCount,
                    aspectRatio, resolution, durationSeconds, generateAudio, userInputText, ps.userVideoPromptInput,
                    bizSeqBase, takeSlots, lock.key, lock.token,
                    genType, ps.lastFrameImageUrl, ps.firstImageRecordId, ps.lastImageRecordId,
                    extractAudioUrls(ps.audioItems), ps.providerExtraOptions));
        }
        VideoBatchJob batchJob = new VideoBatchJob(taskId, userId, modelCode, perShotCount, totalShots,
                seedSuccessCount + newSubtasks, seedSuccessCount, runNo, shotJobs, new ArrayList<>(heldLocks),
                seedRecordIds, seedItems, seedShotResults, seedShotPrior, forcePartial);
        return taskQueueService.submitLocalTask(taskId, projectId, episodeId, userId, modelCode,
                TASK_TYPE_STORYBOARD_VIDEO_GENERATE, () -> runAsyncBatch(batchJob));
    }

    /**
     * 组装单镜头 shotResult，并合并续生携带的既有进度（保证 takeCount/successCount/recordIds 为累计口径）。
     */
    private Map<String, Object> buildShotResultEntry(VideoBatchJob batch, VideoGenJob shot,
            List<Long> shotRecordIds, int shotSuccess, List<Integer> failedTakes)
    {
        Long sid = shot.storyboard.getId();
        Map<String, Object> prior = batch.seedShotPrior.get(sid);
        int origTakeCount = shot.videoCount;
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
    private Map<String, Object> buildBatchResultMap(VideoBatchJob batch, int total, List<Long> recordIds,
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

    /** 组装单镜头单条 take 的 gen_params JSON 快照（落 aid_gen_record.gen_params NOT NULL 列）。
     *  含 parentTaskId：保留父任务快照，便于排查。
     *  含 takeIndex：该 take 的逻辑槽位（0 基），续生时据此精确算出"已成功槽位"，只补缺失槽位、不重复补已成功的；
     *  含 bizSeq：下发媒体层的确定性 bizTaskId（= bizSeqBase + takeIndex），也是父子任务收尾关联键。 */
    private String buildShotGenParamsJson(Long parentTaskId, Long storyboardId, String modelCode, String aspectRatio,
            String resolution, Integer durationSeconds, int takeCount, Boolean generateAudio, String userInputText,
            String videoPromptInput, int takeIndex, long bizSeq)
    {
        Map<String, Object> genParamsMap = new LinkedHashMap<>();
        genParamsMap.put("parentTaskId", parentTaskId);
        genParamsMap.put("storyboardId", storyboardId);
        genParamsMap.put("modelCode", modelCode);
        genParamsMap.put("aspectRatio", aspectRatio);
        genParamsMap.put("resolution", resolution);
        genParamsMap.put("durationSeconds", durationSeconds);
        genParamsMap.put("count", takeCount);
        // 逻辑 take 槽位（0 基）+ 确定性 bizTaskId：续生按槽位做集合差，精确补缺、媒体层幂等去重
        genParamsMap.put("takeIndex", takeIndex);
        genParamsMap.put("bizSeq", bizSeq);
        genParamsMap.put("generateAudio", generateAudio);
        genParamsMap.put("userInputText", userInputText);
        genParamsMap.put("videoPromptInput", videoPromptInput);
        try
        {
            return OBJECT_MAPPER.writeValueAsString(genParamsMap);
        }
        catch (Exception e)
        {
            log.warn("分镜图生视频 genParams 序列化失败, 降级最小快照: storyboardId={}", storyboardId, e);
            return "{\"parentTaskId\":" + parentTaskId + ",\"storyboardId\":" + storyboardId
                    + ",\"takeIndex\":" + takeIndex + ",\"bizSeq\":" + bizSeq + "}";
        }
    }

    /** 执行分镜视频批量生成。 */
    private void runAsyncBatch(VideoBatchJob batch)
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
                log.warn("分镜批量出片任务已被其他线程处理, 跳过: taskId={}", taskId);
                return;
            }
            // 同步提交阶段登记心跳常驻续租：同步直出条目可能耗时 > 租约 TTL(90s)，只在每镜起点续租一次时，
            // 长耗时同步生成期间租约会过期被僵尸回收误杀。转异步在途后于 finally 移出心跳，改由 media 轮询续租。
            assetExtractService.markTaskProcessing(taskId);
            heartbeatActivated = true;
            if (assetExtractService.isTaskCancelled(taskId))
            {
                if (updateTaskCancelled(taskId)) { sseManager.sendCancelled(taskId, "用户取消"); }
                releaseBatchLocksAndSlots(batch);
                return;
            }

            // 提交进度分母 = 本轮实际要提交的 take 总数（fresh=镜头数×每镜条数，续生=缺失槽位数）
            int totalTakes = 0;
            for (VideoGenJob s : batch.shots) { totalTakes += s.takeSlots.size(); }
            int submittedTakes = 0;
            // 先推一条 0/N，替换掉排队阶段的「已获得执行名额」快照，让前端立刻看到进入提交阶段
            pushSubmitProgress(taskId, totalTakes, 0);

            int asyncPending = 0;
            boolean cancelledMid = false;
            outer:
            for (VideoGenJob shot : batch.shots)
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
                            shot.modelCode, shot.aspectRatio, shot.resolution, shot.durationSeconds, shot.videoCount,
                            shot.generateAudio, shot.userInputText, shot.userVideoPromptInput, slot, bizSeq);
                    try
                    {
                        String reused = reconcileExistingMediaUrl(bizSeq);
                        if (StrUtil.isNotBlank(reused))
                        {
                            persistGenRecordIdempotent(shot, bizSeq, reused, genParamsJson);
                            continue;
                        }
                        MediaTaskResponse resp = submitSingleVideoMedia(shot, bizSeq, genParamsJson);
                        String st = Objects.isNull(resp) ? null : resp.getStatus();
                        if (TASK_STATUS_SUCCEEDED.equals(st))
                        {
                            if (Objects.nonNull(resp) && StrUtil.isNotBlank(resp.getOssUrl()))
                            {
                                // DIRECT 同步直出：内联幂等落库，落库失败不计失败（事件监听幂等兜底，避免双计数）
                                try { persistGenRecordIdempotent(shot, bizSeq, resp.getOssUrl(), genParamsJson); }
                                catch (Exception pe) { log.error("分镜批量出片DIRECT内联落库失败,交由事件兜底: taskId={}, bizSeq={}", taskId, bizSeq, pe); }
                            }
                            else
                            {
                                // 成功但 OSS 未就绪 → 等 OSS 持久化事件扇入收尾，不可计失败（已计费）
                                asyncPending++;
                            }
                        }
                        else if (Objects.nonNull(st) && VIDEO_IN_PROGRESS_STATUSES.contains(st))
                        {
                            asyncPending++;
                        }
                        else
                        {
                            log.error("分镜批量出片提交即终态非成功: taskId={}, storyboardId={}, status={}",
                                    taskId, shot.storyboard.getId(), st);
                            if (Objects.nonNull(resp) && Objects.nonNull(resp.getTaskId()))
                            {
                                // 统一走媒体终态消费入口，失败计数完成后同步清理已无用途的分镜上下文。
                                onMediaVideoTaskTerminal(resp.getTaskId(), false, null);
                            }
                            else
                            {
                                fanInSupport.incrFail(taskId);
                            }
                        }
                    }
                    catch (ShotInFlightException inflight)
                    {
                        asyncPending++;
                        log.info("storyboard video batch take has inflight media task: taskId={}, storyboardId={}, take={}",
                                taskId, shot.storyboard.getId(), slot + 1);
                    }
                    catch (Exception perItemEx)
                    {
                        log.error("分镜批量出片单条提交失败: taskId={}, storyboardId={}, take={}, err={}",
                                taskId, shot.storyboard.getId(), slot + 1, perItemEx.getMessage());
                        fanInSupport.incrFail(taskId);
                    }
                    finally
                    {
                        // 无论复用/成功/在飞/失败，该 take 均已处理完提交动作，推「已提交 X/N」进度
                        submittedTakes++;
                        pushSubmitProgress(taskId, totalTakes, submittedTakes);
                    }
                }
            }

            if (cancelledMid || assetExtractService.isTaskCancelled(taskId))
            {
                if (cancelVideoBatchAtCheckpoint(taskId))
                {
                    releaseBatchLocksAndSlots(batch);
                }
                return;
            }
            if (asyncPending > 0)
            {
                // 异步在途：保持 PROCESSING，锁/名额由事件扇入 finalizeVideoBatchIfDone 收尾；
                // 父任务租约由 media 调度中心轮询子任务时续租。转异步前再续一次租约，给轮询接管留完整 TTL 窗口。
                assetExtractService.touchTaskProcessing(taskId);
                log.info("分镜批量出片已提交,转异步等待扇入: taskId={}, asyncPending={}", taskId, asyncPending);
                return;
            }
            finalizeVideoBatchIfDone(taskId);
        }
        catch (Exception e)
        {
            log.error("分镜批量出片提交阶段失败: taskId={}", taskId, e);
            com.aid.common.error.TaskErrorResult errorResult = com.aid.common.error.ErrorNormalizer.normalize(e);
            if (updateTaskFailed(taskId, errorResult)) { sseManager.sendError(taskId, errorResult); }
            releaseBatchLocksAndSlots(batch);
        }
        finally
        {
            // 同步提交阶段结束：停止心跳续租（保留租约 key）。转异步→交 media 轮询续租；同步终态→租约已随收尾释放。
            if (heartbeatActivated)
            {
                assetExtractService.deactivateTaskProcessingHeartbeat(taskId);
            }
        }
    }

    /** 释放本批次镜头锁 + 取消标记 + 并发名额（取消/失败/提交异常路径用；正常异步路径由收尾释放）。 */
    private void releaseBatchLocksAndSlots(VideoBatchJob batch)
    {
        for (ShotLock l : batch.shotLocks) { releaseLockIfMine(l.key, l.token); }
        try { assetExtractService.clearCancelFlag(batch.taskId); } catch (Exception ignore) { /* ignore */ }
        try { assetExtractService.releaseTaskSlots(batch.taskId); } catch (Exception ignore) { /* ignore */ }
    }

    private boolean cancelVideoBatchAtCheckpoint(Long taskId)
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
                log.error("分镜批量出片取消收尾缺少storyboardIds: taskId={}", taskId);
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
            catch (Exception e) { log.warn("分镜批量出片取消结果序列化失败: taskId={}", taskId, e); }
            boolean updated = updateTaskCancelledWithResult(taskId, resultJson);
            if (updated)
            {
                try { sseManager.sendCancelled(taskId, "用户取消"); }
                catch (Exception e) { log.warn("分镜批量出片取消SSE发送失败: taskId={}", taskId, e); }
                try { fanInSupport.cleanup(taskId); }
                catch (Exception e) { log.warn("分镜批量出片取消清理扇入状态失败: taskId={}", taskId, e); }
                log.info("分镜批量出片在安全检查点取消: taskId={}, total={}, success={}, fail={}, pending={}",
                        taskId, total, successCount, failCount, pendingCount);
            }
            return updated;
        }
        catch (Exception e)
        {
            log.error("分镜批量出片取消收尾失败，保留PROCESSING等待租约回收: taskId={}", taskId, e);
            return false;
        }
    }
    @Override
    public StoryboardVideoGenerateVO resumeVideo(Long taskId, Long userId)
    {
        validateUserId(userId);
        if (Objects.isNull(taskId) || taskId <= 0)
        {
            log.error("分镜批量出片续生入参无效: taskId={}", taskId);
            throw new ServiceException("参数错误");
        }
        AidExtractTask task = extractTaskService.getById(taskId);
        if (Objects.isNull(task) || !DEL_FLAG_NORMAL.equals(task.getDelFlag()))
        {
            throw new ServiceException("任务不存在");
        }
        if (!Objects.equals(userId, task.getUserId()))
        {
            log.error("分镜批量出片续生归属校验失败: taskId={}, owner={}, req={}", taskId, task.getUserId(), userId);
            throw new ServiceException("无权访问");
        }
        if (!TASK_TYPE_STORYBOARD_VIDEO_GENERATE.equals(task.getTaskType()))
        {
            throw new ServiceException("类型不支持");
        }
        if (TASK_STATUS_PENDING.equals(task.getStatus())
                || TASK_STATUS_QUEUED.equals(task.getStatus())
                || TASK_STATUS_PROCESSING.equals(task.getStatus()))
        {
            StoryboardVideoGenerateVO vo = new StoryboardVideoGenerateVO();
            vo.setTaskId(taskId);
            vo.setStatus(task.getStatus());
            vo.setModelName(task.getModelCode());
            vo.setTotalSubtasks(task.getTotalCount());
            return vo;
        }
        if (!TASK_STATUS_PARTIAL_FAILED.equals(task.getStatus())
                && !TASK_STATUS_FAILED.equals(task.getStatus())
                && !TASK_STATUS_CANCELLED.equals(task.getStatus()))
        {
            // 允许从 PARTIAL_FAILED 续生；也允许从 FAILED 续生——崩溃 / 僵尸回收会把执行中的批量任务置 FAILED，
            // 其已成功产物已由增量 resultData 落库，续生据此只补未出满的镜头，不重复出片。
            log.info("分镜批量出片续生拒绝：状态不可续: taskId={}, status={}", taskId, task.getStatus());
            throw new ServiceException("状态不支持");
        }
        // 续生失败需回滚的原值（状态/总数/快照），失败时原样还原
        String originalStatus = task.getStatus();
        String originalInputSnapshot = task.getInputSnapshot();

        JsonNode input;
        try
        {
            input = OBJECT_MAPPER.readTree(StrUtil.blankToDefault(task.getInputSnapshot(), "{}"));
        }
        catch (Exception e)
        {
            log.error("分镜批量出片续生 inputSnapshot 解析失败: taskId={}", taskId, e);
            throw new ServiceException("任务数据异常");
        }
        String direction = input.path("direction").asText(DIRECTION_MULTI);
        String modelCode = input.path("modelCode").asText(null);
        String aspectRatio = input.hasNonNull("aspectRatio") ? input.get("aspectRatio").asText() : null;
        String resolution = input.hasNonNull("resolution") ? input.get("resolution").asText() : null;
        Integer durationSeconds = input.hasNonNull("durationSeconds") ? input.get("durationSeconds").asInt() : null;
        Boolean generateAudio = input.hasNonNull("generateAudio") ? input.get("generateAudio").asBoolean() : null;
        String userInputText = input.hasNonNull("userInputText") ? input.get("userInputText").asText() : null;
        int perShotCount = input.path("countPerShot").asInt(1);
        // 运行批次号（单调，用于 bizTaskId 唯一）：超过编码上限直接拒绝，不 clamp（clamp 会让第 1000 次续生复用 runNo=999 的 bizTaskId）
        int priorRunNo = input.path("runNo").asInt(0);
        if (priorRunNo >= MAX_RUN_NO)
        {
            log.warn("分镜批量出片续生次数超限(编码位耗尽): taskId={}, priorRunNo={}", taskId, priorRunNo);
            throw new ServiceException("重试次数超限");
        }
        int newRunNo = priorRunNo + 1;
        // 续生失败回滚用：totalCount 回滚到原值（status/inputSnapshot 用上面已捕获的 originalStatus/originalInputSnapshot）
        Integer originalTotalCount = task.getTotalCount();

        // 既有成功携带（以落库 gen_record 为真源，保留已出片产物、避免重复扣费；
        // 即便"record 已提交、父快照未刷新就崩溃"也不会重复补跑）
        List<Long> seedRecordIds = new ArrayList<>();
        List<Map<String, Object>> seedItems = new ArrayList<>();
        List<Map<String, Object>> seedShotResults = new ArrayList<>();
        // 部分完成且本轮重跑的镜头的既有进度（storyboardId → {takeCount, successCount, recordIds}），供 shots 累计口径
        Map<Long, Map<String, Object>> seedShotPrior = new LinkedHashMap<>();
        // 全部待补镜头的完整既有进度（含 successCount=0 的），若本轮被跳过未受理则原样回填 shots，保持 PARTIAL_FAILED
        Map<Long, Map<String, Object>> priorShotFull = new LinkedHashMap<>();
        // storyboardId → 剩余待补条数
        Map<Long, Integer> remainByShot = new LinkedHashMap<>();

        // 续生以快照全集为准，避免遗漏未开始或崩溃前未落进度的镜头。
        java.util.LinkedHashMap<Long, Integer> origTakeByShot = new java.util.LinkedHashMap<>();
        // 首尾帧续生：storyboardId → {首图记录ID, 尾图记录ID, 配音URL列表}（从快照还原，首尾帧入参不可由分镜单图重建）
        Map<Long, EdgeResolved> edgeResolvedByShot = new LinkedHashMap<>();
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
                // 首尾帧首/尾帧来源（上传 URL / 记录 ID）+ 配音项（仅 edge 方向快照含这些字段）
                String firstUrl = an.path("firstImageUrl").asText(null);
                String lastUrl = an.path("lastImageUrl").asText(null);
                long firstId = an.path("firstImageRecordId").asLong(0);
                long lastId = an.path("lastImageRecordId").asLong(0);
                List<StoryboardVideoEdgeGenerateRequest.EdgeAudioItem> audioItems = parseAudiosSnapshot(an);
                if (sid > 0 && (StrUtil.isNotBlank(firstUrl) || StrUtil.isNotBlank(lastUrl)
                        || firstId > 0 || lastId > 0 || !audioItems.isEmpty()))
                {
                    edgeResolvedByShot.put(sid, new EdgeResolved(
                            StrUtil.trimToNull(firstUrl), firstId > 0 ? firstId : null,
                            StrUtil.trimToNull(lastUrl), lastId > 0 ? lastId : null, audioItems));
                }
            }
        }
        if (origTakeByShot.isEmpty())
        {
            log.error("分镜批量出片续生全集为空(快照缺 allShots/shots): taskId={}", taskId);
            throw new ServiceException("任务数据异常");
        }
        int originalTotalShots = origTakeByShot.size();

        // 已成功进度以落库记录为准，并按逻辑槽位精确补跑缺失项。
        Map<Long, List<Long>> recordsByShot = new LinkedHashMap<>();
        // storyboardId → 已成功逻辑槽位集合（升序、互异、落 [0,takeCount)）
        Map<Long, java.util.TreeSet<Integer>> succeededSlotsByShot = new LinkedHashMap<>();
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
            if (StrUtil.isNotBlank(rec.getFileUrl())) { m.put("videoUrl", rec.getFileUrl()); }
            seedItems.add(m);
            // 解析槽位；缺失/越界/冲突 → 贪心取最低可用槽位，保证集合内槽位互异且落在 [0,takeCount)
            java.util.TreeSet<Integer> slots = succeededSlotsByShot.computeIfAbsent(sid, k -> new java.util.TreeSet<>());
            Integer slot = parseTakeIndexFromGenParams(rec.getGenParams());
            if (slot == null || slot < 0 || slot >= takeCount || slots.contains(slot))
            {
                slot = null;
                for (int j = 0; j < takeCount; j++) { if (!slots.contains(j)) { slot = j; break; } }
            }
            if (slot != null && slot >= 0 && slot < takeCount) { slots.add(slot); }
        }

        // 稳定镜头序号（按 allShots 顺序，0 基）：续生时同一镜头与 fresh 用同一 ordinal → 同槽位同 bizTaskId
        Map<Long, Integer> shotOrdinalById = new LinkedHashMap<>();
        int ordSeq = 0;
        for (Long sid : origTakeByShot.keySet()) { shotOrdinalById.put(sid, ordSeq++); }
        // storyboardId → 本轮要补的缺失槽位（0 基，升序）
        Map<Long, List<Integer>> missingSlotsByShot = new LinkedHashMap<>();

        // 按全集分类已完成镜头与待补镜头。
        for (Map.Entry<Long, Integer> en : origTakeByShot.entrySet())
        {
            Long sid = en.getKey();
            int takeCount = en.getValue();
            java.util.TreeSet<Integer> succeeded = succeededSlotsByShot.getOrDefault(sid, new java.util.TreeSet<>());
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
            log.info("分镜批量出片续生无待补镜头: taskId={}", taskId);
            throw new ServiceException("无可续生项");
        }
        if (StrUtil.isBlank(modelCode))
        {
            throw new ServiceException("任务数据异常");
        }

        // 模型池编码优先取快照持久化值，缺失时按 direction 兜底（兼容无 funcCode 的老快照）
        String funcCode = input.hasNonNull("funcCode") ? input.get("funcCode").asText() : null;
        if (StrUtil.isBlank(funcCode))
        {
            funcCode = resolveFuncCodeByDirection(direction);
        }
        boolean imageLike = DIRECTION_IMAGE.equals(direction) || DIRECTION_GRID.equals(direction);
        boolean edge = DIRECTION_EDGE.equals(direction);
        String resolvedModelCode = resolveModelCode(modelCode, funcCode);
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(resolvedModelCode);
        if (Objects.isNull(modelConfig)) { throw new ServiceException("模型不存在"); }
        if (imageLike && !Boolean.TRUE.equals(modelConfig.getSupportsImageInput())) { throw new ServiceException("该模型不支持图片"); }
        if (edge && !Boolean.TRUE.equals(modelConfig.getSupportsLastFrame())) { throw new ServiceException("不支持尾帧"); }
        Long modelId = modelConfig.getId();

        final Boolean genAudioF = generateAudio;
        final String userInputF = userInputText;
        List<PreparedShot> prepared = new ArrayList<>();
        List<ShotLock> heldLocks = new ArrayList<>();
        // 有待补镜头本轮被跳过（抢锁失败/解析失败）→ 终态须保持 PARTIAL_FAILED，且这些镜头原样回填 shots，不能丢失
        boolean forcePartial = false;
        Long projectId = null;
        Long episodeId = null;
        try
        {
            for (Map.Entry<Long, Integer> en : remainByShot.entrySet())
            {
                Long id = en.getKey();
                int remain = en.getValue();
                String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_VIDEO_GENERATE + ":" + id;
                ShotLock heldThis = null;
                try
                {
                    AidStoryboard sb = loadAndCheckStoryboard(id, userId);
                    String token = acquireShotLock(lockKey, id);
                    if (token == null)
                    {
                        log.info("分镜批量出片续生跳过被占用镜头(保持待补): storyboardId={}", id);
                        forcePartial = true;
                        Map<String, Object> kept = priorShotFull.get(id);
                        if (kept != null) { seedShotResults.add(kept); }
                        continue;
                    }
                    heldThis = new ShotLock(lockKey, token);
                    heldLocks.add(heldThis);
                    PreparedShot ps;
                    if (edge)
                    {
                        StoryboardVideoEdgeGenerateRequest r = new StoryboardVideoEdgeGenerateRequest();
                        r.setGenerateAudio(genAudioF);
                        r.setUserInputText(userInputF);
                        ps = prepareEdgeShot(sb, false, r, edgeResolvedByShot, modelConfig, userId, remain);
                    }
                    else if (imageLike)
                    {
                        StoryboardVideoFromImageGenerateRequest r = new StoryboardVideoFromImageGenerateRequest();
                        r.setGenerateAudio(genAudioF);
                        r.setUserInputText(userInputF);
                        ps = prepareImageShot(sb, false, r, modelConfig, userId, remain);
                    }
                    else
                    {
                        StoryboardVideoGenerateRequest r = new StoryboardVideoGenerateRequest();
                        r.setGenerateAudio(genAudioF);
                        r.setUserInputText(userInputF);
                        ps = prepareMultiShot(sb, false, r, modelConfig, userId, remain);
                    }
                    prepared.add(ps);
                    if (projectId == null) { projectId = sb.getProjectId(); episodeId = sb.getEpisodeId(); }
                }
                catch (RuntimeException e)
                {
                    if (heldThis != null) { releaseLockIfMine(heldThis.key, heldThis.token); heldLocks.remove(heldThis); }
                    // 解析失败（无提示词/无参考图等）→ 该镜头本轮无法补，保持待补 + PARTIAL_FAILED，原样回填 shots
                    log.info("分镜批量出片续生跳过镜头(保持待补): storyboardId={}, reason={}", id, e.getMessage());
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
            // 重写 inputSnapshot：保留 direction/model/比例/时长/音频/countPerShot，更新本轮受理镜头的 storyboardIds + shots(含新 lockToken) + runNo，
            // 使取消/队列收尾能按新 token 释放本轮持有的锁。
            Map<String, Object> newSnap = new LinkedHashMap<>();
            newSnap.put("direction", direction);
            // 续生重写快照须保留 func_code，否则下一轮续生丢失 pro/专属池信息
            newSnap.put("funcCode", funcCode);
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
                // 首尾帧续生：保留本轮镜头的首/尾帧来源 + 配音项
                putEdgeSnapshot(s, ps);
                resumeShots.add(s);
            }
            newSnap.put("storyboardIds", resumeIds);
            newSnap.put("modelCode", resolvedModelCode);
            newSnap.put("aspectRatio", aspectRatio);
            newSnap.put("resolution", resolution);
            newSnap.put("durationSeconds", durationSeconds);
            newSnap.put("countPerShot", perShotCount);
            newSnap.put("generateAudio", generateAudio);
            newSnap.put("userInputText", userInputText);
            newSnap.put("shots", resumeShots);
            // 原批次全集原样保留（续生不改变全集），供后续轮次继续兜底未开始/无快照镜头
            List<Map<String, Object>> allShotsOut = new ArrayList<>();
            for (Map.Entry<Long, Integer> e : origTakeByShot.entrySet())
            {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("storyboardId", e.getKey());
                a.put("takeCount", e.getValue());
                // 首尾帧全集：保留首/尾帧来源（上传 URL / 记录 ID）+ 配音项，使后续轮次续生仍可重解析
                EdgeResolved er = edgeResolvedByShot.get(e.getKey());
                if (er != null)
                {
                    if (StrUtil.isNotBlank(er.firstImageUrl)) { a.put("firstImageUrl", er.firstImageUrl); }
                    if (Objects.nonNull(er.firstImageRecordId)) { a.put("firstImageRecordId", er.firstImageRecordId); }
                    if (StrUtil.isNotBlank(er.lastImageUrl)) { a.put("lastImageUrl", er.lastImageUrl); }
                    if (Objects.nonNull(er.lastImageRecordId)) { a.put("lastImageRecordId", er.lastImageRecordId); }
                    putAudiosSnapshot(a, er.audioItems);
                }
                allShotsOut.add(a);
            }
            newSnap.put("allShots", allShotsOut);
            newSnap.put("runNo", newRunNo);
            // 供队列层回滚恢复 totalCount（续生 CAS 会把 total 改成本轮 seed+new）。
            // 服务层回滚用内存中的 originalInputSnapshot 直接还原 inputSnapshot，无需存进快照。
            newSnap.put("priorTotalCount", originalTotalCount);
            String newSnapJson;
            try
            {
                newSnapJson = OBJECT_MAPPER.writeValueAsString(newSnap);
            }
            catch (Exception e)
            {
                // 简单类型序列化失败极罕见；一旦失败不可降级（会丢新 token/runNo，导致后续按旧值释放锁/判 runNo）→ 释放锁 + 失败
                for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
                log.error("分镜批量出片续生快照序列化失败: taskId={}", taskId, e);
                throw new ServiceException("提交失败，请重试");
            }

            // 单次 CAS：PARTIAL_FAILED/FAILED → PENDING 同时回写 totalCount + 新 inputSnapshot + 清空旧 errorMessage，按行数判定推进权。
            LambdaUpdateWrapper<AidExtractTask> toPending = Wrappers.lambdaUpdate();
            toPending.eq(AidExtractTask::getId, taskId);
            toPending.in(AidExtractTask::getStatus,
                    TASK_STATUS_PARTIAL_FAILED, TASK_STATUS_FAILED, TASK_STATUS_CANCELLED);
            toPending.set(AidExtractTask::getStatus, TASK_STATUS_PENDING);
            toPending.set(AidExtractTask::getTotalCount, seedSuccessCount + newSubtasks);
            toPending.set(AidExtractTask::getInputSnapshot, newSnapJson);
            // 清空上一轮可能残留的 errorMessage（如队列回滚写入的"续生提交失败" / 崩溃回收的"超时未完成"），避免成功后详情仍带旧错误
            toPending.set(AidExtractTask::getErrorMessage, null);
            toPending.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            int casRows;
            try
            {
                casRows = extractTaskService.getBaseMapper().update(null, toPending);
            }
            catch (RuntimeException ex)
            {
                // CAS update 本身异常：状态未推进（仍 PARTIAL_FAILED），仅释放本轮锁即可
                for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
                log.error("分镜批量出片续生状态CAS异常: taskId={}", taskId, ex);
                throw new ServiceException("提交失败，请重试");
            }
            if (casRows == 0)
            {
                for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
                log.warn("分镜批量出片续生 CAS 失败（状态已变）: taskId={}", taskId);
                throw new ServiceException("状态不支持");
            }

            // 已切到 PENDING：此后任何入队失败/异常都必须回滚到原 PARTIAL_FAILED（恢复 totalCount + 释放锁），保留续生入口
            boolean enqueued;
            try
            {
                try { assetExtractService.clearCancelFlag(taskId); } catch (Exception ignore) { /* ignore */ }
                enqueued = buildJobsAndEnqueue(taskId, projectId, episodeId, userId, resolvedModelCode, modelId,
                        modelConfig, aspectRatio, resolution, durationSeconds, genAudioF, userInputF, perShotCount,
                        direction, prepared, heldLocks,
                        newSubtasks, originalTotalShots, seedSuccessCount, seedRecordIds, seedItems, seedShotResults,
                        seedShotPrior, newRunNo, forcePartial, shotOrdinalById, missingSlotsByShot);
            }
            catch (RuntimeException ex)
            {
                log.error("分镜批量出片续生入队异常，回滚原状态: taskId={}", taskId, ex);
                releaseLocksAndRollbackResume(taskId, heldLocks, originalTotalCount, originalStatus, originalInputSnapshot);
                throw new ServiceException("提交失败，请重试");
            }
            if (!enqueued)
            {
                // 续生提交失败：回滚到原状态（保留续生入口与原 resultData/快照），仅向前端返回"提交失败"
                releaseLocksAndRollbackResume(taskId, heldLocks, originalTotalCount, originalStatus, originalInputSnapshot);
                throw new ServiceException("提交失败，请重试");
            }

            StoryboardVideoGenerateVO vo = new StoryboardVideoGenerateVO();
            vo.setTaskId(taskId);
            vo.setStatus(TASK_STATUS_PENDING);
            vo.setModelName(resolvedModelCode);
            vo.setTotalShots(prepared.size());
            vo.setCountPerShot(perShotCount);
            vo.setTotalSubtasks(newSubtasks);
            List<StoryboardVideoGenerateVO.ShotResult> voItems = new ArrayList<>();
            for (PreparedShot ps : prepared)
            {
                voItems.add(new StoryboardVideoGenerateVO.ShotResult(ps.storyboard.getId(), true, null));
            }
            vo.setItems(voItems);
            log.info("分镜批量出片续生提交: taskId={}, resumeShots={}, newSubtasks={}, seedSuccess={}",
                    taskId, prepared.size(), newSubtasks, seedSuccessCount);
            return vo;
        }
        catch (RuntimeException e)
        {
            log.error("分镜批量出片续生受理失败: taskId={}", taskId, e);
            throw e;
        }
    }

    /**
     * Durable 复用 / 阻断决策：按确定性 bizTaskId 反查本逻辑槽位的既有媒体任务（窗口无关，与媒体层 1 小时幂等窗解耦）。
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
            log.error("分镜批量出片 durable 复用查询失败(防重复扣费, fail-closed): bizTaskId={}", bizTaskId, e);
            throw new ServiceException("查询失败");
        }
        if (CollectionUtil.isEmpty(tasks))
        {
            return null; // 无既有媒体任务 → 允许生成
        }
        // 是否存在「不可重生」的旧任务：在飞 / 成功但 OSS 待补偿——这两类都不能新建同槽位任务，否则重复生成/扣费
        boolean blockRegenerate = false;
        for (AidMediaTask t : tasks)
        {
            String st = t.getStatus();
            if (TASK_STATUS_SUCCEEDED.equals(st))
            {
                // 成功且 OSS 就绪 → 直接复用（最高优先，立即返回）
                if (StrUtil.isNotBlank(t.getOssUrl())) { return t.getOssUrl(); }
                // 成功但 OSS 暂空（污染快照）→ 阻断，等补偿回填
                blockRegenerate = true;
            }
            else if (!TASK_STATUS_FAILED.equals(st))
            {
                // 非 SUCCEEDED、非 FAILED 即在飞（PENDING/QUEUED/PROCESSING/WAIT_*）→ 阻断
                blockRegenerate = true;
            }
            // FAILED：未成功扣费的终态，忽略（不阻断重生）
        }
        if (blockRegenerate)
        {
            log.info("storyboard video batch slot has inflight media task, block regenerate: bizTaskId={}", bizTaskId);
            throw new ShotInFlightException();
        }
        return null; // 仅 FAILED 终态 → 允许重新生成
    }

    /** 单条视频调用统一视频生成主链路（计费 / 排队 / 退款全部由媒体主链路处理）。 */
    private static final class ShotInFlightException extends RuntimeException
    {
        ShotInFlightException()
        {
            super("media task inflight");
        }
    }

    private MediaTaskResponse submitSingleVideoMedia(VideoGenJob job, long bizSeq, String genParamsJson)
    {
        AidStoryboard storyboard = job.storyboard;

        MediaVideoGenerateRequest videoRequest = new MediaVideoGenerateRequest();
        videoRequest.setPrompt(job.finalPrompt);
        videoRequest.setModelName(job.modelCode);
        videoRequest.setUserId(job.userId);
        videoRequest.setProjectId(storyboard.getProjectId());
        videoRequest.setEpisodeId(storyboard.getEpisodeId());
        videoRequest.setBizTaskType(BIZ_TASK_TYPE);
        videoRequest.setBizTaskId(bizSeq);

        if (StrUtil.isNotBlank(job.baseImageUrl))
        {
            videoRequest.setImageUrl(job.baseImageUrl);
        }
        if (StrUtil.isNotBlank(job.aspectRatio))
        {
            videoRequest.setAspectRatio(job.aspectRatio);
        }
        if (Objects.nonNull(job.durationSeconds))
        {
            videoRequest.setDurationSeconds(job.durationSeconds);
        }

        Map<String, Object> options = new LinkedHashMap<>();
        if (CollectionUtil.isNotEmpty(job.referenceImages))
        {
            options.put("referenceImages", new ArrayList<>(job.referenceImages));
        }
        // 厂商专属扩展参数（装配策略产出，如 Vidu 主体调用 subjects）：官方字段原样并入
        if (CollectionUtil.isNotEmpty(job.providerExtraOptions))
        {
            options.putAll(job.providerExtraOptions);
        }
        // 清晰度档位：计费 SKU 匹配（BillingInputExtractor）与各厂商 Provider 下发共用同一键，保证扣费与实际出片同档
        if (StrUtil.isNotBlank(job.resolution))
        {
            options.put("resolution", job.resolution);
        }
        if (Objects.nonNull(job.generateAudio))
        {
            options.put("generate_audio", job.generateAudio);
        }
        if (StrUtil.isNotBlank(job.lastFrameImageUrl))
        {
            options.put("lastFrameImageUrl", job.lastFrameImageUrl);
            options.put("endImageUrl", job.lastFrameImageUrl);
            options.put("end_image_url", job.lastFrameImageUrl);
            options.put("start_end", Boolean.TRUE);
        }
        if (CollectionUtil.isNotEmpty(job.referenceAudios))
        {
            options.put("referenceAudios", new ArrayList<>(job.referenceAudios));
        }
        // 扇入上下文：随 request_json 落库，事件回调时 listener 反读重建 gen_record（厂商 Provider 只取已知键）
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("storyboardId", storyboard.getId());
        ctx.put("modelId", job.modelId);
        ctx.put("genType", job.genType);
        ctx.put("finalPrompt", job.finalPrompt);
        ctx.put("userInputText", StrUtil.blankToDefault(job.userVideoPromptInput, job.rawVideoPrompt));
        ctx.put("durationSeconds", job.durationSeconds);
        ctx.put("firstImageRecordId", job.firstImageRecordId);
        ctx.put("lastImageRecordId", job.lastImageRecordId);
        ctx.put("genParams", genParamsJson);
        ctx.put("bizSeq", bizSeq);
        options.put(OPT_KEY_CTX, ctx);
        videoRequest.setOptions(options);

        return mediaGenerationService.generateVideo(videoRequest);
    }

    /** 异步执行所需的全部入参快照（不可变）。 */
    private static final class VideoGenJob
    {
        final Long taskId;
        final Long userId;
        final AidStoryboard storyboard;
        final String modelCode;
        final Long modelId;
        final AiModelConfigVo modelConfig;
        final String finalPrompt;
        final String rawVideoPrompt;
        final List<String> referenceImages; // unmodifiable copy
        final String baseImageUrl;
        final int videoCount;
        final String aspectRatio;
        /** 清晰度档位（已按模型 capability 规范化；可空=不下发走上游默认） */
        final String resolution;
        final Integer durationSeconds;
        final Boolean generateAudio;
        final String userInputText;
        final String userVideoPromptInput;
        /** 该镜头 bizTaskId 基址 = 父任务编码段 + 稳定镜头序号(allShots 位置)*1000（不含 take 槽位 / runNo） */
        final long bizSeqBase;
        /** 本轮要生成的逻辑 take 槽位（0 基）：fresh=[0..takeCount-1]，续生=缺失槽位。size 即本轮条数 */
        final List<Integer> takeSlots;
        final String lockKey;
        final String lockToken;
        /** 产物类型（i2v / edge）：落 aid_gen_record.gen_type */
        final String genType;
        /** 首尾帧出片：尾帧图 URL（其余方向为 null，generateSingleVideo 据此透传厂商尾帧键） */
        final String lastFrameImageUrl;
        /** 首尾帧出片：首图记录 ID（落 aid_gen_record.first_image_id） */
        final Long firstImageRecordId;
        /** 首尾帧出片：尾图记录 ID（落 aid_gen_record.last_image_id） */
        final Long lastImageRecordId;
        /** 首尾帧出片：配音参考 URL 列表（有序，≤7；generateSingleVideo 据此透传 options.referenceAudios） */
        final List<String> referenceAudios;
        /** 厂商专属扩展参数（装配策略产出，如 Vidu 主体调用 subjects），提交时并入 options */
        final Map<String, Object> providerExtraOptions;

        VideoGenJob(Long taskId, Long userId, AidStoryboard storyboard,
                    String modelCode, Long modelId, AiModelConfigVo modelConfig,
                    String finalPrompt, String rawVideoPrompt, List<String> referenceImages,
                    String baseImageUrl, int videoCount, String aspectRatio, String resolution,
                    Integer durationSeconds, Boolean generateAudio,
                    String userInputText, String userVideoPromptInput,
                    long bizSeqBase, List<Integer> takeSlots, String lockKey, String lockToken,
                    String genType, String lastFrameImageUrl, Long firstImageRecordId, Long lastImageRecordId,
                    List<String> referenceAudios, Map<String, Object> providerExtraOptions)
        {
            this.taskId = taskId;
            this.userId = userId;
            this.storyboard = storyboard;
            this.modelCode = modelCode;
            this.modelId = modelId;
            this.modelConfig = modelConfig;
            this.finalPrompt = finalPrompt;
            this.rawVideoPrompt = rawVideoPrompt;
            this.referenceImages = (referenceImages == null)
                    ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(referenceImages));
            this.baseImageUrl = baseImageUrl;
            this.videoCount = videoCount;
            this.aspectRatio = aspectRatio;
            this.resolution = resolution;
            this.durationSeconds = durationSeconds;
            this.generateAudio = generateAudio;
            this.userInputText = userInputText;
            this.userVideoPromptInput = userVideoPromptInput;
            this.bizSeqBase = bizSeqBase;
            this.takeSlots = (takeSlots == null)
                    ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(takeSlots));
            this.lockKey = lockKey;
            this.lockToken = lockToken;
            this.genType = StrUtil.blankToDefault(genType, GEN_TYPE_I2V);
            this.lastFrameImageUrl = lastFrameImageUrl;
            this.firstImageRecordId = firstImageRecordId;
            this.lastImageRecordId = lastImageRecordId;
            this.referenceAudios = (referenceAudios == null)
                    ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(referenceAudios));
            this.providerExtraOptions = (providerExtraOptions == null)
                    ? java.util.Collections.emptyMap()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(providerExtraOptions));
        }
    }

    /** 批量异步执行容器：一个父任务下的多镜头 Job 列表 + 续生携带的既有成功快照（不可变）。 */
    private static final class VideoBatchJob
    {
        final Long taskId;
        final Long userId;
        final String modelCode;
        final int countPerShot;
        /** 原批次镜头总数（累计口径，写入 resultData.totalShots；续生时为原批次全集大小，非本轮补跑数） */
        final int totalShots;
        /** 进度分母 = 既有成功条数 + 本批新子任务条数 */
        final int totalSubtasks;
        /** 续生时携带的既有成功条数（首次提交为 0） */
        final int seedSuccessCount;
        /** 运行批次号（持久化、单调递增，存 input_snapshot）：仅用于"重试超限"守卫与排查追溯；
         *  bizTaskId 按逻辑槽位确定性编码（不含 runNo），续生重跑同槽位得同一 bizTaskId、媒体层幂等去重 */
        final int runNo;
        final List<VideoGenJob> shots;
        final List<ShotLock> shotLocks;
        final List<Long> seedRecordIds;
        final List<Map<String, Object>> seedItems;
        final List<Map<String, Object>> seedShotResults;
        /** 续生时「部分完成且本轮重跑」镜头的既有进度（storyboardId → {takeCount, successCount, recordIds}），用于 shots 累计口径 */
        final Map<Long, Map<String, Object>> seedShotPrior;
        /** 强制部分失败终态：续生时有待补镜头被跳过（未受理）时为 true，避免错误置 SUCCEEDED 关闭续生入口 */
        final boolean forcePartial;

        VideoBatchJob(Long taskId, Long userId, String modelCode, int countPerShot, int totalShots,
                      int totalSubtasks, int seedSuccessCount, int runNo, List<VideoGenJob> shots,
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
    private Long persistGenRecord(VideoGenJob job, long bizSeq, String videoUrl, String genParamsJson)
    {
        AidStoryboard storyboard = job.storyboard;
        AidGenRecord record = new AidGenRecord();
        record.setUserId(job.userId);
        record.setProjectId(storyboard.getProjectId());
        record.setEpisodeId(storyboard.getEpisodeId());
        record.setStoryboardId(storyboard.getId());
        record.setGenType(job.genType);
        record.setModelId(job.modelId);
        // 首尾帧出片：记录首/尾图记录 ID
        if (Objects.nonNull(job.firstImageRecordId)) { record.setFirstImageId(job.firstImageRecordId); }
        if (Objects.nonNull(job.lastImageRecordId)) { record.setLastImageId(job.lastImageRecordId); }
        record.setPromptText(job.finalPrompt);
        record.setUserInputText(StrUtil.blankToDefault(job.userVideoPromptInput, job.rawVideoPrompt));
        record.setFileUrl(videoUrl);
        if (Objects.nonNull(job.durationSeconds))
        {
            record.setVideoDuration(Long.valueOf(job.durationSeconds));
        }
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
            log.info("分镜出片 gen_record 已存在(biz_seq 唯一键冲突,幂等忽略): bizSeq={}, storyboardId={}",
                    bizSeq, storyboard.getId());
            markExistingVideoRecordAsFinal(bizSeq, job.userId);
            return null;
        }
        markStoryboardFinalVideo(storyboard.getId(), record.getId(), job.userId);
        return record.getId();
    }
    /** 注入 aid_media_task.request_json.options 的单一命名空间上下文键。 */
    private static final String OPT_KEY_CTX = "sbzVideoGenCtx";

    /** 通用扇入支撑（失败计数 / 收尾CAS / bizSeq反解 / 快照解析 / 父任务续租），与出图共用。 */
    @Autowired
    private MediaGenFanInSupport fanInSupport;

    /** 幂等落 gen_record：按 bizSeq 查重，已存在则跳过（事件重投 / DIRECT 与事件竞态双保险）。 */
    private void persistGenRecordIdempotent(VideoGenJob shot, long bizSeq, String videoUrl, String genParamsJson)
    {
        AidGenRecord existing = loadGenRecordByBizSeq(bizSeq);
        if (Objects.nonNull(existing))
        {
            Long recordUserId = Objects.nonNull(existing.getUserId()) ? existing.getUserId() : shot.userId;
            markStoryboardFinalVideo(existing.getStoryboardId(), existing.getId(), recordUserId);
            return;
        }
        persistGenRecord(shot, bizSeq, videoUrl, genParamsJson);
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
            log.warn("分镜出片 bizSeq 查重异常(按不存在处理): bizSeq={}", bizSeq, e);
            return null;
        }
    }

    private void markExistingVideoRecordAsFinal(long bizSeq, Long fallbackUserId)
    {
        AidGenRecord existing = loadGenRecordByBizSeq(bizSeq);
        if (Objects.isNull(existing))
        {
            return;
        }
        Long userId = Objects.nonNull(existing.getUserId()) ? existing.getUserId() : fallbackUserId;
        markStoryboardFinalVideo(existing.getStoryboardId(), existing.getId(), userId);
    }

    /** 媒体子任务终态回调（由 StoryboardVideoGenEventListener 调用）：成功幂等落库、失败计数，随后尝试收尾。 */
    @Override
    public void onMediaVideoTaskTerminal(Long mediaTaskId, boolean success, String ossUrl)
    {
        AidMediaTask mt = aidMediaTaskMapper.selectById(mediaTaskId);
        if (Objects.isNull(mt) || Objects.isNull(mt.getBizTaskId())) { return; }
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
            log.error("分镜出片事件落库异常: mediaTaskId={}, bizSeq={}", mediaTaskId, bizSeq, e);
            fanInSupport.incrFail(parentTaskId);
        }
        finalizeVideoBatchIfDone(parentTaskId);
        if (contextConsumed)
        {
            mediaTaskArchiveService.removeConsumedFanInContext(mediaTaskId, OPT_KEY_CTX);
        }
    }

    /** 从 request_json.options.sbzVideoGenCtx 反序列化上下文，幂等落 gen_record。 */
    private boolean persistGenRecordFromCtx(AidMediaTask mt, long bizSeq, String ossUrl)
    {
        AidGenRecord existing = loadGenRecordByBizSeq(bizSeq);
        if (Objects.nonNull(existing))
        {
            Long recordUserId = Objects.nonNull(existing.getUserId()) ? existing.getUserId() : mt.getUserId();
            markStoryboardFinalVideo(existing.getStoryboardId(), existing.getId(), recordUserId);
            return true; // 幂等：已落库时不再依赖 request_json 上下文，兼容上下文压缩后的重复事件
        }
        Map<String, Object> ctx = extractCtxFromMediaTask(mt);
        if (CollectionUtil.isEmpty(ctx))
        {
            log.error("分镜出片事件落库缺少上下文(跳过,计失败防卡死): mediaTaskId={}, bizSeq={}", mt.getId(), bizSeq);
            fanInSupport.incrFail(fanInSupport.decodeParentTaskId(bizSeq));
            return false;
        }
        Long storyboardId = ctx.get("storyboardId") instanceof Number n ? n.longValue() : null;
        if (Objects.isNull(storyboardId))
        {
            // 上下文缺 storyboardId 无法落库：计失败防卡死，与上方"缺上下文"分支保持一致。
            // 否则该 take 既不计成功也不计失败，扇入永远凑不齐 total → 父任务永卡 PROCESSING（仅能靠僵尸对账兜底）。
            log.error("分镜出片事件落库缺 storyboardId(跳过,计失败防卡死): mediaTaskId={}, bizSeq={}", mt.getId(), bizSeq);
            fanInSupport.incrFail(fanInSupport.decodeParentTaskId(bizSeq));
            return false;
        }
        AidGenRecord record = new AidGenRecord();
        record.setUserId(mt.getUserId());
        record.setProjectId(mt.getProjectId());
        record.setEpisodeId(mt.getEpisodeId());
        record.setStoryboardId(storyboardId);
        record.setGenType((String) ctx.get("genType"));
        record.setModelId(ctx.get("modelId") instanceof Number n ? n.longValue() : null);
        if (ctx.get("firstImageRecordId") instanceof Number n) { record.setFirstImageId(n.longValue()); }
        if (ctx.get("lastImageRecordId") instanceof Number n) { record.setLastImageId(n.longValue()); }
        record.setPromptText((String) ctx.get("finalPrompt"));
        record.setUserInputText((String) ctx.get("userInputText"));
        record.setFileUrl(ossUrl);
        if (ctx.get("durationSeconds") instanceof Number n) { record.setVideoDuration(n.longValue()); }
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
            log.info("分镜出片事件落库 gen_record 已存在(biz_seq 唯一键冲突,幂等忽略): bizSeq={}, storyboardId={}",
                    bizSeq, storyboardId);
            markExistingVideoRecordAsFinal(bizSeq, mt.getUserId());
            return true;
        }
        markStoryboardFinalVideo(storyboardId, record.getId(), mt.getUserId());
        return true;
    }

    /**
     * 自动生成成功后同步分镜主视频：video 类内单选互斥（重置同分镜其余分镜视频记录的选中，
     * 配音视频 compose 类独立互斥不受影响），本记录置选中并回写 final_video_id。
     * 失败只记录日志，不影响已成功产物落库与扇入收尾。
     */
    private void markStoryboardFinalVideo(Long storyboardId, Long recordId, Long userId)
    {
        if (Objects.isNull(storyboardId) || Objects.isNull(recordId) || Objects.isNull(userId))
        {
            return;
        }
        try
        {
            // video 类内单选：重置同分镜其余分镜视频记录的选中（不动配音视频）
            LambdaUpdateWrapper<AidGenRecord> resetWrapper = Wrappers.lambdaUpdate();
            resetWrapper.eq(AidGenRecord::getStoryboardId, storyboardId);
            resetWrapper.eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL);
            resetWrapper.in(AidGenRecord::getGenType, VIDEO_MUTEX_GEN_TYPES);
            resetWrapper.ne(AidGenRecord::getId, recordId);
            resetWrapper.eq(AidGenRecord::getIsSelected, 1);
            resetWrapper.set(AidGenRecord::getIsSelected, 0);
            resetWrapper.set(AidGenRecord::getUpdateTime, DateUtils.getNowDate());
            aidGenRecordService.update(resetWrapper);
            // 本记录置选中（幂等：insert 已带 1；幂等重放路径 existing 可能已被并发重置）
            LambdaUpdateWrapper<AidGenRecord> selectWrapper = Wrappers.lambdaUpdate();
            selectWrapper.eq(AidGenRecord::getId, recordId);
            selectWrapper.set(AidGenRecord::getIsSelected, 1);
            selectWrapper.set(AidGenRecord::getUpdateTime, DateUtils.getNowDate());
            aidGenRecordService.update(selectWrapper);

            LambdaUpdateWrapper<AidStoryboard> update = Wrappers.lambdaUpdate();
            update.eq(AidStoryboard::getId, storyboardId);
            update.eq(AidStoryboard::getUserId, userId);
            update.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
            update.set(AidStoryboard::getFinalVideoId, recordId);
            update.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
            update.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
            boolean updated = aidStoryboardService.update(update);
            if (!updated)
            {
                log.warn("分镜视频自动设为主视频失败(分镜不存在或无权): storyboardId={}, recordId={}, userId={}",
                        storyboardId, recordId, userId);
            }
        }
        catch (Exception e)
        {
            log.warn("分镜视频自动设为主视频异常(不阻断): storyboardId={}, recordId={}", storyboardId, recordId, e);
        }
    }

    /** 解析 request_json → options.sbzVideoGenCtx（Map）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractCtxFromMediaTask(AidMediaTask mt)
    {
        if (Objects.isNull(mt) || StrUtil.isBlank(mt.getRequestJson())) { return java.util.Collections.emptyMap(); }
        try
        {
            JsonNode opt = OBJECT_MAPPER.readTree(mt.getRequestJson()).path("options").path(OPT_KEY_CTX);
            if (opt.isMissingNode() || opt.isNull()) { return java.util.Collections.emptyMap(); }
            return OBJECT_MAPPER.convertValue(opt, Map.class);
        }
        catch (Exception e)
        {
            log.warn("分镜出片解析媒体任务上下文失败: mediaTaskId={}", mt.getId(), e);
            return java.util.Collections.emptyMap();
        }
    }

    /** 扇入收尾（幂等）：成功 gen_record 数 + 失败计数 >= 总数时 CAS 一次性置终态 + SSE + 释放锁/名额。 */
    @Override
    public void finalizeVideoBatchIfDone(Long taskId)
    {
        if (Objects.isNull(taskId)) { return; }
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || !TASK_STATUS_PROCESSING.equals(task.getStatus())) { return; }
        int total = task.getTotalCount() == null ? 0 : task.getTotalCount();
        List<Long> storyboardIds = fanInSupport.parseStoryboardIds(task.getInputSnapshot());
        List<AidGenRecord> succ = loadSucceededRecordsByParentTask(taskId, storyboardIds);
        int successCount = succ.size();
        int failCount = fanInSupport.getFailCount(taskId);
        int processed = successCount + failCount;
        if (processed < total)
        {
            // 未凑齐终态：推一次「已处理 X/N」扇入进度，异步等待期前端不再长时间无事件
            pushBatchProgress(taskId, total, processed, successCount, failCount);
            return;
        }
        if (!fanInSupport.tryWinFinalize(taskId)) { return; }
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
                log.info("分镜批量出片收尾检测到取消，结果已保留: taskId={}, total={}, success={}, fail={}",
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
            log.info("分镜批量出片收尾: taskId={}, total={}, success={}, fail={}", taskId, total, successCount, failCount);
        }
        finally
        {
            releaseShotLocksFromSnapshot(task);
            try { assetExtractService.clearCancelFlag(taskId); } catch (Exception ignore) { /* ignore */ }
            try { assetExtractService.releaseTaskSlots(taskId); } catch (Exception ignore) { /* ignore */ }
            fanInSupport.cleanup(taskId);
        }
    }

    /** 释放父任务 input_snapshot.shots[].lockToken 持有的镜头锁（复用通用快照解析）。 */
    private void releaseShotLocksFromSnapshot(AidExtractTask task)
    {
        if (Objects.isNull(task)) { return; }
        for (MediaGenFanInSupport.ShotLockRef ref : fanInSupport.parseShotLocks(task.getInputSnapshot()))
        {
            releaseLockIfMine(FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_VIDEO_GENERATE + ":" + ref.storyboardId, ref.lockToken);
        }
    }

    /** 收尾结果 resultData：按已成功 gen_record 聚合 items/shots（video）。 */
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
            it.put("videoUrl", r.getFileUrl());
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

    /**
     * 续生反查：按 bizSeq 父任务编码段 + storyboardId 取本父任务已成功落库的视频记录。
     */
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
            // fail-closed：反查是防重复扣费真源，一次 SQL/连接异常若被当"无成功记录"会触发重复补跑/扣费，
            // 因此宁可本次续生失败也不返回空列表
            log.error("分镜批量出片续生反查 aid_gen_record 失败(fail-closed): taskId={}", taskId, e);
            throw new ServiceException("任务数据异常");
        }
    }

    /** 从 aid_gen_record.gen_params 解析逻辑 take 槽位（takeIndex）；无法解析返回 null（调用方按贪心兜底分配）。 */
    private Integer parseTakeIndexFromGenParams(String genParams)
    {
        if (StrUtil.isBlank(genParams)) { return null; }
        try
        {
            JsonNode node = OBJECT_MAPPER.readTree(genParams);
            JsonNode ti = node.get("takeIndex");
            if (ti != null && ti.isInt()) { return ti.asInt(); }
            if (ti != null && ti.canConvertToInt()) { return ti.asInt(); }
            return null;
        }
        catch (Exception e)
        {
            log.warn("分镜批量出片续生解析 takeIndex 失败: {}", e.getMessage());
            return null;
        }
    }
    /**
     * 解析最终下发的视频时长（秒）：用户传值须在模型白名单内，未传时按模型默认值兜底。
     */
    private Integer resolveDurationSeconds(Integer userValue, AiModelConfigVo modelConfig)
    {
        List<Integer> allowed = parseIntArray(modelConfig.getCapabilityJson(), "durationOptions");
        Integer defaultFromJson = parseIntField(modelConfig.getCapabilityJson(), "defaultDurationSeconds");
        Integer defaultDuration = Objects.nonNull(defaultFromJson)
                ? defaultFromJson
                : modelConfig.getDefaultDurationSeconds();

        if (Objects.nonNull(userValue) && userValue > 0)
        {
            if (CollectionUtil.isEmpty(allowed) || allowed.contains(userValue))
            {
                return userValue;
            }
            log.error("分镜图生视频 durationSeconds 不在模型白名单内, modelCode={}, userValue={}, allowed={}",
                    modelConfig.getModelCode(), userValue, allowed);
            // 文案 ≤6 字（规范）；详细 allowed 由日志保留供排查
            throw new ServiceException("时长不支持");
        }

        return pickSafeDefaultDuration(allowed, defaultDuration);
    }

    /** 在 allowed 中挑一个最贴近 defaultDuration 的稳妥值；allowed 为空时直接返回 defaultDuration。 */
    private Integer pickSafeDefaultDuration(List<Integer> allowed, Integer defaultDuration)
    {
        if (CollectionUtil.isEmpty(allowed))
        {
            return defaultDuration;
        }
        if (Objects.nonNull(defaultDuration) && allowed.contains(defaultDuration))
        {
            return defaultDuration;
        }
        // default 不在白名单：取 ≤ default 的最大项；若都比 default 大，取首项（白名单已按配置顺序）
        if (Objects.nonNull(defaultDuration))
        {
            Integer best = null;
            for (Integer v : allowed)
            {
                if (Objects.nonNull(v) && v > 0 && v <= defaultDuration
                        && (best == null || v > best))
                {
                    best = v;
                }
            }
            if (Objects.nonNull(best))
            {
                return best;
            }
        }
        return allowed.get(0);
    }

    /**
     * 解析最终下发的视频宽高比：与 {@link #resolveDurationSeconds} 同语义，
     * 白名单来自 {@code capability_json.aspectRatioOptions}，默认值优先
     * {@code capability_json.defaultAspectRatio}，再回 {@code aid_ai_model.default_aspect_ratio}。
     * 用户传值不在白名单时抛 {@code 比例不支持}，不静默回退。
     */
    private String resolveAspectRatio(String userValue, AiModelConfigVo modelConfig)
    {
        List<String> allowed = parseStringArray(modelConfig.getCapabilityJson(), "aspectRatioOptions");
        String defaultFromJson = parseStringField(modelConfig.getCapabilityJson(), "defaultAspectRatio");
        String defaultRatio = StrUtil.isNotBlank(defaultFromJson)
                ? defaultFromJson
                : modelConfig.getDefaultAspectRatio();

        // 用户传值：超范围直接抛错
        if (StrUtil.isNotBlank(userValue))
        {
            String trimmed = userValue.trim();
            if (CollectionUtil.isEmpty(allowed) || allowed.contains(trimmed))
            {
                return trimmed;
            }
            log.error("分镜图生视频 aspectRatio 不在模型白名单内, modelCode={}, userValue={}, allowed={}",
                    modelConfig.getModelCode(), trimmed, allowed);
            throw new ServiceException("画面比例不支持");
        }

        // 用户未传值：取白名单兜底默认（默认不在白名单时回退首项）
        if (CollectionUtil.isNotEmpty(allowed)
                && StrUtil.isNotBlank(defaultRatio)
                && allowed.contains(defaultRatio))
        {
            return defaultRatio;
        }
        if (CollectionUtil.isNotEmpty(allowed))
        {
            return allowed.get(0);
        }
        return defaultRatio;
    }

    /**
     * 解析最终下发的清晰度档位：与 {@link #resolveAspectRatio} 同语义，
     * 白名单来自 {@code capability_json.sizeOptions}（忽略大小写命中，返回配置中的规范写法，
     * 保证计费 SKU 匹配与厂商下发同源），默认值取 {@code capability_json.defaultSize}。
     * 用户传值不在白名单时抛 {@code 清晰度不支持}，不静默回退；模型未声明 sizeOptions 时传值原样放行。
     *
     * @param userValue   用户请求档位（可空）
     * @param modelConfig 出片模型配置
     * @return 规范化档位；模型未声明任何档位且用户未传时返回 null（不下发，走上游默认）
     */
    private String resolveResolution(String userValue, AiModelConfigVo modelConfig)
    {
        List<String> allowed = parseStringArray(modelConfig.getCapabilityJson(), "sizeOptions");
        if (StrUtil.isNotBlank(userValue))
        {
            String trimmed = userValue.trim();
            if (CollectionUtil.isEmpty(allowed))
            {
                return trimmed;
            }
            String matched = matchIgnoreCase(allowed, trimmed);
            if (Objects.nonNull(matched))
            {
                return matched;
            }
            log.error("分镜图生视频 resolution 不在模型白名单内, modelCode={}, userValue={}, allowed={}",
                    modelConfig.getModelCode(), trimmed, allowed);
            throw new ServiceException("清晰度不支持");
        }
        // 用户未传值：模型默认档兜底（默认档不在白名单时回退白名单首项；均无则不下发）
        String defaultSize = parseStringField(modelConfig.getCapabilityJson(), "defaultSize");
        if (StrUtil.isNotBlank(defaultSize))
        {
            if (CollectionUtil.isEmpty(allowed))
            {
                return defaultSize;
            }
            String matched = matchIgnoreCase(allowed, defaultSize);
            if (Objects.nonNull(matched))
            {
                return matched;
            }
        }
        return CollectionUtil.isEmpty(allowed) ? null : allowed.get(0);
    }

    /** 在白名单中忽略大小写查找目标值，命中返回白名单内的规范写法，未命中返回 null。 */
    private String matchIgnoreCase(List<String> allowed, String target)
    {
        for (String option : allowed)
        {
            if (StrUtil.isNotBlank(option) && option.equalsIgnoreCase(target))
            {
                return option;
            }
        }
        return null;
    }

    private List<Integer> parseIntArray(String capabilityJson, String key)
    {
        JsonNode arr = readField(capabilityJson, key);
        if (arr == null || !arr.isArray())
        {
            return java.util.Collections.emptyList();
        }
        List<Integer> list = new ArrayList<>();
        for (JsonNode n : arr)
        {
            if (n.isNumber())
            {
                int v = n.asInt(0);
                if (v > 0)
                {
                    list.add(v);
                }
            }
        }
        return list;
    }

    private List<String> parseStringArray(String capabilityJson, String key)
    {
        JsonNode arr = readField(capabilityJson, key);
        if (arr == null || !arr.isArray())
        {
            return java.util.Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        for (JsonNode n : arr)
        {
            if (n.isTextual())
            {
                String v = n.asText("");
                if (StrUtil.isNotBlank(v))
                {
                    list.add(v.trim());
                }
            }
        }
        return list;
    }

    private Integer parseIntField(String capabilityJson, String key)
    {
        JsonNode v = readField(capabilityJson, key);
        if (v == null || !v.isNumber())
        {
            return null;
        }
        int n = v.asInt(0);
        return n > 0 ? n : null;
    }

    private String parseStringField(String capabilityJson, String key)
    {
        JsonNode v = readField(capabilityJson, key);
        if (v == null || !v.isTextual())
        {
            return null;
        }
        String s = v.asText("");
        return StrUtil.isBlank(s) ? null : s.trim();
    }

    private JsonNode readField(String capabilityJson, String key)
    {
        if (StrUtil.isBlank(capabilityJson))
        {
            return null;
        }
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(capabilityJson);
            return root.get(key);
        }
        catch (Exception e)
        {
            log.warn("分镜图生视频 capability_json 解析失败, key={}, err={}", key, e.getMessage());
            return null;
        }
    }
    /** 扇入等待阶段 SSE 进度：已处理 X/N（进度映射 30→99 区间，接在提交阶段 5→30 之后单调递增）。 */
    private void pushBatchProgress(Long taskId, int total, int processed, int successCount, int failCount)
    {
        if (Objects.isNull(taskId) || total <= 0)
        {
            return;
        }
        int progress = PROGRESS_FANIN_BASE + (int) Math.floor(processed * (double) PROGRESS_FANIN_SPAN / total);
        if (progress > PROGRESS_CAP)
        {
            progress = PROGRESS_CAP;
        }
        String progressText = processed + "/" + total;
        String stepId = "video_gen_" + processed + "_of_" + total;
        String stepTitle = "已处理 " + progressText;

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("taskType", TASK_TYPE_STORYBOARD_VIDEO_GENERATE);
        extras.put("status", TASK_STATUS_PROCESSING);
        extras.put("processedCount", processed);
        extras.put("successCount", successCount);
        extras.put("failCount", failCount);
        extras.put("totalCount", total);
        extras.put("progressText", progressText);
        extras.put("currentCount", processed);

        try
        {
            sseManager.sendStepProgressWithData(taskId, "video_gen", progress,
                    stepId, stepTitle, processed, total, extras);
        }
        catch (Exception e)
        {
            log.warn("分镜批量出片 progress 推送异常: taskId={}, {}/{}", taskId, processed, total);
        }
    }

    /** 提交阶段 SSE 进度：已提交 X/N（进度映射 5→30 区间，替换排队 admitted 快照，避免前端长时间卡在 5%）。 */
    private void pushSubmitProgress(Long taskId, int total, int submitted)
    {
        if (Objects.isNull(taskId) || total <= 0)
        {
            return;
        }
        int progress = PROGRESS_SUBMIT_BASE + (int) Math.floor(submitted * (double) PROGRESS_SUBMIT_SPAN / total);
        String progressText = submitted + "/" + total;
        String stepId = "video_submit_" + submitted + "_of_" + total;
        String stepTitle = "已提交 " + progressText;

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("taskType", TASK_TYPE_STORYBOARD_VIDEO_GENERATE);
        extras.put("status", TASK_STATUS_PROCESSING);
        extras.put("submittedCount", submitted);
        extras.put("totalCount", total);
        extras.put("progressText", progressText);

        try
        {
            sseManager.sendStepProgressWithData(taskId, "video_gen", progress,
                    stepId, stepTitle, submitted, total, extras);
        }
        catch (Exception e)
        {
            log.warn("分镜批量出片提交进度推送异常: taskId={}, {}/{}", taskId, submitted, total);
        }
    }

    /**
     * DB 兜底：检查指定 storyboardId 是否真的有未结束（PENDING / PROCESSING）的
     * storyboard_video_generate 任务。父任务 input_snapshot 内为 {@code "storyboardIds":[...]} 数组，
     * 用数组元素边界（{@code [id] / [id, / ,id, / ,id]}）做 LIKE 匹配，避免数字前缀误命中。
     */
    private boolean hasActiveTaskInDb(Long storyboardId)
    {
        if (Objects.isNull(storyboardId))
        {
            return false;
        }
        String key = "\"storyboardIds\":[";
        String only = "[" + storyboardId + "]";
        String first = "[" + storyboardId + ",";
        String middle = "," + storyboardId + ",";
        String last = "," + storyboardId + "]";
        LambdaQueryWrapper<AidExtractTask> w = Wrappers.lambdaQuery();
        w.eq(AidExtractTask::getTaskType, TASK_TYPE_STORYBOARD_VIDEO_GENERATE)
                .in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_QUEUED, TASK_STATUS_PROCESSING)
                .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)
                .like(AidExtractTask::getInputSnapshot, key)
                .and(q -> q.like(AidExtractTask::getInputSnapshot, only)
                        .or().like(AidExtractTask::getInputSnapshot, first)
                        .or().like(AidExtractTask::getInputSnapshot, middle)
                        .or().like(AidExtractTask::getInputSnapshot, last));
        Long cnt = extractTaskService.getBaseMapper().selectCount(w);
        return Objects.nonNull(cnt) && cnt > 0;
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
        // 成功终态清空 errorMessage，避免续生回滚等写入的旧错误残留
        update.set(AidExtractTask::getErrorMessage, null);
        if (StrUtil.isNotBlank(resultJson))
        {
            update.set(AidExtractTask::getResultData, resultJson);
        }
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, update);
        if (rows == 0)
        {
            log.warn("分镜图生视频成功CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
            return false;
        }
        wechatNotifyService.notifyTaskTerminal(taskId);
        return true;
    }

    /** PROCESSING → PARTIAL_FAILED（部分镜头/条成功 + 至少一条失败，支持续生）。 */
    private boolean updateTaskPartialFailed(Long taskId, int totalCount, String resultJson)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.eq(AidExtractTask::getStatus, TASK_STATUS_PROCESSING);
        update.set(AidExtractTask::getStatus, TASK_STATUS_PARTIAL_FAILED);
        update.set(AidExtractTask::getTotalCount, totalCount);
        // 清空旧 errorMessage（失败明细在 resultData.failedItems），避免续生回滚写入的旧文案残留
        update.set(AidExtractTask::getErrorMessage, null);
        if (StrUtil.isNotBlank(resultJson))
        {
            update.set(AidExtractTask::getResultData, resultJson);
        }
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, update);
        if (rows == 0)
        {
            log.warn("分镜批量出片部分失败CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
            return false;
        }
        wechatNotifyService.notifyTaskTerminal(taskId);
        return true;
    }

    /** 续生提交失败回滚：PENDING→原状态 + 恢复 totalCount + 恢复 inputSnapshot + CAS 释放本轮锁；保留原 resultData 与续生入口。 */
    private void releaseLocksAndRollbackResume(Long taskId, List<ShotLock> heldLocks, Integer originalTotalCount,
            String originalStatus, String originalInputSnapshot)
    {
        try
        {
            LambdaUpdateWrapper<AidExtractTask> u = Wrappers.lambdaUpdate();
            u.eq(AidExtractTask::getId, taskId);
            u.eq(AidExtractTask::getStatus, TASK_STATUS_PENDING);
            u.set(AidExtractTask::getStatus, StrUtil.blankToDefault(originalStatus, TASK_STATUS_PARTIAL_FAILED));
            if (Objects.nonNull(originalTotalCount))
            {
                u.set(AidExtractTask::getTotalCount, originalTotalCount);
            }
            // 恢复续生前的 inputSnapshot（含旧 runNo / 旧 token），避免入队失败也白白消耗 runNo / 残留新 token
            u.set(AidExtractTask::getInputSnapshot, originalInputSnapshot);
            u.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            extractTaskService.getBaseMapper().update(null, u);
        }
        catch (Exception e)
        {
            log.warn("分镜批量出片续生回滚原状态失败: taskId={}", taskId, e);
        }
        for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
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
            log.warn("分镜图生视频失败CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
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
            log.warn("分镜图生视频取消CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
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
            log.warn("分镜图生视频取消(保留结果)CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
            return false;
        }
        return true;
    }
}

