package com.aid.rps.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidConfig;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.domain.AidRoleVoiceBinding;
import com.aid.aid.domain.AidScenePlot;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.domain.AidStoryboardBatch;
import com.aid.aid.domain.AidStoryboardShotGroupPlan;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidConfigService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidGenRecordService;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidRoleVoiceBindingService;
import com.aid.aid.service.IAidScenePlotService;
import com.aid.aid.service.IAidStoryboardBatchService;
import com.aid.aid.service.IAidStoryboardShotGroupPlanService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.enums.BillingConstants;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.BillingAmountCalculator;
import com.aid.billing.service.BillingPriceMultiplierService;
import com.aid.billing.service.BillingRecordMetadataService;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.utils.DateUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.enums.StoryboardShotDensityEnum;
import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.dto.ExtractTaskMessage;
import com.aid.rps.helper.AssetExtractHelper;
import com.aid.rps.resolver.ReferenceAssetSanitizer;
import com.aid.rps.helper.ProjectGenerateLockGuard;
import com.aid.rps.helper.StoryboardScriptBatchPlanner;
import com.aid.rps.helper.StoryboardScriptBatchPlanner.BatchPlanItem;
import com.aid.rps.helper.StoryboardShotGroupPlanParser;
import com.aid.rps.helper.StoryboardShotGroupSplitPromptBuilder;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.service.IExtractBillingService;
import com.aid.rps.service.IStoryboardScriptService;
import com.aid.rps.sse.AssetExtractSseManager;
import com.aid.service.IAiModelConfigService;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import com.aid.common.exception.ServiceException;

/**
 * 批量分镜脚本生成 Service 实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class StoryboardScriptServiceImpl implements IStoryboardScriptService
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String DEL_FLAG_DELETED = "1";
    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_TYPE_STORYBOARD_SCRIPT_BATCH = "storyboard_script_batch";
    private static final String PROMPT_NAME_STORYBOARD_SCRIPT = "aid_storyboard_script_extractor";

    /** 专业版/宫格分镜编剧智能体编码（pro / auto_grid 创作模式命中）：产出中文「镜头组」结构，走独立解析器 */
    private static final String AGENT_CODE_STORYBOARD_WRITER = "aid_storyboard_writer";

    private static final String ASSET_TYPE_SCENE = "scene";
    private static final String ASSET_TYPE_CHARACTER = "character";
    private static final String ASSET_TYPE_PROP = "prop";
    private static final String MODEL_TYPE_TEXT = "text";
    private static final Integer GEN_STATUS_SUCCESS = 1;
    private static final String GEN_TYPE_I2V = "i2v";
    private static final String GEN_TYPE_MULTI = "multi";
    private static final String GEN_TYPE_EDGE = "edge";
    private static final String GEN_TYPE_COMPOSE = "compose";
    private static final String GEN_TYPE_UPLOAD_VIDEO = "upload_video";
    private static final String VOICE_BINDING_ENABLED = "0";
    private static final int MAX_MEDIA_REFERENCE_COUNT = 3;
    private static final String MQ_TOPIC = "ASSET_EXTRACT_TOPIC";
    private static final String MQ_TAG = "extract";

    /**
     * 拆分模式合法值（与 {@link StoryboardShotDensityEnum} 保持一致；
     * 此处仅缓存 value 字符串，避免每次校验都 stream 扫枚举）
     */
    private static final Set<String> VALID_MODES;
    /** 默认模式：标准模式 */
    private static final String DEFAULT_MODE = StoryboardShotDensityEnum.defaultMode().getValue();

    static {
        Set<String> v = new HashSet<>();
        for (StoryboardShotDensityEnum e : StoryboardShotDensityEnum.values()) {
            v.add(e.getValue());
        }
        VALID_MODES = Collections.unmodifiableSet(v);
    }

    /** 业务任务类型（写入 aid_media_task.biz_task_type，便于按业务维度做监控） */
    private static final String BIZ_TASK_TYPE_STORYBOARD_SCRIPT = "storyboard_script";

    /** 镜头数下限配置：aid_config 分类 */
    private static final String CONFIG_CATEGORY_STORYBOARD = "storyboard";

    /**
     * 镜头数下限配置：配置名。
     * config_value 为 JSON 对象 {"charsPerShot":300,"standardRatio":1.5,"detailedRatio":2.0,"maxFloor":40}：
     * charsPerShot = 精简模式每镜字数（底线密度锚点）；standardRatio / detailedRatio = 标准 / 细拆模式
     * 相对精简模式的上浮比例；maxFloor = 单次调用下限封顶。
     */
    private static final String CONFIG_KEY_SHOT_DENSITY_FLOOR = "shot_density_floor";

    /** 镜头数下限兜底默认值：精简模式每镜字数（配置缺失/非法时生效） */
    private static final int DEFAULT_CHARS_PER_SHOT = 300;
    /** 镜头数下限兜底默认值：标准模式上浮比例（与提示词「精简=标准的60%-70%」换算一致，1÷0.65≈1.5） */
    private static final double DEFAULT_STANDARD_RATIO = 1.5;
    /** 镜头数下限兜底默认值：细拆模式上浮比例（与提示词「细拆=标准的130%-150%」换算一致，1.3÷0.65≈2.0） */
    private static final double DEFAULT_DETAILED_RATIO = 2.0;
    /** 镜头数下限兜底默认值：单次调用封顶（防超长片段算出过大下限导致 LLM 输出截断） */
    private static final int DEFAULT_MAX_FLOOR = 40;

    /** 轻量版分镜编剧智能体编码 */
    private static final String AGENT_CODE_STORYBOARD_SIMPLE = "aid_storyboard_script_extractor_simple";

    /**
     * 注入镜头数下限的智能体白名单：仅标准版 / 轻量版。
     * 专业版（writer）靠镜头组拆分的全文覆盖规则保底、解说版提示词自带「剧本内容完整拆分且无重复」规则，
     * 均无欠拆丢内容风险，不注入，保持既有行为零漂移。
     */
    private static final Set<String> SHOT_FLOOR_AGENT_CODES = Set.of(
            PROMPT_NAME_STORYBOARD_SCRIPT, AGENT_CODE_STORYBOARD_SIMPLE);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 单次批量生成的分镜数硬上限，防止 OOM */
    private static final int MAX_BATCH_SHOTS = 5000;
    /** 镜头组拆分 LLM 格式纠偏重试次数 */
    private static final int SHOT_GROUP_SPLIT_RETRY_COUNT = 1;
    /** 镜头组拆分纠偏时携带的上轮输出最大长度 */
    private static final int SHOT_GROUP_SPLIT_RETRY_OUTPUT_LIMIT = 3000;
    /** 分镜批次状态枚举 */
    private static final String BATCH_STATUS_PENDING = "PENDING";
    private static final String BATCH_STATUS_PROCESSING = "PROCESSING";
    private static final String BATCH_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String BATCH_STATUS_FAILED = "FAILED";
    private static final String BATCH_STATUS_CANCELLED = "CANCELLED";

    /** 分镜批次计费状态枚举 */
    private static final String BILLING_STATUS_FROZEN = "FROZEN";
    private static final String BILLING_STATUS_SETTLED = "SETTLED";
    private static final String BILLING_STATUS_REFUNDED = "REFUNDED";

    /** 镜头组拆分计划状态枚举 */
    private static final String PLAN_STATUS_PENDING = "PENDING";
    private static final String PLAN_STATUS_PROCESSING = "PROCESSING";
    private static final String PLAN_STATUS_SUCCESS = "SUCCESS";
    private static final String PLAN_STATUS_FAILED = "FAILED";
    private static final String PLAN_STATUS_CANCELLED = "CANCELLED";

    /** 专业版/宫格 writer 输出 content 必须包含的 10 个固定字段名（顺序固定） */
    private static final List<String> REQUIRED_WRITER_FIELDS = List.of(
            "镜头组", "剧本内容", "画面说明", "台词", "时空环境",
            "引用信息", "镜头模式", "运镜等级", "时长估算", "镜头脚本");

    /** 父任务终态：PARTIAL_FAILED（部分批次成功 + 至少一批失败导致后续批次中断） */
    private static final String TASK_STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
    private static final String TASK_STATUS_CANCELLED = "CANCELLED";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";

    /** 业务类型：创作（与 StoryboardWorkbenchServiceImpl 保持一致） */
    private static final String BIZ_TYPE_CREATE = "create";

    /** 续生窗口（小时）：超出此窗口的 PARTIAL_FAILED 任务不允许续生 */
    private static final long RESUME_WINDOW_HOURS = 24L;
    /**
     * 场景拆分子图方位后缀，顺序固定：主视 / 反打 / 左立面 / 右立面，
     * 与 {@link com.aid.rps.service.impl.RpsFormImageBusinessServiceImpl#SPLIT_POSITION_LABELS}
     * 严格对齐，不要单独维护。
     */
    private static final List<String> KNOWN_DIRECTION_LABELS =
            List.of("主视", "反打", "左立面", "右立面");

    /** 角色设定卡 name 后缀（与 {@code AssetExtractServiceImpl.persistCardFormImage} 落库口径一致） */
    private static final String CHARACTER_CARD_NAME_SUFFIX = "_角色设定";

    /** 角色设定卡的来源类型（区别于 ai_auto 白底主图 / upload 上传图） */
    private static final String FORM_IMAGE_SOURCE_TYPE_BUILDER = "ai_builder";

    /** 拆分产物 / 拆分源图标记字段值 */
    private static final int IS_SPLIT_FLAG_YES = 1;
    private static final int IS_SPLIT_FLAG_NO = 0;

    /** form_image.is_use=1 表示用户主动设为引用 */
    private static final int IS_USE_YES = 1;

    /**
     * 未拆分整张场景图引用说明。
     */
    private static final String UNSPLIT_SCENE_USAGE_NOTE =
            "（整图，2×2 四宫格场景图。引用时请在画面描述里显式点明取哪个方位，并按以下规则使用；"
            + "如所选方位的画面与本镜画面描述里的具体陈设冲突，以画面描述为准）\n"
            + "    - 左上「主视」（图像左上 1/4 区域，横向 0%~50% × 纵向 0%~50%）："
            + "站在场景中心朝正前方平视所见——正面墙体、正对中心点的主要家具与陈设、"
            + "正面方向的门窗或纵深通道。取用要求：本镜背景按该 1/4 区域的空间结构、家具位置、墙面材质、光线方向、整体色调还原。\n"
            + "    - 右上「反打」（图像右上 1/4 区域，横向 50%~100% × 纵向 0%~50%）："
            + "站在场景中心转身 180° 朝正后方平视所见——背后的墙体、回头看到的家具与陈设、可能的进入口或出入门。"
            + "取用要求同上。\n"
            + "    - 左下「左立面」（图像左下 1/4 区域，横向 0%~50% × 纵向 50%~100%）："
            + "站在场景中心向左转 90° 平视所见——左侧墙体、靠左侧布置的家具与陈设、左侧的窗 / 柜 / 通道。"
            + "取用要求同上。\n"
            + "    - 右下「右立面」（图像右下 1/4 区域，横向 50%~100% × 纵向 50%~100%）："
            + "站在场景中心向右转 90° 平视所见——右侧墙体、靠右侧布置的家具与陈设、右侧的窗 / 柜 / 通道。"
            + "取用要求同上。";

    /** 字典日志中文本截断长度，避免长 prompt 刷屏 */
    private static final int VOCAB_LOG_PREVIEW_LIMIT = 1200;
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

    /**
     * 形态图片实例 Service：分镜编剧字典加载阶段需要按"is_use=1 + 拆分子图（is_split_child=1）/ 设定卡（name 后缀 _角色设定）"
     * 维度精确筛选可引用的 form_image，再喂给 LLM。
     */
    @Autowired
    private IAidRolePropSceneFormImageService rpsFormImageService;

    @Autowired
    private IAidStoryboardService storyboardService;

    /** 剧情节拍 Service，用于 batchGenerateStoryboardScript 加载 plot 列表 */
    @Autowired
    private IAidScenePlotService scenePlotService;

    /** 分镜批次表 Service，承载批次粒度执行 / 计费 / 续生状态 */
    @Autowired
    private IAidStoryboardBatchService storyboardBatchService;

    /**
     * 批次切片器：一个场次（plot）= 一批，按全局 scene_code 时序排序。
     */
    @Autowired
    private StoryboardScriptBatchPlanner batchPlanner;

    /** 镜头组拆分计划 Service */
    @Autowired
    private IAidStoryboardShotGroupPlanService shotGroupPlanService;

    /** 角色音色绑定 Service */
    @Autowired
    private IAidRoleVoiceBindingService roleVoiceBindingService;

    /** 生成记录 Service（读取可引用视频资产） */
    @Autowired
    private IAidGenRecordService aidGenRecordService;

    /** 镜头组拆分提示词构建器（系统提示词为代码常量） */
    @Autowired
    private StoryboardShotGroupSplitPromptBuilder shotGroupSplitPromptBuilder;

    /** 镜头组拆分计划解析器 */
    @Autowired
    private StoryboardShotGroupPlanParser shotGroupPlanParser;

    /** 项目级配置统一解析器（3 级兜底：用户传 → 项目配置 → aid_config 默认） */
    @Autowired
    private com.aid.projectgenconfig.service.IProjectGenConfigResolver projectGenConfigResolver;

    @Autowired
    private AssetExtractHelper helper;

    /** 项目/剧集级锁的安全获取器（含僵尸锁自愈，与 AssetExtractServiceImpl 提取锁同一机制） */
    @Autowired
    private ProjectGenerateLockGuard projectLockGuard;

    @Autowired
    private AssetExtractSseManager sseManager;

    @Autowired
    private IExtractBillingService extractBillingService;

    @Autowired
    private BillingAmountCalculator billingAmountCalculator;

    @Autowired
    private BillingPriceMultiplierService billingPriceMultiplierService;

    @Autowired
    private IAiModelConfigService aiModelConfigService;

    /** 账户操作 Service，用于续生场景独立冻结/退款（不复用任务级 prepareBilling 的 CAS 抢占） */
    @Autowired
    private IAccountUpdateService accountUpdateService;

    @Autowired
    private BillingRecordMetadataService billingRecordMetadataService;

    @Autowired
    private IAssetExtractService assetExtractService;

    /** 双模式派发路由器（MQ/本地切换唯一收口） */
    @Autowired
    private com.aid.rps.queue.DualModeTaskDispatcher dualModeTaskDispatcher;

    /** 本地派发终态编排器（与 MQ Consumer 共用同一套终态语义） */
    @Autowired
    private com.aid.rps.queue.BatchTaskLocalOrchestrator batchTaskLocalOrchestrator;

    /** 本地编排规格：分镜脚本 */
    private static final com.aid.rps.queue.BatchTaskLocalOrchestrator.Spec LOCAL_SPEC =
            new com.aid.rps.queue.BatchTaskLocalOrchestrator.Spec(
                    "storyboard_script", "初始化分镜脚本批量生成...",
                    "剩余场景已取消", "部分场景生成失败，可续生",
                    TASK_TYPE_STORYBOARD_SCRIPT_BATCH);

    /** 编程式事务模板：用于「逐批分镜落库 + 批次置 SUCCEEDED」同事务原子化（@Transactional 同类调用会被 AOP 代理绕过） */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 配置服务：读取镜头数下限锚点配置（storyboard / shot_density_floor） */
    @Autowired
    private IAidConfigService aidConfigService;

    @Override
    public AssetExtractTaskVO batchGenerateStoryboardScript(Long projectId, Long episodeId, Long userId,
                                                            List<Long> requestSceneIds,
                                                            String agentCode, String modelCode, String mode,
                                                            Boolean overwrite)
    {
        if (projectId == null || episodeId == null)
        {
            throw new ServiceException("参数错误");
        }

        // 镜头密度（mode）：不传 → 默认"标准模式"；传了非法值 → 拒绝（避免静默降级让用户错以为按自己选的镜头密度跑）
        if (StrUtil.isBlank(mode))
        {
            mode = DEFAULT_MODE;
        }
        else if (!VALID_MODES.contains(mode))
        {
            log.warn("分镜脚本拒绝：镜头密度非法, projectId={}, episodeId={}, userId={}, mode={}, valid={}",
                    projectId, episodeId, userId, mode, VALID_MODES);
            throw new ServiceException("镜头密度有误");
        }
        boolean overwriteFlag = Boolean.TRUE.equals(overwrite);

        AidComicProject project = projectService.selectAidComicProjectById(projectId);
        if (Objects.isNull(project) || !Objects.equals(userId, project.getUserId()))
        {
            throw new ServiceException("项目不存在");
        }

        // 2&3. 统一解析：项目级配置 → aid_config 兜底（3 级链路）。
        //      解析器内部完成：智能体存在 + status=1 + biz_category=main_storyboard_script 校验，
        //      模型存在 + model_type=text + 在该 funcCode 模型池内校验。
        //      用户未传 agentCode/modelCode 时由项目配置/aid_config 默认补齐。
        // ★ 分镜脚本受 创作模式 × 剧本类型 × 策略 影响：默认智能体+模型与可选池由 aid_gen_agent_pool 矩阵解析。
        //   解析器内部按 episodeId 取剧集创作模式（电影取项目默认），并校验最终智能体在可选池内（防越权）。
        com.aid.projectgenconfig.service.ResolvedSceneConfig resolved =
                projectGenConfigResolver.resolve(projectId, episodeId, userId,
                        com.aid.projectgenconfig.enums.ProjectGenConfigScene.STORYBOARD_SCRIPT,
                        agentCode, modelCode, null, null);
        agentCode = resolved.getAgentCode();
        String resolvedModelCode = resolved.getModelCode();

        List<AidRolePropScene> sceneList;
        // 手动场景必须已有有效提示词。
        // 形态按项目级查询：跨集复用后主资产/形态可能归属其他集（剧集角色/复用场景为项目级）
        java.util.Set<Long> assetIdsWithValidForm = new java.util.HashSet<>();
        {
            List<AidRolePropSceneForm> validForms = rpsFormService.list(
                    Wrappers.<AidRolePropSceneForm>lambdaQuery()
                            .select(AidRolePropSceneForm::getAssetId)
                            .eq(AidRolePropSceneForm::getProjectId, projectId)
                            // 强制按当前用户隔离：哪怕项目内有他人手动塞进来的 form 也不会被命中
                            .eq(AidRolePropSceneForm::getUserId, userId)
                            .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL)
                            .isNotNull(AidRolePropSceneForm::getPromptText)
                            .ne(AidRolePropSceneForm::getPromptText, ""));
            for (AidRolePropSceneForm f : validForms)
            {
                if (Objects.nonNull(f.getAssetId()))
                {
                    assetIdsWithValidForm.add(f.getAssetId());
                }
            }
        }

        // 本集场景引用：当前集 aid_scene_plot 关联的场景ID（跨集复用场景的主资产可能归属其他集，
        // 引用关系由 scene_plot 表达，主资产按引用取回）
        java.util.Set<Long> plotSceneIds = loadEpisodePlotSceneIds(projectId, episodeId, userId);

        if (CollectionUtil.isEmpty(requestSceneIds))
        {
            // 未指定场景时按剧情时间线加载全部可用场景：本集直属场景 ∪ 本集剧情引用的复用场景
            sceneList = rpsService.list(
                    Wrappers.<AidRolePropScene>lambdaQuery()
                            .eq(AidRolePropScene::getProjectId, projectId)
                            .eq(AidRolePropScene::getAssetType, ASSET_TYPE_SCENE)
                            .eq(AidRolePropScene::getUserId, userId)
                            .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL)
                            .and(w -> {
                                w.eq(AidRolePropScene::getEpisodeId, episodeId);
                                if (CollectionUtil.isNotEmpty(plotSceneIds))
                                {
                                    w.or().in(AidRolePropScene::getId, plotSceneIds);
                                }
                            })
                            .orderByAsc(AidRolePropScene::getFirstSceneCode)
                            .orderByAsc(AidRolePropScene::getCreateTime)
                            .orderByAsc(AidRolePropScene::getId));
            // 内存过滤手动场景（无有效 form 的）
            sceneList = sceneList.stream()
                    .filter(s -> !"manual".equalsIgnoreCase(s.getCreateSource())
                            || assetIdsWithValidForm.contains(s.getId()))
                    .collect(Collectors.toList());
        }
        else
        {
            // 指定场景必须全部归属当前用户与本项目（跨集复用场景按项目归属校验，引用关系由 scene_plot 表达）
            List<Long> uniqueIds = requestSceneIds.stream().distinct().collect(Collectors.toList());
            sceneList = rpsService.list(
                    Wrappers.<AidRolePropScene>lambdaQuery()
                            .in(AidRolePropScene::getId, uniqueIds)
                            .eq(AidRolePropScene::getProjectId, projectId)
                            .eq(AidRolePropScene::getAssetType, ASSET_TYPE_SCENE)
                            .eq(AidRolePropScene::getUserId, userId)
                            .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL)
                            .orderByAsc(AidRolePropScene::getFirstSceneCode)
                            .orderByAsc(AidRolePropScene::getCreateTime)
                            .orderByAsc(AidRolePropScene::getId));
            if (sceneList.size() != uniqueIds.size())
            {
                log.warn("分镜脚本sceneIds归属校验失败: requested={}, actual={}, projectId={}, episodeId={}, userId={}",
                        uniqueIds.size(), sceneList.size(), projectId, episodeId, userId);
                throw new ServiceException("场景ID列表存在不可用项");
            }
            // 选择性生成：手动场景必须已配置 prompt_text，否则拒绝
            for (AidRolePropScene scene : sceneList)
            {
                if ("manual".equalsIgnoreCase(scene.getCreateSource())
                        && !assetIdsWithValidForm.contains(scene.getId()))
                {
                    log.warn("分镜脚本拒绝：手动场景缺少 prompt_text: sceneId={}, name={}",
                            scene.getId(), scene.getName());
                    throw new ServiceException("场景缺少分镜生成所需的提示词");
                }
            }
        }
        if (CollectionUtil.isEmpty(sceneList))
        {
            throw new ServiceException("请先完成场景提取");
        }

        // 分镜生成必须具备至少一类可引用视觉资产。
        long visualFormCount = rpsFormService.count(
                Wrappers.<AidRolePropSceneForm>lambdaQuery()
                        .eq(AidRolePropSceneForm::getProjectId, projectId)
                        .eq(AidRolePropSceneForm::getEpisodeId, episodeId)
                        .eq(AidRolePropSceneForm::getUserId, userId)
                        .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL)
                        .isNotNull(AidRolePropSceneForm::getPromptText)
                        .ne(AidRolePropSceneForm::getPromptText, ""));
        if (visualFormCount <= 0)
        {
            log.warn("分镜脚本拒绝：视觉资产库为空（角色/道具/场景三者均无可用形态）, projectId={}, episodeId={}, userId={}",
                    projectId, episodeId, userId);
            throw new ServiceException("视觉资产库为空，请先生成");
        }

        // 抢锁失败时走僵尸锁自愈：DB 复核 + 锁年龄检查 + CAS 清理 + 重抢，
        // 避免「DB 无活跃任务但 Redis 锁泄漏」时让用户卡 30 分钟才能重新提交
        long lockTtlSeconds = Math.max(1800L, (long) sceneList.size() * 60L);
        String lockKey = buildLockKey(projectId, episodeId);
        ProjectGenerateLockGuard.AcquireResult lockResult = projectLockGuard.tryAcquireWithStaleClean(
                lockKey, lockTtlSeconds, TASK_TYPE_STORYBOARD_SCRIPT_BATCH, projectId, episodeId);
        if (!lockResult.isAcquired())
        {
            throw new ServiceException("任务处理中");
        }

        // 已存在分镜脚本时必须显式覆盖。
        long existingShotCount = storyboardService.count(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .eq(AidStoryboard::getProjectId, projectId)
                        .eq(AidStoryboard::getEpisodeId, episodeId)
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL));
        if (existingShotCount > 0 && !overwriteFlag)
        {
            // 同步释放走 CAS：仅当锁值仍是本次抢到的 token 才 DEL，避免误删被自愈机制重抢的新锁
            projectLockGuard.releaseIfMatch(lockKey, lockResult.getToken());
            throw new ServiceException("已存在分镜脚本，请确认覆盖");
        }

        AidExtractTask task = null;
        try
        {
            task = new AidExtractTask();
            task.setProjectId(projectId);
            task.setEpisodeId(episodeId);
            task.setUserId(userId);
            task.setTaskType(TASK_TYPE_STORYBOARD_SCRIPT_BATCH);
            task.setModelCode(resolvedModelCode);

            Map<String, Object> inputMap = new LinkedHashMap<>();
            inputMap.put("projectId", projectId);
            inputMap.put("episodeId", episodeId);
            inputMap.put("agentCode", agentCode);
            inputMap.put("modelCode", resolvedModelCode);
            inputMap.put("mode", mode);
            inputMap.put("overwrite", overwriteFlag);
            // selective=true 表示用户显式选了部分场景；false 表示全片/全集生成
            inputMap.put("selective", !CollectionUtil.isEmpty(requestSceneIds));
            List<Long> sceneIds = sceneList.stream()
                    .map(AidRolePropScene::getId).collect(Collectors.toList());
            // 显式存 sceneIds 而非让 consumer 重查，确保即使用户中途新增场景也按提交时的快照执行
            inputMap.put("sceneIds", sceneIds);

            // 保存任务
            boolean useShotGroupSplit = AGENT_CODE_STORYBOARD_WRITER.equals(agentCode);
            task.setInputSnapshot(OBJECT_MAPPER.writeValueAsString(inputMap));
            task.setStatus(TASK_STATUS_PENDING);
            task.setTotalCount(0);
            task.setDelFlag(DEL_FLAG_NORMAL);
            task.setCreateTime(DateUtils.getNowDate());
            task.setCreateBy(String.valueOf(userId));
            extractTaskService.save(task);

            // 专业版/宫格模式：拆分 LLM 调用放到 MQ 执行阶段，提交接口只创建任务并入队，避免同步阻塞
            if (useShotGroupSplit)
            {
                sendMqMessage(task.getId(), projectId, episodeId, userId, resolvedModelCode);
                log.info("分镜脚本批量任务创建(镜头组拆分模式): taskId={}, sceneCount={}",
                        task.getId(), sceneList.size());
                return AssetExtractTaskVO.builder()
                        .taskId(task.getId())
                        .status(TASK_STATUS_PENDING)
                        .totalBatches(0)
                        .totalShots(0)
                        .build();
            }

            // ---- 普通漫剧单镜模式：提交阶段完成拆批 + 建批次 + 冻结计费（保持原有链路不变） ----

            // sceneIds 来自前置筛选后的 sceneList（已应用归属/手动场景 prompt 校验）
            // 防漏字段：仅取 plot 维度切批 / 排序 / 拼接 prompt 必需的列
            List<AidScenePlot> plots = scenePlotService.list(
                    Wrappers.<AidScenePlot>lambdaQuery()
                            .select(AidScenePlot::getId, AidScenePlot::getSceneId,
                                    AidScenePlot::getSceneCode, AidScenePlot::getPlotContent,
                                    AidScenePlot::getCharacters, AidScenePlot::getCharacterActions,
                                    AidScenePlot::getCharacterStates, AidScenePlot::getKeyDialogues,
                                    AidScenePlot::getSceneFunction, AidScenePlot::getTimeOfDay,
                                    AidScenePlot::getEraCoordinate, AidScenePlot::getDateCoordinate,
                                    AidScenePlot::getWeather)
                            .eq(AidScenePlot::getProjectId, projectId)
                            .eq(AidScenePlot::getEpisodeId, episodeId)
                            .eq(AidScenePlot::getUserId, userId)
                            .eq(AidScenePlot::getDelFlag, DEL_FLAG_NORMAL)
                            .in(AidScenePlot::getSceneId, sceneIds));
            if (CollectionUtil.isEmpty(plots))
            {
                log.error("分镜脚本拒绝：该 episode 下无任何 plot 行, projectId={}, episodeId={}", projectId, episodeId);
                throw new ServiceException("暂无剧情可拆");
            }

            Map<Long, AidRolePropScene> sceneIndexById = sceneList.stream()
                    .collect(Collectors.toMap(AidRolePropScene::getId, s -> s, (a, b) -> a));
            plots.sort((p1, p2) -> {
                String pc1 = StrUtil.blankToDefault(p1.getSceneCode(), "999");
                String pc2 = StrUtil.blankToDefault(p2.getSceneCode(), "999");
                return pc1.compareTo(pc2);
            });

            List<BatchPlanItem> batchPlans = batchPlanner.planBatches(plots, sceneIndexById);
            if (CollectionUtil.isEmpty(batchPlans))
            {
                throw new ServiceException("暂无剧情可拆");
            }
            log.info("分镜脚本拆批(普通模式): plotCount={}, batchCount={}", plots.size(), batchPlans.size());

            // 回填实际批次总数
            task.setTotalCount(batchPlans.size());
            extractTaskService.updateById(task);

            AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(resolvedModelCode);
            if (Objects.isNull(modelConfig))
            {
                throw new ServiceException("模型未配置");
            }

            BigDecimal totalFrozen = BigDecimal.ZERO;
            List<AidStoryboardBatch> batchEntities = new ArrayList<>();
            List<Map<String, Object>> itemSnapshots = new ArrayList<>();
            BillingSnapshot settleSnapshot = null;
            boolean settlePricingCompatible = true;
            int estimatedInputTokens = 0;
            int estimatedOutputTokens = 0;
            StoryboardBillingEstimateContext billingContext = loadStoryboardBillingEstimateContext(
                    projectId, episodeId, userId, agentCode, mode, resolvedModelCode, sceneIds);

            for (int i = 0; i < batchPlans.size(); i++)
            {
                BatchPlanItem plan = batchPlans.get(i);
                AidRolePropScene scene = sceneIndexById.get(plan.getSceneId());
                BillingCalcResult itemCalc = estimateBatchCost(plan, scene, modelConfig, billingContext);
                BigDecimal batchFrozen = (Objects.nonNull(itemCalc) && Objects.nonNull(itemCalc.getAmount()))
                        ? itemCalc.getAmount() : BigDecimal.ZERO;
                totalFrozen = totalFrozen.add(batchFrozen);

                AidStoryboardBatch batch = new AidStoryboardBatch();
                batch.setParentTaskId(task.getId());
                batch.setSceneId(plan.getSceneId());
                batch.setBatchIndex(plan.getBatchIndex());
                // shotCodes 沿用历史含义：批内的 sceneCode 列表（一场次一批 = 单元素，如 ["020"]）
                batch.setShotCodes(OBJECT_MAPPER.writeValueAsString(plan.getSceneCodes()));
                batch.setStatus(BATCH_STATUS_PENDING);
                batch.setBillingStatus(BILLING_STATUS_FROZEN);
                batch.setFrozenAmount(batchFrozen);
                batch.setRetryRound(0);
                batch.setShotCount(0);
                batch.setDelFlag(DEL_FLAG_NORMAL);
                batch.setCreateTime(DateUtils.getNowDate());
                batch.setCreateBy(String.valueOf(userId));
                batchEntities.add(batch);

                if (Objects.nonNull(itemCalc) && Objects.nonNull(itemCalc.getSnapshot()))
                {
                    BillingSnapshot itemSnapshot = itemCalc.getSnapshot();
                    settlePricingCompatible = settlePricingCompatible
                            && isSameSettlePricing(settleSnapshot, itemSnapshot);
                    settleSnapshot = chooseSettleSnapshot(settleSnapshot, itemSnapshot);
                    estimatedInputTokens += safeToken(itemSnapshot.getEstimatedInputTokens());
                    estimatedOutputTokens += safeToken(itemSnapshot.getEstimatedOutputTokens());
                    itemSnapshots.add(buildSlimBatchBillingItem(plan, batchFrozen));
                }
            }
            // 批量保存批次记录（数据库层面用 uk_task_scene_batch_round 保证幂等）
            storyboardBatchService.saveBatch(batchEntities);

            if (!settlePricingCompatible)
            {
                log.warn("分镜脚本批量计费快照存在不同计价口径，降级按预冻结结算: taskId={}", task.getId());
            }
            String batchSnapshotJson = buildSlimBatchBillingSnapshotJson(TASK_TYPE_STORYBOARD_SCRIPT_BATCH,
                    totalFrozen, settlePricingCompatible ? settleSnapshot : null,
                    estimatedInputTokens, estimatedOutputTokens,
                    itemSnapshots, null);
            extractBillingService.prepareBilling(task.getId(), userId, totalFrozen, batchSnapshotJson);
            log.info("分镜脚本批量预冻结: taskId={}, sceneCount={}, plotCount={}, batchCount={}, frozen={}",
                    task.getId(), sceneList.size(), plots.size(), batchEntities.size(), totalFrozen);

            sendMqMessage(task.getId(), projectId, episodeId, userId, resolvedModelCode);

            log.info("分镜脚本批量任务创建: taskId={}, sceneCount={}, plotCount={}, batchCount={}",
                    task.getId(), sceneList.size(), plots.size(), batchEntities.size());
            return AssetExtractTaskVO.builder()
                    .taskId(task.getId())
                    .status(TASK_STATUS_PENDING)
                    .totalBatches(batchEntities.size())
                    // 一场次一批后镜头数由提示词在执行阶段决定，提交时未知，置 0（前端以批次数展示进度）
                    .totalShots(0)
                    // 超长场次提醒（非阻断）：最低镜头数被封顶时提示用户拆分场次或改用专业版
                    .warning(buildShotFloorWarning(batchPlans, agentCode, mode))
                    .build();
        }
        catch (Exception e)
        {
            log.error("分镜脚本批量任务创建失败: userId={}", userId, e);
            // 同步释放走 CAS：仅当锁值仍是本次抢到的 token 才 DEL，避免极端尾部场景误删新锁
            projectLockGuard.releaseIfMatch(lockKey, lockResult.getToken());
            // 原始错误文案（用于半态记录与面向用户的错误透传判断）
            String origMsg = StrUtil.nullToEmpty(e.getMessage());
            // 清理半态任务：task 已落库但 prepareBilling/MQ 失败 → 标记 FAILED，避免遗留 PENDING 僵尸
            if (task != null && task.getId() != null)
            {
                try
                {
                    // 提交阶段若已完成预冻结但入队失败，先走统一退款兜底，避免冻结款悬挂。
                    extractBillingService.refundBilling(task.getId(), userId);

                    // 清理半态镜头组拆分计划（拆分成功但后续步骤失败时避免遗留脏数据）
                    LambdaUpdateWrapper<AidStoryboardShotGroupPlan> planClean = Wrappers.lambdaUpdate();
                    planClean.eq(AidStoryboardShotGroupPlan::getTaskId, task.getId());
                    planClean.set(AidStoryboardShotGroupPlan::getDelFlag, DEL_FLAG_DELETED);
                    planClean.set(AidStoryboardShotGroupPlan::getUpdateTime, DateUtils.getNowDate());
                    shotGroupPlanService.update(planClean);

                    LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
                    update.eq(AidExtractTask::getId, task.getId());
                    update.eq(AidExtractTask::getStatus, TASK_STATUS_PENDING);
                    update.set(AidExtractTask::getStatus, TASK_STATUS_FAILED);
                    update.set(AidExtractTask::getErrorMessage, "提交失败: " + StrUtil.sub(origMsg, 0, 80));
                    update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
                    extractTaskService.update(update);
                }
                catch (Exception cleanupEx)
                {
                    log.warn("分镜脚本任务清理半态记录异常: taskId={}", task.getId(), cleanupEx);
                }
            }
            // ★ 透传可恢复的、面向用户的明确错误（避免被 GlobalExceptionHandler 包装成"系统繁忙"）
            //   命中白名单 → 原样透出原始 msg；其它未识别异常走兜底"提交失败"，不暴露堆栈
            if (isUserVisibleSubmitError(origMsg))
            {
                throw new ServiceException(origMsg);
            }
            throw new ServiceException("提交失败，请重试");
        }
    }

    /**
     * 专业版/宫格模式：对每个场次调用镜头组拆分 LLM，生成 ShotGroupPlan 并转换为 BatchPlanItem。
     * 重新生成（overwrite=true）时先硬删除旧计划，再重新拆分。
     * 继续生成在尚未建批次的拆分半态下也会走到这里，并优先复用本任务已有计划。
     */
    private List<BatchPlanItem> splitScenesIntoShotGroups(Long taskId, List<AidScenePlot> plots,
            Map<Long, AidRolePropScene> sceneIndex, String modelCode, Long userId,
            boolean overwrite, Long projectId, Long episodeId, long[] splitChars)
    {
        // 重新生成：硬删除旧镜头组计划，避免旧拆分影响本轮结果。
        if (overwrite)
        {
            boolean removed = shotGroupPlanService.remove(
                    Wrappers.<AidStoryboardShotGroupPlan>lambdaQuery()
                            .eq(AidStoryboardShotGroupPlan::getProjectId, projectId)
                            .eq(AidStoryboardShotGroupPlan::getEpisodeId, episodeId)
                            .eq(AidStoryboardShotGroupPlan::getUserId, userId)
                            .ne(AidStoryboardShotGroupPlan::getTaskId, taskId));
            log.info("镜头组拆分：重新生成已硬删除旧计划, projectId={}, episodeId={}, userId={}, removed={}",
                    projectId, episodeId, userId, removed);
        }

        List<BatchPlanItem> result = new ArrayList<>();
        int globalIndex = 0;
        int splitTotal = 0;
        for (AidScenePlot plot : plots)
        {
            if (Objects.nonNull(plot))
            {
                splitTotal++;
            }
        }
        splitTotal = Math.max(splitTotal, 1);
        int splitDone = 0;

        for (AidScenePlot plot : plots)
        {
            if (Objects.isNull(plot))
            {
                continue;
            }

            // 查询当前场次是否已有拆分计划（同一 task 内幂等）
            List<AidStoryboardShotGroupPlan> existingPlans = shotGroupPlanService.list(
                    Wrappers.<AidStoryboardShotGroupPlan>lambdaQuery()
                            .eq(AidStoryboardShotGroupPlan::getTaskId, taskId)
                            .eq(AidStoryboardShotGroupPlan::getScenePlotId, plot.getId())
                            .eq(AidStoryboardShotGroupPlan::getDelFlag, DEL_FLAG_NORMAL)
                            .orderByAsc(AidStoryboardShotGroupPlan::getGroupIndex));

            List<AidStoryboardShotGroupPlan> plans;
            if (CollectionUtil.isNotEmpty(existingPlans))
            {
                // 复用已有计划（不应走到这里，因为 overwrite 已清理，但兜底保留）
                plans = existingPlans;
                log.info("镜头组拆分：复用已有计划, taskId={}, scenePlotId={}, planCount={}",
                        taskId, plot.getId(), plans.size());
            }
            else
            {
                String splitSystemPrompt = shotGroupSplitPromptBuilder.getSystemPrompt();
                String splitUserContent = shotGroupSplitPromptBuilder.buildUserContent(plot);
                plans = callAndParseShotGroupSplit(taskId, plot, modelCode, userId,
                        splitSystemPrompt, splitUserContent, splitChars);
                if (CollectionUtil.isEmpty(plans))
                {
                    log.error("镜头组拆分失败：解析结果为空, taskId={}, scenePlotId={}, sceneCode={}",
                            taskId, plot.getId(), plot.getSceneCode());
                    throw new ServiceException("镜头组拆分失败");
                }

                // 填充计划元数据并逐条保存，确保自增 ID 已回填后再绑定 batch。
                for (AidStoryboardShotGroupPlan plan : plans)
                {
                    plan.setTaskId(taskId);
                    plan.setProjectId(projectId);
                    plan.setEpisodeId(episodeId);
                    plan.setUserId(userId);
                    plan.setCreateTime(DateUtils.getNowDate());
                    plan.setCreateBy(String.valueOf(userId));
                    boolean saved = shotGroupPlanService.save(plan);
                    if (!saved || Objects.isNull(plan.getId()))
                    {
                        log.error("镜头组拆分计划保存失败: taskId={}, scenePlotId={}, sceneCode={}, groupCode={}",
                                taskId, plot.getId(), plot.getSceneCode(), plan.getGroupCode());
                        throw new ServiceException("拆分保存失败");
                    }
                }
                touchExtractTask(taskId);
                log.info("镜头组拆分完成: taskId={}, scenePlotId={}, sceneCode={}, groupCount={}",
                        taskId, plot.getId(), plot.getSceneCode(), plans.size());
            }

            // 把计划转换为 BatchPlanItem
            for (AidStoryboardShotGroupPlan plan : plans)
            {
                BatchPlanItem item = convertPlanToBatchItem(plan, plot, sceneIndex);
                item.setBatchIndex(globalIndex);
                result.add(item);
                globalIndex++;
            }
            splitDone++;
            int progress = 5 + (int) ((splitDone * 5.0) / splitTotal);
            sseManager.sendStepProgress(taskId, "storyboard_script", progress,
                    "shot_group_split_" + plot.getSceneCode(),
                    "正在拆分镜头组 " + splitDone + "/" + splitTotal,
                    splitDone, splitTotal);
        }

        return result;
    }

    /**
     * 调用并严格解析镜头组拆分；模型偶发漏字段时允许带错误原因纠偏重试一次。
     */
    private List<AidStoryboardShotGroupPlan> callAndParseShotGroupSplit(Long taskId, AidScenePlot plot,
            String modelCode, Long userId, String splitSystemPrompt, String splitUserContent, long[] splitChars)
    {
        RuntimeException lastError = null;
        String lastOutput = null;
        String baseDigest = "镜头组拆分|场次:" + plot.getSceneCode() + "|scenePlotId:" + plot.getId();
        for (int attempt = 0; attempt <= SHOT_GROUP_SPLIT_RETRY_COUNT; attempt++)
        {
            String currentUserContent = attempt == 0
                    ? splitUserContent
                    : buildShotGroupSplitRetryUserContent(splitUserContent, lastError, lastOutput);
            String digest = attempt == 0 ? baseDigest : baseDigest + "|纠偏:" + attempt;
            touchExtractTask(taskId);
            String splitOutput = helper.callLlmRaw(splitSystemPrompt, currentUserContent, modelCode,
                    taskId, userId, digest, BIZ_TASK_TYPE_STORYBOARD_SCRIPT);
            touchExtractTask(taskId);
            lastOutput = splitOutput;
            addSplitChars(splitChars, splitSystemPrompt, currentUserContent, splitOutput, modelCode);
            try
            {
                return shotGroupPlanParser.parse(splitOutput, taskId, plot, userId);
            }
            catch (RuntimeException e)
            {
                lastError = e;
                if (attempt >= SHOT_GROUP_SPLIT_RETRY_COUNT)
                {
                    throw e;
                }
                log.warn("镜头组拆分解析失败，准备纠偏重试: taskId={}, scenePlotId={}, sceneCode={}, attempt={}, err={}, outputHead={}",
                        taskId, plot.getId(), plot.getSceneCode(), attempt + 1,
                        StrUtil.sub(StrUtil.nullToEmpty(e.getMessage()), 0, 80),
                        StrUtil.sub(StrUtil.nullToEmpty(splitOutput), 0, 300));
                sseManager.sendStepProgress(taskId, "storyboard_script", 8,
                        "shot_group_split_retry_" + plot.getSceneCode(),
                        "镜头组格式纠偏重试", 1, 1);
            }
        }
        throw lastError == null ? new ServiceException("拆分格式异常") : lastError;
    }

    /**
     * 组装纠偏输入：不在代码里猜剧情边界，要求模型按原始 plotContent 重新输出完整合法 JSON。
     * 解析器通过 ServiceException.detailMessage 携带第一处差异定位（原文/输出对照片段），
     * 一并下发给模型，让纠偏重试有明确的修改目标，而不是盲目重发。
     */
    private String buildShotGroupSplitRetryUserContent(String originalUserContent, RuntimeException error, String lastOutput)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(originalUserContent).append('\n');
        sb.append("【上一轮输出格式错误】").append(StrUtil.nullToDefault(error == null ? null : error.getMessage(), "拆分格式异常")).append('\n');
        // 附上解析器定位到的具体差异（如标点被改写、原文片段被遗漏），指明改哪里
        String divergenceDetail = (error instanceof ServiceException serviceError)
                ? serviceError.getDetailMessage() : null;
        if (StrUtil.isNotBlank(divergenceDetail))
        {
            sb.append("【具体差异定位】").append(divergenceDetail).append('\n');
        }
        sb.append("【必须纠正】\n");
        sb.append("1. 重新输出完整 JSON object，根节点只能是 shotGroups。\n");
        sb.append("2. 每个 shotGroups 元素必须包含 groupCode、groupIndex、plotContent、characters、keyDialogues。\n");
        sb.append("3. plotContent 字段名必须严格写作 plotContent，且内容必须来自输入 plotContent 的连续原文。\n");
        sb.append("4. plotContent 必须逐字符复制原文：标点符号也是原文的一部分，必须原样保留输入中的全角/半角写法");
        sb.append("（原文是【】就不得写成[]、原文是「」就不得写成\"\"、原文是……就不得写成...），禁止转换、增删或替换任何标点。\n");
        sb.append("5. 禁止只修补局部，必须重新输出可直接解析的完整 JSON。\n");
        sb.append("【上一轮输出节选】\n").append(StrUtil.sub(StrUtil.nullToEmpty(lastOutput), 0, SHOT_GROUP_SPLIT_RETRY_OUTPUT_LIMIT));
        return sb.toString();
    }

    /**
     * 累积拆分 LLM 字符数，纳入父任务结算。
     */
    private void addSplitChars(long[] splitChars, String systemPrompt, String userContent,
                               String output, String modelCode)
    {
        if (Objects.nonNull(splitChars) && splitChars.length >= 2)
        {
            splitChars[0] += helper.estimateLlmInputChars(systemPrompt, userContent, modelCode);
            splitChars[1] += StrUtil.length(output);
        }
    }

    /**
     * 把镜头组拆分计划转换为 BatchPlanItem（复用 merged* 字段承载镜头组级数据）。
     */
    private BatchPlanItem convertPlanToBatchItem(AidStoryboardShotGroupPlan plan, AidScenePlot plot,
            Map<Long, AidRolePropScene> sceneIndex)
    {
        BatchPlanItem item = new BatchPlanItem();
        item.setShotGroupPlanId(plan.getId());
        item.setSceneId(plan.getSceneId());
        AidRolePropScene scene = (sceneIndex == null) ? null : sceneIndex.get(plan.getSceneId());
        if (Objects.nonNull(scene))
        {
            item.setSceneName(scene.getName());
        }
        // 镜头组级字段
        item.setSourceSceneCode(plan.getSourceSceneCode());
        item.setGroupCode(plan.getGroupCode());
        item.setGroupIndex(plan.getGroupIndex());
        item.setGroupPreviousSummary(plan.getPreviousSummary());
        item.setGroupNextSummary(plan.getNextSummary());

        // 复用 merged* 字段承载镜头组级数据（buildLlmInput 直接读取）
        item.setMergedPlotContent(plan.getPlotContent());
        item.setMergedCharacters(parseJsonArrayString(plan.getCharactersJson()));
        item.setMergedKeyDialogues(parseJsonArrayString(plan.getKeyDialoguesJson()));
        // 场次级上下文（来自原始 plot）
        item.setMergedSceneFunction(plot.getSceneFunction());
        item.setMergedTimeOfDay(plot.getTimeOfDay());
        item.setMergedEraCoordinate(plot.getEraCoordinate());
        item.setMergedDateCoordinate(plot.getDateCoordinate());
        item.setMergedWeather(plot.getWeather());

        // 兼容字段
        item.getSceneCodes().add(plan.getSourceSceneCode());
        item.setShotCodes(new ArrayList<>(item.getSceneCodes()));
        item.getPlotIds().add(plot.getId());
        item.setCharCount(StrUtil.length(plan.getPlotContent()));
        return item;
    }

    /** 同步更新镜头组计划状态（shotGroupPlanId 为空时跳过） */
    private void updatePlanStatus(Long shotGroupPlanId, String status, String errorMsg)
    {
        if (Objects.isNull(shotGroupPlanId))
        {
            return;
        }
        try
        {
            LambdaUpdateWrapper<AidStoryboardShotGroupPlan> upd = Wrappers.lambdaUpdate();
            upd.eq(AidStoryboardShotGroupPlan::getId, shotGroupPlanId);
            upd.set(AidStoryboardShotGroupPlan::getStatus, status);
            if (StrUtil.isNotBlank(errorMsg))
            {
                upd.set(AidStoryboardShotGroupPlan::getErrorMsg, errorMsg);
            }
            else
            {
                upd.set(AidStoryboardShotGroupPlan::getErrorMsg, null);
            }
            upd.set(AidStoryboardShotGroupPlan::getUpdateTime, DateUtils.getNowDate());
            shotGroupPlanService.update(upd);
        }
        catch (Exception ex)
        {
            log.warn("updatePlanStatus 异常: planId={}, status={}, err={}", shotGroupPlanId, status, ex.getMessage());
        }
    }

    /** 把 JSON 数组字符串解析为 List<String>，解析失败返回空列表 */
    private static List<String> parseJsonArrayString(String json)
    {
        if (StrUtil.isBlank(json))
        {
            return new ArrayList<>();
        }
        try
        {
            List<String> parsed = OBJECT_MAPPER.readValue(json,
                    OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
            return CollectionUtil.isNotEmpty(parsed) ? parsed : new ArrayList<>();
        }
        catch (Exception e)
        {
            return new ArrayList<>();
        }
    }

    /**
     * 镜头组拆分模式首次执行：拆分场次 → 保存计划 → 建批次 → 冻结计费 → 返回批次列表。
     * 在 MQ 执行阶段调用，避免提交接口同步阻塞。
     */
    private List<AidStoryboardBatch> prepareShotGroupBatches(Long taskId, Long projectId, Long episodeId,
            Long userId, List<Long> sceneIds, Map<Long, AidRolePropScene> sceneIdx,
            String agentCode, String modelCode, String mode, boolean overwrite, long[] splitChars)
    {
        // 加载 plot 并排序
        List<AidScenePlot> plots = scenePlotService.list(
                Wrappers.<AidScenePlot>lambdaQuery()
                        .select(AidScenePlot::getId, AidScenePlot::getSceneId,
                                AidScenePlot::getSceneCode, AidScenePlot::getPlotContent,
                                AidScenePlot::getCharacters, AidScenePlot::getCharacterActions,
                                AidScenePlot::getCharacterStates, AidScenePlot::getKeyDialogues,
                                AidScenePlot::getSceneFunction, AidScenePlot::getTimeOfDay,
                                AidScenePlot::getEraCoordinate, AidScenePlot::getDateCoordinate,
                                AidScenePlot::getWeather)
                        .eq(AidScenePlot::getProjectId, projectId)
                        .eq(AidScenePlot::getEpisodeId, episodeId)
                        .eq(AidScenePlot::getUserId, userId)
                        .eq(AidScenePlot::getDelFlag, DEL_FLAG_NORMAL)
                        .in(AidScenePlot::getSceneId, sceneIds));
        if (CollectionUtil.isEmpty(plots))
        {
            throw new ServiceException("暂无剧情可拆");
        }
        plots.sort((p1, p2) -> {
            String pc1 = StrUtil.blankToDefault(p1.getSceneCode(), "999");
            String pc2 = StrUtil.blankToDefault(p2.getSceneCode(), "999");
            return pc1.compareTo(pc2);
        });

        int splitTotal = Math.max(plots.size(), 1);
        sseManager.sendStepProgress(taskId, "storyboard_script", 5,
                "shot_group_split", "正在拆分镜头组 0/" + splitTotal, 0, splitTotal);

        // 拆分场次 → 保存计划 → 转 BatchPlanItem
        List<BatchPlanItem> batchPlans = splitScenesIntoShotGroups(taskId, plots, sceneIdx,
                modelCode, userId, overwrite, projectId, episodeId, splitChars);
        if (CollectionUtil.isEmpty(batchPlans))
        {
            throw new ServiceException("暂无剧情可拆");
        }
        sseManager.sendStepProgress(taskId, "storyboard_script", 10,
                "shot_group_split_done", "镜头组拆分完成 " + splitTotal + "/" + splitTotal,
                splitTotal, splitTotal);

        // 更新任务批次总数
        AidExtractTask taskUpd = new AidExtractTask();
        taskUpd.setId(taskId);
        taskUpd.setTotalCount(batchPlans.size());
        taskUpd.setUpdateTime(DateUtils.getNowDate());
        extractTaskService.updateById(taskUpd);

        // 估算费用 + 创建批次记录（每个批次绑定 shotGroupPlanId）
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(modelCode);
        if (Objects.isNull(modelConfig))
        {
            throw new ServiceException("模型未配置");
        }
        BigDecimal totalFrozen = BigDecimal.ZERO;
        List<AidStoryboardBatch> batchEntities = new ArrayList<>();
        List<Map<String, Object>> itemSnapshots = new ArrayList<>();
        BillingSnapshot settleSnapshot = null;
        boolean settlePricingCompatible = true;
        int estimatedInputTokens = 0;
        int estimatedOutputTokens = 0;
        Map<String, Object> splitSummary = null;
        StoryboardBillingEstimateContext billingContext = loadStoryboardBillingEstimateContext(
                projectId, episodeId, userId, agentCode, mode, modelCode, sceneIds);

        if (Objects.nonNull(splitChars) && splitChars.length >= 2 && (splitChars[0] > 0 || splitChars[1] > 0))
        {
            BillingCalcResult splitCalc = estimateTextCostFromChars(splitChars[0], splitChars[1], modelConfig);
            BigDecimal splitFrozen = (Objects.nonNull(splitCalc) && Objects.nonNull(splitCalc.getAmount()))
                    ? splitCalc.getAmount() : BigDecimal.ZERO;
            totalFrozen = totalFrozen.add(splitFrozen);
            if (Objects.nonNull(splitCalc) && Objects.nonNull(splitCalc.getSnapshot()))
            {
                BillingSnapshot splitSnapshot = splitCalc.getSnapshot();
                settlePricingCompatible = settlePricingCompatible
                        && isSameSettlePricing(settleSnapshot, splitSnapshot);
                settleSnapshot = chooseSettleSnapshot(settleSnapshot, splitSnapshot);
                estimatedInputTokens += safeToken(splitSnapshot.getEstimatedInputTokens());
                estimatedOutputTokens += safeToken(splitSnapshot.getEstimatedOutputTokens());
                splitSummary = buildSplitBillingSummary(splitChars, splitFrozen);
            }
        }

        for (int i = 0; i < batchPlans.size(); i++)
        {
            BatchPlanItem plan = batchPlans.get(i);
            AidRolePropScene scene = sceneIdx.get(plan.getSceneId());
            BillingCalcResult itemCalc = estimateBatchCost(plan, scene, modelConfig, billingContext);
            BigDecimal batchFrozen = (Objects.nonNull(itemCalc) && Objects.nonNull(itemCalc.getAmount()))
                    ? itemCalc.getAmount() : BigDecimal.ZERO;
            totalFrozen = totalFrozen.add(batchFrozen);

            AidStoryboardBatch batch = new AidStoryboardBatch();
            batch.setParentTaskId(taskId);
            batch.setSceneId(plan.getSceneId());
            batch.setBatchIndex(plan.getBatchIndex());
            try
            {
                batch.setShotCodes(OBJECT_MAPPER.writeValueAsString(plan.getSceneCodes()));
            }
            catch (Exception e)
            {
                log.error("镜头组批次编码序列化失败: taskId={}, batchIndex={}", taskId, plan.getBatchIndex(), e);
                throw new ServiceException("批次数据异常");
            }
            batch.setShotGroupPlanId(plan.getShotGroupPlanId());
            batch.setStatus(BATCH_STATUS_PENDING);
            batch.setBillingStatus(BILLING_STATUS_FROZEN);
            batch.setFrozenAmount(batchFrozen);
            batch.setRetryRound(0);
            batch.setShotCount(0);
            batch.setDelFlag(DEL_FLAG_NORMAL);
            batch.setCreateTime(DateUtils.getNowDate());
            batch.setCreateBy(String.valueOf(userId));
            batchEntities.add(batch);

            if (Objects.nonNull(itemCalc) && Objects.nonNull(itemCalc.getSnapshot()))
            {
                BillingSnapshot itemSnapshot = itemCalc.getSnapshot();
                settlePricingCompatible = settlePricingCompatible
                        && isSameSettlePricing(settleSnapshot, itemSnapshot);
                settleSnapshot = chooseSettleSnapshot(settleSnapshot, itemSnapshot);
                estimatedInputTokens += safeToken(itemSnapshot.getEstimatedInputTokens());
                estimatedOutputTokens += safeToken(itemSnapshot.getEstimatedOutputTokens());
                itemSnapshots.add(buildSlimBatchBillingItem(plan, batchFrozen));
            }
        }
        boolean batchSaved = storyboardBatchService.saveBatch(batchEntities);
        List<AidStoryboardBatch> savedBatches = storyboardBatchService.list(
                Wrappers.<AidStoryboardBatch>lambdaQuery()
                        .eq(AidStoryboardBatch::getParentTaskId, taskId)
                        .eq(AidStoryboardBatch::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidStoryboardBatch::getBatchIndex));
        if (!batchSaved || savedBatches.size() != batchEntities.size())
        {
            log.error("镜头组拆分批次保存异常: taskId={}, expected={}, actual={}, saved={}",
                    taskId, batchEntities.size(), savedBatches.size(), batchSaved);
            throw new ServiceException("批次保存失败");
        }

        // 冻结计费
        String batchSnapshotJson;
        try
        {
            if (!settlePricingCompatible)
            {
                log.warn("镜头组拆分计费快照存在不同计价口径，降级按预冻结结算: taskId={}", taskId);
            }
            batchSnapshotJson = buildSlimBatchBillingSnapshotJson(TASK_TYPE_STORYBOARD_SCRIPT_BATCH,
                    totalFrozen, settlePricingCompatible ? settleSnapshot : null,
                    estimatedInputTokens, estimatedOutputTokens,
                    itemSnapshots, splitSummary);
        }
        catch (Exception e)
        {
            log.error("镜头组计费快照序列化失败: taskId={}", taskId, e);
            throw new ServiceException("计费数据异常");
        }
        extractBillingService.prepareBilling(taskId, userId, totalFrozen, batchSnapshotJson);
        log.info("分镜脚本镜头组拆分模式建批次完成: taskId={}, batchCount={}, frozen={}",
                taskId, savedBatches.size(), totalFrozen);

        return savedBatches;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String doStoryboardScriptBatch(Long taskId, Long userId)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || StrUtil.isBlank(task.getInputSnapshot()))
        {
            throw new ServiceException("任务不存在");
        }

        Map<String, Object> input;
        try
        {
            input = OBJECT_MAPPER.readValue(task.getInputSnapshot(), Map.class);
        }
        catch (Exception e)
        {
            throw new ServiceException("解析失败");
        }

        Long projectId = Convert.toLong(input.get("projectId"));
        Long episodeId = Convert.toLong(input.get("episodeId"));
        if (projectId == null || episodeId == null)
        {
            throw new ServiceException("参数缺失");
        }

        try
        {
        String agentCode = String.valueOf(input.getOrDefault("agentCode", PROMPT_NAME_STORYBOARD_SCRIPT));
        String modelCode = String.valueOf(input.getOrDefault("modelCode", ""));
        String mode = String.valueOf(input.getOrDefault("mode", DEFAULT_MODE));
        if (!VALID_MODES.contains(mode))
        {
            mode = DEFAULT_MODE;
        }
        // 镜头数下限：读取「精简模式每镜字数」锚点配置（每任务只读一次），
        //   标准/轻量智能体按片段字数计算最低镜头数并注入 user message，防止长片段被欠拆丢内容
        ShotDensityFloorConfig shotFloorConfig = loadShotDensityFloorConfig();
        boolean injectShotFloor = SHOT_FLOOR_AGENT_CODES.contains(agentCode);
        boolean overwriteFlag = Boolean.TRUE.equals(input.get("overwrite"));
        boolean selective = Boolean.TRUE.equals(input.get("selective"));
        AidExtractTask currentTask = extractTaskService.selectAidExtractTaskById(taskId);
        boolean hasResumeMarker = Objects.nonNull(currentTask)
                && StrUtil.startWith(currentTask.getRemark(), "RESUME_TRACE:");
        // 续生模式检测：父任务已有 SUCCEEDED 批次时视为续生
        //   续生时 persistAllShots 必须 overwrite=false（否则会删掉首跑已落库的分镜）
        boolean isResumeMode = storyboardBatchService.count(
                Wrappers.<AidStoryboardBatch>lambdaQuery()
                        .eq(AidStoryboardBatch::getParentTaskId, taskId)
                        .eq(AidStoryboardBatch::getStatus, BATCH_STATUS_SUCCEEDED)
                        .eq(AidStoryboardBatch::getDelFlag, DEL_FLAG_NORMAL)) > 0
                || hasResumeMarker;
        boolean effectiveOverwrite = overwriteFlag && !isResumeMode;
        if (isResumeMode)
        {
            log.info("分镜脚本续生模式: taskId={}, 已有 SUCCEEDED 批次，本次落库不删除旧分镜", taskId);
        }
        List<Number> rawIds = (List<Number>) input.getOrDefault("sceneIds", new ArrayList<>());
        List<Long> sceneIds = rawIds.stream().map(Number::longValue).collect(Collectors.toList());
        int total = sceneIds.size();
        if (total == 0)
        {
            throw new ServiceException("场景数据为空");
        }

        // 加载智能体提示词模板：复用统一的「文件优先 → 回源 aid_agent → 回写文件」机制，与角色/场景/道具提取一致
        String promptTemplate = helper.loadPromptByName(agentCode);

        // 参考资产=当前项目全部资产：剧集角色为项目级（episodeId=0），道具跨集复用后主资产可能归属其他集
        List<AidRolePropScene> characterList = rpsService.list(
                Wrappers.<AidRolePropScene>lambdaQuery()
                        .eq(AidRolePropScene::getProjectId, projectId)
                        .eq(AidRolePropScene::getAssetType, ASSET_TYPE_CHARACTER)
                        .eq(AidRolePropScene::getUserId, userId)
                        .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL));
        List<AidRolePropScene> propList = rpsService.list(
                Wrappers.<AidRolePropScene>lambdaQuery()
                        .eq(AidRolePropScene::getProjectId, projectId)
                        .eq(AidRolePropScene::getAssetType, ASSET_TYPE_PROP)
                        .eq(AidRolePropScene::getUserId, userId)
                        .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL));

        // 预加载角色形态
        List<Long> characterIds = characterList.stream()
                .map(AidRolePropScene::getId).collect(Collectors.toList());
        Set<Long> characterIdSet = new LinkedHashSet<>(characterIds);
        List<AidRolePropSceneForm> characterForms = CollectionUtil.isEmpty(characterIds)
                ? new ArrayList<>()
                : rpsFormService.list(Wrappers.<AidRolePropSceneForm>lambdaQuery()
                        .in(AidRolePropSceneForm::getAssetId, characterIds)
                        .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL));

        // 预加载道具形态
        List<Long> propIds = propList.stream()
                .map(AidRolePropScene::getId).collect(Collectors.toList());
        List<AidRolePropSceneForm> propForms = CollectionUtil.isEmpty(propIds)
                ? new ArrayList<>()
                : rpsFormService.list(Wrappers.<AidRolePropSceneForm>lambdaQuery()
                        .in(AidRolePropSceneForm::getAssetId, propIds)
                        .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL));

        // 预建索引：避免 buildLlmInput 内部 stream.filter 的 O(N²) 扫描
        Map<Long, AidRolePropScene> characterIdx = characterList.stream()
                .collect(Collectors.toMap(AidRolePropScene::getId, c -> c, (a, b) -> a));
        Map<Long, AidRolePropScene> propIdx = propList.stream()
                .collect(Collectors.toMap(AidRolePropScene::getId, p -> p, (a, b) -> a));
        Map<Long, AidRoleVoiceBinding> voiceBindingByAssetId = loadEnabledVoiceBindings(
                characterIdSet, episodeId, userId);
        List<String> audioReferenceNames = loadAudioReferenceNames(characterIdSet, characterIdx,
                voiceBindingByAssetId, episodeId, userId);
        List<String> videoReferenceNames = loadVideoReferenceNames(projectId, episodeId, userId);

        // 一次性加载所有目标场景，避免循环内逐个 getById（N+1）
        Map<Long, AidRolePropScene> sceneIdx = new HashMap<>();
        if (!sceneIds.isEmpty())
        {
            List<AidRolePropScene> scenes = rpsService.listByIds(sceneIds);
            for (AidRolePropScene s : scenes)
            {
                if (Objects.equals(DEL_FLAG_NORMAL, s.getDelFlag()))
                {
                    sceneIdx.put(s.getId(), s);
                }
            }
        }

        // 一次性加载所有场景的四视图 form，按 assetId 分组
        Map<Long, List<AidRolePropSceneForm>> sceneFormsByAsset = new HashMap<>();
        if (!sceneIds.isEmpty())
        {
            List<AidRolePropSceneForm> allSceneForms = rpsFormService.list(
                    Wrappers.<AidRolePropSceneForm>lambdaQuery()
                            .in(AidRolePropSceneForm::getAssetId, sceneIds)
                            .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL));
            sceneFormsByAsset = allSceneForms.stream()
                    .collect(Collectors.groupingBy(AidRolePropSceneForm::getAssetId));
        }

        // 从 aid_storyboard_batch 加载该任务的所有批次记录
        //   续生模式：只跑 status=PENDING 的批次（已 SUCCEEDED 跳过；FAILED/CANCELLED 由 resume 接口改回 PENDING）
        //   首跑模式：所有批次都是 PENDING
        //   排序：按 batchIndex（全局场次时序）升序——保证处理顺序＝故事时序，sortOrder 据此递增
        boolean useShotGroupSplit = AGENT_CODE_STORYBOARD_WRITER.equals(agentCode);
        long totalInputChars = 0L;
        long totalOutputChars = 0L;
        List<AidStoryboardBatch> allBatches = storyboardBatchService.list(
                Wrappers.<AidStoryboardBatch>lambdaQuery()
                        .eq(AidStoryboardBatch::getParentTaskId, taskId)
                        .eq(AidStoryboardBatch::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidStoryboardBatch::getBatchIndex));
        if (CollectionUtil.isEmpty(allBatches))
        {
            // 镜头组拆分模式首次执行：无 batch 记录时，先拆分 + 建批次 + 冻结计费
            if (useShotGroupSplit)
            {
                log.info("分镜脚本镜头组拆分模式首次执行: taskId={}, 开始拆分", taskId);
                long[] splitChars = new long[2];
                allBatches = prepareShotGroupBatches(taskId, projectId, episodeId, userId,
                        sceneIds, sceneIdx, agentCode, modelCode, mode, overwriteFlag, splitChars);
                // 拆分 LLM 字符数纳入结算
                totalInputChars += splitChars[0];
                totalOutputChars += splitChars[1];
            }
            else
            {
                // 兼容路径：旧任务没有 batch 记录
                log.warn("分镜脚本任务无 batch 记录（旧任务或异常）: taskId={}", taskId);
            }
        }
        // 待跑批次：status=PENDING 的（首跑全部、续生只剩失败/取消的）
        List<AidStoryboardBatch> pendingBatches = allBatches.stream()
                .filter(b -> BATCH_STATUS_PENDING.equalsIgnoreCase(b.getStatus()))
                .collect(Collectors.toList());

        // 一次性加载该 taskId 涉及的所有 plot，按 batch.shotCodes（sceneCode 列表）重建合并内容
        //   防漏字段：select 仅取 batch 拼装 LLM 入参所需列
        Set<String> allBatchSceneCodes = new HashSet<>();
        for (AidStoryboardBatch b : allBatches)
        {
            try
            {
                List<String> codes = OBJECT_MAPPER.readValue(StrUtil.blankToDefault(b.getShotCodes(), "[]"),
                        OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
                if (CollectionUtil.isNotEmpty(codes))
                {
                    allBatchSceneCodes.addAll(codes);
                }
            }
            catch (Exception ignore) { /* 历史脏数据兜底 */ }
        }
        Map<String, AidScenePlot> plotByCode = new HashMap<>();
        if (CollectionUtil.isNotEmpty(allBatchSceneCodes))
        {
            // 防漏字段：仅取批次合并所需列；后续若新增字段（plot_summary 等）请同步 select 列
            List<AidScenePlot> plotList = scenePlotService.list(
                    Wrappers.<AidScenePlot>lambdaQuery()
                            .select(AidScenePlot::getId, AidScenePlot::getSceneId,
                                    AidScenePlot::getSceneCode, AidScenePlot::getPlotContent,
                                    AidScenePlot::getCharacters, AidScenePlot::getCharacterActions,
                                    AidScenePlot::getCharacterStates, AidScenePlot::getKeyDialogues,
                                    AidScenePlot::getSceneFunction, AidScenePlot::getTimeOfDay,
                                    AidScenePlot::getEraCoordinate, AidScenePlot::getDateCoordinate,
                                    AidScenePlot::getWeather)
                            .eq(AidScenePlot::getProjectId, projectId)
                            .eq(AidScenePlot::getEpisodeId, episodeId)
                            .eq(AidScenePlot::getUserId, userId)
                            .eq(AidScenePlot::getDelFlag, DEL_FLAG_NORMAL)
                            .in(AidScenePlot::getSceneCode, allBatchSceneCodes));
            for (AidScenePlot p : plotList)
            {
                if (StrUtil.isNotBlank(p.getSceneCode()))
                {
                    plotByCode.put(p.getSceneCode(), p);
                }
            }
        }

        // 专业版/宫格模式：加载镜头组拆分计划，按 planId 建立索引（batch.shotGroupPlanId 直接关联）
        Map<Long, AidStoryboardShotGroupPlan> planById = new HashMap<>();
        if (useShotGroupSplit)
        {
            List<AidStoryboardShotGroupPlan> allPlans = shotGroupPlanService.list(
                    Wrappers.<AidStoryboardShotGroupPlan>lambdaQuery()
                            .eq(AidStoryboardShotGroupPlan::getTaskId, taskId)
                            .eq(AidStoryboardShotGroupPlan::getDelFlag, DEL_FLAG_NORMAL));
            for (AidStoryboardShotGroupPlan p : allPlans)
            {
                planById.put(p.getId(), p);
            }
            log.info("分镜脚本镜头组计划加载: taskId={}, planCount={}, batchCount={}",
                    taskId, allPlans.size(), allBatches.size());
        }

        List<Map<String, Object>> successItems = new ArrayList<>();
        List<Map<String, Object>> failedItems = new ArrayList<>();
        int totalShotCount = 0;       // 本轮已落库分镜总数（逐批累加，用于单批上限校验与日志）
        int successCount = 0;
        int failCount = 0;
        boolean hasFailureMidway = false;
        int processedBatchPos = -1;   // 已处理（成功/失败）的批次在 pendingBatches 里的下标

        // ★ 覆盖删除收口为「循环前一次性」执行：仅首跑且用户确认覆盖（effectiveOverwrite=true）时触发，
        //   续生（已有 SUCCEEDED 批次）恒为 false 不会进入。删除放到逐批落库与起始序号计算之前，
        //   保证 globalSortOrder 取的是「删除之后」的库内最大值，避免「删旧后仍按旧最大值编号」造成序号断层。
        if (effectiveOverwrite)
        {
            deleteShotsForOverwrite(projectId, episodeId, userId, sceneIds, selective);
        }

        // sortOrder 起始值：删除（若有）之后取库内现存最大值，逐批 +1 接续。
        //   - 全量覆盖：旧分镜已删 → 最大值 0 → 从 1 起编号；
        //   - 选择性覆盖：仅删本次场景 → 接续其余场景最大值；
        //   - 续生：不删 → 接续已落库分镜最大值，避免与旧分镜撞号。
        Long maxSort = storyboardService.getBaseMapper().selectObjs(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getSortOrder)
                        .eq(AidStoryboard::getProjectId, projectId)
                        .eq(AidStoryboard::getEpisodeId, episodeId)
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByDesc(AidStoryboard::getSortOrder)
                        .last("LIMIT 1"))
                .stream().findFirst().map(o -> ((Number) o).longValue()).orElse(0L);
        int globalSortOrder = maxSort.intValue();
        log.info("分镜脚本落库起始 sortOrder: taskId={}, startSort={}, overwrite={}, selective={}",
                taskId, globalSortOrder, effectiveOverwrite, selective);

        // 可引用资产白名单（整批查一次）：口径与出图前置校验 loadReferenceableAssetNames 完全一致
        //   （is_use=1 + is_split_source=0 + del_flag=0 + image_url 非空）。用于落库前清洗分镜脚本
        //   「引用信息」里 LLM 杜撰的字典外引用，从源头杜绝下游「资产失效」整批失败。
        final List<String> referenceWhitelist = mergeReferenceWhitelists(
                loadReferenceableAssetNames(projectId, episodeId, userId),
                videoReferenceNames, audioReferenceNames);

        final int batchTotal = pendingBatches.size();
        for (int b = 0; b < batchTotal; b++)
        {
            AidStoryboardBatch batch = pendingBatches.get(b);
            touchExtractTask(taskId);

            // ★ 取消检查点
            if (assetExtractService.isTaskCancelled(taskId))
            {
                log.info("分镜脚本批量被取消: taskId={}, done={}/{}", taskId, b, batchTotal);
                // 剩余批次置 CANCELLED 并退款
                for (int j = b; j < batchTotal; j++)
                {
                    AidStoryboardBatch leftover = pendingBatches.get(j);
                    cancelBatchAndRefund(leftover, userId, "用户取消");
                    Map<String, Object> skip = new LinkedHashMap<>();
                    skip.put("sceneId", leftover.getSceneId());
                    skip.put("batchIndex", leftover.getBatchIndex());
                    skip.put("message", "已取消");
                    failedItems.add(skip);
                }
                hasFailureMidway = true;
                break;
            }

            int progress = 10 + (int) ((b * 80.0) / Math.max(batchTotal, 1));
            sseManager.sendStepProgress(taskId, "storyboard_script", progress,
                    "batch_" + batch.getId(),
                    "正在生成分镜脚本批次 " + (b + 1) + "/" + batchTotal,
                    b + 1, batchTotal);

            try
            {
                long batchStart = System.currentTimeMillis();
                AidRolePropScene scene = sceneIdx.get(batch.getSceneId());
                if (Objects.isNull(scene))
                {
                    throw new ServiceException("场景不存在: " + batch.getSceneId());
                }

                // 把批次 status 推进到 PROCESSING
                LambdaUpdateWrapper<AidStoryboardBatch> proc = Wrappers.lambdaUpdate();
                proc.eq(AidStoryboardBatch::getId, batch.getId());
                proc.set(AidStoryboardBatch::getStatus, BATCH_STATUS_PROCESSING);
                proc.set(AidStoryboardBatch::getUpdateTime, DateUtils.getNowDate());
                storyboardBatchService.update(proc);

                // 镜头组拆分模式：batch 必须稳定绑定 plan，缺失即数据异常，不回退普通场次模式
                AidStoryboardShotGroupPlan currentPlan = null;
                if (useShotGroupSplit)
                {
                    if (Objects.isNull(batch.getShotGroupPlanId()))
                    {
                        log.error("镜头组拆分批次缺少 planId: taskId={}, batchId={}, batchIndex={}",
                                taskId, batch.getId(), batch.getBatchIndex());
                        throw new ServiceException("批次数据异常");
                    }
                    currentPlan = planById.get(batch.getShotGroupPlanId());
                    if (Objects.isNull(currentPlan))
                    {
                        log.error("镜头组拆分批次找不到 plan: taskId={}, batchId={}, planId={}",
                                taskId, batch.getId(), batch.getShotGroupPlanId());
                        throw new ServiceException("批次数据异常");
                    }
                }
                updatePlanStatus(batch.getShotGroupPlanId(), PLAN_STATUS_PROCESSING, null);

                // 该场景的四视图形态（已预加载）
                List<AidRolePropSceneForm> sceneForms = sceneFormsByAsset
                        .getOrDefault(batch.getSceneId(), new ArrayList<>());

                // 从 batch.shotCodes（sceneCode 列表）解析 sceneCode（两种模式共用）
                List<String> batchSceneCodes;
                try
                {
                    batchSceneCodes = OBJECT_MAPPER.readValue(StrUtil.blankToDefault(batch.getShotCodes(), "[]"),
                            OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
                }
                catch (Exception parseEx)
                {
                    log.error("解析 batch.shotCodes 失败: taskId={}, batchId={}, shotCodes={}",
                            taskId, batch.getId(), batch.getShotCodes(), parseEx);
                    throw new ServiceException("批次数据异常");
                }

                BatchPlanItem batchPlan;
                if (useShotGroupSplit)
                {
                    // 镜头组拆分模式：从 ShotGroupPlan 重建 BatchPlanItem（使用镜头组级 plotContent）
                    String sceneCode = currentPlan.getSourceSceneCode();
                    AidScenePlot plotForContext = plotByCode.get(sceneCode);
                    if (Objects.isNull(plotForContext))
                    {
                        log.error("镜头组计划对应的 plot 不存在: taskId={}, batchId={}, planId={}, sceneCode={}",
                                taskId, batch.getId(), currentPlan.getId(), sceneCode);
                        throw new ServiceException("剧情数据缺失");
                    }
                    batchPlan = convertPlanToBatchItem(currentPlan, plotForContext, sceneIdx);
                    batchPlan.setSceneName(scene.getName());
                    batchPlan.setBatchIndex(batch.getBatchIndex());
                }
                else
                {
                    // 普通模式：从 batch.shotCodes 重建 BatchPlanItem（一场次一批）
                    batchPlan = new BatchPlanItem();
                    batchPlan.setSceneId(batch.getSceneId());
                    batchPlan.setSceneName(scene.getName());
                    batchPlan.setBatchIndex(batch.getBatchIndex());
                    for (String code : batchSceneCodes)
                    {
                        AidScenePlot p = plotByCode.get(code);
                        if (Objects.nonNull(p))
                        {
                            batchPlan.appendPlot(p);
                        }
                        else
                        {
                            log.warn("batch sceneCode 在 plot 表查不到对应行（数据可能被清理）: taskId={}, batchId={}, sceneCode={}",
                                    taskId, batch.getId(), code);
                        }
                    }
                    if (CollectionUtil.isEmpty(batchPlan.getPlotIds()))
                    {
                        throw new ServiceException("剧情数据缺失");
                    }
                }

                // 镜头数下限：标准/轻量智能体按本批实际下发的剧本片段字数计算（普通模式=整场字数），
                //   与提示词「具体目标镜数由后端在 user message 中注入」的约定咬合；其余智能体不注入
                Integer minShotFloor = injectShotFloor
                        ? calcMinShotFloor(batchPlan.getCharCount(), mode, shotFloorConfig)
                        : null;

                // 构建 LLM 入参（按场次：注入模式名 + 最低镜头数下限，上限由提示词按内容节拍自行决定）
                //   输出格式硬约束随智能体分流：专业版 writer 用 {"content":[...]} 对象约束，
                //   其余标准/轻量/解说智能体用「纯 JSON 数组」约束（与各自提示词输出模板一致）
                String userContent = buildLlmInput(batchPlan, scene, sceneForms, characterIdx,
                        characterForms, propIdx, propForms, mode,
                        episodeId, userId, videoReferenceNames, audioReferenceNames, useShotGroupSplit,
                        minShotFloor);

                String digest = "分镜脚本生成|场景:" + scene.getName() + "|批次:" + batch.getBatchIndex()
                        + "|轮次:" + batch.getRetryRound() + "|sceneId:" + batch.getSceneId();

                long llmStart = System.currentTimeMillis();

                // ★ 调试日志：打印第一个批次的完整入参（只打一次）
                if (b == 0)
                {
                    log.info("========== 分镜脚本LLM入参调试（首个批次） ==========");
                    log.info("[SYSTEM PROMPT] length={}", StrUtil.length(promptTemplate));
                    log.info("[USER CONTENT] length={}\n{}", StrUtil.length(userContent), userContent);
                    log.info("[MODEL] modelCode={}, batchId={}, sceneId={}, batchIndex={}",
                            modelCode, batch.getId(), batch.getSceneId(), batch.getBatchIndex());
                    log.info("========== 调试日志结束 ==========");
                }

                String llmOutput = helper.callLlmRaw(promptTemplate, userContent, modelCode,
                        taskId, userId, digest, BIZ_TASK_TYPE_STORYBOARD_SCRIPT);
                long llmCostMs = System.currentTimeMillis() - llmStart;

                if (StrUtil.isBlank(llmOutput))
                {
                    throw new ServiceException("模型返回为空");
                }

                // ★ 按场次：本批唯一 sceneCode 作为权威归属码（一场次一批 → batchSceneCodes 必为单元素）
                String canonicalSceneCode = (batchSceneCodes.size() == 1) ? batchSceneCodes.get(0) : null;

                // 解析（带 batchId；按场次强制 sourceSceneCode = 本批唯一 sceneCode，避免 LLM 漏填/乱填污染归属与排序）
                // 解析器分叉：专业版/宫格（agent=aid_storyboard_writer）产出中文「镜头组」结构，走独立解析器；
                //   其余标准版走现有扁平英文 key 解析器。两者落库口径一致（同 aid_storyboard、同 script_params 中文 key）。
                List<AidStoryboard> batchShots = AGENT_CODE_STORYBOARD_WRITER.equals(agentCode)
                        ? parseShotGroupsToEntities(llmOutput, projectId, episodeId,
                                userId, batch.getSceneId(), globalSortOrder, batch.getId(), canonicalSceneCode, referenceWhitelist,
                                batchPlan.getGroupCode(), batchPlan.getGroupIndex())
                        : parseShotsToEntities(llmOutput, projectId, episodeId,
                                userId, batch.getSceneId(), globalSortOrder, batch.getId(), canonicalSceneCode, referenceWhitelist);

                // ★ 空分镜判失败：一场次一批后，0 分镜 = 该场次内容丢失，必须失败走退款 + 续跑，
                //   绝不能标记 SUCCEEDED（否则重新出现"某场次 0 分镜"）
                if (CollectionUtil.isEmpty(batchShots))
                {
                    log.error("分镜脚本批次未产出分镜: taskId={}, batchId={}, sceneId={}, sceneCode={}",
                            taskId, batch.getId(), batch.getSceneId(), canonicalSceneCode);
                    throw new ServiceException("请先生成分镜");
                }

                // 下限对账：实际镜头数低于注入下限时打 warn 观测（仅观测不重试，便于评估模型遵循率）
                if (Objects.nonNull(minShotFloor) && batchShots.size() < minShotFloor)
                {
                    log.warn("分镜脚本批次实际镜头数低于注入下限: taskId={}, batchId={}, mode={}, "
                                    + "charCount={}, minShotFloor={}, actualShots={}",
                            taskId, batch.getId(), mode, batchPlan.getCharCount(), minShotFloor, batchShots.size());
                }

                if (totalShotCount + batchShots.size() > MAX_BATCH_SHOTS)
                {
                    throw new ServiceException("分镜数量超过单批上限 " + MAX_BATCH_SHOTS + "，请减少场景数后重试");
                }

                // ★ 原子落库：本批分镜写入 aid_storyboard 与「批次置 SUCCEEDED」放进同一事务，
                //   保证「批次=SUCCEEDED」恒等价于「分镜已持久化」。否则一旦本轮在标记成功之后、统一落库之前
                //   被重启 / 异常打断，会出现「批次 SUCCEEDED 但无对应分镜」，续生只重试 FAILED/CANCELLED 会
                //   跳过它，导致中间批次内容永久丢失（parse 已按 globalSortOrder 连续编号并生成 title）。
                final List<AidStoryboard> shotsToPersist = batchShots;
                final String batchResultJson = llmOutput;
                final AidStoryboardBatch currentBatch = batch;
                final AidStoryboardShotGroupPlan planToUpdate = currentPlan;
                transactionTemplate.executeWithoutResult(status -> {
                    storyboardService.saveBatch(shotsToPersist, 200);
                    LambdaUpdateWrapper<AidStoryboardBatch> doneUpd = Wrappers.lambdaUpdate();
                    doneUpd.eq(AidStoryboardBatch::getId, currentBatch.getId());
                    doneUpd.set(AidStoryboardBatch::getStatus, BATCH_STATUS_SUCCEEDED);
                    doneUpd.set(AidStoryboardBatch::getBillingStatus, BILLING_STATUS_SETTLED);
                    doneUpd.set(AidStoryboardBatch::getSettledAmount, currentBatch.getFrozenAmount());
                    doneUpd.set(AidStoryboardBatch::getResultData, batchResultJson);
                    doneUpd.set(AidStoryboardBatch::getShotCount, shotsToPersist.size());
                    doneUpd.set(AidStoryboardBatch::getUpdateTime, DateUtils.getNowDate());
                    storyboardBatchService.update(doneUpd);

                    // 镜头组拆分模式：回填计划状态为 SUCCESS + 分镜ID
                    if (Objects.nonNull(planToUpdate) && CollectionUtil.isNotEmpty(shotsToPersist))
                    {
                        Long storyboardId = shotsToPersist.get(0).getId();
                        LambdaUpdateWrapper<AidStoryboardShotGroupPlan> planDone = Wrappers.lambdaUpdate();
                        planDone.eq(AidStoryboardShotGroupPlan::getId, planToUpdate.getId());
                        planDone.set(AidStoryboardShotGroupPlan::getStatus, PLAN_STATUS_SUCCESS);
                        planDone.set(AidStoryboardShotGroupPlan::getStoryboardId, storyboardId);
                        planDone.set(AidStoryboardShotGroupPlan::getErrorMsg, null);
                        planDone.set(AidStoryboardShotGroupPlan::getUpdateTime, DateUtils.getNowDate());
                        shotGroupPlanService.update(planDone);
                    }
                });
                touchExtractTask(taskId);

                totalShotCount += batchShots.size();
                globalSortOrder += batchShots.size();

                totalInputChars += helper.estimateLlmInputChars(promptTemplate, userContent, modelCode);
                totalOutputChars += StrUtil.length(llmOutput);

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("batchId", batch.getId());
                item.put("sceneId", batch.getSceneId());
                item.put("sceneName", scene.getName());
                item.put("batchIndex", batch.getBatchIndex());
                item.put("shotsCount", batchShots.size());
                successItems.add(item);
                successCount++;
                processedBatchPos = b;

                long batchCostMs = System.currentTimeMillis() - batchStart;
                log.info("分镜脚本批次完成: taskId={}, batchId={}, sceneId={}, shots={}, llmCostMs={}, totalCostMs={}",
                        taskId, batch.getId(), batch.getSceneId(), batchShots.size(), llmCostMs, batchCostMs);
            }
            catch (Exception e)
            {
                log.error("分镜脚本批次失败: taskId={}, batchId={}, sceneId={}",
                        taskId, batch.getId(), batch.getSceneId(), e);

                // 标记本批 FAILED + 退款
                String errMsg = StrUtil.sub(e.getMessage(), 0, 500);
                failBatchAndRefund(batch, userId, errMsg);
                touchExtractTask(taskId);

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("batchId", batch.getId());
                item.put("sceneId", batch.getSceneId());
                item.put("batchIndex", batch.getBatchIndex());
                item.put("message", StrUtil.sub(errMsg, 0, 80));
                failedItems.add(item);
                failCount++;
                processedBatchPos = b;
                hasFailureMidway = true;

                // ★ 立即中断后续批次
                for (int j = b + 1; j < batchTotal; j++)
                {
                    AidStoryboardBatch leftover = pendingBatches.get(j);
                    cancelBatchAndRefund(leftover, userId, "前序批次失败导致取消");
                    Map<String, Object> skip = new LinkedHashMap<>();
                    skip.put("batchId", leftover.getId());
                    skip.put("sceneId", leftover.getSceneId());
                    skip.put("batchIndex", leftover.getBatchIndex());
                    skip.put("message", "已取消（前序批次失败）");
                    failedItems.add(skip);
                }
                break;
            }
        }

        // 分镜已在循环内逐批原子落库（与「批次置 SUCCEEDED」同事务），此处无需再统一落库。
        //   若本轮零成功，保留用户原数据（首跑覆盖删除已在循环前一次性完成）。
        if (totalShotCount == 0)
        {
            log.info("分镜脚本批量无成功结果，保留用户原数据: taskId={}, fail={}", taskId, failCount);
        }

        // 释放项目级锁
        releaseProjectLockQuietly(projectId, episodeId);

        // 批次粒度计费汇总（加固：失败后立即重试 1 次，都失败写 errorMessage 让前端展示"退款处理中"）
        boolean billingHandled = false;
        Exception lastBillingEx = null;
        for (int billingAttempt = 0; billingAttempt < 2 && !billingHandled; billingAttempt++)
        {
            try
            {
                if (isResumeMode)
                {
                    settleResumeBatches(taskId, userId, pendingBatches, processedBatchPos, hasFailureMidway);
                }
                else if (successCount > 0)
                {
                    Map<String, Object> usageData = new HashMap<>();
                    usageData.put("input_chars_estimate", totalInputChars);
                    usageData.put("input_tokens_estimate",
                            BillingConstants.charsToTokens((int) Math.min(totalInputChars, Integer.MAX_VALUE)));
                    usageData.put("output_chars_estimate", totalOutputChars);
                    usageData.put("output_tokens_estimate",
                            BillingConstants.charsToTokens((int) Math.min(totalOutputChars, Integer.MAX_VALUE)));
                    usageData.put("total_chars_estimate", totalInputChars + totalOutputChars);
                    extractBillingService.settleBilling(taskId, userId, usageData);
                    log.info("分镜脚本批量结算: taskId={}, attempt={}, successBatch={}, failBatch={}",
                            taskId, billingAttempt + 1, successCount, failCount);
                }
                else
                {
                    extractBillingService.refundBilling(taskId, userId);
                    log.info("分镜脚本批量全部失败已退款: taskId={}, attempt={}", taskId, billingAttempt + 1);
                }
                billingHandled = true;
            }
            catch (Exception billingEx)
            {
                lastBillingEx = billingEx;
                log.error("分镜脚本批量计费异常 attempt={}: taskId={}", billingAttempt + 1, taskId, billingEx);
                if (billingAttempt == 0)
                {
                    try { Thread.sleep(500L); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        if (!billingHandled)
        {
            // 计费两次都失败：写 errorMessage 让前端展示"退款处理中"，不抛异常
            // ExtractBillingTask Quartz 定时任务会扫 billing_status=FROZEN/SETTLING/REFUNDING 兜底
            log.error("[BG-ALERT] 分镜脚本计费两次重试均失败，等待 ExtractBillingTask 兜底: taskId={}, lastErr={}",
                    taskId, lastBillingEx == null ? null : lastBillingEx.getMessage());
            try
            {
                LambdaUpdateWrapper<AidExtractTask> alertUpd = Wrappers.lambdaUpdate();
                alertUpd.eq(AidExtractTask::getId, taskId);
                alertUpd.set(AidExtractTask::getErrorMessage, "退款处理中，请稍后查看余额");
                alertUpd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
                extractTaskService.update(alertUpd);
            }
            catch (Exception ignore) { /* 静默兜底，定时任务会修正 */ }
        }

        log.info("分镜脚本批量执行完成: taskId={}, successBatch={}, failBatch={}, totalShots={}, midwayFailure={}",
                taskId, successCount, failCount, totalShotCount, hasFailureMidway);

        // 父任务终态判定：
        //   - 全部批次成功 → 让 Consumer 标记 SUCCEEDED
        //   - 至少一批失败但有成功批 → 标记 PARTIAL_FAILED
        //   - 零成功批次：
        //       · 首跑模式 → 抛失败让 Consumer 标记 FAILED
        //       · 续生模式且首跑有成功批 → 仍标记 PARTIAL_FAILED（保留续生入口）
        if (hasFailureMidway && successCount > 0)
        {
            String reason = "部分批次失败 (" + successCount + " success / " + failCount + " fail)";
            log.warn("分镜脚本批量部分失败: taskId={}, {}", taskId, reason);
            markTaskPartialFailed(taskId, reason);
            return buildBatchResultJson(batchTotal, successCount, failCount, successItems, failedItems);
        }
        if (successCount == 0 && failCount > 0)
        {
            String reason = "全部批次失败 (" + failCount + "/" + batchTotal + ")";
            if (isResumeMode)
            {
                // 续生模式下本轮全部失败但首跑已有 SUCCEEDED 批
                //   不能让任务标记为 FAILED（用户会失去续生入口），仍标记 PARTIAL_FAILED
                log.warn("分镜脚本续生本轮全部失败: taskId={}, {}", taskId, reason);
                markTaskPartialFailed(taskId, reason);
                return buildBatchResultJson(batchTotal, successCount, failCount, successItems, failedItems);
            }
            log.warn("分镜脚本批量全部失败: taskId={}, {}", taskId, reason);
            throw new ServiceException(reason);
        }

        return buildBatchResultJson(batchTotal, successCount, failCount, successItems, failedItems);
        }
        catch (RuntimeException e)
        {
            releaseProjectLockQuietly(projectId, episodeId);
            throw e;
        }
    }

    @Override
    public AssetExtractTaskVO resumeStoryboardScript(Long taskId, Long userId)
    {
        if (Objects.isNull(taskId))
        {
            throw new ServiceException("任务不能为空");
        }

        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || !DEL_FLAG_NORMAL.equals(task.getDelFlag()))
        {
            throw new ServiceException("任务不存在");
        }
        if (!Objects.equals(userId, task.getUserId()))
        {
            log.warn("分镜脚本续生：任务不属于当前用户: taskId={}, taskUserId={}, userId={}",
                    taskId, task.getUserId(), userId);
            throw new ServiceException("任务不存在");
        }
        if (!TASK_TYPE_STORYBOARD_SCRIPT_BATCH.equals(task.getTaskType()))
        {
            throw new ServiceException("任务类型不支持续生");
        }
        if (!TASK_STATUS_PARTIAL_FAILED.equals(task.getStatus())
                && !TASK_STATUS_FAILED.equals(task.getStatus())
                && !TASK_STATUS_CANCELLED.equals(task.getStatus()))
        {
            throw new ServiceException("任务状态不支持续生");
        }

        // 24 小时窗口校验
        if (Objects.nonNull(task.getCreateTime()))
        {
            long ageMs = System.currentTimeMillis() - task.getCreateTime().getTime();
            long ageHours = ageMs / 3600_000L;
            if (ageHours > RESUME_WINDOW_HOURS)
            {
                throw new ServiceException("任务已过期，请重新发起");
            }
        }

        // 续生防重——任务级 Redis 锁，TTL 30 分钟
        //   防止用户连点两次"继续生成"导致两次 freeze + 两条 MQ
        String resumeLockKey = "storyboard:script:resume:lock:" + taskId;
        Boolean resumeLocked = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(resumeLockKey, "1", 30L * 60L, TimeUnit.SECONDS);
        String originalStatus = task.getStatus();
        String originalRemark = task.getRemark();
        if (resumeLocked == null || !resumeLocked)
        {
            throw new ServiceException("任务处理中，请勿重复");
        }

        try
        {
            // 续生开始前要重新获取项目级 Redis 锁
            //   避免续生执行期间用户又点了"批量生成分镜脚本"创建新任务造成 sortOrder 撞号
            //   抢锁失败时同样走僵尸锁自愈，避免项目锁泄漏导致续生永久卡住
            String projectLockKey = buildLockKey(task.getProjectId(), task.getEpisodeId());
            // 锁 TTL 按未完成批次数动态算（每批 60 秒，最少 30 分钟），与首跑保持一致
            // 但获取不到时不允许续生，避免与并发创建冲突
            ProjectGenerateLockGuard.AcquireResult resumeLockResult = projectLockGuard.tryAcquireWithStaleClean(
                    projectLockKey, 30L * 60L, TASK_TYPE_STORYBOARD_SCRIPT_BATCH,
                    task.getProjectId(), task.getEpisodeId());
            if (!resumeLockResult.isAcquired())
            {
                throw new ServiceException("项目任务执行中，请稍候");
            }

            // 取所有未成功的批次（PENDING + FAILED + CANCELLED）。
            // 用户在排队期停止时，批次仍可能保持 PENDING，需要一并恢复。
            List<AidStoryboardBatch> retryBatches = storyboardBatchService.list(
                    Wrappers.<AidStoryboardBatch>lambdaQuery()
                            .eq(AidStoryboardBatch::getParentTaskId, taskId)
                            .in(AidStoryboardBatch::getStatus,
                                    BATCH_STATUS_PENDING, BATCH_STATUS_FAILED, BATCH_STATUS_CANCELLED)
                            .eq(AidStoryboardBatch::getDelFlag, DEL_FLAG_NORMAL));
            if (CollectionUtil.isEmpty(retryBatches))
            {
                long batchCount = storyboardBatchService.count(
                        Wrappers.<AidStoryboardBatch>lambdaQuery()
                                .eq(AidStoryboardBatch::getParentTaskId, taskId)
                                .eq(AidStoryboardBatch::getDelFlag, DEL_FLAG_NORMAL));
                if (batchCount == 0 && isShotGroupSplitTask(task))
                {
                    LambdaUpdateWrapper<AidExtractTask> taskUpd = Wrappers.lambdaUpdate();
                    taskUpd.eq(AidExtractTask::getId, taskId);
                    taskUpd.in(AidExtractTask::getStatus,
                            TASK_STATUS_PARTIAL_FAILED, TASK_STATUS_FAILED, TASK_STATUS_CANCELLED);
                    taskUpd.set(AidExtractTask::getStatus, TASK_STATUS_PENDING);
                    taskUpd.set(AidExtractTask::getErrorMessage, null);
                    taskUpd.set(AidExtractTask::getRemark, null);
                    taskUpd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
                    taskUpd.set(AidExtractTask::getUpdateBy, String.valueOf(userId));
                    boolean parentUpdated = extractTaskService.update(taskUpd);
                    if (!parentUpdated)
                    {
                        projectLockGuard.releaseIfMatch(projectLockKey, resumeLockResult.getToken());
                        throw new ServiceException("状态不支持");
                    }
                    try
                    {
                        sendMqMessage(taskId, task.getProjectId(), task.getEpisodeId(), userId, task.getModelCode());
                    }
                    catch (RuntimeException submitEx)
                    {
                        rollbackStoryboardScriptResumeTask(taskId, originalStatus, originalRemark,
                                "提交失败", userId);
                        projectLockGuard.releaseIfMatch(projectLockKey, resumeLockResult.getToken());
                        log.error("分镜脚本拆分阶段续生提交失败: taskId={}", taskId, submitEx);
                        throw (submitEx instanceof ServiceException)
                                ? submitEx : new ServiceException("续生失败，请重试");
                    }
                    log.info("分镜脚本拆分阶段续生提交: taskId={}", taskId);
                    return AssetExtractTaskVO.builder()
                            .taskId(taskId)
                            .status(TASK_STATUS_PENDING)
                            .totalBatches(0)
                            .build();
                }
                projectLockGuard.releaseIfMatch(projectLockKey, resumeLockResult.getToken());
                throw new ServiceException("无可续生批次");
            }

            // 累加待重试批次冻结金额
            List<StoryboardBatchResumeSnapshot> batchSnapshots = snapshotStoryboardBatches(retryBatches);
            BigDecimal totalRetryFrozen = retryBatches.stream()
                    .map(AidStoryboardBatch::getFrozenAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 不复用 prepareBilling（CAS billing_trace_id IS NULL 必失败）
            //   独立调 accountUpdateService.freeze，traceId 包含 retry_round 保证幂等
            //   计算下一轮 retryRound（取所有待重试批次中最大的 retry_round +1，避免和已有冲突）
            int nextRound = retryBatches.stream()
                    .map(AidStoryboardBatch::getRetryRound)
                    .filter(Objects::nonNull)
                    .max(Integer::compareTo)
                    .orElse(0) + 1;
            String resumeTraceId = "sb_resume_" + taskId + "_r" + nextRound;
            try
            {
                accountUpdateService.freeze(userId, totalRetryFrozen, resumeTraceId,
                        BIZ_TYPE_CREATE, billingRecordMetadataService.buildExtractBizName(task, true),
                        billingRecordMetadataService.resolveExtractModelCodes(task));
            }
            catch (Exception e)
            {
                log.error("分镜脚本续生预冻结失败: taskId={}, retryBatches={}, totalRetryFrozen={}, traceId={}",
                        taskId, retryBatches.size(), totalRetryFrozen, resumeTraceId, e);
                projectLockGuard.releaseIfMatch(projectLockKey, resumeLockResult.getToken());
                throw new ServiceException("续生预冻结失败：" + StrUtil.sub(e.getMessage(), 0, 80));
            }

            // 重置批次状态：retry_round +1, status=PENDING, billing=FROZEN, error_message=null
            // 注意：所有重试批的 retry_round 统一设为 nextRound，便于关联同一笔 freeze 流水
            String resumeMarkerForCompensation = "RESUME_TRACE:" + resumeTraceId + "|FROZEN:" + totalRetryFrozen.toPlainString();
            try
            {
                for (AidStoryboardBatch batch : retryBatches)
                {
                    LambdaUpdateWrapper<AidStoryboardBatch> upd = Wrappers.lambdaUpdate();
                    upd.eq(AidStoryboardBatch::getId, batch.getId());
                    upd.set(AidStoryboardBatch::getStatus, BATCH_STATUS_PENDING);
                    upd.set(AidStoryboardBatch::getBillingStatus, BILLING_STATUS_FROZEN);
                    upd.set(AidStoryboardBatch::getRetryRound, nextRound);
                    upd.set(AidStoryboardBatch::getErrorMessage, null);
                    upd.set(AidStoryboardBatch::getUpdateTime, DateUtils.getNowDate());
                    storyboardBatchService.update(upd);
                    // 同步计划状态为 PENDING
                    updatePlanStatus(batch.getShotGroupPlanId(), PLAN_STATUS_PENDING, null);
                }

                // 把续生 traceId 暂存到任务的 remark 字段，便于 settleResumeBatches 拿到
                // （不动 billing_trace_id，那个是首跑的 traceId）
                String resumeMarker = "RESUME_TRACE:" + resumeTraceId + "|FROZEN:" + totalRetryFrozen.toPlainString();
                // 父任务状态回到 PENDING，随后由统一队列推进到 QUEUED / PROCESSING。
                LambdaUpdateWrapper<AidExtractTask> taskUpd = Wrappers.lambdaUpdate();
                taskUpd.eq(AidExtractTask::getId, taskId);
                taskUpd.in(AidExtractTask::getStatus, TASK_STATUS_PARTIAL_FAILED, TASK_STATUS_FAILED, TASK_STATUS_CANCELLED);
                taskUpd.set(AidExtractTask::getStatus, TASK_STATUS_PENDING);
                taskUpd.set(AidExtractTask::getErrorMessage, null);
                taskUpd.set(AidExtractTask::getRemark, resumeMarker);
                taskUpd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
                boolean parentUpdated = extractTaskService.update(taskUpd);
                if (!parentUpdated)
                {
                    throw new ServiceException("状态不支持");
                }

                // 重新发 MQ 触发执行
                sendMqMessage(taskId, task.getProjectId(), task.getEpisodeId(), userId, task.getModelCode());
            }
            catch (RuntimeException submitEx)
            {
                boolean refunded = refundStoryboardScriptResumeFreeze(userId, totalRetryFrozen, resumeTraceId, taskId);
                if (refunded)
                {
                    restoreStoryboardScriptResumeBatches(batchSnapshots);
                    rollbackStoryboardScriptResumeTask(taskId, originalStatus, originalRemark,
                            "提交失败", userId);
                }
                else
                {
                    rollbackStoryboardScriptResumeTask(taskId, TASK_STATUS_FAILED, resumeMarkerForCompensation,
                            "提交失败", userId);
                }
                projectLockGuard.releaseIfMatch(projectLockKey, resumeLockResult.getToken());
                log.error("分镜脚本续生提交失败: taskId={}, traceId={}",
                        taskId, resumeTraceId, submitEx);
                throw (submitEx instanceof ServiceException)
                        ? submitEx : new ServiceException("续生失败，请重试");
            }

            log.info("分镜脚本续生提交: taskId={}, retryBatchCount={}, totalRetryFrozen={}, retryRound={}, traceId={}",
                    taskId, retryBatches.size(), totalRetryFrozen, nextRound, resumeTraceId);
            return AssetExtractTaskVO.builder()
                    .taskId(taskId)
                    .status(TASK_STATUS_PENDING)
                    .totalBatches(retryBatches.size())
                    .build();
        }
        finally
        {
            // resume 操作锁释放（项目级锁由 doStoryboardScriptBatch 末尾释放）
            redisCache.deleteObject(resumeLockKey);
        }
    }

    private boolean isShotGroupSplitTask(AidExtractTask task)
    {
        if (Objects.isNull(task) || StrUtil.isBlank(task.getInputSnapshot()))
        {
            return false;
        }
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(task.getInputSnapshot());
            JsonNode agentNode = root.get("agentCode");
            String agentCode = Objects.nonNull(agentNode) && !agentNode.isNull()
                    ? agentNode.asText() : PROMPT_NAME_STORYBOARD_SCRIPT;
            return AGENT_CODE_STORYBOARD_WRITER.equals(agentCode);
        }
        catch (Exception e)
        {
            log.warn("分镜脚本续生判断拆分模式失败: taskId={}, err={}", task.getId(), e.getMessage());
            return false;
        }
    }

    /** 分镜脚本续生提交前的批次状态快照，用于 MQ 投递失败时恢复。 */
    private static class StoryboardBatchResumeSnapshot
    {
        Long id;
        Long shotGroupPlanId;
        String status;
        String billingStatus;
        Integer retryRound;
        String errorMessage;
    }

    private List<StoryboardBatchResumeSnapshot> snapshotStoryboardBatches(List<AidStoryboardBatch> batches)
    {
        List<StoryboardBatchResumeSnapshot> snapshots = new ArrayList<>();
        if (CollectionUtil.isEmpty(batches))
        {
            return snapshots;
        }
        for (AidStoryboardBatch batch : batches)
        {
            StoryboardBatchResumeSnapshot snapshot = new StoryboardBatchResumeSnapshot();
            snapshot.id = batch.getId();
            snapshot.shotGroupPlanId = batch.getShotGroupPlanId();
            snapshot.status = batch.getStatus();
            snapshot.billingStatus = batch.getBillingStatus();
            snapshot.retryRound = batch.getRetryRound();
            snapshot.errorMessage = batch.getErrorMessage();
            snapshots.add(snapshot);
        }
        return snapshots;
    }

    private boolean refundStoryboardScriptResumeFreeze(Long userId, BigDecimal frozenAmount, String resumeTraceId, Long taskId)
    {
        if (Objects.isNull(frozenAmount) || frozenAmount.compareTo(BigDecimal.ZERO) <= 0)
        {
            return true;
        }
        try
        {
            accountUpdateService.refund(userId, frozenAmount, resumeTraceId,
                    BIZ_TYPE_CREATE, "分镜脚本续生退款");
            return true;
        }
        catch (Exception e)
        {
            log.error("分镜脚本续生退款失败: taskId={}, traceId={}, amount={}",
                    taskId, resumeTraceId, frozenAmount, e);
            return false;
        }
    }

    private void restoreStoryboardScriptResumeBatches(List<StoryboardBatchResumeSnapshot> snapshots)
    {
        if (CollectionUtil.isEmpty(snapshots))
        {
            return;
        }
        for (StoryboardBatchResumeSnapshot snapshot : snapshots)
        {
            LambdaUpdateWrapper<AidStoryboardBatch> restore = Wrappers.lambdaUpdate();
            restore.eq(AidStoryboardBatch::getId, snapshot.id);
            restore.set(AidStoryboardBatch::getStatus, snapshot.status);
            restore.set(AidStoryboardBatch::getBillingStatus, snapshot.billingStatus);
            restore.set(AidStoryboardBatch::getRetryRound, snapshot.retryRound);
            restore.set(AidStoryboardBatch::getErrorMessage, snapshot.errorMessage);
            restore.set(AidStoryboardBatch::getUpdateTime, DateUtils.getNowDate());
            storyboardBatchService.update(restore);
            updatePlanStatus(snapshot.shotGroupPlanId, mapBatchStatusToPlanStatus(snapshot.status), snapshot.errorMessage);
        }
    }

    private String mapBatchStatusToPlanStatus(String batchStatus)
    {
        if (BATCH_STATUS_FAILED.equalsIgnoreCase(batchStatus))
        {
            return PLAN_STATUS_FAILED;
        }
        if (BATCH_STATUS_CANCELLED.equalsIgnoreCase(batchStatus))
        {
            return PLAN_STATUS_CANCELLED;
        }
        if (BATCH_STATUS_SUCCEEDED.equalsIgnoreCase(batchStatus))
        {
            return PLAN_STATUS_SUCCESS;
        }
        return PLAN_STATUS_PENDING;
    }

    private void rollbackStoryboardScriptResumeTask(Long taskId, String status, String remark,
                                                    String errorMessage, Long userId)
    {
        LambdaUpdateWrapper<AidExtractTask> rollback = Wrappers.lambdaUpdate();
        rollback.eq(AidExtractTask::getId, taskId);
        rollback.set(AidExtractTask::getStatus, status);
        rollback.set(AidExtractTask::getRemark, remark);
        rollback.set(AidExtractTask::getErrorMessage, errorMessage);
        rollback.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        rollback.set(AidExtractTask::getUpdateBy, String.valueOf(userId));
        extractTaskService.update(rollback);
    }

    /**
     * 加载当前集剧情引用的场景ID集合（aid_scene_plot.scene_id）。
     * 跨集复用场景的主资产归属其他集，本集可用性由剧情引用关系表达。
     * 查询字段精简：仅 scene_id（新增使用字段时此处必须同步补充）。
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID
     * @param userId    当前用户ID
     * @return 本集剧情引用的场景ID集合
     */
    private java.util.Set<Long> loadEpisodePlotSceneIds(Long projectId, Long episodeId, Long userId)
    {
        java.util.Set<Long> sceneIds = new LinkedHashSet<>();
        List<AidScenePlot> plots = scenePlotService.list(
                Wrappers.<AidScenePlot>lambdaQuery()
                        .select(AidScenePlot::getSceneId)
                        .eq(AidScenePlot::getProjectId, projectId)
                        .eq(AidScenePlot::getEpisodeId, episodeId)
                        .eq(AidScenePlot::getUserId, userId)
                        .eq(AidScenePlot::getDelFlag, DEL_FLAG_NORMAL)
                        .isNotNull(AidScenePlot::getSceneId));
        for (AidScenePlot plot : plots)
        {
            sceneIds.add(plot.getSceneId());
        }
        return sceneIds;
    }

    /** 加载与执行阶段同源的分镜脚本消息装配上下文，整批仅加载一次。 */
    private StoryboardBillingEstimateContext loadStoryboardBillingEstimateContext(
            Long projectId, Long episodeId, Long userId, String agentCode, String mode,
            String modelCode, List<Long> sceneIds)
    {
        String promptTemplate = helper.loadPromptByName(agentCode);
        // 参考资产=当前项目全部资产（与执行阶段同口径）：剧集角色为项目级、道具跨集复用
        List<AidRolePropScene> characterList = rpsService.list(
                Wrappers.<AidRolePropScene>lambdaQuery()
                        .eq(AidRolePropScene::getProjectId, projectId)
                        .eq(AidRolePropScene::getAssetType, ASSET_TYPE_CHARACTER)
                        .eq(AidRolePropScene::getUserId, userId)
                        .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL));
        List<AidRolePropScene> propList = rpsService.list(
                Wrappers.<AidRolePropScene>lambdaQuery()
                        .eq(AidRolePropScene::getProjectId, projectId)
                        .eq(AidRolePropScene::getAssetType, ASSET_TYPE_PROP)
                        .eq(AidRolePropScene::getUserId, userId)
                        .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL));

        List<Long> characterIds = characterList.stream().map(AidRolePropScene::getId).collect(Collectors.toList());
        Set<Long> characterIdSet = new LinkedHashSet<>(characterIds);
        List<Long> propIds = propList.stream().map(AidRolePropScene::getId).collect(Collectors.toList());
        List<AidRolePropSceneForm> characterForms = CollectionUtil.isEmpty(characterIds)
                ? new ArrayList<>()
                : rpsFormService.list(Wrappers.<AidRolePropSceneForm>lambdaQuery()
                        .in(AidRolePropSceneForm::getAssetId, characterIds)
                        .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL));
        List<AidRolePropSceneForm> propForms = CollectionUtil.isEmpty(propIds)
                ? new ArrayList<>()
                : rpsFormService.list(Wrappers.<AidRolePropSceneForm>lambdaQuery()
                        .in(AidRolePropSceneForm::getAssetId, propIds)
                        .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL));
        Map<Long, AidRolePropScene> characterIdx = characterList.stream()
                .collect(Collectors.toMap(AidRolePropScene::getId, value -> value, (a, b) -> a));
        Map<Long, AidRolePropScene> propIdx = propList.stream()
                .collect(Collectors.toMap(AidRolePropScene::getId, value -> value, (a, b) -> a));
        Map<Long, AidRoleVoiceBinding> voiceBindings = loadEnabledVoiceBindings(characterIdSet, episodeId, userId);
        List<String> audioReferenceNames = loadAudioReferenceNames(
                characterIdSet, characterIdx, voiceBindings, episodeId, userId);
        List<String> videoReferenceNames = loadVideoReferenceNames(projectId, episodeId, userId);
        Map<Long, List<AidRolePropSceneFormImage>> characterImagesByAsset =
                loadInUseFormImagesByAssets(new LinkedHashSet<>(characterIds), episodeId, userId);
        Map<Long, List<AidRolePropSceneFormImage>> propImagesByAsset =
                loadInUseFormImagesByAssets(new LinkedHashSet<>(propIds), episodeId, userId);

        Map<Long, List<AidRolePropSceneForm>> sceneFormsByAsset = new HashMap<>();
        if (CollectionUtil.isNotEmpty(sceneIds))
        {
            List<AidRolePropSceneForm> sceneForms = rpsFormService.list(
                    Wrappers.<AidRolePropSceneForm>lambdaQuery()
                            .in(AidRolePropSceneForm::getAssetId, sceneIds)
                            .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL));
            sceneFormsByAsset = sceneForms.stream()
                    .collect(Collectors.groupingBy(AidRolePropSceneForm::getAssetId));
        }
        return new StoryboardBillingEstimateContext(promptTemplate, modelCode, mode, episodeId, userId,
                characterIdx, characterForms, propIdx, propForms, sceneFormsByAsset,
                characterImagesByAsset, propImagesByAsset, videoReferenceNames, audioReferenceNames,
                AGENT_CODE_STORYBOARD_WRITER.equals(agentCode), SHOT_FLOOR_AGENT_CODES.contains(agentCode),
                loadShotDensityFloorConfig());
    }

    /** 单批次预冻结费用估算：直接复用执行阶段的 user message 装配，并计入真实 system。 */
    private BillingCalcResult estimateBatchCost(BatchPlanItem batch, AidRolePropScene scene,
                                                 AiModelConfigVo modelConfig,
                                                 StoryboardBillingEstimateContext context)
    {
        List<AidRolePropSceneForm> sceneForms = context.sceneFormsByAsset()
                .getOrDefault(scene.getId(), new ArrayList<>());
        Integer minShotFloor = context.injectShotFloor()
                ? calcMinShotFloor(batch.getCharCount(), context.mode(), context.shotFloorConfig()) : null;
        String userContent = buildLlmInput(batch, scene, sceneForms, context.characterIdx(),
                context.characterForms(), context.propIdx(), context.propForms(), context.mode(),
                context.episodeId(), context.userId(), context.videoReferenceNames(),
                context.audioReferenceNames(), context.useShotGroupSplit(), minShotFloor,
                context.characterImagesByAsset(), context.propImagesByAsset());
        int inputCharsEstimate = helper.estimateLlmInputChars(
                context.promptTemplate(), userContent, context.modelCode());
        int outputCharsEstimate = (int) Math.min(
                Math.ceil(inputCharsEstimate * 0.8D), Integer.MAX_VALUE);
        Map<String, Object> params = new HashMap<>();
        params.put("inputChars", inputCharsEstimate);
        params.put("inputTokens", BillingConstants.charsToTokens(inputCharsEstimate));
        params.put("outputTokens", BillingConstants.charsToTokens(outputCharsEstimate));
        params.put("estimatedOutputChars", outputCharsEstimate);
        params.put("totalChars", inputCharsEstimate + outputCharsEstimate);
        BillingInput billingInput = new BillingInput("TEXT", params);

        BillingCalcResult result = billingAmountCalculator.calculatePreHoldAmount(modelConfig, billingInput);
        if (Objects.nonNull(result) && result.isMatched())
        {
            return result;
        }
        // 降级：SKU 未命中时按官方固定原价 × 两级倍率兜底，并保留倍率快照。
        BigDecimal rawUnitPrice = Objects.nonNull(modelConfig.getCostCredits())
                ? modelConfig.getCostCredits() : BigDecimal.ZERO;
        BigDecimal unitPrice = billingPriceMultiplierService.apply(
                rawUnitPrice, modelConfig.getBillingMultiplier());
        BillingSnapshot fallbackSnap = new BillingSnapshot();
        fallbackSnap.setModelId(modelConfig.getId());
        fallbackSnap.setModelName(modelConfig.getModelCode());
        fallbackSnap.setModelType(MODEL_TYPE_TEXT.toUpperCase());
        fallbackSnap.setBillingMode("FIXED");
        fallbackSnap.setUnitPrice(unitPrice);
        fallbackSnap.setBaseAmount(rawUnitPrice);
        fallbackSnap.setModelBillingMultiplier(billingPriceMultiplierService.resolveModelMultiplier(
                modelConfig.getBillingMultiplier()));
        fallbackSnap.setGlobalBillingMultiplier(billingPriceMultiplierService.getGlobalMultiplier());
        fallbackSnap.setFinalBillingMultiplier(fallbackSnap.getModelBillingMultiplier()
                .multiply(fallbackSnap.getGlobalBillingMultiplier()));
        fallbackSnap.setPreHoldAmount(unitPrice);
        fallbackSnap.setEstimatedInputTokens(BillingConstants.charsToTokens(inputCharsEstimate));
        fallbackSnap.setEstimatedOutputTokens(BillingConstants.charsToTokens(outputCharsEstimate));
        return BillingCalcResult.fixed(unitPrice, fallbackSnap);
    }

    private record StoryboardBillingEstimateContext(
            String promptTemplate,
            String modelCode,
            String mode,
            Long episodeId,
            Long userId,
            Map<Long, AidRolePropScene> characterIdx,
            List<AidRolePropSceneForm> characterForms,
            Map<Long, AidRolePropScene> propIdx,
            List<AidRolePropSceneForm> propForms,
            Map<Long, List<AidRolePropSceneForm>> sceneFormsByAsset,
            Map<Long, List<AidRolePropSceneFormImage>> characterImagesByAsset,
            Map<Long, List<AidRolePropSceneFormImage>> propImagesByAsset,
            List<String> videoReferenceNames,
            List<String> audioReferenceNames,
            boolean useShotGroupSplit,
            boolean injectShotFloor,
            ShotDensityFloorConfig shotFloorConfig) { }

    private BillingCalcResult estimateTextCostFromChars(long inputChars, long outputChars, AiModelConfigVo modelConfig)
    {
        int safeInputChars = (int) Math.min(Math.max(inputChars, 0L), Integer.MAX_VALUE);
        int safeOutputChars = (int) Math.min(Math.max(outputChars, 0L), Integer.MAX_VALUE);
        Map<String, Object> params = new HashMap<>();
        params.put("inputChars", safeInputChars);
        params.put("inputTokens", BillingConstants.charsToTokens(safeInputChars));
        params.put("outputTokens", BillingConstants.charsToTokens(safeOutputChars));
        params.put("estimatedOutputChars", safeOutputChars);
        params.put("totalChars", (long) safeInputChars + safeOutputChars);
        BillingInput billingInput = new BillingInput("TEXT", params);

        BillingCalcResult result = billingAmountCalculator.calculatePreHoldAmount(modelConfig, billingInput);
        if (Objects.nonNull(result) && result.isMatched())
        {
            return result;
        }
        BigDecimal rawUnitPrice = Objects.nonNull(modelConfig.getCostCredits())
                ? modelConfig.getCostCredits() : BigDecimal.ZERO;
        BigDecimal unitPrice = billingPriceMultiplierService.apply(
                rawUnitPrice, modelConfig.getBillingMultiplier());
        BillingSnapshot fallbackSnap = new BillingSnapshot();
        fallbackSnap.setModelId(modelConfig.getId());
        fallbackSnap.setModelName(modelConfig.getModelCode());
        fallbackSnap.setModelType(MODEL_TYPE_TEXT.toUpperCase());
        fallbackSnap.setBillingMode("FIXED");
        fallbackSnap.setUnitPrice(unitPrice);
        fallbackSnap.setBaseAmount(rawUnitPrice);
        fallbackSnap.setModelBillingMultiplier(billingPriceMultiplierService.resolveModelMultiplier(
                modelConfig.getBillingMultiplier()));
        fallbackSnap.setGlobalBillingMultiplier(billingPriceMultiplierService.getGlobalMultiplier());
        fallbackSnap.setFinalBillingMultiplier(fallbackSnap.getModelBillingMultiplier()
                .multiply(fallbackSnap.getGlobalBillingMultiplier()));
        fallbackSnap.setPreHoldAmount(unitPrice);
        fallbackSnap.setEstimatedInputTokens(BillingConstants.charsToTokens(safeInputChars));
        fallbackSnap.setEstimatedOutputTokens(BillingConstants.charsToTokens(safeOutputChars));
        return BillingCalcResult.fixed(unitPrice, fallbackSnap);
    }

    private String buildSlimBatchBillingSnapshotJson(String batchType, BigDecimal totalFrozen,
                                                     BillingSnapshot settleSnapshot,
                                                     int estimatedInputTokens,
                                                     int estimatedOutputTokens,
                                                     List<Map<String, Object>> items,
                                                     Map<String, Object> splitSummary)
    {
        if (Objects.isNull(settleSnapshot) && CollectionUtil.isEmpty(items) && Objects.isNull(splitSummary))
        {
            return null;
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("batchType", batchType);
        root.put("itemCount", CollectionUtil.isEmpty(items) ? 0 : items.size());
        root.put("preHoldAmount", totalFrozen);
        if (Objects.nonNull(settleSnapshot))
        {
            BillingSnapshot snapshot = OBJECT_MAPPER.convertValue(settleSnapshot, BillingSnapshot.class);
            snapshot.setRequestParams(null);
            snapshot.setMatchedRuleConditions(null);
            snapshot.setPreHoldAmount(totalFrozen);
            snapshot.setEstimatedInputTokens(estimatedInputTokens);
            snapshot.setEstimatedOutputTokens(estimatedOutputTokens);
            snapshot.setActualInputTokens(null);
            snapshot.setActualOutputTokens(null);
            snapshot.setActualAmount(null);
            snapshot.setRefundAmount(null);
            snapshot.setSettleTime(null);
            snapshot.setTextSettleDone(false);
            snapshot.setExtraChargeRequired(null);
            snapshot.setExtraChargeActual(null);
            snapshot.setPartialExtraCharge(null);
            root.put("settleSnapshot", snapshot);
        }
        if (Objects.nonNull(splitSummary))
        {
            root.put("split", splitSummary);
        }
        root.put("items", CollectionUtil.isEmpty(items) ? Collections.emptyList() : items);
        try
        {
            return OBJECT_MAPPER.writeValueAsString(root);
        }
        catch (Exception e)
        {
            log.error("批量计费快照序列化失败: batchType={}", batchType, e);
            throw new ServiceException("计费数据异常");
        }
    }

    private BillingSnapshot chooseSettleSnapshot(BillingSnapshot current, BillingSnapshot candidate)
    {
        if (Objects.nonNull(current))
        {
            return current;
        }
        if (Objects.isNull(candidate))
        {
            return null;
        }
        BillingSnapshot snapshot = OBJECT_MAPPER.convertValue(candidate, BillingSnapshot.class);
        snapshot.setRequestParams(null);
        snapshot.setMatchedRuleConditions(null);
        return snapshot;
    }

    private boolean isSameSettlePricing(BillingSnapshot current, BillingSnapshot candidate)
    {
        if (Objects.isNull(current) || Objects.isNull(candidate))
        {
            return true;
        }
        return Objects.equals(current.getMeterType(), candidate.getMeterType())
                && Objects.equals(current.getBillingMode(), candidate.getBillingMode())
                && Objects.equals(current.getBillingRuleJson(), candidate.getBillingRuleJson())
                && Objects.equals(current.getInputPricePerMillion(), candidate.getInputPricePerMillion())
                && Objects.equals(current.getOutputPricePerMillion(), candidate.getOutputPricePerMillion())
                && Objects.equals(current.getPricePerSecond(), candidate.getPricePerSecond())
                && Objects.equals(current.getSkuPackagePrice(), candidate.getSkuPackagePrice())
                && Objects.equals(current.getFinalBillingMultiplier(), candidate.getFinalBillingMultiplier());
    }

    private Map<String, Object> buildSlimBatchBillingItem(BatchPlanItem plan, BigDecimal amount)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("b", Objects.isNull(plan) ? null : plan.getBatchIndex());
        item.put("s", Objects.isNull(plan) ? null : plan.getSceneId());
        if (Objects.nonNull(plan) && Objects.nonNull(plan.getShotGroupPlanId()))
        {
            item.put("p", plan.getShotGroupPlanId());
        }
        item.put("c", Objects.isNull(plan) ? 0 : plan.getCharCount());
        item.put("a", amount);
        return item;
    }

    private Map<String, Object> buildSplitBillingSummary(long[] splitChars, BigDecimal amount)
    {
        Map<String, Object> split = new LinkedHashMap<>();
        split.put("ic", splitChars[0]);
        split.put("oc", splitChars[1]);
        split.put("a", amount);
        return split;
    }

    private int safeToken(Integer value)
    {
        return Objects.isNull(value) ? 0 : value;
    }

    private String buildLockKey(Long projectId, Long episodeId)
    {
        return "storyboard:script:lock:" + projectId + ":" + episodeId;
    }

    private void releaseProjectLockQuietly(Long projectId, Long episodeId)
    {
        if (Objects.isNull(projectId) || Objects.isNull(episodeId))
        {
            return;
        }
        try
        {
            redisCache.deleteObject(buildLockKey(projectId, episodeId));
        }
        catch (Exception e)
        {
            log.warn("分镜脚本项目锁释放异常: projectId={}, episodeId={}, err={}",
                    projectId, episodeId, e.getMessage());
        }
    }

    private void touchExtractTask(Long taskId)
    {
        if (Objects.isNull(taskId))
        {
            return;
        }
        try
        {
            AidExtractTask upd = new AidExtractTask();
            upd.setId(taskId);
            upd.setUpdateTime(DateUtils.getNowDate());
            extractTaskService.updateById(upd);
        }
        catch (Exception e)
        {
            log.warn("分镜脚本任务心跳更新异常: taskId={}, err={}", taskId, e.getMessage());
        }
    }

    /**
     * 判定提交阶段抛出的异常是否属于「面向用户、应原样透传」的错误。
     */
    private boolean isUserVisibleSubmitError(String msg)
    {
        if (StrUtil.isBlank(msg))
        {
            return false;
        }
        // 余额 / 计费类（来自 ExtractBillingServiceImpl + AccountUpdateService）
        if (msg.contains("余额不足") || msg.contains("账户异常") || msg.contains("账户冻结")
                || msg.contains("额度不足") || msg.contains("系统繁忙"))
        {
            return true;
        }
        // 业务前置校验（视觉资产库 / 剧情节拍 / 任务并发）
        if (msg.contains("视觉资产库为空") || msg.contains("暂无剧情可拆") || msg.contains("任务处理中")
                || msg.contains("场景缺少") || msg.contains("场景ID列表存在不可用项"))
        {
            return true;
        }
        // 智能体 / 模型配置类
        if (msg.contains("模型未配置") || msg.contains("模型不存在") || msg.contains("模型不可用")
                || msg.contains("智能体配置异常") || msg.contains("智能体不存在") || msg.contains("智能体停用"))
        {
            return true;
        }
        // 项目 / 风格类
        if (msg.contains("项目不存在") || msg.contains("请先选择风格") || msg.contains("画面比例")
                || msg.contains("剧本类型") || msg.contains("视频风格"))
        {
            return true;
        }
        // 覆盖确认 / 已存在分镜
        if (msg.contains("已存在分镜脚本"))
        {
            return true;
        }
        return false;
    }

    /**
     * 把批次置 FAILED + billing 置 REFUNDED（计费层面金额由父任务级 settleBilling 通过差额退回机制统一处理）。
     */
    private void failBatchAndRefund(AidStoryboardBatch batch, Long userId, String errMsg)
    {
        if (Objects.isNull(batch))
        {
            return;
        }
        try
        {
            LambdaUpdateWrapper<AidStoryboardBatch> upd = Wrappers.lambdaUpdate();
            upd.eq(AidStoryboardBatch::getId, batch.getId());
            upd.set(AidStoryboardBatch::getStatus, BATCH_STATUS_FAILED);
            upd.set(AidStoryboardBatch::getBillingStatus, BILLING_STATUS_REFUNDED);
            upd.set(AidStoryboardBatch::getErrorMessage, StrUtil.sub(errMsg, 0, 500));
            upd.set(AidStoryboardBatch::getUpdateTime, DateUtils.getNowDate());
            storyboardBatchService.update(upd);
            // 同步计划状态为 FAILED
            updatePlanStatus(batch.getShotGroupPlanId(), PLAN_STATUS_FAILED, StrUtil.sub(errMsg, 0, 500));
        }
        catch (Exception ex)
        {
            log.warn("failBatchAndRefund 状态推进异常: batchId={}, err={}", batch.getId(), ex.getMessage());
        }
    }

    /**
     * 把批次置 CANCELLED + billing 置 REFUNDED。
     * 用于"前序批次失败"或"用户主动取消"场景。
     */
    private void cancelBatchAndRefund(AidStoryboardBatch batch, Long userId, String reason)
    {
        if (Objects.isNull(batch))
        {
            return;
        }
        try
        {
            LambdaUpdateWrapper<AidStoryboardBatch> upd = Wrappers.lambdaUpdate();
            upd.eq(AidStoryboardBatch::getId, batch.getId());
            upd.set(AidStoryboardBatch::getStatus, BATCH_STATUS_CANCELLED);
            upd.set(AidStoryboardBatch::getBillingStatus, BILLING_STATUS_REFUNDED);
            upd.set(AidStoryboardBatch::getErrorMessage, StrUtil.sub(reason, 0, 500));
            upd.set(AidStoryboardBatch::getUpdateTime, DateUtils.getNowDate());
            storyboardBatchService.update(upd);
            // 同步计划状态为 CANCELLED
            updatePlanStatus(batch.getShotGroupPlanId(), PLAN_STATUS_CANCELLED, StrUtil.sub(reason, 0, 500));
        }
        catch (Exception ex)
        {
            log.warn("cancelBatchAndRefund 状态推进异常: batchId={}, err={}", batch.getId(), ex.getMessage());
        }
    }

    /**
     * 把父任务标记为 PARTIAL_FAILED（终态）。
     * 由 Consumer 调用 doStoryboardScriptBatch 后正常返回不抛异常，避免 Consumer 把任务标记为 FAILED。
     * 业务层主动通过此方法把 aid_extract_task.status 改成 PARTIAL_FAILED。
     */
    private void markTaskPartialFailed(Long taskId, String reason)
    {
        try
        {
            LambdaUpdateWrapper<AidExtractTask> upd = Wrappers.lambdaUpdate();
            upd.eq(AidExtractTask::getId, taskId);
            upd.set(AidExtractTask::getStatus, TASK_STATUS_PARTIAL_FAILED);
            upd.set(AidExtractTask::getErrorMessage, StrUtil.sub(reason, 0, 500));
            upd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            extractTaskService.update(upd);
        }
        catch (Exception ex)
        {
            log.warn("markTaskPartialFailed 异常: taskId={}, err={}", taskId, ex.getMessage());
        }
    }

    /**
     * 续生模式独立结算。
     */
    private void settleResumeBatches(Long taskId, Long userId, List<AidStoryboardBatch> processedPlans,
                                      int processedBatchPos, boolean hasFailureMidway)
    {
        // 从任务 remark 反解 traceId / 总冻结金额
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || StrUtil.isBlank(task.getRemark()) || !task.getRemark().startsWith("RESUME_TRACE:"))
        {
            log.warn("settleResumeBatches 找不到 RESUME_TRACE 标记，跳过结算: taskId={}", taskId);
            return;
        }
        String marker = task.getRemark();
        String resumeTraceId;
        BigDecimal totalFrozen;
        try
        {
            int p1 = marker.indexOf("RESUME_TRACE:") + "RESUME_TRACE:".length();
            int p2 = marker.indexOf("|FROZEN:");
            int p3 = p2 + "|FROZEN:".length();
            resumeTraceId = marker.substring(p1, p2);
            totalFrozen = new BigDecimal(marker.substring(p3));
        }
        catch (Exception e)
        {
            log.error("settleResumeBatches 解析 RESUME_TRACE 失败: taskId={}, remark={}", taskId, marker, e);
            // 即使解析失败也要清空 marker，避免下一轮续生拿到坏值
            clearResumeMarker(taskId);
            return;
        }

        // 按 processedPlans 范围内的批次重新查 DB 拿最新 status
        if (CollectionUtil.isEmpty(processedPlans))
        {
            log.info("settleResumeBatches 无处理批次，全额退款: taskId={}, totalFrozen={}", taskId, totalFrozen);
            try
            {
                accountUpdateService.refund(userId, totalFrozen, resumeTraceId, BIZ_TYPE_CREATE, "分镜脚本续生退款");
            }
            catch (Exception e)
            {
                log.error("分镜脚本续生全额退款异常: taskId={}, traceId={}", taskId, resumeTraceId, e);
            }
            clearResumeMarker(taskId);
            return;
        }

        List<Long> batchIds = processedPlans.stream()
                .map(AidStoryboardBatch::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        List<AidStoryboardBatch> latestBatches = storyboardBatchService.list(
                Wrappers.<AidStoryboardBatch>lambdaQuery()
                        .in(AidStoryboardBatch::getId, batchIds)
                        .eq(AidStoryboardBatch::getDelFlag, DEL_FLAG_NORMAL));

        BigDecimal succeededAmount = BigDecimal.ZERO;
        BigDecimal unsuccessAmount = BigDecimal.ZERO;
        for (AidStoryboardBatch batch : latestBatches)
        {
            BigDecimal amt = batch.getFrozenAmount() == null ? BigDecimal.ZERO : batch.getFrozenAmount();
            if (BATCH_STATUS_SUCCEEDED.equalsIgnoreCase(batch.getStatus()))
            {
                succeededAmount = succeededAmount.add(amt);
            }
            else if (BATCH_STATUS_FAILED.equalsIgnoreCase(batch.getStatus())
                    || BATCH_STATUS_CANCELLED.equalsIgnoreCase(batch.getStatus()))
            {
                unsuccessAmount = unsuccessAmount.add(amt);
            }
        }

        try
        {
            if (succeededAmount.compareTo(BigDecimal.ZERO) > 0)
            {
                accountUpdateService.settle(userId, succeededAmount, resumeTraceId,
                        BIZ_TYPE_CREATE, "分镜脚本续生结算");
            }
            if (unsuccessAmount.compareTo(BigDecimal.ZERO) > 0)
            {
                accountUpdateService.refund(userId, unsuccessAmount, resumeTraceId,
                        BIZ_TYPE_CREATE, "分镜脚本续生退款");
            }
            log.info("分镜脚本续生结算完成: taskId={}, traceId={}, totalFrozen={}, settled={}, refunded={}",
                    taskId, resumeTraceId, totalFrozen, succeededAmount, unsuccessAmount);
        }
        catch (Exception e)
        {
            log.error("分镜脚本续生结算异常: taskId={}, traceId={}", taskId, resumeTraceId, e);
        }

        clearResumeMarker(taskId);
    }

    /** 清理 task.remark 上的 RESUME_TRACE 标记，避免下一轮续生拿到旧 traceId 误结算。 */
    private void clearResumeMarker(Long taskId)
    {
        try
        {
            LambdaUpdateWrapper<AidExtractTask> clearUpd = Wrappers.lambdaUpdate();
            clearUpd.eq(AidExtractTask::getId, taskId);
            clearUpd.set(AidExtractTask::getRemark, null);
            extractTaskService.update(clearUpd);
        }
        catch (Exception ignore) { /* 静默兜底 */ }
    }

    private void sendMqMessage(Long taskId, Long projectId, Long episodeId, Long userId, String modelCode)
    {
        // 双模式派发统一收口：MQ 开走 MQ；MQ 关走本地线程（共用同一执行体 + 终态编排）
        boolean enqueued = dualModeTaskDispatcher.dispatch(taskId, projectId, episodeId, userId, modelCode,
                TASK_TYPE_STORYBOARD_SCRIPT_BATCH,
                () -> batchTaskLocalOrchestrator.run(taskId, userId, LOCAL_SPEC,
                        () -> doStoryboardScriptBatch(taskId, userId),
                        () -> assetExtractService.releaseBatchFormLocks(taskId, TASK_TYPE_STORYBOARD_SCRIPT_BATCH)));
        if (!enqueued)
        {
            log.error("分镜脚本批量入队失败: taskId={}", taskId);
            throw new ServiceException("提交失败，请重试");
        }
    }

    /**
     * 镜头数下限配置快照（每次任务执行读取一次，循环内复用）。
     */
    private static class ShotDensityFloorConfig
    {
        /** 精简模式每镜字数（底线密度锚点） */
        private final int charsPerShot;
        /** 标准模式相对精简的上浮比例 */
        private final double standardRatio;
        /** 细拆模式相对精简的上浮比例 */
        private final double detailedRatio;
        /** 单次调用下限封顶 */
        private final int maxFloor;

        private ShotDensityFloorConfig(int charsPerShot, double standardRatio, double detailedRatio, int maxFloor)
        {
            this.charsPerShot = charsPerShot;
            this.standardRatio = standardRatio;
            this.detailedRatio = detailedRatio;
            this.maxFloor = maxFloor;
        }
    }

    /**
     * 读取镜头数下限配置（storyboard / shot_density_floor）。
     * 配置缺失 / JSON 非法 / 数值非法时逐项回退代码默认值并打 warn，保证任务不因配置问题失败。
     */
    private ShotDensityFloorConfig loadShotDensityFloorConfig()
    {
        int charsPerShot = DEFAULT_CHARS_PER_SHOT;
        double standardRatio = DEFAULT_STANDARD_RATIO;
        double detailedRatio = DEFAULT_DETAILED_RATIO;
        int maxFloor = DEFAULT_MAX_FLOOR;
        try
        {
            // 防漏字段：仅 select 计算下限必需的 configValue，新增字段时需同步扩展
            LambdaQueryWrapper<AidConfig> wrapper = Wrappers.lambdaQuery();
            wrapper.eq(AidConfig::getCategory, CONFIG_CATEGORY_STORYBOARD);
            wrapper.eq(AidConfig::getConfigName, CONFIG_KEY_SHOT_DENSITY_FLOOR);
            wrapper.eq(AidConfig::getDelFlag, DEL_FLAG_NORMAL);
            wrapper.select(AidConfig::getConfigValue);
            wrapper.last("LIMIT 1");
            AidConfig cfg = aidConfigService.getOne(wrapper, false);
            if (Objects.nonNull(cfg) && StrUtil.isNotBlank(cfg.getConfigValue()))
            {
                JsonNode node = OBJECT_MAPPER.readTree(cfg.getConfigValue());
                int cps = node.path("charsPerShot").asInt(0);
                double sr = node.path("standardRatio").asDouble(0);
                double dr = node.path("detailedRatio").asDouble(0);
                int mf = node.path("maxFloor").asInt(0);
                if (cps > 0)
                {
                    charsPerShot = cps;
                }
                if (sr > 1)
                {
                    standardRatio = sr;
                }
                if (dr > 1)
                {
                    detailedRatio = dr;
                }
                if (mf > 0)
                {
                    maxFloor = mf;
                }
            }
        }
        catch (Exception e)
        {
            log.warn("镜头数下限配置解析失败，使用默认值: {}", e.getMessage());
        }
        // 比例倒挂校验：细拆必须大于标准，否则双双回退默认值
        if (detailedRatio <= standardRatio)
        {
            log.warn("镜头数下限配置比例倒挂，回退默认比例: standardRatio={}, detailedRatio={}",
                    standardRatio, detailedRatio);
            standardRatio = DEFAULT_STANDARD_RATIO;
            detailedRatio = DEFAULT_DETAILED_RATIO;
        }
        return new ShotDensityFloorConfig(charsPerShot, standardRatio, detailedRatio, maxFloor);
    }

    /**
     * 按剧本片段字数与拆分模式计算未封顶的理论最低镜头数。
     * 基准 = 精简模式底线（每 charsPerShot 字至少 1 镜），标准 / 细拆按上浮比例推导，
     * 结构上保证三档下限 精简 ≤ 标准 ≤ 细拆，任何档位不会低于精简底线。
     *
     * @param charCount 本批实际下发拆分的剧本片段字数
     * @param mode      拆分模式（精简/标准/细拆）
     * @param cfg       下限配置快照
     * @return 未封顶的理论最低镜头数（≥1）
     */
    private int calcUncappedShotFloor(int charCount, String mode, ShotDensityFloorConfig cfg)
    {
        int base = Math.max(1, (int) Math.ceil((double) charCount / cfg.charsPerShot));
        double ratio = 1.0;
        if (Objects.equals(StoryboardShotDensityEnum.STANDARD.getValue(), mode))
        {
            ratio = cfg.standardRatio;
        }
        else if (Objects.equals(StoryboardShotDensityEnum.DETAILED.getValue(), mode))
        {
            ratio = cfg.detailedRatio;
        }
        return Math.max(1, (int) Math.ceil(base * ratio));
    }

    /**
     * 按剧本片段字数与拆分模式计算注入用最低镜头数（含封顶）。
     * 封顶只作用于「系统强制要求的最低数」，不限制 AI 实际可拆更多；
     * 超封顶的超长场次在提交时会通过 {@link #buildShotFloorWarning} 提醒用户。
     *
     * @param charCount 本批实际下发拆分的剧本片段字数
     * @param mode      拆分模式（精简/标准/细拆）
     * @param cfg       下限配置快照
     * @return 最低镜头数（≥1，且不超过 maxFloor 封顶）
     */
    private int calcMinShotFloor(int charCount, String mode, ShotDensityFloorConfig cfg)
    {
        int floor = calcUncappedShotFloor(charCount, mode, cfg);
        if (floor > cfg.maxFloor)
        {
            // 超长片段封顶：防止单次调用要求过多镜头导致输出截断；超限场次建议拆分场次或改用专业版（镜头组拆分）
            log.warn("镜头数下限超过单次调用封顶，按封顶执行: charCount={}, mode={}, floor={}, maxFloor={}",
                    charCount, mode, floor, cfg.maxFloor);
            floor = cfg.maxFloor;
        }
        return floor;
    }

    /**
     * 提交时的超长场次检测：未封顶理论下限超过 maxFloor 的场次，生成用户提示文案。
     * 封顶只降低「强制最低镜头数」，AI 仍可按内容自行多拆，因此不阻断提交、只提醒。
     *
     * @param batchPlans 普通模式批次计划（一场次一批）
     * @param agentCode  最终解析出的智能体编码（仅下限白名单智能体检测）
     * @param mode       拆分模式
     * @return 提示文案；无超长场次或不适用时返回 {@code null}
     */
    private String buildShotFloorWarning(List<BatchPlanItem> batchPlans, String agentCode, String mode)
    {
        if (!SHOT_FLOOR_AGENT_CODES.contains(agentCode) || CollectionUtil.isEmpty(batchPlans))
        {
            return null;
        }
        ShotDensityFloorConfig cfg = loadShotDensityFloorConfig();
        List<String> overlongCodes = new ArrayList<>();
        for (BatchPlanItem plan : batchPlans)
        {
            if (Objects.isNull(plan))
            {
                continue;
            }
            if (calcUncappedShotFloor(plan.getCharCount(), mode, cfg) > cfg.maxFloor)
            {
                // 一场次一批：sceneCodes 单元素，取首元素做展示编码
                String code = CollectionUtil.isEmpty(plan.getSceneCodes()) ? "?" : plan.getSceneCodes().get(0);
                overlongCodes.add(code);
            }
        }
        if (overlongCodes.isEmpty())
        {
            return null;
        }
        String sample = overlongCodes.stream().limit(3).collect(Collectors.joining("、"));
        String head = overlongCodes.size() > 3
                ? "场次 " + sample + " 等 " + overlongCodes.size() + " 个场次"
                : "场次 " + sample;
        // 提示不阻断任务：封顶只作用于"强制最低"，AI 仍可自行多拆
        log.info("分镜脚本提交检测到超长场次: mode={}, maxFloor={}, overlongCodes={}", mode, cfg.maxFloor, overlongCodes);
        return head + "剧情较长，AI 单次强制的最低镜头数已按 " + cfg.maxFloor
                + " 镜封顶（AI 仍会按内容自行多拆，不受此限制）；如需更高拆分密度，建议拆分场次或改用专业版";
    }

    /**
     * 构建分镜脚本 LLM 的用户输入内容（按场次：一个 BatchPlanItem = 单个场次 plot）。
     *
     * @param batch        当前批次（单场次的剧情字段、字数、sceneCode）
     * @param scene        所属场景资产（用于读取空间字段、name/introduction 兜底）
     * @param sceneForms   场景四视图形态
     * @param mode         拆分模式（精简/标准/细拆）——镜头数由提示词据此自行决定
     * @param episodeId    剧集 ID（用于按 form_image 维度筛选可引用形态图）
     * @param userId       当前用户 ID（防越权）
     * @param writerOutput 是否专业版 writer 输出（true=追加 {"content":[...]} 对象硬约束；
     *                     false=追加「纯 JSON 数组」硬约束，与标准/轻量/解说智能体提示词模板一致）
     * @param minShotFloor 本片段最低镜头数（按字数与模式算出的下限，只约束下限不设上限；null=不注入）
     */
    private String buildLlmInput(BatchPlanItem batch,
                                  AidRolePropScene scene,
                                  List<AidRolePropSceneForm> sceneForms,
                                  Map<Long, AidRolePropScene> characterIdx,
                                  List<AidRolePropSceneForm> characterForms,
                                  Map<Long, AidRolePropScene> propIdx,
                                   List<AidRolePropSceneForm> propForms,
                                   String mode,
                                   Long episodeId,
                                   Long userId,
                                   List<String> videoReferenceNames,
                                   List<String> audioReferenceNames,
                                   boolean writerOutput,
                                   Integer minShotFloor)
    {
        return buildLlmInput(batch, scene, sceneForms, characterIdx, characterForms, propIdx, propForms,
                mode, episodeId, userId, videoReferenceNames, audioReferenceNames, writerOutput, minShotFloor,
                null, null);
    }

    /** 允许预估阶段传入整批预加载的形态图索引，避免按批次重复查询。 */
    private String buildLlmInput(BatchPlanItem batch,
                                  AidRolePropScene scene,
                                  List<AidRolePropSceneForm> sceneForms,
                                  Map<Long, AidRolePropScene> characterIdx,
                                  List<AidRolePropSceneForm> characterForms,
                                  Map<Long, AidRolePropScene> propIdx,
                                  List<AidRolePropSceneForm> propForms,
                                  String mode,
                                  Long episodeId,
                                  Long userId,
                                  List<String> videoReferenceNames,
                                  List<String> audioReferenceNames,
                                  boolean writerOutput,
                                  Integer minShotFloor,
                                  Map<Long, List<AidRolePropSceneFormImage>> preloadedCharacterImages,
                                  Map<Long, List<AidRolePropSceneFormImage>> preloadedPropImages)
    {
        if (Objects.isNull(batch))
        {
            log.error("分镜脚本任务异常: batch 为空");
            throw new ServiceException("批次数据异常");
        }

        StringBuilder sb = new StringBuilder();

        sb.append("【拆分镜头数量指导模式】：").append(mode).append("\n");
        // 提示词输出的 shotNumber 是当前场次/镜头组内的临时序号；项目全局编号由后端
        // 按 aid_storyboard.sort_order 统一生成并回写，避免模型无法感知其它场次已占用的编号。
        sb.append("【镜号编号口径】：本次只处理当前场次/镜头组，shotNumber 从 001 开始按本次输出顺序递增；"
                + "不要尝试跨场次连续编号。项目全局镜号由后端按全局 sortOrder 生成。\n");

        // 最低镜头数注入：兑现提示词「具体目标镜数由后端在 user message 中注入」的约定；
        //   只设下限防止长片段被欠拆丢内容，上限仍由提示词按内容节拍自行决定
        if (Objects.nonNull(minShotFloor))
        {
            sb.append("【本片段最低镜头数】：本剧本片段约 ").append(batch.getCharCount())
                    .append(" 字，按当前模式拆分产出的镜头数不得少于 ").append(minShotFloor)
                    .append(" 个；可以多于此数，不得少于；台词拆分等内容规则要求更多镜头时，以内容规则为准。\n");
        }

        // 单场次批次由提示词按内容节拍推导镜头数（注入下限时不得低于下限）。
        // sceneCodes 区间填充：首-尾区间字符串（如 001-005），便于 LLM 自检 sceneCode 范围
        List<String> codes = batch.getSceneCodes();
        if (CollectionUtil.isNotEmpty(codes))
        {
            String first = codes.get(0);
            String last = codes.get(codes.size() - 1);
            String range = Objects.equals(first, last) ? first : (first + "-" + last);
            sb.append("【场次序号范围】：").append(range)
                    .append("（共 ").append(codes.size()).append(" 个场次节拍）\n");
        }
        sb.append("\n");

        // 镜头组拆分模式：注入镜头组级上下文与生成约束
        if (Objects.nonNull(batch.getShotGroupPlanId()))
        {
            if (StrUtil.isNotBlank(batch.getGroupCode()))
            {
                sb.append("【镜头组编号】：").append(batch.getGroupCode()).append("\n");
            }
            if (Objects.nonNull(batch.getGroupIndex()))
            {
                sb.append("【镜头组序号】：").append(batch.getGroupIndex()).append("\n");
            }
            appendKv(sb, "上一组承接摘要", batch.getGroupPreviousSummary());
            appendKv(sb, "下一组承接摘要", batch.getGroupNextSummary());
            sb.append("\n【生成约束】\n");
            sb.append("本次只生成当前镜头组。\n");
            sb.append("禁止输出多个 content。\n");
            sb.append("禁止输出数组根节点。\n");
            if (StrUtil.isNotBlank(batch.getGroupCode()))
            {
                sb.append("镜头组编号使用").append(batch.getGroupCode()).append("。\n");
            }
            sb.append("剧本内容只允许使用当前镜头组剧情。\n\n");
        }

        sb.append("【剧本片段】\n");
        appendKv(sb, "剧情内容", batch.getMergedPlotContent());
        appendKv(sb, "出场人物", joinNames(batch.getMergedCharacters()));
        appendKv(sb, "人物动作事件", batch.getMergedCharacterActions());
        appendKv(sb, "角色状态", batch.getMergedCharacterStates());
        appendKv(sb, "关键台词", joinNames(batch.getMergedKeyDialogues()));
        appendKv(sb, "场次功能", batch.getMergedSceneFunction());
        appendKv(sb, "时间坐标", batch.getMergedTimeOfDay());
        appendKv(sb, "年代坐标", batch.getMergedEraCoordinate());
        appendKv(sb, "日期坐标", batch.getMergedDateCoordinate());
        appendKv(sb, "气候天象", batch.getMergedWeather());

        // scene 维度的空间字段（只在 scene.profile_data 保留）
        JsonNode profileNode = null;
        String profileData = Objects.nonNull(scene) ? scene.getProfileData() : null;
        if (StrUtil.isNotBlank(profileData))
        {
            try
            {
                profileNode = OBJECT_MAPPER.readTree(profileData);
                appendField(sb, profileNode, "spatial_position", "空间位置");
                appendField(sb, profileNode, "region_coordinate", "地域坐标");
                appendField(sb, profileNode, "environment_type", "环境类型");
                appendField(sb, profileNode, "spatial_zone_division", "空间区域划分");
                appendField(sb, profileNode, "scene_feature_description", "场景特征描述");
            }
            catch (Exception e)
            {
                log.warn("场景 profileData 解析失败: sceneId={}", Objects.isNull(scene) ? null : scene.getId(), e);
            }
        }
        if (StrUtil.isBlank(batch.getMergedPlotContent()) && Objects.nonNull(scene))
        {
            // plot 字段全空时兜底 introduction（极端老数据场景）
            appendKv(sb, "剧情内容兜底", scene.getIntroduction());
        }

        sb.append("\n【视觉资产库】\n");

        // 出场人物名集合：来自 batch.mergedCharacters；profile_node 兜底
        Set<String> sceneCharacterNames = new HashSet<>();
        if (CollectionUtil.isNotEmpty(batch.getMergedCharacters()))
        {
            for (String n : batch.getMergedCharacters())
            {
                if (StrUtil.isNotBlank(n)) { sceneCharacterNames.add(n.trim()); }
            }
        }
        else
        {
            sceneCharacterNames = extractSceneCharacterNames(profileNode);
        }

        // 道具图按主资产维度聚合可用形态图。
        // 防漏字段：仅 select 出业务必需字段（assetId/name/sourceType），新增字段时需同步扩展。
        sb.append("道具图：");
        if (CollectionUtil.isNotEmpty(propForms))
        {
            List<String> names = new ArrayList<>();
            Set<Long> propAssetIds = propForms.stream()
                    .map(AidRolePropSceneForm::getAssetId).filter(Objects::nonNull).collect(Collectors.toSet());
            Map<Long, List<AidRolePropSceneFormImage>> propImagesByAsset = Objects.nonNull(preloadedPropImages)
                    ? preloadedPropImages : loadInUseFormImagesByAssets(propAssetIds, episodeId, userId);
            // 道具字典输入断面日志，便于排查"道具被漏 / 多算"问题
            log.info("分镜脚本字典-道具图入参: episodeId={}, userId={}, propFormCount={}, propAssetIds={}, "
                            + "imgCountByAsset={}",
                    episodeId, userId, propForms.size(), propAssetIds,
                    propImagesByAsset.entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size())));
            for (Long assetId : propAssetIds)
            {
                AidRolePropScene parent = propIdx.get(assetId);
                if (Objects.isNull(parent)) { continue; }
                List<AidRolePropSceneFormImage> imgs = propImagesByAsset.getOrDefault(assetId, Collections.emptyList());
                if (CollectionUtil.isEmpty(imgs))
                {
                    // 主资产存在 form 但没有任何 is_use=1 的 form_image：跳过，不让 LLM 引用一张不存在的图
                    continue;
                }
                for (AidRolePropSceneFormImage img : imgs)
                {
                    String refName = buildAssetReferenceName(parent.getName(), img.getName());
                    if (StrUtil.isNotBlank(refName))
                    {
                        names.add("[" + refName + "]");
                    }
                }
            }
            sb.append(names.isEmpty() ? "无" : String.join("、", names));
        }
        else
        {
            sb.append("无");
        }
        sb.append("\n");

        // 角色图按本批出场人物过滤并聚合可用形态图。
        // sceneCharacterNames 与 asset.name 用宽松匹配（trim + 双向 contains），
        //           避免 LLM 写"林深 "（带尾空格）/ "主角林深" / "林深（档案管理员）" 等不规整名字
        //           导致整角色被精确字符串匹配漏掉。
        // 兜底：若整批角色资产经过出场人物过滤后**全部命中失败**，回退给全部角色，
        //           避免分镜脚本字典完全空导致下游 @图片N 引用断链。
        sb.append("角色图：");
        if (CollectionUtil.isNotEmpty(characterForms))
        {
            List<String> names = new ArrayList<>();
            Set<Long> charAssetIds = characterForms.stream()
                    .map(AidRolePropSceneForm::getAssetId).filter(Objects::nonNull).collect(Collectors.toSet());
            Map<Long, List<AidRolePropSceneFormImage>> charImagesByAsset = Objects.nonNull(preloadedCharacterImages)
                    ? preloadedCharacterImages : loadInUseFormImagesByAssets(charAssetIds, episodeId, userId);
            // 角色字典输入断面日志（关键日志，排查"林深 / 艾拉等角色被漏"问题的入口）
            // 输出：本批出场人物名 / 全部角色资产 ID 与名 / 每个资产命中的 is_use=1 form_image 数量
            Map<Long, String> charAssetIdToName = charAssetIds.stream()
                    .collect(Collectors.toMap(id -> id,
                            id -> Objects.nonNull(characterIdx.get(id))
                                    ? StrUtil.nullToEmpty(characterIdx.get(id).getName())
                                    : ""));
            log.info("分镜脚本字典-角色图入参: episodeId={}, userId={}, sceneCharacterNames={}, "
                            + "characterFormCount={}, charAssetIdToName={}, imgCountByAsset={}",
                    episodeId, userId, sceneCharacterNames,
                    characterForms.size(), charAssetIdToName,
                    charImagesByAsset.entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size())));
            // 第一遍：按出场人物（宽松匹配）过滤
            List<String> matchedNames = new ArrayList<>();
            for (Long assetId : charAssetIds)
            {
                AidRolePropScene parent = characterIdx.get(assetId);
                if (Objects.isNull(parent)) { continue; }
                if (!sceneCharacterNames.isEmpty()
                        && !looselyMatchCharacterName(parent.getName(), sceneCharacterNames))
                {
                    // 被 sceneCharacterNames 过滤掉的角色日志（典型情况：林深没在 mergedCharacters 里）
                    log.info("分镜脚本字典-角色图被过滤: assetId={}, assetName={}, sceneCharacterNames={}",
                            assetId, parent.getName(), sceneCharacterNames);
                    continue;
                }
                List<AidRolePropSceneFormImage> assetImgs = charImagesByAsset.get(assetId);
                int beforeSize = matchedNames.size();
                appendCharacterDictEntries(parent, assetImgs, matchedNames);
                // 角色被命中但没出 form_image 条目的情况日志（is_use=1 图为空 / refName 全空）
                if (matchedNames.size() == beforeSize)
                {
                    log.warn("分镜脚本字典-角色图命中但无 form_image 条目: assetId={}, assetName={}, "
                                    + "imgCount={}",
                            assetId, parent.getName(),
                            CollectionUtil.isEmpty(assetImgs) ? 0 : assetImgs.size());
                }
            }
            // 兜底：第一遍命中为空且确实有角色资产 → 第二遍给全部角色（不做 sceneCharacterNames 过滤）
            if (matchedNames.isEmpty() && !sceneCharacterNames.isEmpty() && !charAssetIds.isEmpty())
            {
                log.info("分镜脚本字典：出场人物过滤后角色为空，启用兜底（全部角色资产入字典）；sceneCharacterNames={}",
                        sceneCharacterNames);
                for (Long assetId : charAssetIds)
                {
                    AidRolePropScene parent = characterIdx.get(assetId);
                    if (Objects.isNull(parent)) { continue; }
                    appendCharacterDictEntries(parent, charImagesByAsset.get(assetId), matchedNames);
                }
            }
            names.addAll(matchedNames);
            // 角色字典最终输出日志
            log.info("分镜脚本字典-角色图出参: episodeId={}, userId={}, finalEntryCount={}, finalEntries={}",
                    episodeId, userId, names.size(), names);
            sb.append(names.isEmpty() ? "无" : String.join("、", names));
        }
        else
        {
            sb.append("无");
        }
        sb.append("\n");

        // 场景图优先使用方位子图，缺失时回退到整图或纯文本背景。
        sb.append("场景图：\n");
        Long sceneAssetId = Objects.nonNull(scene) ? scene.getId() : null;
        Set<String> availableLabels = loadAvailableSceneDirectionLabels(sceneAssetId, episodeId, userId);
        if (Objects.nonNull(scene) && CollectionUtil.isNotEmpty(availableLabels))
        {
            // 已拆分四方位子图：按 KNOWN_DIRECTION_LABELS 固定顺序输出可用方位（视觉一致性）
            for (String label : KNOWN_DIRECTION_LABELS)
            {
                if (!availableLabels.contains(label)) { continue; }
                String imgName = scene.getName() + "_" + label;
                sb.append("  • [").append(imgName).append("]\n");
            }
            log.info("分镜脚本字典-场景图(已拆分): sceneId={}, sceneName={}, availableLabels={}",
                    sceneAssetId, scene.getName(), availableLabels);
        }
        else
        {
            // 兜底：未拆分场景但用户有 is_use=1 的整图 → 列整图条目 + 四宫格方位说明
            List<AidRolePropSceneFormImage> unsplitImgs =
                    loadInUseUnsplitSceneImages(sceneAssetId, episodeId, userId);
            if (Objects.nonNull(scene) && CollectionUtil.isNotEmpty(unsplitImgs))
            {
                // 多张同 form_image 共存时，按 sort_order 取首张代表名（同 form 下 name 通常一致）
                String entryName = pickRepresentativeUnsplitName(scene.getName(), unsplitImgs);
                sb.append("  • [").append(entryName).append("]")
                        .append(buildUnsplitSceneEntry(entryName))
                        .append("\n");
                log.info("分镜脚本字典-场景图(未拆分整图兜底): sceneId={}, sceneName={}, entryName={}, "
                                + "imgCount={}, imgIds={}",
                        sceneAssetId, scene.getName(), entryName, unsplitImgs.size(),
                        unsplitImgs.stream().map(AidRolePropSceneFormImage::getId).collect(Collectors.toList()));
            }
            else
            {
                // 都没有 → 字典留空提示，让 LLM 走"纯文字背景描述"路径
                sb.append("  无可引用方位图，背景仅可用纯文字描述\n");
                log.info("分镜脚本字典-场景图(空): sceneId={}, sceneName={}, hasScene={}",
                        sceneAssetId, Objects.isNull(scene) ? null : scene.getName(),
                        Objects.nonNull(scene));
            }
        }

        sb.append("视频引用：");
        appendReferenceNameLine(sb, limitReferenceNames(videoReferenceNames, MAX_MEDIA_REFERENCE_COUNT));
        sb.append("\n");

        sb.append("音频引用：");
        appendReferenceNameLine(sb, limitReferenceNames(
                filterAudioReferencesForCharacters(audioReferenceNames, sceneCharacterNames),
                MAX_MEDIA_REFERENCE_COUNT));
        sb.append("\n");

        // 输出格式硬约束按智能体分流：writer 是 {"content":[...]} 单对象结构，
        // 标准/轻量/解说是纯 JSON 数组结构，两者互斥——注错约束会把模型带偏、导致解析整批失败
        if (writerOutput)
        {
            appendWriterJsonOutputGuard(sb);
        }
        else
        {
            appendStandardJsonOutputGuard(sb);
        }

        // 字典出口完整日志（截断防刷屏，便于排查"林深 / 艾拉等角色没出现在 @图片N"问题）
        String vocabPreview = StrUtil.sub(sb.toString(), 0, VOCAB_LOG_PREVIEW_LIMIT);
        log.info("分镜脚本字典构造完成: episodeId={}, userId={}, sceneId={}, "
                        + "sceneCharacterNames={}, totalLength={}, preview={}",
                episodeId, userId, sceneAssetId, sceneCharacterNames,
                sb.length(), vocabPreview);

        return sb.toString();
    }

    /**
     * 追加标准/轻量/解说智能体的 JSON 数组输出硬约束（离输出最近，优先级最高）。
     * 不限定字段名与字段数量——字段由各智能体系统提示词的「输出模板」自行约定，
     * 此处只锁根结构，保证 {@link #parseShotsToEntities} 的数组解析口径稳定。
     */
    private void appendStandardJsonOutputGuard(StringBuilder sb)
    {
        sb.append("\n【最终JSON输出硬约束】\n");
        sb.append("以下规则优先级高于所有示例、模板和内部推演：\n");
        sb.append("1. 最终响应只能是一个纯 JSON 数组，第一个非空字符必须是 [，最后一个非空字符必须是 ]。\n");
        sb.append("2. 数组每个元素必须是一个 JSON 对象，字段名与字段数量严格按系统提示词「输出模板」的英文 key 要求。\n");
        sb.append("3. 禁止输出 {\"content\":[...]} 等对象包裹结构，禁止输出多个根节点。\n");
        sb.append("4. 禁止 Markdown 代码块、解释、注释、前后缀文本；输出必须是可直接解析的纯 JSON 字符串。\n");
        sb.append("5. 字符串值内部严禁出现未转义的英文双引号，引号统一使用中文直角引号「」。\n");
    }

    /**
     * 追加离输出最近的 JSON 闭合约束（专业版 writer：{"content":[10个字符串]} 单对象结构专用）。
     */
    private void appendWriterJsonOutputGuard(StringBuilder sb)
    {
        sb.append("\n【最终JSON输出硬约束】\n");
        sb.append("以下规则优先级高于所有示例、模板和内部推演：\n");
        sb.append("1. 最终响应只能是一个 JSON object，格式只能是 {\"content\":[...]}。\n");
        sb.append("2. content 必须是字符串数组，且只包含 10 个字符串元素，禁止嵌套 object、array 或第二个 content。\n");
        sb.append("3. 第 10 个字符串必须是「镜头脚本：...」，镜头脚本字符串结束后只能立即关闭 content 数组和根对象。\n");
        sb.append("4. 最后 3 个非空字符必须依次是英文双引号、右方括号、右大括号，即 \"]}。\n");
        sb.append("5. 禁止尾部出现 }]} 这类在数组关闭前先多关对象的闭合顺序；严禁在右方括号前多输出右大括号。\n");
        sb.append("6. 禁止 Markdown 代码块、解释、注释、前后缀文本；闭合顺序只能是字符串、数组、对象。\n");
    }

    /**
     * 拼装 "label：value\n"，value 空白则跳过。
     */
    private void appendKv(StringBuilder sb, String label, String value)
    {
        if (StrUtil.isBlank(value)) { return; }
        sb.append(label).append("：").append(value).append("\n");
    }

    /**
     * 把 List<String> 用顿号拼接，过滤空白。
     */
    private String joinNames(List<String> names)
    {
        if (CollectionUtil.isEmpty(names)) { return ""; }
        List<String> filtered = new ArrayList<>();
        for (String n : names)
        {
            if (StrUtil.isNotBlank(n)) { filtered.add(n.trim()); }
        }
        return String.join("、", filtered);
    }

    private void appendReferenceNameLine(StringBuilder sb, List<String> names)
    {
        if (CollectionUtil.isEmpty(names))
        {
            sb.append("无");
            return;
        }
        List<String> refs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : names)
        {
            String trimmed = StrUtil.trim(name);
            if (StrUtil.isNotBlank(trimmed) && seen.add(trimmed))
            {
                refs.add("[" + trimmed + "]");
            }
        }
        sb.append(refs.isEmpty() ? "无" : String.join("、", refs));
    }

    private List<String> filterAudioReferencesForCharacters(List<String> audioReferenceNames, Set<String> sceneCharacterNames)
    {
        if (CollectionUtil.isEmpty(audioReferenceNames) || CollectionUtil.isEmpty(sceneCharacterNames))
        {
            return audioReferenceNames;
        }
        List<String> result = new ArrayList<>();
        for (String audioName : audioReferenceNames)
        {
            String roleName = StrUtil.removePrefix(StrUtil.nullToEmpty(audioName), "音频-");
            if (looselyMatchCharacterName(roleName, sceneCharacterNames))
            {
                result.add(audioName);
            }
        }
        return result;
    }

    private List<String> limitReferenceNames(List<String> names, int maxCount)
    {
        if (CollectionUtil.isEmpty(names) || maxCount <= 0)
        {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>(Math.min(names.size(), maxCount));
        Set<String> seen = new LinkedHashSet<>();
        for (String name : names)
        {
            String trimmed = StrUtil.trim(name);
            if (StrUtil.isNotBlank(trimmed) && seen.add(trimmed))
            {
                result.add(trimmed);
                if (result.size() >= maxCount)
                {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 构造资产引用名（去重 + 下划线分隔）。
     */
    private String buildAssetReferenceName(String mainName, String subName)
    {
        if (StrUtil.isBlank(mainName)) { return StrUtil.nullToEmpty(subName).trim(); }
        if (StrUtil.isBlank(subName)) { return mainName.trim(); }
        String main = mainName.trim();
        String sub = subName.trim();
        if (Objects.equals(main, sub)) { return main; }
        if (sub.startsWith(main)) { return sub; }
        return main + "_" + sub;
    }

    /**
     * 加载本项目本剧集「可引用参考图」白名单：{@code aid_role_prop_scene_form_image.name}。
     *
     * @param projectId 项目 ID
     * @param episodeId 剧集 ID
     * @param userId    当前用户 ID
     * @return 可引用资产名列表（去重保序）；参数缺失时返回空列表
     */
    private List<String> loadReferenceableAssetNames(Long projectId, Long episodeId, Long userId)
    {
        if (Objects.isNull(projectId) || Objects.isNull(episodeId) || Objects.isNull(userId))
        {
            return new ArrayList<>();
        }
        List<AidRolePropSceneFormImage> imgs = rpsFormImageService.list(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getName, AidRolePropSceneFormImage::getImageUrl)
                        .eq(AidRolePropSceneFormImage::getProjectId, projectId)
                        .eq(AidRolePropSceneFormImage::getEpisodeId, episodeId)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getIsUse, IS_USE_YES)
                        .eq(AidRolePropSceneFormImage::getIsSplitSource, IS_SPLIT_FLAG_NO)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL));
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AidRolePropSceneFormImage img : imgs)
        {
            String nm = StrUtil.trim(img.getName());
            // 仅收口 image_url 非空（与出图解析器"命中行 image_url 为空视为未匹配"一致），去重保序
            if (StrUtil.isNotBlank(nm) && StrUtil.isNotBlank(img.getImageUrl()) && seen.add(nm))
            {
                names.add(nm);
            }
        }
        return names;
    }

    private Map<Long, AidRoleVoiceBinding> loadEnabledVoiceBindings(Set<Long> characterAssetIds,
            Long episodeId, Long userId)
    {
        if (CollectionUtil.isEmpty(characterAssetIds) || Objects.isNull(userId))
        {
            return Collections.emptyMap();
        }
        List<AidRoleVoiceBinding> bindings = roleVoiceBindingService.list(
                Wrappers.<AidRoleVoiceBinding>lambdaQuery()
                        .select(AidRoleVoiceBinding::getAssetId,
                                AidRoleVoiceBinding::getEpisodeId,
                                AidRoleVoiceBinding::getVoiceLibraryId,
                                AidRoleVoiceBinding::getVoiceCode,
                                AidRoleVoiceBinding::getVoiceName)
                        .in(AidRoleVoiceBinding::getAssetId, characterAssetIds)
                        .and(Objects.nonNull(episodeId), w -> w
                                .eq(AidRoleVoiceBinding::getEpisodeId, episodeId)
                                .or()
                                .isNull(AidRoleVoiceBinding::getEpisodeId)
                                .or()
                                .eq(AidRoleVoiceBinding::getEpisodeId, 0L))
                        .eq(AidRoleVoiceBinding::getUserId, userId)
                        .eq(AidRoleVoiceBinding::getStatus, VOICE_BINDING_ENABLED)
                        .eq(AidRoleVoiceBinding::getDelFlag, DEL_FLAG_NORMAL));
        if (CollectionUtil.isEmpty(bindings))
        {
            return Collections.emptyMap();
        }
        Map<Long, AidRoleVoiceBinding> result = new HashMap<>();
        for (AidRoleVoiceBinding binding : bindings)
        {
            if (Objects.isNull(binding.getAssetId()))
            {
                continue;
            }
            AidRoleVoiceBinding existing = result.get(binding.getAssetId());
            if (Objects.isNull(existing) || shouldUseVoiceBinding(binding, existing, episodeId))
            {
                result.put(binding.getAssetId(), binding);
            }
        }
        return result;
    }

    private boolean shouldUseVoiceBinding(AidRoleVoiceBinding candidate, AidRoleVoiceBinding existing, Long episodeId)
    {
        boolean candidateExact = Objects.nonNull(episodeId) && Objects.equals(candidate.getEpisodeId(), episodeId);
        boolean existingExact = Objects.nonNull(episodeId) && Objects.equals(existing.getEpisodeId(), episodeId);
        return candidateExact && !existingExact;
    }

    private List<String> loadAudioReferenceNames(Set<Long> characterAssetIds,
            Map<Long, AidRolePropScene> characterIdx,
            Map<Long, AidRoleVoiceBinding> voiceBindingByAssetId,
            Long episodeId, Long userId)
    {
        if (CollectionUtil.isEmpty(characterAssetIds) || CollectionUtil.isEmpty(voiceBindingByAssetId))
        {
            return new ArrayList<>();
        }
        Map<Long, List<AidRolePropSceneFormImage>> charImagesByAsset =
                loadInUseFormImagesByAssets(characterAssetIds, episodeId, userId);
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Long assetId : characterAssetIds)
        {
            AidRoleVoiceBinding binding = voiceBindingByAssetId.get(assetId);
            AidRolePropScene parent = Objects.isNull(characterIdx) ? null : characterIdx.get(assetId);
            if (Objects.isNull(binding) || Objects.isNull(parent))
            {
                continue;
            }
            List<AidRolePropSceneFormImage> imgs = charImagesByAsset.getOrDefault(assetId, Collections.emptyList());
            if (CollectionUtil.isEmpty(imgs))
            {
                addAudioReferenceName(result, seen, parent.getName());
                continue;
            }
            for (AidRolePropSceneFormImage img : imgs)
            {
                addAudioReferenceName(result, seen, buildAssetReferenceName(parent.getName(), img.getName()));
            }
        }
        return result;
    }

    private void addAudioReferenceName(List<String> result, Set<String> seen, String roleReferenceName)
    {
        if (StrUtil.isBlank(roleReferenceName))
        {
            return;
        }
        String audioName = "音频-" + roleReferenceName.trim();
        if (seen.add(audioName))
        {
            result.add(audioName);
        }
    }

    private List<String> loadVideoReferenceNames(Long projectId, Long episodeId, Long userId)
    {
        if (Objects.isNull(projectId) || Objects.isNull(episodeId) || Objects.isNull(userId))
        {
            return new ArrayList<>();
        }
        List<AidGenRecord> records = aidGenRecordService.list(
                Wrappers.<AidGenRecord>lambdaQuery()
                        .select(AidGenRecord::getId,
                                AidGenRecord::getStoryboardId,
                                AidGenRecord::getGenType,
                                AidGenRecord::getFileUrl,
                                AidGenRecord::getCreateTime)
                        .eq(AidGenRecord::getProjectId, projectId)
                        .eq(AidGenRecord::getEpisodeId, episodeId)
                        .eq(AidGenRecord::getUserId, userId)
                        .eq(AidGenRecord::getStatus, GEN_STATUS_SUCCESS)
                        .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL)
                        .in(AidGenRecord::getGenType, List.of(GEN_TYPE_I2V, GEN_TYPE_MULTI, GEN_TYPE_EDGE,
                                GEN_TYPE_COMPOSE, GEN_TYPE_UPLOAD_VIDEO))
                        .isNotNull(AidGenRecord::getFileUrl)
                        .ne(AidGenRecord::getFileUrl, "")
                        .orderByDesc(AidGenRecord::getCreateTime)
                        .last("LIMIT 30"));
        if (CollectionUtil.isEmpty(records))
        {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AidGenRecord record : records)
        {
            String name = buildVideoReferenceName(record);
            if (StrUtil.isNotBlank(name) && seen.add(name))
            {
                result.add(name);
            }
        }
        return result;
    }

    private String buildVideoReferenceName(AidGenRecord record)
    {
        if (Objects.isNull(record) || Objects.isNull(record.getId()))
        {
            return "";
        }
        String type = StrUtil.blankToDefault(record.getGenType(), "video");
        if (Objects.nonNull(record.getStoryboardId()))
        {
            return "视频-分镜" + record.getStoryboardId() + "-" + type;
        }
        return "视频-素材" + record.getId() + "-" + type;
    }

    @SafeVarargs
    private final List<String> mergeReferenceWhitelists(List<String>... parts)
    {
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (parts == null)
        {
            return result;
        }
        for (List<String> part : parts)
        {
            if (CollectionUtil.isEmpty(part))
            {
                continue;
            }
            for (String name : part)
            {
                String trimmed = StrUtil.trim(name);
                if (StrUtil.isNotBlank(trimmed) && seen.add(trimmed))
                {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    /**
     * 按主资产 ID 集合一次性加载 is_use=1 的 form_image 列表，按 assetId 分组返回。
     *
     * @param assetIds  主资产 ID 集合（character / prop 通用）
     * @param episodeId 剧集 ID（防越权）
     * @param userId    当前用户 ID（防越权）
     * @return Map，按 sort_order 升序
     */
    private Map<Long, List<AidRolePropSceneFormImage>> loadInUseFormImagesByAssets(
            Set<Long> assetIds, Long episodeId, Long userId)
    {
        if (CollectionUtil.isEmpty(assetIds) || Objects.isNull(userId))
        {
            return Collections.emptyMap();
        }
        // 防漏字段：select 列表中需包含字典构造所需的全部字段
        List<AidRolePropSceneFormImage> imgs = rpsFormImageService.list(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getId,
                                AidRolePropSceneFormImage::getAssetId,
                                AidRolePropSceneFormImage::getFormId,
                                AidRolePropSceneFormImage::getName,
                                AidRolePropSceneFormImage::getSourceType,
                                AidRolePropSceneFormImage::getSortOrder)
                        .in(AidRolePropSceneFormImage::getAssetId, assetIds)
                        .eq(AidRolePropSceneFormImage::getEpisodeId, episodeId)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getIsUse, IS_USE_YES)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidRolePropSceneFormImage::getSortOrder));
        if (CollectionUtil.isEmpty(imgs))
        {
            return Collections.emptyMap();
        }
        return imgs.stream().collect(Collectors.groupingBy(AidRolePropSceneFormImage::getAssetId));
    }

    /**
     * 加载场景资产下所有 is_use=1 + is_split_child=1 的方位子图，提取后缀（主视/反打/左立面/右立面）集合。
     *
     * @param sceneAssetId 场景主资产 ID
     * @param episodeId    剧集 ID（防越权）
     * @param userId       当前用户 ID（防越权）
     * @return 实际可引用的方位标签集合，可能为空（场景未拆分 / 没有任何 is_use=1 子图）
     */
    private Set<String> loadAvailableSceneDirectionLabels(Long sceneAssetId, Long episodeId, Long userId)
    {
        if (Objects.isNull(sceneAssetId) || Objects.isNull(userId))
        {
            return Collections.emptySet();
        }
        // 防漏字段：仅 select name 列（用于解析方位后缀），后续需要其他字段请同步扩展
        List<AidRolePropSceneFormImage> imgs = rpsFormImageService.list(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getName)
                        .eq(AidRolePropSceneFormImage::getAssetId, sceneAssetId)
                        .eq(AidRolePropSceneFormImage::getEpisodeId, episodeId)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getIsUse, IS_USE_YES)
                        .eq(AidRolePropSceneFormImage::getIsSplitChild, IS_SPLIT_FLAG_YES)
                        .eq(AidRolePropSceneFormImage::getIsSplitSource, IS_SPLIT_FLAG_NO)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL));
        if (CollectionUtil.isEmpty(imgs))
        {
            return Collections.emptySet();
        }
        Set<String> labels = new LinkedHashSet<>();
        for (AidRolePropSceneFormImage img : imgs)
        {
            String label = extractDirectionLabel(img.getName());
            if (StrUtil.isNotBlank(label))
            {
                labels.add(label);
            }
        }
        return labels;
    }

    /**
     * 加载场景资产下所有 is_use=1 的"未拆分整图"列表。
     *
     * @param sceneAssetId 场景主资产 ID
     * @param episodeId    剧集 ID（防越权）
     * @param userId       当前用户 ID（防越权）
     * @return is_use=1 的整张场景图列表（按 sort_order 升序），无则空列表
     */
    private List<AidRolePropSceneFormImage> loadInUseUnsplitSceneImages(
            Long sceneAssetId, Long episodeId, Long userId)
    {
        if (Objects.isNull(sceneAssetId) || Objects.isNull(userId))
        {
            return Collections.emptyList();
        }
        // 防漏字段：select id / name / sort_order，后续需扩展时请同步
        List<AidRolePropSceneFormImage> imgs = rpsFormImageService.list(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getId,
                                AidRolePropSceneFormImage::getName,
                                AidRolePropSceneFormImage::getSortOrder)
                        .eq(AidRolePropSceneFormImage::getAssetId, sceneAssetId)
                        .eq(AidRolePropSceneFormImage::getEpisodeId, episodeId)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getIsUse, IS_USE_YES)
                        .eq(AidRolePropSceneFormImage::getIsSplitChild, IS_SPLIT_FLAG_NO)
                        .eq(AidRolePropSceneFormImage::getIsSplitSource, IS_SPLIT_FLAG_NO)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidRolePropSceneFormImage::getSortOrder));
        return CollectionUtil.isEmpty(imgs) ? Collections.emptyList() : imgs;
    }

    /**
     * 选取未拆分场景图的代表性引用名。
     */
    private String pickRepresentativeUnsplitName(String sceneName,
                                                  List<AidRolePropSceneFormImage> imgs)
    {
        String fallback = StrUtil.nullToEmpty(sceneName).trim();
        if (CollectionUtil.isEmpty(imgs))
        {
            return fallback;
        }
        for (AidRolePropSceneFormImage img : imgs)
        {
            String n = StrUtil.nullToEmpty(img.getName()).trim();
            if (StrUtil.isNotBlank(n) && Objects.equals(n, fallback))
            {
                return n;
            }
        }
        for (AidRolePropSceneFormImage img : imgs)
        {
            String n = StrUtil.nullToEmpty(img.getName()).trim();
            if (StrUtil.isNotBlank(n))
            {
                return n;
            }
        }
        return fallback;
    }

    /**
     * 拼装"未拆分整张场景图"在字典里的方位说明文本。
     */
    private String buildUnsplitSceneEntry(String entryName)
    {
        // entryName 仅用于日志归因——说明文本本身不依赖实际场景名，避免每镜重复写场景全名让 prompt 膨胀
        return UNSPLIT_SCENE_USAGE_NOTE;
    }

    /**
     * 从 form_image.name 末尾提取方位后缀（仅匹配 KNOWN_DIRECTION_LABELS）。
     * 例：{@code 市图书馆_傍晚_主视} → {@code "主视"}；不带后缀返回空串。
     */
    private String extractDirectionLabel(String name)
    {
        if (StrUtil.isBlank(name)) { return ""; }
        for (String label : KNOWN_DIRECTION_LABELS)
        {
            if (StrUtil.endWith(name, "_" + label))
            {
                return label;
            }
        }
        return "";
    }

    /**
     * 从 LLM 原始输出中提取顶层 JSON 数组：
     * 1) 优先剥 markdown 代码块标记
     * 2) 用「字符串感知 + 括号配对」找出第一个 [...] 顶层数组
     * 3) 都失败则兜底返回 trim 后原文，让 Jackson 报错（不静默吞）
     */
    private String extractJsonArray(String llmOutput)
    {
        String src = stripJsonCodeFence(llmOutput);
        int start = src.indexOf('[');
        if (start < 0)
        {
            return src;
        }
        int depth = 0;
        boolean inStr = false;
        boolean escape = false;
        for (int i = start; i < src.length(); i++)
        {
            char c = src.charAt(i);
            if (escape) { escape = false; continue; }
            if (inStr)
            {
                if (c == '\\') { escape = true; }
                else if (c == '"') { inStr = false; }
                continue;
            }
            if (c == '"') { inStr = true; }
            else if (c == '[') { depth++; }
            else if (c == ']')
            {
                depth--;
                if (depth == 0)
                {
                    return src.substring(start, i + 1);
                }
            }
        }
        // 配对未闭合，返回原文让 Jackson 抛异常
        return src;
    }

    private String stripJsonCodeFence(String llmOutput)
    {
        String src = llmOutput == null ? "" : llmOutput.trim();
        if (src.startsWith("```"))
        {
            int firstNewline = src.indexOf('\n');
            if (firstNewline > 0)
            {
                src = src.substring(firstNewline + 1);
            }
            if (src.endsWith("```"))
            {
                src = src.substring(0, src.length() - 3).trim();
            }
        }
        return src;
    }

    /**
     * 严格提取顶层 JSON 对象：首字符必须 {、尾字符必须 }，禁止代码块、禁止数组根、禁止前后多文本。
     * 仅处理末尾多关对象符的确定性格式错误，其余格式不符直接抛异常。
     */
    private String extractJsonObject(String llmOutput)
    {
        if (StrUtil.isBlank(llmOutput))
        {
            throw new ServiceException("模型格式异常");
        }
        String trimmed = llmOutput.trim();
        if (trimmed.startsWith("```") || trimmed.startsWith("["))
        {
            log.error("专业版分镜脚本输出含代码块或数组根: head={}", StrUtil.sub(trimmed, 0, 50));
            throw new ServiceException("模型格式异常");
        }
        if (trimmed.charAt(0) != '{' || trimmed.charAt(trimmed.length() - 1) != '}')
        {
            log.error("专业版分镜脚本输出首尾不是大括号: head={}, tail={}",
                    StrUtil.sub(trimmed, 0, 30), StrUtil.sub(trimmed, -30, trimmed.length()));
            throw new ServiceException("模型格式异常");
        }
        return normalizeKnownWriterJsonTail(trimmed);
    }

    /**
     * 只纠正 writer 偶发的尾部多余对象闭合符，候选 JSON 仍需通过 Jackson 严格解析。
     */
    private String normalizeKnownWriterJsonTail(String json)
    {
        int rootEnd = previousNonWhitespaceIndex(json, json.length() - 1);
        int arrayEnd = previousNonWhitespaceIndex(json, rootEnd - 1);
        int extraObjectEnd = previousNonWhitespaceIndex(json, arrayEnd - 1);
        int stringEnd = previousNonWhitespaceIndex(json, extraObjectEnd - 1);
        if (rootEnd < 0 || arrayEnd < 0 || extraObjectEnd < 0 || stringEnd < 0)
        {
            return json;
        }
        if (json.charAt(rootEnd) != '}' || json.charAt(arrayEnd) != ']'
                || json.charAt(extraObjectEnd) != '}' || json.charAt(stringEnd) != '"')
        {
            return json;
        }
        String candidate = json.substring(0, extraObjectEnd) + json.substring(extraObjectEnd + 1);
        try
        {
            OBJECT_MAPPER.readTree(candidate);
            log.warn("专业版分镜脚本输出尾部多余对象闭合符，已按确定性规则纠正: tail={}",
                    StrUtil.sub(json, Math.max(0, json.length() - 60), json.length()));
            return candidate;
        }
        catch (Exception e)
        {
            return json;
        }
    }

    private int previousNonWhitespaceIndex(String text, int start)
    {
        for (int i = start; i >= 0; i--)
        {
            if (!Character.isWhitespace(text.charAt(i)))
            {
                return i;
            }
        }
        return -1;
    }

    private void appendField(StringBuilder sb, JsonNode node, String fieldName, String label)
    {
        JsonNode fieldNode = node.get(fieldName);
        if (fieldNode == null || fieldNode.isNull())
        {
            return;
        }
        String value;
        if (fieldNode.isArray())
        {
            // 数组字段：拍平为 "、" 分隔的中文友好串，避免 toString 残留方括号双引号
            List<String> items = new ArrayList<>();
            fieldNode.forEach(n -> {
                if (n != null && !n.isNull())
                {
                    items.add(n.isTextual() ? n.asText() : n.toString());
                }
            });
            value = String.join("、", items);
        }
        else if (fieldNode.isTextual())
        {
            value = fieldNode.asText();
        }
        else
        {
            value = fieldNode.toString();
        }
        if (StrUtil.isNotBlank(value))
        {
            sb.append(label).append("：").append(value).append("\n");
        }
    }

    /**
     * 从场景 profile_data 提取出场人物名集合，用于过滤角色资产库。
     *
     * 直接读顶层 {@code characters} 字段。
     */
    private Set<String> extractSceneCharacterNames(JsonNode profileNode)
    {
        if (profileNode == null) { return Collections.emptySet(); }
        Set<String> result = new HashSet<>();
        collectCharacterNames(profileNode.get("characters"), result);
        return result;
    }

    /**
     * 把一个 characters JsonNode 的内容（数组或逗号分隔字符串）加入到目标集合中（自动 trim 和去空）。
     */
    private void collectCharacterNames(JsonNode chars, Set<String> result)
    {
        if (chars == null || chars.isNull()) { return; }
        if (chars.isArray())
        {
            chars.forEach(n -> {
                if (n != null && !n.isNull())
                {
                    String name = n.isTextual() ? n.asText() : n.toString();
                    if (StrUtil.isNotBlank(name))
                    {
                        result.add(name.trim());
                    }
                }
            });
        }
        else if (chars.isTextual())
        {
            // 兜底：万一是字符串（"沈宴, 方蕴秋"）也支持
            String text = chars.asText();
            for (String s : text.split("[,，、]"))
            {
                String t = s.trim();
                if (!t.isEmpty())
                {
                    result.add(t);
                }
            }
        }
    }

    /**
     * 解析 LLM 输出的 18 字段 JSON 数组为 AidStoryboard 列表（不落库）。
     *
     * 每条分镜带上 batchId（来自 aid_storyboard_batch.id）和 sourceSceneCode
     * （从 LLM 输出的 sceneCode 字段读取，用于全局二级排序）。
     */
    @SuppressWarnings("unchecked")
    private List<AidStoryboard> parseShotsToEntities(String llmOutput, Long projectId, Long episodeId,
                                                      Long userId, Long sourceSceneId, int startSortOrder,
                                                      Long batchId, String canonicalSceneCode,
                                                      List<String> referenceWhitelist)
    {
        String jsonStr = extractJsonArray(llmOutput);

        List<Map<String, Object>> shots;
        try
        {
            shots = OBJECT_MAPPER.readValue(jsonStr,
                    OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
        }
        catch (Exception e)
        {
            log.error("分镜脚本JSON解析失败: output={}", StrUtil.sub(jsonStr, 0, 200), e);
            throw new ServiceException("模型返回异常");
        }

        if (CollectionUtil.isEmpty(shots)) { return new ArrayList<>(); }

        // 合并相邻且剧本内容完全相同的镜头，避免重复拆分同一段文本。
        shots = mergeShotsBySameScriptContent(shots);

        List<AidStoryboard> result = new ArrayList<>(shots.size());
        int sortOrder = startSortOrder;
        int innerPos = 0;
        for (Map<String, Object> shot : shots)
        {
            sortOrder++;
            innerPos++;
            AidStoryboard sb = new AidStoryboard();
            sb.setProjectId(projectId);
            sb.setEpisodeId(episodeId);
            sb.setUserId(userId);
            sb.setSourceSceneId(sourceSceneId);
            sb.setSortOrder((long) sortOrder);

            // 分镜携带 batchId / sourceSceneCode，用于断点续生定位 + 全局二级排序
            sb.setBatchId(batchId);
            String llmSceneCode = str(shot, "sceneCode");
            // 按场次：本批唯一 sceneCode 为权威归属码，强制覆盖 LLM 输出（漏填 / 乱填都归一），
            //   保证 sourceSceneCode 与"场次序号"和实际场次一致，不污染归属与排序
            String effectiveSceneCode = StrUtil.isNotBlank(canonicalSceneCode) ? canonicalSceneCode : llmSceneCode;
            if (StrUtil.isNotBlank(canonicalSceneCode) && StrUtil.isNotBlank(llmSceneCode)
                    && !Objects.equals(canonicalSceneCode, llmSceneCode))
            {
                log.warn("LLM 输出 sceneCode 与本批不符，已强制归一: batchId={}, expected={}, got={}",
                        batchId, canonicalSceneCode, llmSceneCode);
            }
            if (StrUtil.isNotBlank(effectiveSceneCode))
            {
                sb.setSourceSceneCode(effectiveSceneCode);
            }

            // 全局序号统一编号，避免不同 scene 产生重复 title
            sb.setTitle(String.format("分镜脚本%03d", sortOrder));
            // 台词若存在则同步写入 dialogue_text 专用列（下游 TTS/配音直接读该列），
            // script_params.台词 同时保留，双写兼容；无台词则置空
            String dialogueText = str(shot, "dialogue");
            sb.setDialogueText(StrUtil.isNotBlank(dialogueText) ? dialogueText : null);

            // script_params 改用中文 key 存储，便于 DB 直接阅读 / 前端无需翻译表
            // 字段顺序与 LLM 输出一致；llmInnerPosition 是后端注入的批内位置（用于二级排序）
            Map<String, Object> extra = new LinkedHashMap<>();
            // 镜号是项目/剧集内的全局编号镜像，必须与 sort_order 一致；LLM 返回的 shotNumber
            // 会在每个场次内从 001 重新开始，不能直接作为落库镜号。
            extra.put("镜号", formatGlobalShotNumber(sortOrder));
            // 场次序号同样以本批权威 sceneCode 为准（覆盖 LLM 输出），与 sourceSceneCode 保持一致
            if (StrUtil.isNotBlank(effectiveSceneCode))
            {
                extra.put("场次序号", effectiveSceneCode);
            }
            extra.put("批内位置", innerPos);
            // 剧本内容：LLM 提示词约定的英文 key 是 scriptContent（详见提示词
            //   "## 输出模板" 节）。该字段是该镜头对应的原文片段（逐字保留），下游分镜
            //   画师智能体的"输入来源 1: 分镜脚本"里把它列为入参，用作叙事背景参考、
            //   纯字幕识别和"一句话概括"剧情参考。之前漏接，导致下游入参字段缺失。
            putNonBlankWithKey(extra, "剧本内容",      shot, "scriptContent");
            putNonBlankWithKey(extra, "画面描述",      shot, "visualDescription");
            putNonBlankWithKey(extra, "台词",          shot, "dialogue");
            putNonBlankWithKey(extra, "动作状态",      shot, "actionState");
            putNonBlankWithKey(extra, "叙事功能",      shot, "narrativeFunction");
            putNonBlankWithKey(extra, "时间坐标",      shot, "timeOfDay");
            putNonBlankWithKey(extra, "年代坐标",      shot, "eraCoordinate");
            putNonBlankWithKey(extra, "日期坐标",      shot, "dateCoordinate");
            putNonBlankWithKey(extra, "气候天象",      shot, "weather");
            // 引用信息：落库前清洗 LLM 杜撰的字典外引用（全局兜底，防止下游出图前置校验「资产失效」整批失败）
            Object refRaw = shot.get("referenceAssets");
            String refStr = Objects.isNull(refRaw) ? null : String.valueOf(refRaw);
            if (StrUtil.isNotBlank(refStr))
            {
                ReferenceAssetSanitizer.Result refResult = ReferenceAssetSanitizer.sanitize(refStr, referenceWhitelist);
                if (refResult.hasRemoval())
                {
                    log.warn("分镜脚本引用信息清洗：剔除字典外杜撰引用 batchId={}, sortOrder={}, removed={}",
                            batchId, sortOrder, refResult.getRemoved());
                }
                if (StrUtil.isNotBlank(refResult.getText()))
                {
                    extra.put("引用信息", refResult.getText());
                }
            }
            putNonBlankWithKey(extra, "景别",          shot, "shotSize");
            putNonBlankWithKey(extra, "拍摄角度",      shot, "cameraAngle");
            putNonBlankWithKey(extra, "镜头焦距",      shot, "focalLength");
            putNonBlankWithKey(extra, "镜头运动",      shot, "cameraMovement");
            putNonBlankWithKey(extra, "构图",          shot, "composition");
            putNonBlankWithKey(extra, "画面氛围",      shot, "atmosphere");
            // 字典枚举字段（来自 aid_prompt_lib：color_tone / lighting / exposure_blur）
            putNonBlankWithKey(extra, "色彩倾向",      shot, "colorTone");
            putNonBlankWithKey(extra, "光线",          shot, "lighting");
            putNonBlankWithKey(extra, "曝光虚化",      shot, "exposureBlur");
            putNonBlankWithKey(extra, "音效",          shot, "soundEffect");
            try { sb.setScriptParams(OBJECT_MAPPER.writeValueAsString(extra)); }
            catch (Exception ignore) { sb.setScriptParams("{}"); }

            // story_script 改为人类可读的中文多行文本：与 script_params 同一份字段数据，
            // 但渲染为 "字段名：值" 多行形式，便于直接在 DB / 编辑器里阅读，无需手动解 JSON。
            sb.setStoryScript(renderShotAsReadableText(extra));

            sb.setDelFlag(DEL_FLAG_NORMAL);
            sb.setCreateTime(DateUtils.getNowDate());
            sb.setUpdateTime(DateUtils.getNowDate());
            sb.setCreateBy(String.valueOf(userId));
            // 显式置空 BaseEntity 的 remark，避免某些 ORM 路径误把其它内容路由到 remark 列
            sb.setRemark(null);
            result.add(sb);
        }
        return result;
    }

    /**
     * 解析专业版/宫格分镜编剧（{@code aid_storyboard_writer}）输出的单个「镜头组」JSON 对象为 AidStoryboard 列表（不落库）。
     */
    @SuppressWarnings("unchecked")
    private List<AidStoryboard> parseShotGroupsToEntities(String llmOutput, Long projectId, Long episodeId,
                                                          Long userId, Long sourceSceneId, int startSortOrder,
                                                          Long batchId, String canonicalSceneCode,
                                                          List<String> referenceWhitelist,
                                                          String groupCode, Integer groupIndex)
    {
        String jsonStr = extractJsonObject(llmOutput);

        Map<String, Object> group;
        try
        {
            group = OBJECT_MAPPER.readValue(jsonStr,
                    OBJECT_MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        }
        catch (Exception e)
        {
            log.error("分镜镜头组JSON解析失败: output={}", StrUtil.sub(jsonStr, 0, 200), e);
            throw new ServiceException("模型格式异常");
        }
        if (CollectionUtil.isEmpty(group))
        {
            log.error("分镜镜头组JSON为空: output={}", StrUtil.sub(jsonStr, 0, 200));
            throw new ServiceException("模型格式异常");
        }
        if (group.size() != 1 || !group.containsKey("content"))
        {
            log.error("分镜镜头组根字段异常: batchId={}, fieldCount={}, keys={}",
                    batchId, group.size(), group.keySet());
            throw new ServiceException("模型格式异常");
        }

        List<AidStoryboard> result = new ArrayList<>(1);
        int sortOrder = startSortOrder;
        int innerPos = 1;
        // 取 content 字符串数组（10 项 "字段名：字段值"）
        Object contentObj = group.get("content");
        if (!(contentObj instanceof List<?> contentList) || contentList.isEmpty())
        {
            log.error("镜头组缺少 content 数组: batchId={}, output={}", batchId, StrUtil.sub(jsonStr, 0, 200));
            throw new ServiceException("模型格式异常");
        }
        // 按冒号切分为 key→value（值内允许再含冒号，如「镜头脚本」里的「景别：全景」，故只切首个冒号）
        Map<String, String> fields = new LinkedHashMap<>();
        for (Object lineObj : contentList)
        {
            if (!(lineObj instanceof String line) || StrUtil.isBlank(line)) { continue; }
            // 优先全角冒号 ：；找不到再兜底半角冒号 :（LLM 中英混排偶发吐半角，避免整行字段被静默丢弃）
            int idx = line.indexOf('：');
            if (idx < 0) { idx = line.indexOf(':'); }
            if (idx <= 0)
            {
                log.warn("镜头组字段行无法切出 key(无冒号或冒号在行首), 已跳过: batchId={}, line={}",
                        batchId, StrUtil.sub(line, 0, 40));
                continue;
            }
            String key = line.substring(0, idx).trim();
            String val = line.substring(idx + 1).trim();
            if (StrUtil.isNotBlank(key)) { fields.putIfAbsent(key, val); }
        }
        if (fields.isEmpty())
        {
            log.error("镜头组 content 无有效字段: batchId={}, output={}", batchId, StrUtil.sub(jsonStr, 0, 200));
            throw new ServiceException("模型格式异常");
        }
        // 10 字段强校验：缺任意项直接失败，不落库成功
        for (String required : REQUIRED_WRITER_FIELDS)
        {
            if (StrUtil.isBlank(fields.get(required)))
            {
                log.error("镜头组缺少必填字段: batchId={}, missingField={}, 镜头组={}, contentSize={}",
                        batchId, required, fields.get("镜头组"), contentList.size());
                throw new ServiceException("模型格式异常");
            }
        }

        sortOrder++;
        AidStoryboard sb = new AidStoryboard();
        sb.setProjectId(projectId);
        sb.setEpisodeId(episodeId);
        sb.setUserId(userId);
        sb.setSourceSceneId(sourceSceneId);
        sb.setSortOrder((long) sortOrder);
        sb.setBatchId(batchId);
        // 按场次：本批唯一 sceneCode 为权威归属码（专业版镜头组不带 sceneCode，统一用本批的）
        if (StrUtil.isNotBlank(canonicalSceneCode))
        {
            sb.setSourceSceneCode(canonicalSceneCode);
        }
        sb.setTitle(String.format("分镜脚本%03d", sortOrder));
        // 台词若存在则同步写入 dialogue_text 专用列（下游 TTS/配音直接读该列），
        // script_params.台词 同时保留，双写兼容（与标准版一致）；无台词则置空
        String dialogueText = fields.get("台词");
        sb.setDialogueText(StrUtil.isNotBlank(dialogueText) ? dialogueText : null);

        // script_params：中文 key 落库（10 字段原样 + 场次序号/批内位置，便于下游消费与全局二级排序）
        Map<String, Object> extra = new LinkedHashMap<>();
        // 专业镜头组同样写入全局镜号，保证所有创作模式使用同一编号契约。
        extra.put("镜号", formatGlobalShotNumber(sortOrder));
        putNonBlankStr(extra, "镜头组", fields.get("镜头组"));
        if (StrUtil.isNotBlank(canonicalSceneCode))
        {
            extra.put("场次序号", canonicalSceneCode);
        }
        extra.put("批内位置", innerPos);
        // 镜头组拆分计划信息（便于下游按镜头组维度消费与展示编码 4-1 格式）
        if (StrUtil.isNotBlank(groupCode))
        {
            putNonBlankStr(extra, "镜头组编号", groupCode);
        }
        if (Objects.nonNull(groupIndex))
        {
            extra.put("镜头组序号", groupIndex);
        }
        putNonBlankStr(extra, "剧本内容", fields.get("剧本内容"));
        putNonBlankStr(extra, "画面说明", fields.get("画面说明"));
        putNonBlankStr(extra, "台词", fields.get("台词"));
        putNonBlankStr(extra, "时空环境", fields.get("时空环境"));
        // 引用信息：落库前清洗 LLM 杜撰的字典外引用（全局兜底，与标准版同口径）
        String refGroup = fields.get("引用信息");
        if (StrUtil.isNotBlank(refGroup))
        {
            ReferenceAssetSanitizer.Result refResult = ReferenceAssetSanitizer.sanitize(refGroup, referenceWhitelist);
            if (refResult.hasRemoval())
            {
                log.warn("分镜脚本引用信息清洗(专业版/宫格)：剔除字典外杜撰引用 batchId={}, removed={}",
                        batchId, refResult.getRemoved());
            }
            putNonBlankStr(extra, "引用信息", refResult.getText());
        }
        putNonBlankStr(extra, "镜头模式", fields.get("镜头模式"));
        putNonBlankStr(extra, "运镜等级", fields.get("运镜等级"));
        putNonBlankStr(extra, "时长估算", fields.get("时长估算"));
        // 镜头脚本：多分段明文整段（下游按专业版格式再解析），原样保留
        putNonBlankStr(extra, "镜头脚本", fields.get("镜头脚本"));
        try { sb.setScriptParams(OBJECT_MAPPER.writeValueAsString(extra)); }
        catch (Exception ignore) { sb.setScriptParams("{}"); }

        sb.setStoryScript(renderShotAsReadableText(extra));

        sb.setDelFlag(DEL_FLAG_NORMAL);
        sb.setCreateTime(DateUtils.getNowDate());
        sb.setUpdateTime(DateUtils.getNowDate());
        sb.setCreateBy(String.valueOf(userId));
        sb.setRemark(null);
        result.add(sb);
        return result;
    }

    /** 非空则按中文 key 存入 script_params map（专业版镜头组落库用，值已是字符串）。 */
    private void putNonBlankStr(Map<String, Object> target, String key, String value)
    {
        if (StrUtil.isNotBlank(value))
        {
            target.put(key, value);
        }
    }

    /**
     * 整批落库：先硬删除旧分镜（仅当 overwrite=true 且批内有成功项），再批量写入新分镜。
     */
    public void persistAllShots(Long projectId, Long episodeId, Long userId,
                                 List<Long> sceneIds,
                                 List<AidStoryboard> shots, boolean overwrite, boolean selective)
    {
        // 全局二级排序（落库前）
        //   主排序：sourceSceneCode ASC（剧情时间线，闪回剧本中同空间多次切回也按 sceneCode 自然交错）
        //   次排序：保持原 sortOrder（来自 LLM 输出顺序 + globalSortOrder 累加，已是正确的同 sceneCode 内序列）
        //   排序后重新分配连续 sortOrder = startSort + 1, +2, ...
        if (CollectionUtil.isNotEmpty(shots))
        {
            // 计算起始 sortOrder：取本次 shots 列表中的最小 sortOrder - 1
            long minSort = shots.stream()
                    .map(AidStoryboard::getSortOrder)
                    .filter(Objects::nonNull)
                    .min(Long::compareTo)
                    .orElse(1L);
            long startSort = Math.max(0L, minSort - 1L);

            shots.sort((a, b) -> {
                String ca = StrUtil.blankToDefault(a.getSourceSceneCode(), "");
                String cb = StrUtil.blankToDefault(b.getSourceSceneCode(), "");
                int cmp = ca.compareTo(cb);
                if (cmp != 0) { return cmp; }
                Long sa = a.getSortOrder() == null ? 0L : a.getSortOrder();
                Long sb = b.getSortOrder() == null ? 0L : b.getSortOrder();
                return sa.compareTo(sb);
            });

            long seq = startSort;
            for (AidStoryboard sb : shots)
            {
                seq++;
                synchronizeGeneratedStoryboardNumber(sb, seq);
            }
        }

        transactionTemplate.executeWithoutResult(status -> {
            if (overwrite)
            {
                deleteShotsForOverwrite(projectId, episodeId, userId, sceneIds, selective);
            }
            // 显式指定 batchSize=200，避免单批 SQL 超长
            storyboardService.saveBatch(shots, 200);
        });
        log.info("分镜脚本批量落库完成: projectId={}, episodeId={}, count={}, overwrite={}, selective={}",
                projectId, episodeId, shots.size(), overwrite, selective);
    }

    /**
     * 同步生成分镜的全局编号字段，保证 sort_order、默认标题和 script_params.镜号三者一致。
     */
    private void synchronizeGeneratedStoryboardNumber(AidStoryboard storyboard, long sortOrder)
    {
        storyboard.setSortOrder(sortOrder);
        storyboard.setTitle(String.format("分镜脚本%03d", sortOrder));
        Map<String, Object> params = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(storyboard.getScriptParams()))
        {
            try
            {
                params = OBJECT_MAPPER.readValue(storyboard.getScriptParams(),
                        OBJECT_MAPPER.getTypeFactory().constructMapType(
                                LinkedHashMap.class, String.class, Object.class));
            }
            catch (Exception e)
            {
                log.error("分镜编号同步失败, storyboardId={}, sortOrder={}",
                        storyboard.getId(), sortOrder, e);
                throw new ServiceException("分镜编号失败");
            }
        }
        params.put("镜号", formatGlobalShotNumber(sortOrder));
        try
        {
            storyboard.setScriptParams(OBJECT_MAPPER.writeValueAsString(params));
        }
        catch (Exception e)
        {
            log.error("分镜编号序列化失败, storyboardId={}, sortOrder={}",
                    storyboard.getId(), sortOrder, e);
            throw new ServiceException("分镜编号失败");
        }
    }

    /** 全局分镜编号统一补齐三位数，超过三位时保留完整数值。 */
    private String formatGlobalShotNumber(long sortOrder)
    {
        return String.format("%03d", sortOrder);
    }

    /**
     * 覆盖式生成的「一次性删除」：把该剧集（或本次涉及场景）已落库的旧分镜硬删除。
     *
     * @param sceneIds  本次涉及的场景ID集合（selective 时按此过滤删除范围）
     * @param selective true=仅删本次涉及场景（sceneIds）的旧分镜；false=删整集旧分镜
     */
    private void deleteShotsForOverwrite(Long projectId, Long episodeId, Long userId,
                                         List<Long> sceneIds, boolean selective)
    {
        LambdaQueryWrapper<AidStoryboard> deleteWrapper = Wrappers.lambdaQuery();
        deleteWrapper.eq(AidStoryboard::getProjectId, projectId);
        deleteWrapper.eq(AidStoryboard::getEpisodeId, episodeId);
        deleteWrapper.eq(AidStoryboard::getUserId, userId);
        if (selective && CollectionUtil.isNotEmpty(sceneIds))
        {
            // 仅删除本次涉及场景的旧分镜，避免影响其它场景的历史数据
            deleteWrapper.in(AidStoryboard::getSourceSceneId, sceneIds);
        }
        boolean removed = storyboardService.remove(deleteWrapper);
        log.info("分镜脚本覆盖硬删除旧分镜完成: projectId={}, episodeId={}, selective={}, removed={}",
                projectId, episodeId, selective, removed);
    }

    private String str(Map<String, Object> map, String key)
    {
        Object val = map.get(key);
        return val == null ? "" : String.valueOf(val);
    }

    /**
     * 把一个角色资产的所有 is_use=1 form_image 转成"[引用名]"或"[引用名]（多方位设定卡...）"
     * 字典条目，追加到 names 列表。
     */
    private void appendCharacterDictEntries(AidRolePropScene parent,
                                             List<AidRolePropSceneFormImage> imgs,
                                             List<String> names)
    {
        if (Objects.isNull(parent) || CollectionUtil.isEmpty(imgs))
        {
            // 上层已经过滤 parent != null，这里主要捕"该角色 is_use=1 图为空"的情况
            log.info("分镜脚本字典-角色 form_image 为空: assetId={}, assetName={}, imgsNull={}",
                    Objects.isNull(parent) ? null : parent.getId(),
                    Objects.isNull(parent) ? null : parent.getName(),
                    CollectionUtil.isEmpty(imgs));
            return;
        }
        for (AidRolePropSceneFormImage img : imgs)
        {
            String refName = buildAssetReferenceName(parent.getName(), img.getName());
            if (StrUtil.isBlank(refName))
            {
                log.warn("分镜脚本字典-角色 form_image refName 为空: assetId={}, assetName={}, imgId={}, imgName={}",
                        parent.getId(), parent.getName(), img.getId(), img.getName());
                continue;
            }
            String entry = "[" + refName + "]";
            // 以 _角色设定 结尾或 sourceType=ai_builder 的 form_image 是 21:9 多方位拼接卡，
            // 明确标注让 LLM 优先引用
            if (StrUtil.endWith(img.getName(), CHARACTER_CARD_NAME_SUFFIX)
                    || FORM_IMAGE_SOURCE_TYPE_BUILDER.equalsIgnoreCase(img.getSourceType()))
            {
                entry += "（多方位设定卡，可作为角色多角度参考图）";
            }
            names.add(entry);
            // 单条字典条目命中日志，便于追溯"林深为什么不在/在字典里"
            log.info("分镜脚本字典-角色条目命中: assetId={}, assetName={}, imgId={}, imgName={}, sourceType={}, entry={}",
                    parent.getId(), parent.getName(), img.getId(), img.getName(),
                    img.getSourceType(), entry);
        }
    }

    /**
     * 角色名宽松匹配。
     */
    private boolean looselyMatchCharacterName(String assetName, Set<String> sceneCharacterNames)
    {
        if (StrUtil.isBlank(assetName) || CollectionUtil.isEmpty(sceneCharacterNames))
        {
            return false;
        }
        String an = assetName.trim();
        if (StrUtil.isBlank(an))
        {
            return false;
        }
        for (String scn : sceneCharacterNames)
        {
            if (StrUtil.isBlank(scn))
            {
                continue;
            }
            String s = scn.trim();
            if (s.isEmpty())
            {
                continue;
            }
            if (Objects.equals(an, s))
            {
                return true;
            }
            // 双向 contains：asset.name 在 plot 角色名内 / plot 角色名在 asset.name 内
            if (s.contains(an) || an.contains(s))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 把 source[sourceKey] 的非空值用 targetKey 写入 target。
     * 用于把 LLM 输出的英文字段名（如 visualDescription）翻译成中文 key（如 画面描述）后存入 script_params JSON。
     */
    private void putNonBlankWithKey(Map<String, Object> target, String targetKey,
                                     Map<String, Object> source, String sourceKey)
    {
        Object val = source.get(sourceKey);
        if (val != null && StrUtil.isNotBlank(String.valueOf(val)))
        {
            target.put(targetKey, String.valueOf(val));
        }
    }

    /**
     * 合并相邻 scriptContent 完全相同的镜头（后端兜底，与提示词"剧本内容唯一性"硬规则呼应）。
     */
    private List<Map<String, Object>> mergeShotsBySameScriptContent(List<Map<String, Object>> shots)
    {
        if (CollectionUtil.isEmpty(shots) || shots.size() < 2)
        {
            return shots;
        }
        List<Map<String, Object>> merged = new ArrayList<>(shots.size());
        Map<String, Object> previous = null;
        String prevScript = null;
        int mergedCount = 0;
        for (Map<String, Object> shot : shots)
        {
            String currentScript = StrUtil.trimToEmpty(str(shot, "scriptContent"));
            // 与上一镜剧本内容一字不差相同 → 合并到上一镜
            if (Objects.nonNull(previous)
                    && StrUtil.isNotBlank(currentScript)
                    && StrUtil.isNotBlank(prevScript)
                    && Objects.equals(currentScript, prevScript))
            {
                String currentDialogue = StrUtil.trimToEmpty(str(shot, "dialogue"));
                String prevDialogue = StrUtil.trimToEmpty(str(previous, "dialogue"));
                if (StrUtil.isNotBlank(currentDialogue))
                {
                    String combined = StrUtil.isBlank(prevDialogue)
                            ? currentDialogue
                            : prevDialogue + "\n" + currentDialogue;
                    previous.put("dialogue", combined);
                }
                mergedCount++;
                // 当前镜整体丢弃，不进 merged 列表
                continue;
            }
            merged.add(shot);
            previous = shot;
            prevScript = currentScript;
        }
        if (mergedCount > 0)
        {
            log.info("分镜脚本兜底合并: 检测到 {} 个相邻 scriptContent 复读镜，已合并到首镜并保留台词；原镜数={}, 合并后镜数={}",
                    mergedCount, shots.size(), merged.size());
        }
        return merged;
    }

    /**
     * 把分镜的中文 key 字段渲染为人类可读的多行文本，写入 {@code aid_storyboard.story_script}。
     * 与 {@code script_params} 同源同字段顺序，差别仅在于呈现形式：JSON → "字段名：值\n字段名：值..."。
     * 跳过 {@code 镜号} / {@code 场次序号} / {@code 批内位置}：前两者属于元数据编号（已存储于
     * {@code source_scene_code} / {@code script_params}），第三者仅供后端二级排序用，对编辑/审阅无意义。
     */
    private String renderShotAsReadableText(Map<String, Object> shot)
    {
        if (shot == null || shot.isEmpty()) { return ""; }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : shot.entrySet())
        {
            String key = entry.getKey();
            // 镜号 / 场次序号：编号类元数据，已经在 source_scene_code / script_params 里有据可查，
            //                  在 story_script 这种"对人阅读"的文本里再写一遍只会干扰阅读，跳过
            if ("镜号".equals(key) || "场次序号".equals(key)) { continue; }
            // 批内位置仅供后端二级排序使用，对人类阅读无意义，跳过
            if ("批内位置".equals(key)) { continue; }
            Object val = entry.getValue();
            String strVal = (val == null) ? "" : String.valueOf(val);
            if (StrUtil.isBlank(strVal)) { continue; }
            if (sb.length() > 0) { sb.append('\n'); }
            sb.append(key).append("：").append(strVal);
        }
        return sb.toString();
    }

    private String buildBatchResultJson(int total, int successCount, int failCount,
                                         List<Map<String, Object>> successItems,
                                         List<Map<String, Object>> failedItems)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", total);
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("successItems", successItems);
        result.put("failedItems", failedItems);
        try { return OBJECT_MAPPER.writeValueAsString(result); }
        catch (Exception e) { return "{}"; }
    }
}
