package com.aid.rps.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidAgent;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.agent.IAidAgentService;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.enums.BillingConstants;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.BillingAmountCalculator;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.model.service.IAiModelBusinessService;
import com.aid.model.vo.AiModelVO;
import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.dto.StoryboardVideoWithPromptRequest;
import com.aid.enums.CreationModeEnum;
import com.aid.rps.helper.AssetExtractHelper;
import com.aid.rps.helper.ProjectGenerateLockGuard;
import com.aid.rps.queue.TaskQueueService;
import com.aid.rps.queue.BatchTaskLocalOrchestrator;
import com.aid.rps.resolver.ReferenceAssetSanitizer;
import com.aid.rps.resolver.StoryboardImageReferenceResolver;
import com.aid.common.aid.rocketmq.config.RocketMqConfigManager;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.service.IExtractBillingService;
import com.aid.rps.service.IStoryboardVideoPromptService;
import com.aid.rps.sse.AssetExtractSseManager;
import com.aid.service.IAiModelConfigService;
import com.aid.storyboard.dto.ChainTriggerResult;
import com.aid.storyboard.service.impl.StoryboardStepChainService;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 批量生成"分镜视频提示词"实现（视觉导演融合版）。
 * 复用 {@link StoryboardImagePromptServiceImpl} 的任务体系骨架：
 * aid_extract_task 父任务 + 项目级 Redis 锁 + 计费 + MQ + SSE + 僵尸自愈。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class StoryboardVideoPromptServiceImpl implements IStoryboardVideoPromptService
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_QUEUED = "QUEUED";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
    private static final String TASK_STATUS_CANCELLED = "CANCELLED";

    /**
     * 可「断线重连」的任务状态集合：全部已生成、本次没有新目标镜头时，
     * 若最近一条任务处于活跃(PENDING/QUEUED/PROCESSING)或有产物的终态(SUCCEEDED/PARTIAL_FAILED)，
     * 则回传该任务供前端重订阅 SSE，而非直接抛「已生成」死路。
     * 纯失败/取消(FAILED/CANCELLED)无产物，不纳入重连。
     */
    private static final Set<String> RECONNECTABLE_STATUS = Set.of(
            "PENDING", "QUEUED", "PROCESSING", "SUCCEEDED", "PARTIAL_FAILED");

    /** 计费状态：已完整结算（= ExtractBillingStatus.SUCCESS，续生前置校验用） */
    private static final String BILLING_STATUS_SUCCESS = "SUCCESS";
    /** 计费状态：已退款（ExtractBillingStatus.FAILED），排队期停止后允许重新预冻结续生 */
    private static final String BILLING_STATUS_REFUNDED = "FAILED";

    /** 任务类型常量 */
    public static final String TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH = "storyboard_video_prompt_batch";

    /** 缺失分镜提示最多展示数量，避免异常文案过长。 */
    private static final int MISSING_STORYBOARD_LABEL_LIMIT = 10;

    /** 默认智能体编码（多参方向） */
    private static final String DEFAULT_AGENT_CODE = "aid_visual_director";

    /** 智能体业务分类（多参方向，必须严格匹配） */
    private static final String BIZ_CATEGORY = "main_storyboard_video_prompt";

    /** 默认智能体编码（图生方向，漫剧版） */
    private static final String DEFAULT_AGENT_CODE_IMAGE = "aid_visual_director_image";

    /** 智能体业务分类（图生方向，必须严格匹配） */
    private static final String BIZ_CATEGORY_IMAGE = "main_storyboard_video_prompt_image";

    /** 默认智能体编码（宫格方向，auto_grid 专业版）。 */
    private static final String DEFAULT_AGENT_CODE_GRID = "aid_visual_director_grid";

    /** 多参方向-专业版智能体编码（pro 创作模式命中）：吃「中文镜头组」script_params，输出三段式（与标准多参同校验）。 */
    private static final String AGENT_CODE_MULTIREF = "aid_visual_director_multiref";

    /** 方向标识：多参（回填 video_prompt）/ 图生（回填 video_prompt_image）/ 宫格（auto_grid，回填 video_prompt_image，复用图生列不新增列）。
     *  存入 inputSnapshot.direction；旧任务无该键时默认多参，保持向后兼容。 */
    private static final String DIRECTION_MULTI = "multi";
    private static final String DIRECTION_IMAGE = "image";
    private static final String DIRECTION_GRID = "grid";

    /** 模型类型必须为 text */
    private static final String MODEL_TYPE_TEXT = "text";

    /** RocketMQ topic / tag */
    private static final String MQ_TOPIC = "ASSET_EXTRACT_TOPIC";
    private static final String MQ_TAG = "extract";

    /** 业务任务类型（写入 aid_media_task.biz_task_type） */
    private static final String BIZ_TASK_TYPE = "storyboard_video_prompt";

    /** 续生窗口：24 小时 */
    private static final long RESUME_WINDOW_HOURS = 24L;

    /** LLM 输出 prompt 平均字符数（用于预估输出 token） */
    private static final int ESTIMATED_OUTPUT_CHARS = 600;

    /**
     * 视频提示词格式校验必须命中的标签——多参方向（agent=aid_visual_director）。
     * 对齐该智能体输出契约（其 prompt_content 第七/九节）：每条 prompt 为固定三段式
     * {@code # 主题 / # 运镜 / # 风格}（C 级轻量镜头同样保留三段）。
     */
    private static final String[] VIDEO_PROMPT_REQUIRED_TOKENS = {"# 主题", "# 运镜", "# 风格"};

    /**
     * 视频提示词格式校验必须命中的标签——图生方向（漫剧版）（agent=aid_visual_director_image）。
     * 该智能体输出公式为 {@code 镜头运动：[...]\n画面描述：[...]}，与多参方向的三段式不同，
     * 故两方向用各自的校验标签（共用一套会误杀其中一个方向）。
     */
    private static final String[] VIDEO_PROMPT_IMAGE_REQUIRED_TOKENS = {"镜头运动：", "画面描述："};

    /**
     * 视频提示词格式校验必须命中的标签——宫格方向（auto_grid 专业版）（agent=aid_visual_director_grid）。
     * 宫格视觉导演输出结构与多参三段式、图生「镜头运动/画面描述」均不同（核心是「切换镜头 + 参考分镜图」逐宫格描述），
     * 故单独一套校验标签，避免被另两套误杀。
     */
    private static final String[] VIDEO_PROMPT_GRID_REQUIRED_TOKENS = {"切换镜头", "参考分镜图"};

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 视频提示词中的业务私有图片占位，输出落库前按当前资产白名单做文字降级。 */
    private static final Pattern IMAGE_REFERENCE_PATTERN = Pattern.compile("@图片(\\d+)\\[([^\\]]+)\\]");
    @Autowired
    private IAidStoryboardService storyboardService;
    @Autowired
    private IAidComicProjectService projectService;
    @Autowired
    private IAidComicEpisodeService aidComicEpisodeService;
    @Autowired
    private IAidExtractTaskService extractTaskService;
    @Autowired
    private IAidAgentService aidAgentService;
    @Autowired
    private IAiModelConfigService aiModelConfigService;

    /** 项目级配置统一解析器（3 级兜底：用户传 → 项目配置 → aid_config 默认） */
    @Autowired
    private com.aid.projectgenconfig.service.IProjectGenConfigResolver projectGenConfigResolver;
    /** 业务功能模型池查询（图生方向强校验模型是否在 main_storyboard_video_prompt_image 池内） */
    @Autowired
    private IAiModelBusinessService aiModelBusinessService;
    @Autowired
    private IExtractBillingService extractBillingService;
    @Autowired
    private BillingAmountCalculator billingAmountCalculator;
    /** 账户服务（建任务前的余额只读预检，冻结仍走统一 prepareBilling） */
    @Autowired
    private com.aid.billing.service.IAccountUpdateService accountUpdateService;
    @Autowired
    private IAssetExtractService assetExtractService;
    @Autowired
    private AssetExtractHelper helper;
    @Autowired
    private RedisCache redisCache;

    /** 项目/剧集级锁的安全获取器（含僵尸锁自愈，与 AssetExtractServiceImpl 提取锁同一机制） */
    @Autowired
    private ProjectGenerateLockGuard projectLockGuard;

    /** 任务排队 / 多维并发调度服务 */
    @Autowired
    private TaskQueueService taskQueueService;

    /** 双模式派发路由器（MQ/本地切换唯一收口） */
    @Autowired
    private com.aid.rps.queue.DualModeTaskDispatcher dualModeTaskDispatcher;

    /** 本地派发终态编排器（与 MQ Consumer 共用同一套终态语义） */
    @Autowired
    private BatchTaskLocalOrchestrator batchTaskLocalOrchestrator;

    @Autowired
    private StoryboardStepChainService storyboardStepChainService;

    /** 本地编排规格：分镜视频提示词 */
    private static final BatchTaskLocalOrchestrator.Spec LOCAL_SPEC =
            new BatchTaskLocalOrchestrator.Spec(
                    "storyboard_video_prompt", "初始化视频提示词批量生成...",
                    "剩余镜头已取消", "部分镜头生成失败，可续生",
                    TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH);
    @Autowired
    private AssetExtractSseManager sseManager;

    @Autowired
    private IAidRolePropSceneService rolePropSceneService;

    @Autowired
    private IAidRolePropSceneFormService rolePropSceneFormService;

    @Autowired
    private IAidRolePropSceneFormImageService rolePropSceneFormImageService;

    /** aid_media_task 直读：结算阶段聚合 LLM 真实 token（provider usage 落在 billing_snapshot_json）。
     *  aid_media_task 无独立 Service，沿用现有直读 Mapper 的统一做法（参考 AssetExtractServiceImpl）。 */
    @Autowired
    private AidMediaTaskMapper aidMediaTaskMapper;
    @Override
    public boolean isVideoPromptFormatValid(String videoPrompt)
    {
        // 公共方法供「手动保存」(saveManualVideoPrompt 写 video_prompt，属多参方向) 使用 → 校验多参方向三段式
        return matchesAllTokens(videoPrompt, VIDEO_PROMPT_REQUIRED_TOKENS);
    }

    /**
     * 按方向校验视频提示词格式（批量回填用）。
     * 多参方向(aid_visual_director)输出三段式 {@code # 主题/# 运镜/# 风格}；
     * 图生方向(aid_visual_director_image)输出 {@code 镜头运动：/画面描述：} 公式。两方向各用各的标签，避免互相误杀。
     *
     * @param videoPrompt 模型输出的单条 prompt
     * @param direction   方向：{@code multi}=三段式 / {@code image}=镜头运动+画面描述 / {@code grid}=切换镜头+参考分镜图
     */
    private boolean isVideoPromptFormatValid(String videoPrompt, String direction)
    {
        String[] tokens;
        if (DIRECTION_GRID.equals(direction))
        {
            tokens = VIDEO_PROMPT_GRID_REQUIRED_TOKENS;
        }
        else if (DIRECTION_IMAGE.equals(direction))
        {
            tokens = VIDEO_PROMPT_IMAGE_REQUIRED_TOKENS;
        }
        else
        {
            // 多参方向（含标准 aid_visual_director 与专业版 aid_visual_director_multiref）均为三段式
            tokens = VIDEO_PROMPT_REQUIRED_TOKENS;
        }
        return matchesAllTokens(videoPrompt, tokens);
    }

    /** 文本是否命中给定的全部必需标签（任一缺失即不合法；空文本不合法）。 */
    private boolean matchesAllTokens(String text, String[] tokens)
    {
        if (StrUtil.isBlank(text))
        {
            return false;
        }
        for (String token : tokens)
        {
            if (!text.contains(token))
            {
                return false;
            }
        }
        return true;
    }
    @Override
    public void saveManualVideoPrompt(Long storyboardId, String videoPrompt, Long userId)
    {
        if (Objects.isNull(storyboardId) || Objects.isNull(userId))
        {
            throw new ServiceException("参数错误");
        }
        if (StrUtil.isBlank(videoPrompt))
        {
            log.info("手动视频提示词为空: storyboardId={}, userId={}", storyboardId, userId);
            throw new ServiceException("提示词不能为空");
        }
        // 格式校验
        if (!isVideoPromptFormatValid(videoPrompt))
        {
            log.info("手动视频提示词格式不规范: storyboardId={}, userId={}", storyboardId, userId);
            throw new ServiceException("提示词不规范");
        }
        // 归属校验 + 落库
        LambdaUpdateWrapper<AidStoryboard> upd = Wrappers.lambdaUpdate();
        upd.eq(AidStoryboard::getId, storyboardId);
        upd.eq(AidStoryboard::getUserId, userId);
        upd.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
        upd.set(AidStoryboard::getVideoPrompt, videoPrompt);
        upd.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
        upd.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
        boolean updated = storyboardService.update(upd);
        if (!updated)
        {
            log.info("手动视频提示词落库失败（分镜不存在或无权）: storyboardId={}, userId={}", storyboardId, userId);
            throw new ServiceException("分镜不存在");
        }
        log.info("手动视频提示词保存成功: storyboardId={}, userId={}", storyboardId, userId);
    }
    @Override
    public AssetExtractTaskVO batchGenerateVideoPrompt(Long projectId, Long episodeId, Long userId,
                                                       List<Long> storyboardIds,
                                                       String agentCode, String modelCode,
                                                       Boolean overwrite, Map<String, Object> chainNext)
    {
        // 多参方向：回填 aid_storyboard.video_prompt
        return batchGenerateInternal(DIRECTION_MULTI, projectId, episodeId, userId,
                storyboardIds, agentCode, modelCode, overwrite, chainNext);
    }

    @Override
    public AssetExtractTaskVO batchGenerateVideoPromptImage(Long projectId, Long episodeId, Long userId,
                                                            List<Long> storyboardIds,
                                                            String agentCode, String modelCode,
                                                            Boolean overwrite)
    {
        // 图生方向（漫剧版）：回填 aid_storyboard.video_prompt_image，与多参物理隔离
        return batchGenerateInternal(DIRECTION_IMAGE, projectId, episodeId, userId,
                storyboardIds, agentCode, modelCode, overwrite, null);
    }

    @Override
    public AssetExtractTaskVO batchGenerateVideoPromptGrid(Long projectId, Long episodeId, Long userId,
                                                           List<Long> storyboardIds,
                                                           String agentCode, String modelCode,
                                                           Boolean overwrite)
    {
        // 宫格方向（auto_grid 专业版）：biz=main_storyboard_video_prompt_grid，agent=aid_visual_director_grid，
        // 复用图生列 video_prompt_image 回填（不新增列），与图生/多参共用任务/计费/锁/队列骨架。
        return batchGenerateInternal(DIRECTION_GRID, projectId, episodeId, userId,
                storyboardIds, agentCode, modelCode, overwrite, null);
    }

    /** chainNext 链路类型常量（与 StoryboardStepChainService 协议一致）。 */
    private static final String CHAIN_TYPE_VIDEO = "video";
    private static final String CHAIN_TYPE_VIDEO_IMAGE = "video_image";
    private static final String CHAIN_TYPE_VIDEO_GRID = "video_grid";

    /**
     * 【统一视频合一】批量生成分镜视频提示词 + 自动出片，按创作模式自动路由。
     * i2v→图生方向(video_image)、multi/pro→多参方向(video，pro 池在出片侧自动切)、auto_grid→宫格方向(video_grid)。
     * 提示词阶段智能体由 resolver 依据 creation_mode 自动解析（agentCode 透传，通常为空）。
     */
    @Override
    public AssetExtractTaskVO batchGenerateVideoWithPromptAuto(StoryboardVideoWithPromptRequest request, Long userId)
    {
        // 入参兜底（详细字段校验交给内部实现）
        if (Objects.isNull(request) || Objects.isNull(request.getProjectId()) || Objects.isNull(request.getEpisodeId()))
        {
            log.error("统一视频合一入参无效: userId={}", userId);
            throw new ServiceException("参数错误");
        }
        String creationMode = resolveCreationMode(request.getProjectId(), request.getEpisodeId(), userId);
        CreationModeEnum mode = CreationModeEnum.getByValue(creationMode);
        String direction;
        String chainType;
        if (CreationModeEnum.I2V.equals(mode))
        {
            direction = DIRECTION_IMAGE;                 // 图生：回填 video_prompt_image
            chainType = CHAIN_TYPE_VIDEO_IMAGE;          // 出片走 generateVideoFromImage
        }
        else if (CreationModeEnum.AUTO_GRID.equals(mode))
        {
            direction = DIRECTION_GRID;                  // 宫格：回填 video_prompt_image
            chainType = CHAIN_TYPE_VIDEO_GRID;           // 出片走 generateVideoFromGrid
        }
        else
        {
            // multi / pro / 缺省：多参方向（pro 的专属池切换在出片侧按创作模式自动完成）
            direction = DIRECTION_MULTI;                 // 回填 video_prompt
            chainType = CHAIN_TYPE_VIDEO;                // 出片走 generateVideo
        }
        Map<String, Object> chainNext = new LinkedHashMap<>();
        chainNext.put("type", chainType);                                  // 出片链路类型
        chainNext.put("modelName", request.getGenModelName());             // 出片模型（可选）
        chainNext.put("aspectRatio", request.getGenAspectRatio());         // 出片宽高比（可选）
        chainNext.put("resolution", request.getGenResolution());           // 出片清晰度档位（可选）
        chainNext.put("durationSeconds", request.getGenDurationSeconds()); // 出片时长秒（可选）
        chainNext.put("generateAudio", request.getGenGenerateAudio());     // 是否出音频（可选）
        log.info("统一视频合一路由: userId={}, projectId={}, episodeId={}, creationMode={}, direction={}, chainType={}",
                userId, request.getProjectId(), request.getEpisodeId(), creationMode, direction, chainType);
        return batchGenerateInternal(direction, request.getProjectId(), request.getEpisodeId(), userId,
                request.getStoryboardIds(), request.getAgentCode(), request.getModelCode(),
                request.getOverwrite(), chainNext);
    }

    /**
     * 解析创作模式（剧集 creation_mode 优先 → 项目 default_creation_mode 兜底 → 空串）。
     * 口径与 {@code StoryboardVideoGenerationServiceImpl.resolveCreationMode} 一致，保证提示词侧路由与出片侧分流同源。
     */
    private String resolveCreationMode(Long projectId, Long episodeId, Long userId)
    {
        if (Objects.nonNull(episodeId) && episodeId > 0)
        {
            AidComicEpisode episode = aidComicEpisodeService.getOne(
                    Wrappers.<AidComicEpisode>lambdaQuery()
                            .select(AidComicEpisode::getId, AidComicEpisode::getCreationMode)
                            .eq(AidComicEpisode::getId, episodeId)
                            .eq(AidComicEpisode::getProjectId, projectId)
                            .eq(AidComicEpisode::getUserId, userId)
                            .eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL)
                            .last("limit 1"), false);
            if (Objects.nonNull(episode) && StrUtil.isNotBlank(episode.getCreationMode()))
            {
                return episode.getCreationMode().trim();
            }
        }
        AidComicProject project = projectService.selectAidComicProjectById(projectId);
        return Objects.isNull(project) ? "" : StrUtil.trimToEmpty(project.getDefaultCreationMode());
    }

    /**
     * 批量生成视频提示词内部实现：多参 / 图生 / 宫格三方向共用同一套任务/计费/锁/队列骨架，
     * 仅以 {@code direction} 区分 业务分类 / 默认智能体 / 回填列 / 输入装配 / "已生成"判定列。
     */
    private AssetExtractTaskVO batchGenerateInternal(String direction, Long projectId, Long episodeId, Long userId,
                                                     List<Long> storyboardIds,
                                                     String agentCode, String modelCode,
                                                     Boolean overwrite, Map<String, Object> chainNext)
    {
        boolean imageDir = DIRECTION_IMAGE.equals(direction);
        boolean gridDir = DIRECTION_GRID.equals(direction);
        // 回填/判定列：图生与宫格都用 video_prompt_image 列（宫格复用图生列，不新增列）
        boolean imageColumn = imageDir || gridDir;
        if (Objects.isNull(projectId) || Objects.isNull(episodeId) || Objects.isNull(userId))
        {
            throw new ServiceException("参数错误");
        }
        // storyboardIds 可选：不传 → 处理本剧集全部分镜；传了 → 仅处理这些
        List<Long> uniqueIds = CollectionUtil.isEmpty(storyboardIds) ? new ArrayList<>()
                : storyboardIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        boolean explicitShots = CollectionUtil.isNotEmpty(uniqueIds);
        // 传了分镜 → 默认覆盖（重新生成这些镜头）；不传 → 由 overwrite 区分 重新生成(true)/继续生成(false)
        boolean overwriteFlag = explicitShots || Boolean.TRUE.equals(overwrite);

        AidComicProject project = projectService.selectAidComicProjectById(projectId);
        if (Objects.isNull(project) || !Objects.equals(userId, project.getUserId())
                || !DEL_FLAG_NORMAL.equals(project.getDelFlag()))
        {
            throw new ServiceException("项目不存在");
        }

        // 2&3. 统一解析：项目级配置 → aid_config 兜底（3 级链路）。
        //      按方向选场景：多参=main_storyboard_video_prompt / 图生=main_storyboard_video_prompt_image。
        //      解析器内部完成：智能体存在 + status=1 + biz_category 校验，
        //      模型存在 + model_type=text + 在该 funcCode 模型池内校验（两方向均做池校验）。
        com.aid.projectgenconfig.enums.ProjectGenConfigScene scene = gridDir
                ? com.aid.projectgenconfig.enums.ProjectGenConfigScene.STORYBOARD_VIDEO_PROMPT_GRID
                : (imageDir
                    ? com.aid.projectgenconfig.enums.ProjectGenConfigScene.STORYBOARD_VIDEO_PROMPT_IMAGE
                    : com.aid.projectgenconfig.enums.ProjectGenConfigScene.STORYBOARD_VIDEO_PROMPT);
        com.aid.projectgenconfig.service.ResolvedSceneConfig resolved =
                projectGenConfigResolver.resolve(projectId, episodeId, userId, scene, agentCode, modelCode, null, null);
        agentCode = resolved.getAgentCode();
        String resolvedModelCode = resolved.getModelCode();
        // 智能体 prompt_content 非空校验（resolver 不返回 agent 实体，按解析后的 agentCode 重载一次）
        AidAgent agent = aidAgentService.getByAgentCode(agentCode);
        if (Objects.isNull(agent) || StrUtil.isBlank(agent.getPromptContent()))
        {
            throw new ServiceException("智能体不可用");
        }
        // 计费需要 modelConfig（resolver 已校验存在 + text + 在池内，此处仅取配置对象供预冻结使用）
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(resolvedModelCode);
        if (Objects.isNull(modelConfig))
        {
            throw new ServiceException("模型不可用");
        }

        List<AidStoryboard> storyboardList = explicitShots
                ? loadTargetStoryboards(projectId, episodeId, userId, uniqueIds)
                : loadEpisodeStoryboards(projectId, episodeId, userId);
        if (CollectionUtil.isEmpty(storyboardList))
        {
            throw new ServiceException("分镜不能为空");
        }

        //    图生方向判定 video_prompt_image，多参方向判定 video_prompt
        List<AidStoryboard> targetList = overwriteFlag ? storyboardList : storyboardList.stream()
                .filter(s -> StrUtil.isBlank(imageColumn ? s.getVideoPromptImage() : s.getVideoPrompt()))
                .collect(Collectors.toList());
        if (CollectionUtil.isEmpty(targetList))
        {
            // 断线重连兜底：全部已生成且未 overwrite 时，本次没有新目标镜头。
            // 若存在可重连的最近任务（活跃 / 有产物终态），回传其 taskId 供前端重订阅 SSE 拉取进度或终态，
            // 不再直接抛「已生成」死路（解决「生成中途断线 → 回到页面重复点击 → 提示已生成无法恢复」）。
            AssetExtractTaskVO reconnect = findReconnectableTask(projectId, episodeId, userId);
            if (Objects.nonNull(reconnect))
            {
                return reconnect;
            }
            throw new ServiceException("视频提示词已生成");
        }

        //    图生 / 宫格方向额外要求 image_prompt。镜头组结构（pro multiref + auto_grid grid）校验「画面说明」。
        boolean groupStructure = gridDir || AGENT_CODE_MULTIREF.equals(agentCode);
        if (imageColumn)
        {
            assertImagePromptReadyForVideoPrompt(targetList);
        }
        for (AidStoryboard sb : targetList)
        {
            validateShotForVideoPrompt(sb, imageColumn, groupStructure);
        }

        //    校验前置：预估金额与余额预检提前到建任务之前，
        //    余额不足时直接拦截，不再产生「先建任务再冻结失败回滚成 FAILED」的废任务记录。
        //    结算阶段按 provider 真实 token 重算实际金额（TOKEN 口径多退少补，与统一计费机制一致）。
        String styleType = StrUtil.nullToEmpty(project.getVideoStyleType());
        String styleValue = StrUtil.nullToEmpty(project.getVideoStyleValue());
        int batchInputChars = estimateBatchInputChars(direction, agentCode, resolvedModelCode,
                styleType, styleValue, targetList, projectId, episodeId, userId);
        int batchOutputChars = ESTIMATED_OUTPUT_CHARS * targetList.size();
        Map<String, Object> billingParams = new HashMap<>();
        billingParams.put("inputChars", batchInputChars);
        billingParams.put("inputTokens", BillingConstants.charsToTokens(batchInputChars));
        billingParams.put("outputTokens", BillingConstants.charsToTokens(batchOutputChars));
        billingParams.put("totalChars", batchInputChars + batchOutputChars);
        BillingInput billingInput = new BillingInput("TEXT", billingParams);
        BillingCalcResult calc = billingAmountCalculator.calculatePreHoldAmount(modelConfig, billingInput);
        BigDecimal totalFrozen = (calc != null && calc.isMatched() && calc.getAmount() != null)
                ? calc.getAmount() : BigDecimal.ZERO;
        // 余额前置预检（只读）：不足直接抛「余额不足」，最终仍以 prepareBilling 锁内冻结为准
        accountUpdateService.precheckBalance(userId, totalFrozen);

        // 抢锁失败时走僵尸锁自愈：DB 复核 + 锁年龄检查 + CAS 清理 + 重抢，
        // 避免「DB 无活跃任务但 Redis 锁泄漏」时让用户卡 30 分钟才能重新提交
        long lockTtlSeconds = Math.max(1800L, (long) targetList.size() * 30L);
        String lockKey = buildLockKey(projectId, episodeId);
        ProjectGenerateLockGuard.AcquireResult lockResult = projectLockGuard.tryAcquireWithStaleClean(
                lockKey, lockTtlSeconds, TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH, projectId, episodeId);
        if (!lockResult.isAcquired())
        {
            throw new ServiceException("任务处理中");
        }

        AidExtractTask task = null;
        try
        {
            task = new AidExtractTask();
            task.setProjectId(projectId);
            task.setEpisodeId(episodeId);
            task.setUserId(userId);
            task.setTaskType(TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH);
            task.setStatus(TASK_STATUS_PENDING);
            task.setModelCode(resolvedModelCode);
            task.setTotalCount(targetList.size());
            // inputSnapshot
            Map<String, Object> inputMap = new LinkedHashMap<>();
            inputMap.put("projectId", projectId);
            inputMap.put("episodeId", episodeId);
            inputMap.put("direction", direction);
            inputMap.put("agentCode", agentCode);
            inputMap.put("modelCode", resolvedModelCode);
            inputMap.put("overwrite", overwriteFlag);
            inputMap.put("storyboardIds", targetList.stream().map(AidStoryboard::getId).collect(Collectors.toList()));
            // 合并接口：链式触发下一步（出视频）规格，提示词终态后由 StoryboardStepChainService 自动发起
            if (chainNext != null && !chainNext.isEmpty())
            {
                inputMap.put("chainNext", chainNext);
            }
            try
            {
                task.setInputSnapshot(OBJECT_MAPPER.writeValueAsString(inputMap));
            }
            catch (Exception e)
            {
                log.error("视频提示词 inputSnapshot 序列化失败: projectId={}, episodeId={}", projectId, episodeId, e);
                throw new ServiceException("提交失败，请重试");
            }
            task.setDelFlag(DEL_FLAG_NORMAL);
            task.setCreateTime(DateUtils.getNowDate());
            task.setCreateBy(String.valueOf(userId));
            task.setUpdateTime(DateUtils.getNowDate());
            task.setUpdateBy(String.valueOf(userId));
            extractTaskService.save(task);

            // 保存计费快照：结算时 settleBilling 据此 + 实际 usage(token) 重算，否则会按预冻结额全扣
            String billingSnapshotJson = null;
            if (calc != null && calc.getSnapshot() != null)
            {
                try { billingSnapshotJson = JSONUtil.toJsonStr(calc.getSnapshot()); }
                catch (Exception ignore) { /* 快照序列化失败不阻断，退化为按预冻结额结算 */ }
            }
            extractBillingService.prepareBilling(task.getId(), userId, totalFrozen, billingSnapshotJson);
            log.info("视频提示词批量预冻结: taskId={}, shotCount={}, frozen={}", task.getId(), targetList.size(), totalFrozen);

            sendMqMessage(task.getId(), projectId, episodeId, userId, resolvedModelCode);

            return AssetExtractTaskVO.builder()
                    .taskId(task.getId())
                    .status(TASK_STATUS_PENDING)
                    .totalShots(targetList.size())
                    .build();
        }
        catch (Exception e)
        {
            // 半态清理（CAS 释放，避免极端尾部场景误删被自愈机制重抢的新锁）
            projectLockGuard.releaseIfMatch(lockKey, lockResult.getToken());
            if (task != null && task.getId() != null)
            {
                // 先退回已冻结金额（幂等：未冻结/未到 FROZEN 则 no-op），再标 FAILED，杜绝冻结款悬挂
                try { extractBillingService.refundBilling(task.getId(), userId); }
                catch (Exception ignore) { }
                try
                {
                    LambdaUpdateWrapper<AidExtractTask> upd = Wrappers.lambdaUpdate();
                    upd.eq(AidExtractTask::getId, task.getId());
                    upd.eq(AidExtractTask::getStatus, TASK_STATUS_PENDING);
                    upd.set(AidExtractTask::getStatus, "FAILED");
                    upd.set(AidExtractTask::getErrorMessage, "提交失败");
                    upd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
                    extractTaskService.update(upd);
                }
                catch (Exception ignore) { }
            }
            if (e instanceof ServiceException) { throw (ServiceException) e; }
            throw new ServiceException("提交失败，请重试");
        }
    }

    /**
     * 断线重连兜底查询：当「全部已生成 + 未 overwrite」导致本次没有新目标镜头时，。
     *
     * @return 可重连任务 VO；无可重连任务时返回 {@code null}
     */
    private AssetExtractTaskVO findReconnectableTask(Long projectId, Long episodeId, Long userId)
    {
        // 防漏字段：除重连所需的 id/status/totalCount 外，补取 inputSnapshot/resultData，
        // 用于修复历史合并任务“提示词已成功但链式子任务未创建”的终态快照。
        AidExtractTask latest = extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getStatus, AidExtractTask::getTotalCount,
                                AidExtractTask::getInputSnapshot, AidExtractTask::getResultData)
                        .eq(AidExtractTask::getProjectId, projectId)
                        .eq(AidExtractTask::getEpisodeId, episodeId)
                        .eq(AidExtractTask::getUserId, userId)
                        .eq(AidExtractTask::getTaskType, TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH)
                        .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByDesc(AidExtractTask::getId)
                        .last("limit 1"), false);
        // 无历史任务：交回调用方抛「已生成」（可能是手动保存等非任务链路填充）
        if (Objects.isNull(latest) || StrUtil.isBlank(latest.getStatus()))
        {
            return null;
        }
        String status = latest.getStatus();
        // 纯失败/取消无产物，不重连
        if (!RECONNECTABLE_STATUS.contains(status))
        {
            return null;
        }
        boolean chainTask = hasChainNext(latest.getInputSnapshot());
        if (!chainTask)
        {
            return null;
        }
        if ((TASK_STATUS_SUCCEEDED.equals(status) || TASK_STATUS_PARTIAL_FAILED.equals(status))
                && (!hasChainChildTaskId(latest.getResultData()) || isChainFailedResult(latest.getResultData())))
        {
            AssetExtractTaskVO chainRetry = retryChainAfterPromptsReady(latest);
            if (Objects.nonNull(chainRetry))
            {
                return chainRetry;
            }
        }
        log.info("视频提示词全部已生成，回传最近任务供前端断线重连: projectId={}, episodeId={}, taskId={}, status={}",
                projectId, episodeId, latest.getId(), status);
        return AssetExtractTaskVO.builder()
                .taskId(latest.getId())
                .status(status)
                .totalShots(latest.getTotalCount())
                .build();
    }
    @Override
    public String doStoryboardVideoPromptBatch(Long taskId, Long userId)
    {
        try
        {
            return doStoryboardVideoPromptBatchInternal(taskId, userId);
        }
        catch (RuntimeException e)
        {
            // 运行期早失败兜底退款（快照解析 / 智能体 / 模型 / LLM 前置异常等在结算块之前抛出时，冻结款本会等补偿才退）。
            // refundBilling 幂等：正常路径已结算(billing=SUCCESS)或已退款则 no-op，仅 FROZEN 时真正退回；不阻断异常上抛
            try { extractBillingService.refundBilling(taskId, userId); }
            catch (Exception ignore) { }
            throw e;
        }
    }

    private String doStoryboardVideoPromptBatchInternal(Long taskId, Long userId)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || StrUtil.isBlank(task.getInputSnapshot()))
        {
            throw new ServiceException("任务不存在");
        }
        Map<String, Object> input;
        try { input = OBJECT_MAPPER.readValue(task.getInputSnapshot(), Map.class); }
        catch (Exception e) { throw new ServiceException("解析失败"); }

        Long projectId = Convert.toLong(input.get("projectId"));
        Long episodeId = Convert.toLong(input.get("episodeId"));
        String direction = String.valueOf(input.getOrDefault("direction", DIRECTION_MULTI));
        boolean imageDir = DIRECTION_IMAGE.equals(direction);
        boolean gridDir = DIRECTION_GRID.equals(direction);
        boolean imageColumn = imageDir || gridDir; // 图生 + 宫格都回填 video_prompt_image
        String defaultAgent = gridDir ? DEFAULT_AGENT_CODE_GRID : (imageDir ? DEFAULT_AGENT_CODE_IMAGE : DEFAULT_AGENT_CODE);
        String agentCode = StrUtil.blankToDefault(String.valueOf(input.getOrDefault("agentCode", defaultAgent)), defaultAgent);
        String modelCode = String.valueOf(input.getOrDefault("modelCode", task.getModelCode()));
        boolean overwriteFlag = Boolean.TRUE.equals(input.get("overwrite"));

        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) input.get("storyboardIds");
        List<Long> storyboardIds = CollectionUtil.isEmpty(rawIds) ? new ArrayList<>()
                : rawIds.stream().map(Convert::toLong).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (CollectionUtil.isEmpty(storyboardIds)) { throw new ServiceException("分镜不能为空"); }

        // 加载智能体提示词模板：复用统一的「文件优先 → 回源 aid_agent → 回写文件」机制，与角色/场景/道具提取一致
        String systemPrompt = helper.loadPromptByName(agentCode);

        // 加载分镜
        List<AidStoryboard> storyboardList = storyboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getProjectId, AidStoryboard::getEpisodeId,
                                AidStoryboard::getUserId, AidStoryboard::getSortOrder,
                                AidStoryboard::getScriptParams, AidStoryboard::getImagePrompt,
                                AidStoryboard::getVideoPrompt, AidStoryboard::getVideoPromptImage,
                                AidStoryboard::getDelFlag)
                        .in(AidStoryboard::getId, storyboardIds)
                        .eq(AidStoryboard::getProjectId, projectId)
                        .eq(AidStoryboard::getEpisodeId, episodeId)
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidStoryboard::getSortOrder));
        if (CollectionUtil.isEmpty(storyboardList)) { throw new ServiceException("镜头列表为空"); }

        // 加载项目（用于全局风格拼接：风格名称 / 风格内容）
        AidComicProject project = projectService.selectAidComicProjectById(projectId);
        String styleType = project == null ? "" : StrUtil.nullToEmpty(project.getVideoStyleType());
        String styleValue = project == null ? "" : StrUtil.nullToEmpty(project.getVideoStyleValue());

        int total = storyboardList.size();
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;
        long totalInputChars = 0;
        long totalOutputChars = 0;
        List<Map<String, Object>> successItems = new ArrayList<>();
        List<Map<String, Object>> failedItems = new ArrayList<>();
        // 本次 LLM 调用的真实 token usage（结算用，从 aid_media_task.billing_snapshot_json 聚合）；空则降级字符估算
        Map<String, Object> realUsage = null;

        sseManager.sendStepProgress(taskId, "storyboard_video_prompt", 10, "init", "初始化视频提示词生成...", 0, total);

        // 继续生成（overwrite=false）：已生成视频提示词的镜头跳过并保留，仅对未生成镜头批量生成（B1：只喂未生成）；
        // 重新生成（overwrite=true）：全部覆盖。
        List<AidStoryboard> targetList = new ArrayList<>();
        for (AidStoryboard sb : storyboardList)
        {
            if (!overwriteFlag && StrUtil.isNotBlank(imageColumn ? sb.getVideoPromptImage() : sb.getVideoPrompt()))
            {
                skipCount++;
                successItems.add(Map.of("storyboardId", sb.getId(), "reason", "已有视频提示词，跳过"));
                continue;
            }
            targetList.add(sb);
        }

        if (CollectionUtil.isNotEmpty(targetList) && !assetExtractService.isTaskCancelled(taskId))
        {
            List<String> referenceAssetNames = loadReferenceableAssetNames(projectId, userId);
            // 整段批量：把【全局风格 + 整段分镜脚本表】一次性喂给视觉导演，一次调用产出 JSON 数组 [{prompt,duration},...]
            // 入参分叉：
            //   · 宫格方向（grid）：吃「中文镜头组」script_params + 宫格图提示词（image_prompt），走 buildBatchLlmInputGroup(含图)
            //   · 多参方向 + 专业版 multiref：吃「中文镜头组」script_params（pro 不出图，直接据镜头组引用资产做多参），走 buildBatchLlmInputGroup(不含图)
            //   · 图生方向（漫剧版）：额外拼【分镜画面生图提示词】+【角色设定】，走 buildBatchLlmInputImage
            //   · 标准多参方向：18 字段单镜表，走 buildBatchLlmInput
            String userContent;
            if (gridDir)
            {
                userContent = buildBatchLlmInputGroup(styleType, styleValue, targetList, true,
                        referenceAssetNames);
            }
            else if (imageDir)
            {
                userContent = buildBatchLlmInputImage(styleType, styleValue, targetList,
                        projectId, episodeId, userId, referenceAssetNames);
            }
            else if (AGENT_CODE_MULTIREF.equals(agentCode))
            {
                userContent = buildBatchLlmInputGroup(styleType, styleValue, targetList, false,
                        referenceAssetNames);
            }
            else
            {
                userContent = buildBatchLlmInput(styleType, styleValue, targetList, referenceAssetNames);
            }
            // provider 无真实 usage 时也按最终 messages 兜底，不能漏掉 system 或合并消息标题。
            totalInputChars = helper.estimateLlmInputChars(systemPrompt, userContent, modelCode);

            String llmRaw = null;
            Long llmMediaTaskId = null;
            try
            {
                // 取终态媒体响应：据本次实际返回的 mediaTaskId 回读真实 token，
                // 即使命中媒体层幂等复用（不产生新行）也能定位被复用的那条媒体任务，避免漏算
                MediaTaskResponse llmResp = helper.callLlmRawForResponse(
                        systemPrompt, userContent, modelCode, taskId, userId, null, BIZ_TASK_TYPE);
                if (llmResp != null)
                {
                    llmRaw = llmResp.getTextContent();
                    llmMediaTaskId = llmResp.getTaskId();
                }
            }
            catch (Exception e)
            {
                log.error("视频提示词批量调用失败: taskId={}, err={}", taskId, e.getMessage(), e);
            }
            totalOutputChars = StrUtil.length(StrUtil.nullToEmpty(llmRaw));

            // 按本次实际媒体任务 id 回读 provider 真实 token（结算按实际 token，charsToTokens 仅作降级兜底）
            realUsage = aggregateActualTokenUsage(llmMediaTaskId);

            // 解析 JSON 数组，按顺序与 targetList 一一对应
            List<JsonNode> elems = parseLlmOutputArray(llmRaw);
            for (int i = 0; i < targetList.size(); i++)
            {
                AidStoryboard sb = targetList.get(i);
                JsonNode elem = i < elems.size() ? elems.get(i) : null;
                String prompt = elem == null ? "" : elem.path("prompt").asText("");
                if (StrUtil.isBlank(prompt) || !isVideoPromptFormatValid(prompt, direction))
                {
                    failCount++;
                    failedItems.add(Map.of("storyboardId", sb.getId(), "errorMessage", "输出格式异常"));
                    continue;
                }
                prompt = sanitizeVideoPromptReferences(prompt, referenceAssetNames, taskId, sb.getId());
                // 回填：多参方向写 video_prompt(+raw)；图生 / 宫格方向写 video_prompt_image（宫格复用图生列）
                int suggestDuration = elem.path("duration").asInt(0);
                if (AGENT_CODE_MULTIREF.equals(agentCode) && suggestDuration <= 0)
                {
                    failCount++;
                    failedItems.add(Map.of("storyboardId", sb.getId(), "errorMessage", "缺少视频时长"));
                    continue;
                }
                LambdaUpdateWrapper<AidStoryboard> upd = Wrappers.lambdaUpdate();
                upd.eq(AidStoryboard::getId, sb.getId());
                upd.eq(AidStoryboard::getUserId, userId);
                upd.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
                if (imageColumn)
                {
                    upd.set(AidStoryboard::getVideoPromptImage, prompt);
                }
                else
                {
                    upd.set(AidStoryboard::getVideoPrompt, prompt);
                }
                // 保存视觉导演建议的视频时长（秒）到 script_params，留给后续「按建议时长出片」消费。
                // 专业版 multiref 已在上方强制要求 duration；其它方向缺失时仍沿用模型默认/用户选择时长。
                if (suggestDuration > 0)
                {
                    String mergedParams = mergeVideoDurationIntoScriptParams(sb.getScriptParams(), suggestDuration);
                    if (StrUtil.isNotBlank(mergedParams))
                    {
                        upd.set(AidStoryboard::getScriptParams, mergedParams);
                    }
                }
                upd.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
                upd.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
                storyboardService.update(upd);

                successCount++;
                successItems.add(Map.of("storyboardId", sb.getId(), "promptLength", StrUtil.length(prompt)));
            }
            sseManager.sendStepProgress(taskId, "generating", 98, "batch",
                    "批量生成完成", skipCount + successCount + failCount, total);
        }

        // 计费结算：首跑与续生统一走 settleBilling（按本次真实 token 多退少补）/ refundBilling（全失败退回）。
        // 续生在 resumeVideoPrompt 已通过 rearmBillingForResume 重置为新一轮 FROZEN（新 traceId/金额/快照），
        // 故此处无需区分首跑 / 续生，彻底统一两条计费路径。
        try
        {
            if (successCount > 0 || skipCount > 0)
            {
                // 结算优先用 provider 真实 token；聚合不到（异常/未落快照）才降级字符估算，保证仍能结算
                Map<String, Object> usageData;
                if (CollectionUtil.isNotEmpty(realUsage))
                {
                    usageData = realUsage;
                    log.info("视频提示词按真实token结算: taskId={}, usage={}", taskId, usageData);
                }
                else
                {
                    usageData = new HashMap<>();
                    usageData.put("input_tokens", BillingConstants.charsToTokens((int) Math.min(totalInputChars, Integer.MAX_VALUE)));
                    usageData.put("output_tokens", BillingConstants.charsToTokens((int) Math.min(totalOutputChars, Integer.MAX_VALUE)));
                    usageData.put("total_chars_estimate", totalInputChars + totalOutputChars);
                    log.warn("视频提示词真实token聚合为空，降级字符估算结算: taskId={}", taskId);
                }
                extractBillingService.settleBilling(taskId, userId, usageData);
                log.info("视频提示词批量结算: taskId={}, success={}, skip={}, fail={}",
                        taskId, successCount, skipCount, failCount);
            }
            else
            {
                extractBillingService.refundBilling(taskId, userId);
                log.info("视频提示词批量全部失败已退款: taskId={}", taskId);
            }
        }
        catch (Exception e) { log.error("视频提示词计费结算异常（不阻断任务终态）: taskId={}", taskId, e); }

        // 释放项目级锁
        try { redisCache.deleteObject(buildLockKey(task.getProjectId(), task.getEpisodeId())); }
        catch (Exception ignore) { }

        // 全部失败抛异常让 Consumer 标 FAILED
        if (failCount == total && total > 0) { throw new ServiceException("操作失败，请重试"); }

        // 返回结果 JSON
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", total);
        result.put("successCount", successCount);
        result.put("skipCount", skipCount);
        result.put("failCount", failCount);
        result.put("successItems", successItems);
        result.put("failedItems", failedItems);
        try { return OBJECT_MAPPER.writeValueAsString(result); }
        catch (Exception e) { return "{}"; }
    }
    @Override
    public AssetExtractTaskVO resumeVideoPrompt(Long taskId, Long userId)
    {
        if (Objects.isNull(taskId)) { throw new ServiceException("任务不能为空"); }
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || !DEL_FLAG_NORMAL.equals(task.getDelFlag())) { throw new ServiceException("任务不存在"); }
        if (!Objects.equals(userId, task.getUserId())) { throw new ServiceException("任务不存在"); }
        if (!TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH.equals(task.getTaskType())) { throw new ServiceException("类型不支持"); }
        if (isActiveTaskStatus(task.getStatus()))
        {
            return AssetExtractTaskVO.builder()
                    .taskId(taskId)
                    .status(task.getStatus())
                    .totalShots(task.getTotalCount())
                    .build();
        }
        if (!TASK_STATUS_PARTIAL_FAILED.equals(task.getStatus())
                && !TASK_STATUS_FAILED.equals(task.getStatus())
                && !TASK_STATUS_CANCELLED.equals(task.getStatus())) { throw new ServiceException("状态不支持"); }
        String originalStatus = task.getStatus();
        String originalRemark = task.getRemark();
        String originalInputSnapshot = task.getInputSnapshot();
        Integer originalTotalCount = task.getTotalCount();
        String originalErrorMessage = task.getErrorMessage();
        // 旧计费周期必须已完整结算（billing_status=SUCCESS）才允许续生：
        // 若仍是 PARTIAL_SUCCESS（首跑欠扣待追补），重置会覆盖旧周期追补快照导致欠款无法追扣，
        // 待 retryPartialExtraCharges 把旧周期追平为 SUCCESS 后再续生。
        boolean refundedTerminal = (TASK_STATUS_CANCELLED.equals(task.getStatus()) || TASK_STATUS_FAILED.equals(task.getStatus()))
                && BILLING_STATUS_REFUNDED.equals(task.getBillingStatus());
        if (!BILLING_STATUS_SUCCESS.equals(task.getBillingStatus()) && !refundedTerminal)
        {
            log.info("视频提示词续生拒绝：旧计费周期未完整结算, taskId={}, billingStatus={}", taskId, task.getBillingStatus());
            throw new ServiceException("结算未完成");
        }
        if (Objects.nonNull(task.getCreateTime()))
        {
            long ageHours = (System.currentTimeMillis() - task.getCreateTime().getTime()) / 3600_000L;
            if (ageHours > RESUME_WINDOW_HOURS) { throw new ServiceException("任务已过期"); }
        }

        // 续生防重锁
        String resumeLockKey = "storyboard:video_prompt:resume:lock:" + taskId;
        Boolean resumeLocked = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(resumeLockKey, "1", 30L * 60L, TimeUnit.SECONDS);
        if (resumeLocked == null || !resumeLocked) { throw new ServiceException("任务处理中"); }

        try
        {
            // 项目级锁（续生场景同样走僵尸锁自愈，避免项目锁泄漏卡死续生入口）
            String projectLockKey = buildLockKey(task.getProjectId(), task.getEpisodeId());
            ProjectGenerateLockGuard.AcquireResult resumeProjectLockResult = projectLockGuard.tryAcquireWithStaleClean(
                    projectLockKey, 30L * 60L, TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH,
                    task.getProjectId(), task.getEpisodeId());
            if (!resumeProjectLockResult.isAcquired()) { throw new ServiceException("项目处理中"); }

            // 解析 inputSnapshot
            Map<String, Object> input;
            try { input = OBJECT_MAPPER.readValue(task.getInputSnapshot(), Map.class); }
            catch (Exception e) { projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken()); throw new ServiceException("解析失败"); }

            boolean imageDir = DIRECTION_IMAGE.equals(String.valueOf(input.getOrDefault("direction", DIRECTION_MULTI)));
            boolean gridDir = DIRECTION_GRID.equals(String.valueOf(input.getOrDefault("direction", DIRECTION_MULTI)));
            boolean imageColumn = imageDir || gridDir; // 图生 + 宫格都用 video_prompt_image 列
            String resumeAgentCode = String.valueOf(input.getOrDefault("agentCode", gridDir ? DEFAULT_AGENT_CODE_GRID : (imageDir ? DEFAULT_AGENT_CODE_IMAGE : DEFAULT_AGENT_CODE)));
            boolean groupStructure = gridDir || AGENT_CODE_MULTIREF.equals(resumeAgentCode);
            String modelCode = StrUtil.blankToDefault(String.valueOf(input.getOrDefault("modelCode", task.getModelCode())), task.getModelCode());
            @SuppressWarnings("unchecked")
            List<Object> rawIds = (List<Object>) input.get("storyboardIds");
            List<Long> originalIds = CollectionUtil.isEmpty(rawIds) ? new ArrayList<>()
                    : rawIds.stream().map(Convert::toLong).filter(Objects::nonNull).distinct().collect(Collectors.toList());
            if (CollectionUtil.isEmpty(originalIds)) { projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken()); throw new ServiceException("分镜不能为空"); }

            // 加载剩余未生成的镜头
            List<AidStoryboard> remaining = storyboardService.list(
                    Wrappers.<AidStoryboard>lambdaQuery()
                            .select(AidStoryboard::getId, AidStoryboard::getScriptParams,
                                    AidStoryboard::getImagePrompt, AidStoryboard::getVideoPrompt,
                                    AidStoryboard::getVideoPromptImage, AidStoryboard::getDelFlag)
                            .in(AidStoryboard::getId, originalIds)
                            .eq(AidStoryboard::getProjectId, task.getProjectId())
                            .eq(AidStoryboard::getEpisodeId, task.getEpisodeId())
                            .eq(AidStoryboard::getUserId, userId)
                            .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL))
                    .stream().filter(s -> StrUtil.isBlank(imageColumn ? s.getVideoPromptImage() : s.getVideoPrompt())).collect(Collectors.toList());
            if (CollectionUtil.isEmpty(remaining))
            {
                try
                {
                    AssetExtractTaskVO chainRetry = retryChainAfterPromptsReady(task);
                    if (Objects.nonNull(chainRetry)) { return chainRetry; }
                    throw new ServiceException("无可续生镜头");
                }
                finally
                {
                    projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                }
            }

            // 缺字段校验（与首跑一致，必须在冻结之前）：避免用户在部分失败后编辑过分镜 / 旧数据不完整时先冻结再失败
            try
            {
                for (AidStoryboard sb : remaining) { validateShotForVideoPrompt(sb, imageColumn, groupStructure); }
            }
            catch (ServiceException ve)
            {
                projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                throw ve;
            }

            // 预冻结：整批一次按估算 token 计算（charsToTokens 仅预冻结）+ 保存可结算快照；
            // 续生与首跑走同一计费链路：rearmBillingForResume 把已完整结算（仅 SUCCESS）任务重置为新一轮 FROZEN
            // → 消费端 settleBilling 按真实 token 多退少补。
            AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(modelCode);
            if (Objects.isNull(modelConfig)) { projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken()); throw new ServiceException("模型不存在"); }
            AidComicProject project = projectService.selectAidComicProjectById(task.getProjectId());
            String styleType = Objects.isNull(project) ? "" : StrUtil.nullToEmpty(project.getVideoStyleType());
            String styleValue = Objects.isNull(project) ? "" : StrUtil.nullToEmpty(project.getVideoStyleValue());
            String direction = String.valueOf(input.getOrDefault("direction", DIRECTION_MULTI));
            int batchInputChars = estimateBatchInputChars(direction, resumeAgentCode, modelCode,
                    styleType, styleValue, remaining, task.getProjectId(), task.getEpisodeId(), userId);
            int batchOutputChars = ESTIMATED_OUTPUT_CHARS * remaining.size();
            Map<String, Object> billingParams = new HashMap<>();
            billingParams.put("inputChars", batchInputChars);
            billingParams.put("inputTokens", BillingConstants.charsToTokens(batchInputChars));
            billingParams.put("outputTokens", BillingConstants.charsToTokens(batchOutputChars));
            billingParams.put("totalChars", batchInputChars + batchOutputChars);
            BillingInput billingInput = new BillingInput("TEXT", billingParams);
            BillingCalcResult calc = billingAmountCalculator.calculatePreHoldAmount(modelConfig, billingInput);
            BigDecimal totalRetryFrozen = (calc != null && calc.isMatched() && calc.getAmount() != null)
                    ? calc.getAmount() : BigDecimal.ZERO;
            String resumeSnapshotJson = null;
            if (calc != null && calc.getSnapshot() != null)
            {
                try { resumeSnapshotJson = JSONUtil.toJsonStr(calc.getSnapshot()); }
                catch (Exception ignore) { /* 快照序列化失败则退化为按预冻结额结算 */ }
            }

            // 续生改写 inputSnapshot：仅处理剩余镜头 + overwrite=true，保证消费端精确处理 remaining，
            // 与本次冻结 / 真实 token 口径一致（不误把已生成镜头重算重扣）
            List<Long> remainingIds = remaining.stream().map(AidStoryboard::getId).collect(Collectors.toList());
            Map<String, Object> resumeInput = new LinkedHashMap<>(input);
            resumeInput.put("storyboardIds", remainingIds);
            resumeInput.put("overwrite", Boolean.TRUE);
            resumeInput.put("modelCode", modelCode);
            String resumeInputJson;
            try { resumeInputJson = OBJECT_MAPPER.writeValueAsString(resumeInput); }
            catch (Exception e) { projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken()); throw new ServiceException("续生失败，请重试"); }

            LambdaUpdateWrapper<AidExtractTask> taskUpd = Wrappers.lambdaUpdate();
            taskUpd.eq(AidExtractTask::getId, taskId);
            taskUpd.in(AidExtractTask::getStatus, TASK_STATUS_PARTIAL_FAILED, TASK_STATUS_FAILED, TASK_STATUS_CANCELLED);
            taskUpd.set(AidExtractTask::getStatus, TASK_STATUS_PENDING);
            taskUpd.set(AidExtractTask::getErrorMessage, null);
            taskUpd.set(AidExtractTask::getRemark, null);
            taskUpd.set(AidExtractTask::getInputSnapshot, resumeInputJson);
            taskUpd.set(AidExtractTask::getTotalCount, remaining.size());
            taskUpd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            taskUpd.set(AidExtractTask::getUpdateBy, String.valueOf(userId));
            boolean statusFlipped = extractTaskService.update(taskUpd);
            if (!statusFlipped) { projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken()); throw new ServiceException("状态不支持"); }

            //    失败则把任务状态回滚回 PARTIAL_FAILED，避免卡在 PENDING
            //    先捕获上一轮已结算计费字段，供入队失败时回滚本轮冻结并恢复上一轮周期
            String priorBillingStatus = task.getBillingStatus();
            String priorTraceId = task.getBillingTraceId();
            BigDecimal priorFrozen = task.getFrozenAmount();
            String priorSnapshotRef = task.getBillingSnapshotJson();
            String priorSnapshot = extractBillingService.resolveBillingSnapshotJson(taskId, priorSnapshotRef);
            try
            {
                extractBillingService.rearmBillingForResume(taskId, userId, totalRetryFrozen, resumeSnapshotJson);
            }
            catch (RuntimeException be)
            {
                rollbackVideoPromptResumeTask(taskId, originalStatus, originalRemark,
                        originalInputSnapshot, originalTotalCount, originalErrorMessage, userId);
                projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                throw (be instanceof ServiceException) ? be : new ServiceException("续生失败，请重试");
            }

            // 发 MQ：入队失败必须回滚——回滚成功(退款确认+恢复上一轮 SUCCESS 周期)则任务恢复 PARTIAL_FAILED 可再续生；
            // 回滚未确认(本轮冻结待统一补偿退款)则置 FAILED，不显示成可续生，避免死结
            try
            {
                sendMqMessage(taskId, task.getProjectId(), task.getEpisodeId(), userId, modelCode);
            }
            catch (RuntimeException mqEx)
            {
                boolean restored = false;
                try
                {
                    restored = extractBillingService.rollbackResumeBilling(taskId, userId,
                            priorBillingStatus, priorTraceId, priorFrozen, priorSnapshot, priorSnapshotRef);
                }
                catch (Exception ignore) { }
                // 回滚成功（已退款 + 恢复上一轮 SUCCESS 计费周期）→ 任务恢复 PARTIAL_FAILED，可再续生；
                // 回滚未确认（本轮冻结待补偿退款、未恢复上一轮）→ 置 FAILED，不显示成可续生，
                // 避免 task=PARTIAL_FAILED（要求 billing=SUCCESS 才可续生）但 billing 卡在本轮的死结
                if (restored)
                {
                    rollbackVideoPromptResumeTask(taskId, originalStatus, originalRemark,
                            originalInputSnapshot, originalTotalCount, "提交失败", userId);
                }
                else
                {
                    rollbackVideoPromptResumeTask(taskId, TASK_STATUS_FAILED, originalRemark,
                            originalInputSnapshot, originalTotalCount, "提交失败", userId);
                }
                projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                throw (mqEx instanceof ServiceException) ? mqEx : new ServiceException("续生失败，请重试");
            }

            return AssetExtractTaskVO.builder().taskId(taskId).status(TASK_STATUS_PENDING).totalShots(remaining.size()).build();
        }
        catch (RuntimeException e)
        {
            if (e instanceof ServiceException) { throw e; }
            throw new ServiceException("续生失败，请重试");
        }
        finally { redisCache.deleteObject(resumeLockKey); }
    }
    private boolean isActiveTaskStatus(String status)
    {
        return TASK_STATUS_PENDING.equals(status)
                || TASK_STATUS_QUEUED.equals(status)
                || TASK_STATUS_PROCESSING.equals(status);
    }

    private AssetExtractTaskVO retryChainAfterPromptsReady(AidExtractTask task)
    {
        if (!hasChainNext(task.getInputSnapshot())
                || (hasChainChildTaskId(task.getResultData()) && !isChainFailedResult(task.getResultData())))
        {
            return null;
        }
        ChainTriggerResult chain = storyboardStepChainService.onPromptBatchTerminal(
                task.getId(), TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH, TASK_STATUS_SUCCEEDED);
        if (Objects.isNull(chain) || chain.isChainFailed() || CollectionUtil.isEmpty(chain.getChildTaskIds()))
        {
            ChainTriggerResult failed = Objects.isNull(chain)
                    ? ChainTriggerResult.failed("提交失败") : chain;
            String resultJson = appendChainFailure(task.getResultData(), failed);
            List<Long> chainChildTaskIds = chainChildTaskIdsFromResult(resultJson);
            updatePromptChainTerminal(task.getId(), TASK_STATUS_PARTIAL_FAILED, resultJson, chainMessage(failed, "提交失败"));
            sendPartialFailedSafely(task.getId(), resultJson, chainMessage(failed, "提交失败"),
                    chainChildTaskIds, failed.getChildTaskType());
            throw new ServiceException(chainMessage(failed, "提交失败"));
        }

        String resultJson = appendChainSuccess(task.getResultData(), chain);
        List<Long> chainChildTaskIds = chainChildTaskIdsFromResult(resultJson);
        String nextStatus = hasPromptFailedItems(resultJson) ? TASK_STATUS_PARTIAL_FAILED : TASK_STATUS_SUCCEEDED;
        updatePromptChainTerminal(task.getId(), nextStatus, resultJson, null);
        if (TASK_STATUS_SUCCEEDED.equals(nextStatus))
        {
            sendCompleteSafely(task.getId(), resultJson, chainChildTaskIds, chain.getChildTaskType());
        }
        else
        {
            sendPartialFailedSafely(task.getId(), resultJson, "部分镜头生成失败，可续生",
                    chainChildTaskIds, chain.getChildTaskType());
        }
        log.info("视频提示词续生重试链式出片成功: taskId={}, childTaskId={}, childTaskType={}",
                task.getId(), chain.getChildTaskId(), chain.getChildTaskType());
        return AssetExtractTaskVO.builder()
                .taskId(task.getId())
                .status(nextStatus)
                .totalShots(task.getTotalCount())
                .resultData(resultJson)
                .chainChildTaskId(firstChainChildTaskId(chainChildTaskIds))
                .chainChildTaskIds(chainChildTaskIds)
                .chainChildTaskType(chain.getChildTaskType())
                .build();
    }

    private boolean hasChainNext(String inputSnapshot)
    {
        if (StrUtil.isBlank(inputSnapshot)) { return false; }
        try
        {
            JsonNode chainNode = OBJECT_MAPPER.readTree(inputSnapshot).path("chainNext");
            return !chainNode.isMissingNode() && !chainNode.isNull() && !chainNode.isEmpty();
        }
        catch (Exception e)
        {
            log.warn("视频提示词续生解析chainNext失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean hasChainChildTaskId(String resultData)
    {
        Map<String, Object> result = parseResultMap(resultData);
        Object listVal = result.get("chainChildTaskIds");
        if (listVal instanceof List<?> list)
        {
            return CollectionUtil.isNotEmpty(list);
        }
        Object val = result.get("chainChildTaskId");
        if (val instanceof Number n)
        {
            return n.longValue() > 0L;
        }
        if (val != null)
        {
            try { return Long.parseLong(String.valueOf(val)) > 0L; }
            catch (Exception ignore) { return false; }
        }
        return false;
    }

    private boolean isChainFailedResult(String resultData)
    {
        Map<String, Object> result = parseResultMap(resultData);
        Object val = result.get("chainFailed");
        return Boolean.TRUE.equals(val) || "true".equalsIgnoreCase(String.valueOf(val));
    }

    private boolean hasPromptFailedItems(String resultData)
    {
        Map<String, Object> result = parseResultMap(resultData);
        int total = numberValue(result.get("totalCount"));
        int success = numberValue(result.get("successCount"));
        int skip = numberValue(result.get("skipCount"));
        return total > 0 && success + skip < total;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResultMap(String resultData)
    {
        if (StrUtil.isBlank(resultData)) { return new LinkedHashMap<>(); }
        try
        {
            return OBJECT_MAPPER.readValue(resultData, Map.class);
        }
        catch (Exception e)
        {
            log.warn("视频提示词续生解析resultData失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private int numberValue(Object value)
    {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private String appendChainSuccess(String resultData, ChainTriggerResult chain)
    {
        Map<String, Object> result = parseResultMap(resultData);
        List<Long> chainChildTaskIds = mergeChainChildTaskIds(result, chain);
        result.remove("chainFailed");
        result.remove("chainMessage");
        if (CollectionUtil.isNotEmpty(chainChildTaskIds))
        {
            result.put("chainChildTaskId", chainChildTaskIds.get(0));
            result.put("chainChildTaskIds", chainChildTaskIds);
        }
        result.put("chainChildTaskType", chain.getChildTaskType());
        try { return OBJECT_MAPPER.writeValueAsString(result); }
        catch (Exception e) { return resultData; }
    }

    private String appendChainFailure(String resultData, ChainTriggerResult chain)
    {
        Map<String, Object> result = parseResultMap(resultData);
        result.put("chainFailed", true);
        result.put("chainMessage", chainMessage(chain, "提交失败"));
        if (StrUtil.isNotBlank(chain.getChildTaskType()))
        {
            result.put("chainChildTaskType", chain.getChildTaskType());
        }
        List<Long> chainChildTaskIds = mergeChainChildTaskIds(result, chain);
        if (CollectionUtil.isNotEmpty(chainChildTaskIds))
        {
            result.put("chainChildTaskId", chainChildTaskIds.get(0));
            result.put("chainChildTaskIds", chainChildTaskIds);
        }
        try { return OBJECT_MAPPER.writeValueAsString(result); }
        catch (Exception e) { return resultData; }
    }

    private List<Long> mergeChainChildTaskIds(Map<String, Object> result, ChainTriggerResult chain)
    {
        List<Long> merged = new ArrayList<>();
        addChainChildTaskIds(merged, result.get("chainChildTaskIds"));
        addChainChildTaskId(merged, result.get("chainChildTaskId"));
        if (Objects.nonNull(chain))
        {
            addChainChildTaskIds(merged, chain.getChildTaskIds());
            addChainChildTaskId(merged, chain.getChildTaskId());
        }
        return merged;
    }

    private List<Long> chainChildTaskIdsFromResult(String resultData)
    {
        return mergeChainChildTaskIds(parseResultMap(resultData), null);
    }

    private Long firstChainChildTaskId(List<Long> taskIds)
    {
        return CollectionUtil.isEmpty(taskIds) ? null : taskIds.get(0);
    }

    private void addChainChildTaskIds(List<Long> target, Object value)
    {
        if (value instanceof List<?> values)
        {
            for (Object item : values)
            {
                addChainChildTaskId(target, item);
            }
        }
    }

    private void addChainChildTaskId(List<Long> target, Object value)
    {
        if (Objects.isNull(value))
        {
            return;
        }
        Long taskId = null;
        if (value instanceof Number n)
        {
            taskId = n.longValue();
        }
        else if (StrUtil.isNotBlank(String.valueOf(value)))
        {
            try { taskId = Long.parseLong(String.valueOf(value)); }
            catch (Exception ignore) { return; }
        }
        if (Objects.nonNull(taskId) && taskId > 0L && !target.contains(taskId))
        {
            target.add(taskId);
        }
    }

    private String chainMessage(ChainTriggerResult chain, String fallback)
    {
        return Objects.nonNull(chain) ? StrUtil.blankToDefault(chain.getMessage(), fallback) : fallback;
    }

    private void updatePromptChainTerminal(Long taskId, String status, String resultJson, String errorMessage)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.set(AidExtractTask::getStatus, status);
        update.set(AidExtractTask::getResultData, resultJson);
        update.set(AidExtractTask::getErrorMessage, errorMessage);
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        extractTaskService.update(update);
    }

    private void sendCompleteSafely(Long taskId, String resultJson, List<Long> chainChildTaskIds, String chainChildTaskType)
    {
        try { sseManager.sendComplete(taskId, OBJECT_MAPPER.readValue(resultJson, Map.class), chainChildTaskIds, chainChildTaskType); }
        catch (Exception e) { sseManager.sendComplete(taskId, resultJson, chainChildTaskIds, chainChildTaskType); }
    }

    private void sendPartialFailedSafely(Long taskId, String resultJson, String message,
                                         List<Long> chainChildTaskIds, String chainChildTaskType)
    {
        try { sseManager.sendPartialFailed(taskId, OBJECT_MAPPER.readValue(resultJson, Map.class), message, chainChildTaskIds, chainChildTaskType); }
        catch (Exception e) { sseManager.sendPartialFailed(taskId, resultJson, message, chainChildTaskIds, chainChildTaskType); }
    }

    private void rollbackVideoPromptResumeTask(Long taskId, String status, String remark,
                                               String inputSnapshot, Integer totalCount,
                                               String errorMessage, Long userId)
    {
        LambdaUpdateWrapper<AidExtractTask> rollback = Wrappers.lambdaUpdate();
        rollback.eq(AidExtractTask::getId, taskId);
        rollback.eq(AidExtractTask::getStatus, TASK_STATUS_PENDING);
        rollback.set(AidExtractTask::getStatus, status);
        rollback.set(AidExtractTask::getRemark, remark);
        rollback.set(AidExtractTask::getInputSnapshot, inputSnapshot);
        rollback.set(AidExtractTask::getTotalCount, totalCount);
        rollback.set(AidExtractTask::getErrorMessage, errorMessage);
        rollback.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        rollback.set(AidExtractTask::getUpdateBy, String.valueOf(userId));
        extractTaskService.update(rollback);
    }

    private String buildLockKey(Long projectId, Long episodeId)
    {
        return "storyboard:video_prompt:lock:" + projectId + ":" + episodeId;
    }

    /**
     * 校验模型是否落在指定功能编码（func_code）的可选模型池内。
     * 与 {@code StoryboardImageGenerationServiceImpl.assertModelInBizCategoryPool} 口径一致：
     * 池未配置 / 模型不在池内一律拒绝，保证运营后台 model_ids 配置具备约束力。
     */
    private void assertModelInFuncPool(String modelCode, Long modelId, String funcCode)
    {
        List<AiModelVO> pool = aiModelBusinessService.listAvailableModelsByFuncCode(funcCode);
        if (CollectionUtil.isEmpty(pool))
        {
            log.error("图生视频提示词模型池未配置或全部失效: funcCode={}, modelCode={}", funcCode, modelCode);
            throw new ServiceException("模型未配置");
        }
        boolean inPool = pool.stream().anyMatch(m -> Objects.equals(modelId, m.getId()));
        if (!inPool)
        {
            log.error("图生视频提示词模型不在业务池内: funcCode={}, modelCode={}, poolSize={}", funcCode, modelCode, pool.size());
            throw new ServiceException("模型不可用");
        }
    }

    /**
     * 取功能池内第一个可用模型编码（图生方向"只配池就能跑"的默认兜底）：池为空抛"功能可选模型未配置"。
     */
    private String firstModelInPool(String funcCode)
    {
        List<AiModelVO> pool = aiModelBusinessService.listAvailableModelsByFuncCode(funcCode);
        if (CollectionUtil.isEmpty(pool))
        {
            log.error("图生视频提示词模型池未配置或全部失效（取默认模型）: funcCode={}", funcCode);
            throw new ServiceException("模型未配置");
        }
        return pool.get(0).getModelCode();
    }

    /**
     * 按本次调用返回的媒体任务 id 回读 provider 真实 token usage（input/output）。
     *
     * @param mediaTaskId 本次 LLM 调用实际返回的媒体任务 id（{@code MediaTaskResponse.taskId}）
     * @return {@code {input_tokens, output_tokens}}；无真实 token 时返回空 Map
     */
    private Map<String, Object> aggregateActualTokenUsage(Long mediaTaskId)
    {
        if (Objects.isNull(mediaTaskId))
        {
            return new HashMap<>();
        }
        AidMediaTask mt = aidMediaTaskMapper.selectOne(
                Wrappers.<AidMediaTask>lambdaQuery()
                        .select(AidMediaTask::getId, AidMediaTask::getBillingSnapshotJson)
                        .eq(AidMediaTask::getId, mediaTaskId)
                        .last("limit 1"));
        Map<String, Object> usage = new HashMap<>();
        if (Objects.isNull(mt) || StrUtil.isBlank(mt.getBillingSnapshotJson()))
        {
            return usage;
        }
        try
        {
            BillingSnapshot snapshot = JSONUtil.toBean(mt.getBillingSnapshotJson(), BillingSnapshot.class);
            int inputTokens = snapshot.getActualInputTokens() == null ? 0 : snapshot.getActualInputTokens();
            int outputTokens = snapshot.getActualOutputTokens() == null ? 0 : snapshot.getActualOutputTokens();
            if (inputTokens > 0 || outputTokens > 0)
            {
                usage.put("input_tokens", inputTokens);
                usage.put("output_tokens", outputTokens);
            }
        }
        catch (Exception e)
        {
            log.warn("视频提示词回读真实token解析快照失败: mediaTaskId={}", mediaTaskId, e);
        }
        return usage;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseScriptParams(String scriptParams)
    {
        if (StrUtil.isBlank(scriptParams)) { return new LinkedHashMap<>(); }
        try { return OBJECT_MAPPER.readValue(scriptParams, Map.class); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    /**
     * 把视觉导演建议的视频时长（秒）合并进 {@code script_params}（key=「视频时长建议秒」），留给后续「按建议时长出片」消费。
     * 读-改-写：保留原有全部 key，仅追加/覆盖该时长 key。解析或序列化失败返回 {@code null}（调用方降级为不写，
     * 不影响 video_prompt 回填本身）。
     */
    private String mergeVideoDurationIntoScriptParams(String scriptParamsJson, int durationSeconds)
    {
        try
        {
            Map<String, Object> params = parseScriptParams(scriptParamsJson);
            params.put("视频时长建议秒", durationSeconds);
            return OBJECT_MAPPER.writeValueAsString(params);
        }
        catch (Exception e)
        {
            log.warn("合并视频时长建议到 script_params 失败(降级不写): err={}", e.getMessage());
            return null;
        }
    }

    /**
     * 加载目标分镜并严格做归属校验。
     */
    private List<AidStoryboard> loadTargetStoryboards(Long projectId, Long episodeId, Long userId, List<Long> ids)
    {
        if (CollectionUtil.isEmpty(ids)) { return new ArrayList<>(); }
        List<AidStoryboard> matched = storyboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getProjectId, AidStoryboard::getEpisodeId,
                                AidStoryboard::getUserId, AidStoryboard::getSortOrder,
                                AidStoryboard::getScriptParams, AidStoryboard::getImagePrompt,
                                AidStoryboard::getVideoPrompt, AidStoryboard::getVideoPromptImage,
                                AidStoryboard::getDelFlag)
                        .in(AidStoryboard::getId, ids)
                        .eq(AidStoryboard::getProjectId, projectId)
                        .eq(AidStoryboard::getEpisodeId, episodeId)
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidStoryboard::getSortOrder));
        if (matched.size() != ids.size())
        {
            throw new ServiceException("镜头存在不可用项");
        }
        return matched;
    }

    /**
     * 加载本剧集全部分镜（未指定 storyboardIds 时用），严格归属校验 + 按 sort_order。
     */
    private List<AidStoryboard> loadEpisodeStoryboards(Long projectId, Long episodeId, Long userId)
    {
        return storyboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getProjectId, AidStoryboard::getEpisodeId,
                                AidStoryboard::getUserId, AidStoryboard::getSortOrder,
                                AidStoryboard::getScriptParams, AidStoryboard::getImagePrompt,
                                AidStoryboard::getVideoPrompt, AidStoryboard::getVideoPromptImage,
                                AidStoryboard::getDelFlag)
                        .eq(AidStoryboard::getProjectId, projectId)
                        .eq(AidStoryboard::getEpisodeId, episodeId)
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidStoryboard::getSortOrder));
    }

    /**
     * 缺字段校验（在创建任务 / 计费冻结之前调用）：视觉导演入参取自 {@code script_params}。
     *
     * @param needImagePrompt 是否要求已有分镜生图提示词（image_prompt）：图生方向(i2v) + 宫格方向(auto_grid) 为 true
     * @param groupStructure  是否「中文镜头组」结构（pro 多参 multiref + auto_grid 宫格）：
     *                        true → 校验镜头组 key {@code 画面说明}；false → 漫剧单镜 key {@code 画面描述}
     */
    private void validateShotForVideoPrompt(AidStoryboard sb, boolean needImagePrompt, boolean groupStructure)
    {
        Map<String, Object> shot = parseScriptParams(sb.getScriptParams());
        if (shot.isEmpty())
        {
            log.info("视频提示词拒绝：分镜脚本未生成, storyboardId={}", sb.getId());
            throw new ServiceException(storyboardLabel(sb) + "缺分镜");
        }
        // 镜头组结构（pro/auto_grid）落库 key 是「画面说明」，漫剧单镜结构是「画面描述」
        String pictureKey = groupStructure ? "画面说明" : "画面描述";
        if (StrUtil.isBlank(Convert.toStr(shot.get(pictureKey))))
        {
            log.info("视频提示词拒绝：缺{}, storyboardId={}", pictureKey, sb.getId());
            throw new ServiceException(storyboardLabel(sb) + "缺画面描述");
        }
        // 引用信息允许为空：用户删除资产后，该镜应按画面文字继续生成，不能被历史引用永久锁死。
        // 图生方向（漫剧版）/ 宫格方向均强依赖分镜生图提示词（漫剧版提取形态名身份绑定；宫格版对齐参考分镜图），未生成则拒绝
        if (needImagePrompt && StrUtil.isBlank(sb.getImagePrompt()))
        {
            log.info("视频提示词拒绝：缺分镜生图提示词, storyboardId={}", sb.getId());
            throw new ServiceException(storyboardLabel(sb) + "图脚本为空");
        }
    }

    /**
     * 图生视频 / 宫格视频必须先具备分镜图脚本；一次性列出缺失镜头，方便用户定位。
     */
    private void assertImagePromptReadyForVideoPrompt(List<AidStoryboard> storyboards)
    {
        List<String> missingLabels = storyboards.stream()
                .filter(storyboard -> StrUtil.isBlank(storyboard.getImagePrompt()))
                .limit(MISSING_STORYBOARD_LABEL_LIMIT + 1L)
                .map(this::storyboardLabel)
                .collect(Collectors.toList());
        if (CollectionUtil.isEmpty(missingLabels))
        {
            return;
        }
        boolean truncated = missingLabels.size() > MISSING_STORYBOARD_LABEL_LIMIT;
        List<String> shownLabels = truncated
                ? missingLabels.subList(0, MISSING_STORYBOARD_LABEL_LIMIT)
                : missingLabels;
        String message = String.join("、", shownLabels) + (truncated ? "等" : "") + "图脚本为空";
        log.info("视频提示词拒绝：存在未生成分镜图脚本的镜头, message={}", message);
        throw new ServiceException(message);
    }

    /**
     * 构建用户可读的分镜定位文案，优先使用排序镜号，兜底使用分镜主键。
     */
    private String storyboardLabel(AidStoryboard storyboard)
    {
        if (Objects.nonNull(storyboard) && Objects.nonNull(storyboard.getSortOrder()))
        {
            return "第" + storyboard.getSortOrder() + "镜";
        }
        if (Objects.nonNull(storyboard) && Objects.nonNull(storyboard.getId()))
        {
            return "分镜" + storyboard.getId();
        }
        return "当前分镜";
    }

    /** 按执行阶段的真实分支组装 system + user，用于首跑和续生的整批预冻结。 */
    private int estimateBatchInputChars(String direction, String agentCode, String modelCode,
                                        String styleType, String styleValue, List<AidStoryboard> shots,
                                        Long projectId, Long episodeId, Long userId)
    {
        boolean imageDir = DIRECTION_IMAGE.equals(direction);
        boolean gridDir = DIRECTION_GRID.equals(direction);
        List<String> referenceAssetNames = loadReferenceableAssetNames(projectId, userId);
        String userContent;
        if (gridDir)
        {
            userContent = buildBatchLlmInputGroup(styleType, styleValue, shots, true, referenceAssetNames);
        }
        else if (imageDir)
        {
            userContent = buildBatchLlmInputImage(styleType, styleValue, shots, projectId, episodeId, userId,
                    referenceAssetNames);
        }
        else if (AGENT_CODE_MULTIREF.equals(agentCode))
        {
            userContent = buildBatchLlmInputGroup(styleType, styleValue, shots, false, referenceAssetNames);
        }
        else
        {
            userContent = buildBatchLlmInput(styleType, styleValue, shots, referenceAssetNames);
        }
        String systemPrompt = helper.loadPromptByName(agentCode);
        return helper.estimateLlmInputChars(systemPrompt, userContent, modelCode);
    }

    /**
     * 整段批量入参拼装：【全局风格(风格名称/风格内容)】+【整段分镜脚本表(每镜 18 字段，源自 script_params 中文 key)】。
     * 镜头顺序＝传入顺序（已按 sort_order），与 LLM 输出 JSON 数组按下标一一对应。
     */
    private String buildBatchLlmInput(String styleType, String styleValue, List<AidStoryboard> shots,
                                      List<String> referenceAssetNames)
    {
        StringBuilder out = new StringBuilder(16384);
        // 全局风格（固定格式）
        out.append("【全局风格】\n");
        out.append("风格名称：").append(StrUtil.nullToEmpty(styleType)).append('\n');
        out.append("风格内容：").append(StrUtil.nullToEmpty(styleValue)).append('\n').append('\n');
        // 分镜脚本表
        out.append("【分镜脚本表】\n");
        int idx = 1;
        for (AidStoryboard sb : shots)
        {
            Map<String, Object> shot = parseScriptParams(sb.getScriptParams());
            sanitizeReferenceInfoInPlace(shot, referenceAssetNames, sb.getId());
            out.append("--- 镜头 ").append(idx++).append(" ---\n");
            appendShotField(out, shot, "镜号");
            appendShotField(out, shot, "剧本内容");
            appendShotField(out, shot, "画面描述");
            appendShotField(out, shot, "台词");
            appendShotField(out, shot, "动作状态");
            appendShotField(out, shot, "叙事功能");
            appendShotField(out, shot, "时间坐标");
            appendShotField(out, shot, "年代坐标");
            appendShotField(out, shot, "日期坐标");
            appendShotField(out, shot, "气候天象");
            appendShotField(out, shot, "引用信息");
            appendShotField(out, shot, "景别");
            appendShotField(out, shot, "拍摄角度");
            appendShotField(out, shot, "镜头焦距");
            appendShotField(out, shot, "镜头运动");
            appendShotField(out, shot, "构图");
            appendShotField(out, shot, "画面氛围");
            appendShotField(out, shot, "音效");
            out.append('\n');
        }
        return out.toString();
    }

    /** 输出一行"中文key：值"（值为空也输出空值行，保证字段位置稳定，便于 LLM 对齐 18 字段）。 */
    private void appendShotField(StringBuilder out, Map<String, Object> shot, String key)
    {
        String v = Convert.toStr(shot.get(key), "");
        out.append(key).append('：').append(StrUtil.nullToEmpty(v)).append('\n');
    }

    /** 加载当前仍可作为图片引用的名称白名单，整批一次查询，避免逐镜访问数据库。
     *  可引用域=项目+用户（不按集过滤），与出图解析器口径一致：
     *  项目级角色图（episode_id=0）/ 跨集复用资产图按集过滤会漏出白名单。 */
    private List<String> loadReferenceableAssetNames(Long projectId, Long userId)
    {
        if (Objects.isNull(projectId) || Objects.isNull(userId))
        {
            return new ArrayList<>();
        }
        List<AidRolePropSceneFormImage> images = rolePropSceneFormImageService.list(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getName, AidRolePropSceneFormImage::getImageUrl,
                                AidRolePropSceneFormImage::getSortOrder)
                        .eq(AidRolePropSceneFormImage::getProjectId, projectId)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getIsUse, 1)
                        .eq(AidRolePropSceneFormImage::getIsSplitSource, 0)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidRolePropSceneFormImage::getSortOrder));
        if (CollectionUtil.isEmpty(images))
        {
            return new ArrayList<>();
        }
        return images.stream()
                .filter(image -> StrUtil.isNotBlank(image.getName()) && StrUtil.isNotBlank(image.getImageUrl()))
                .map(image -> StrUtil.trim(image.getName()))
                .distinct()
                .collect(Collectors.toList());
    }

    /** 脚本中的失效引用在喂给视觉导演前剔除；全空时移除字段，按画面描述自由生成。 */
    private void sanitizeReferenceInfoInPlace(Map<String, Object> shot, List<String> whitelist, Long storyboardId)
    {
        if (Objects.isNull(shot) || Objects.isNull(shot.get("引用信息")))
        {
            return;
        }
        String refText = Convert.toStr(shot.get("引用信息"), "");
        if (StrUtil.isBlank(refText))
        {
            shot.remove("引用信息");
            return;
        }
        List<String> effectiveWhitelist = augmentReferenceWhitelistForMedia(refText, whitelist);
        if (CollectionUtil.isEmpty(effectiveWhitelist))
        {
            log.warn("视频提示词输入引用清洗：当前无可用图片，引用信息全部文字降级, storyboardId={}", storyboardId);
            shot.remove("引用信息");
            return;
        }
        ReferenceAssetSanitizer.Result result = ReferenceAssetSanitizer.sanitize(refText, effectiveWhitelist);
        if (result.hasRemoval())
        {
            log.warn("视频提示词输入引用清洗: storyboardId={}, removed={}", storyboardId, result.getRemoved());
        }
        if (StrUtil.isBlank(result.getText()))
        {
            shot.remove("引用信息");
        }
        else
        {
            shot.put("引用信息", result.getText());
        }
    }

    /** 视频和音频引用不是图片槽位，按原分区保留，避免图片白名单清洗误删其它媒体引用。 */
    private List<String> augmentReferenceWhitelistForMedia(String refText, List<String> imageWhitelist)
    {
        List<String> result = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(imageWhitelist))
        {
            result.addAll(imageWhitelist);
        }
        appendBracketNamesInSection(refText, result, "视频");
        appendBracketNamesInSection(refText, result, "音频");
        return result;
    }

    /** 提取引用信息指定分区内的方括号名称。 */
    private void appendBracketNamesInSection(String text, List<String> target, String sectionName)
    {
        if (StrUtil.isBlank(text) || Objects.isNull(target) || StrUtil.isBlank(sectionName))
        {
            return;
        }
        int start = text.indexOf(sectionName + "：");
        if (start < 0) { start = text.indexOf(sectionName + ":"); }
        if (start < 0) { return; }
        int sectionStart = start + sectionName.length() + 1;
        int sectionEnd = text.length();
        for (String label : List.of("场景", "角色", "道具", "视频", "音频"))
        {
            if (Objects.equals(label, sectionName)) { continue; }
            int full = text.indexOf(label + "：", sectionStart);
            int half = text.indexOf(label + ":", sectionStart);
            int next = full >= 0 && half >= 0 ? Math.min(full, half) : Math.max(full, half);
            if (next >= 0) { sectionEnd = Math.min(sectionEnd, next); }
        }
        Matcher matcher = Pattern.compile("\\[([^\\]]+)\\]").matcher(text.substring(sectionStart, sectionEnd));
        while (matcher.find())
        {
            String name = StrUtil.trimToEmpty(matcher.group(1));
            if (StrUtil.isNotBlank(name)) { target.add(name); }
        }
    }

    /** 视觉导演仍杜撰或沿用失效图片名时，落库前剥掉占位壳，仅保留名称语义。 */
    private String sanitizeVideoPromptReferences(String prompt, List<String> whitelist,
                                                  Long taskId, Long storyboardId)
    {
        Set<String> normalizedWhitelist = CollectionUtil.isEmpty(whitelist) ? java.util.Collections.emptySet()
                : whitelist.stream().filter(StrUtil::isNotBlank)
                        .map(StoryboardImageReferenceResolver::normalizeAssetRefName)
                        .collect(Collectors.toSet());
        Matcher matcher = IMAGE_REFERENCE_PATTERN.matcher(prompt);
        StringBuffer rewritten = new StringBuffer();
        List<String> degraded = new ArrayList<>();
        while (matcher.find())
        {
            String name = StrUtil.trimToEmpty(matcher.group(2));
            boolean valid = false;
            for (String candidate : StoryboardImageReferenceResolver.candidateLookupKeys(name))
            {
                if (normalizedWhitelist.contains(candidate))
                {
                    valid = true;
                    break;
                }
            }
            String replacement = valid ? matcher.group() : name;
            if (!valid) { degraded.add(name); }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        if (CollectionUtil.isNotEmpty(degraded))
        {
            log.warn("视频提示词输出引用文字降级: taskId={}, storyboardId={}, degraded={}",
                    taskId, storyboardId, degraded);
        }
        return rewritten.toString();
    }

    /**
     * 专业版「中文镜头组」整段批量入参拼装：供 多参方向-专业版（multiref）与 宫格方向（grid）共用。
     * 上游脚本由专业版编剧产出镜头组结构，{@code script_params} 的 key 与漫剧单镜版不同，
     * 此处按镜头组 10 字段装配：镜头组/剧本内容/画面说明/台词/时空环境/引用信息/镜头模式/运镜等级/时长估算/镜头脚本。
     *
     * @param includeImagePrompt 宫格方向=true：额外拼每个镜头组的「宫格图提示词」(image_prompt，由宫格画师产出)，
     *                           供宫格视觉导演按多宫格分镜图逐格出片；多参方向(pro)=false（pro 不出图）。
     */
    private String buildBatchLlmInputGroup(String styleType, String styleValue, List<AidStoryboard> shots,
                                           boolean includeImagePrompt, List<String> referenceAssetNames)
    {
        StringBuilder out = new StringBuilder(16384);
        out.append("【全局风格】\n");
        out.append("风格名称：").append(StrUtil.nullToEmpty(styleType)).append('\n');
        out.append("风格内容：").append(StrUtil.nullToEmpty(styleValue)).append('\n').append('\n');
        out.append("【分镜脚本表（镜头组）】\n");
        int idx = 1;
        for (AidStoryboard sb : shots)
        {
            Map<String, Object> group = parseScriptParams(sb.getScriptParams());
            sanitizeReferenceInfoInPlace(group, referenceAssetNames, sb.getId());
            out.append("--- 镜头组 ").append(idx++).append(" ---\n");
            appendShotField(out, group, "镜头组");
            appendShotField(out, group, "剧本内容");
            appendShotField(out, group, "画面说明");
            appendShotField(out, group, "台词");
            appendShotField(out, group, "时空环境");
            appendShotField(out, group, "引用信息");
            appendShotField(out, group, "镜头模式");
            appendShotField(out, group, "运镜等级");
            appendShotField(out, group, "时长估算");
            appendShotField(out, group, "镜头脚本");
            if (includeImagePrompt)
            {
                // 宫格图提示词（宫格画师产出的多宫格图 prompt），宫格视觉导演据此对齐"参考分镜图N"
                out.append("宫格图提示词：").append(StrUtil.nullToEmpty(sb.getImagePrompt())).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * 图生方向（漫剧版）整段批量入参拼装：
     * 【全局风格】+【角色设定(本剧集角色形态：形态名/性别/形态专属特征)】+
     * 【分镜脚本表(每镜：画面描述/台词/动作状态/镜头运动/引用信息 + 分镜画面生图提示词)】。
     * 漫剧版需据「分镜画面生图提示词」提取形态名，再到「角色设定」匹配性别/服装/配饰做身份绑定，
     * 故图生方向比多参方向额外拼入 image_prompt 与角色形态设定。镜头顺序＝传入顺序（已按 sort_order）。
     */
    private String buildBatchLlmInputImage(String styleType, String styleValue, List<AidStoryboard> shots,
                                           Long projectId, Long episodeId, Long userId,
                                           List<String> referenceAssetNames)
    {
        StringBuilder out = new StringBuilder(16384);
        // 全局风格
        out.append("【全局风格】\n");
        out.append("风格名称：").append(StrUtil.nullToEmpty(styleType)).append('\n');
        out.append("风格内容：").append(StrUtil.nullToEmpty(styleValue)).append('\n').append('\n');
        // 角色设定（形态名 + 性别 + 形态专属特征），供漫剧版身份绑定
        out.append(buildRoleSettingsSection(projectId, episodeId, userId));
        // 分镜脚本表 + 分镜画面生图提示词
        out.append("【分镜脚本表】\n");
        int idx = 1;
        for (AidStoryboard sb : shots)
        {
            Map<String, Object> shot = parseScriptParams(sb.getScriptParams());
            sanitizeReferenceInfoInPlace(shot, referenceAssetNames, sb.getId());
            out.append("--- 镜头 ").append(idx++).append(" ---\n");
            appendShotField(out, shot, "镜号");
            appendShotField(out, shot, "画面描述");
            appendShotField(out, shot, "台词");
            appendShotField(out, shot, "动作状态");
            appendShotField(out, shot, "镜头运动");
            appendShotField(out, shot, "引用信息");
            // 分镜画面生图提示词（漫剧版据此提取形态名）
            out.append("分镜画面生图提示词：").append(StrUtil.nullToEmpty(sb.getImagePrompt())).append('\n');
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * 组装【角色设定】段：项目级 character 资产的每个形态（形态名 / 性别 / 变更原因 / 形态专属特征 promptText）。
     * 剧集角色主资产项目内唯一（episodeId=0），按项目维度查询覆盖全局角色与历史按集角色。
     * 仅读必要列；无角色资产时返回提示行（不阻断，LLM 退化为无身份绑定）。
     */
    private String buildRoleSettingsSection(Long projectId, Long episodeId, Long userId)
    {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("【角色设定】\n");
        List<AidRolePropScene> roles = rolePropSceneService.list(
                Wrappers.<AidRolePropScene>lambdaQuery()
                        .select(AidRolePropScene::getId, AidRolePropScene::getName, AidRolePropScene::getGender,
                                AidRolePropScene::getAssetType, AidRolePropScene::getDelFlag)
                        .eq(AidRolePropScene::getProjectId, projectId)
                        .eq(AidRolePropScene::getUserId, userId)
                        .eq(AidRolePropScene::getAssetType, "character")
                        .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL));
        if (CollectionUtil.isEmpty(roles))
        {
            sb.append("（暂无角色设定）\n\n");
            return sb.toString();
        }
        for (AidRolePropScene role : roles)
        {
            String gender = StrUtil.blankToDefault(role.getGender(), "未知");
            sb.append("- 角色：").append(StrUtil.nullToEmpty(role.getName()))
              .append("（性别：").append(gender).append("）\n");
            // 该角色全部形态
            List<AidRolePropSceneForm> forms = rolePropSceneFormService.list(
                    Wrappers.<AidRolePropSceneForm>lambdaQuery()
                            .select(AidRolePropSceneForm::getId, AidRolePropSceneForm::getName,
                                    AidRolePropSceneForm::getChangeReason, AidRolePropSceneForm::getPromptText,
                                    AidRolePropSceneForm::getDelFlag)
                            .eq(AidRolePropSceneForm::getAssetId, role.getId())
                            .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL));
            if (CollectionUtil.isEmpty(forms))
            {
                continue;
            }
            for (AidRolePropSceneForm form : forms)
            {
                sb.append("  · 形态：").append(StrUtil.nullToEmpty(form.getName()));
                if (StrUtil.isNotBlank(form.getChangeReason()))
                {
                    sb.append("（").append(form.getChangeReason()).append("）");
                }
                sb.append("｜特征：").append(StrUtil.nullToEmpty(form.getPromptText())).append('\n');
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * 解析视觉导演 LLM 输出的 JSON 数组（[{"prompt":..,"duration":..}, ...]），按顺序返回元素节点。
     * 剥 markdown 代码块包装；解析失败返回空列表（调用方按"输出格式异常"逐镜标失败）。
     */
    private List<JsonNode> parseLlmOutputArray(String llmRaw)
    {
        List<JsonNode> result = new ArrayList<>();
        String text = StrUtil.trimToEmpty(llmRaw);
        if (text.startsWith("```"))
        {
            int firstNl = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl)
            {
                text = text.substring(firstNl + 1, lastFence).trim();
            }
        }
        if (StrUtil.isBlank(text)) { return result; }
        try
        {
            JsonNode node = OBJECT_MAPPER.readTree(text);
            if (node.isArray())
            {
                for (JsonNode e : node) { result.add(e); }
            }
            else if (node.isObject())
            {
                result.add(node);
            }
        }
        catch (Exception e)
        {
            log.error("视频提示词 LLM 输出 JSON 数组解析失败: head={}, err={}", StrUtil.sub(text, 0, 200), e.getMessage());
        }
        return result;
    }

    private void sendMqMessage(Long taskId, Long projectId, Long episodeId, Long userId, String modelCode)
    {
        // 双模式派发统一收口：MQ 开走 MQ；MQ 关走本地线程（共用同一执行体 + 终态编排）
        boolean enqueued = dualModeTaskDispatcher.dispatch(taskId, projectId, episodeId, userId, modelCode,
                TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH,
                () -> batchTaskLocalOrchestrator.run(taskId, userId, LOCAL_SPEC,
                        () -> doStoryboardVideoPromptBatch(taskId, userId),
                        () -> assetExtractService.releaseBatchFormLocks(taskId, TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH)));
        if (!enqueued)
        {
            // 入队失败必须上抛：由提交 / 续生链路统一回滚冻结款与任务状态，杜绝冻结款 / 任务状态悬挂
            log.error("视频提示词批量入队失败: taskId={}", taskId);
            throw new ServiceException("提交失败，请重试");
        }
    }
}
