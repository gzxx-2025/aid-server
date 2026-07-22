package com.aid.rps.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.agent.IAidAgentService;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.enums.BillingConstants;
import com.aid.billing.service.BillingAmountCalculator;
import com.aid.common.aid.rocketmq.config.RocketMqConfigManager;
import com.aid.common.aid.rocketmq.core.MqTemplateFactory;
import com.aid.common.aid.rocketmq.entity.MqResult;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.dto.ExtractTaskMessage;
import com.aid.rps.helper.AssetExtractHelper;
import com.aid.rps.helper.ProjectGenerateLockGuard;
import com.aid.rps.resolver.ReferenceAssetSanitizer;
import com.aid.rps.resolver.StoryboardImageReferenceResolver;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.service.IExtractBillingService;
import com.aid.rps.service.IStoryboardImagePromptService;
import com.aid.rps.sse.AssetExtractSseManager;
import com.aid.service.IAiModelConfigService;
import com.aid.storyboard.dto.ChainTriggerResult;
import com.aid.storyboard.service.impl.StoryboardStepChainService;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 批量生成"分镜图脚本"（图生图 prompt）实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class StoryboardImagePromptServiceImpl implements IStoryboardImagePromptService
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_QUEUED = "QUEUED";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
    private static final String TASK_STATUS_CANCELLED = "CANCELLED";
    private static final Set<String> RECONNECTABLE_STATUS = Set.of(
            TASK_STATUS_PENDING, TASK_STATUS_QUEUED, TASK_STATUS_PROCESSING,
            TASK_STATUS_SUCCEEDED, TASK_STATUS_PARTIAL_FAILED);

    /** Billing status that can be safely rearmed for another resume round. */
    private static final String BILLING_STATUS_SUCCESS = "SUCCESS";
    private static final String BILLING_STATUS_REFUNDED = "FAILED";

    /** 任务类型常量（与 Consumer / AssetExtractServiceImpl 完全一致） */
    private static final String TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH = "storyboard_image_prompt_batch";

    /** 默认智能体编码 */
    private static final String DEFAULT_AGENT_CODE = "aid_storyboard_script_stylist";

    /** 专业版/宫格分镜画师智能体编码（auto_grid 创作模式命中）：吃「中文镜头组」script_params，产出多宫格生图 prompt */
    private static final String AGENT_CODE_GRID_PAINTER = "aid_storyboard_grid_painter";

    /** form_image 启用标记 / 非拆源标记（与出图解析器 StoryboardImageReferenceResolver 口径一致） */
    private static final Integer IS_USE_YES = 1;
    private static final Integer IS_SPLIT_SOURCE_NO = 0;

    /** 智能体业务分类（必须严格匹配） */
    private static final String BIZ_CATEGORY_MAIN_STORYBOARD_STYLIST = "main_storyboard_stylist";

    /** 模型类型必须为 text */
    private static final String MODEL_TYPE_TEXT = "text";

    /** RocketMQ topic / tag */
    private static final String MQ_TOPIC = "ASSET_EXTRACT_TOPIC";
    private static final String MQ_TAG = "extract";

    /** 业务任务类型（写入 aid_media_task.biz_task_type） */
    private static final String BIZ_TASK_TYPE = "storyboard_image_prompt";

    /** 资产类型常量（用于校验视觉资产库非空） */
    private static final String ASSET_TYPE_CHARACTER = "character";

    /** 续生窗口：24 小时 */
    private static final long RESUME_WINDOW_HOURS = 24L;

    /** LLM 输出 prompt 平均字符数（用于预估输出 token） */
    private static final int ESTIMATED_OUTPUT_CHARS = 1500;

    /** 默认画面比例兜底 */
    private static final String DEFAULT_ASPECT_RATIO = "16:9";

    /** 引用信息中的资产名提取正则：匹配 [name]（name 不含右方括号），与 @图片N[name] 占位的 name 同口径。 */
    private static final Pattern REFERENCE_NAME_PATTERN = Pattern.compile("\\[([^\\]]+)\\]");

    /** 画师输出的参考图占位正则 {@code @图片N[name]}：组1=编号 N，组2=name（与出图解析器同口径）。 */
    private static final Pattern IMAGE_REF_PATTERN = Pattern.compile("@图片(\\d+)\\[([^\\]]+)\\]");

    /** 单个方括号内可能并列多个资产名时的分隔符（顿号/逗号/斜杠/分号），逐名校验，避免 [A、B] 被当成一个名误杀。 */
    private static final Pattern REFERENCE_NAME_SPLIT_PATTERN = Pattern.compile("[、，,/／;；]+");

    /**
     * 引用信息方括号内的"占位 / 无引用"记号，视为本处无引用、不参与失效判定（防止 [无]/[暂无]/[/] 等被误判为失效整批拦截）。
     * 整括号或拆分后的单名命中本集合即跳过。
     */
    private static final Set<String> REFERENCE_PLACEHOLDER_TOKENS = Set.of(
            "无", "暂无", "无引用", "无引用资产", "空", "none", "null", "n/a", "na", "/", "／", "-", "—");

    /** 参考资产失效时，提示文案最多展示的镜号数量，超出以"等"省略。 */
    private static final int MAX_INVALID_SHOT_HINT = 3;

    /** 业务类型：创作（与 StoryboardScriptServiceImpl 保持一致） */
    /**
     * 分镜画师输出 prompt 的固定 key 顺序（共 11 维度）。
     */
    private static final List<String> PROMPT_KEYS_FOR_NEWLINE = List.of(
            "人物表现", "视觉重点", "构图", "景别", "拍摄角度", "镜头焦距",
            "色彩倾向", "光线", "曝光虚化", "画面风格");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    @Autowired
    private IAidStoryboardService storyboardService;

    @Autowired
    private IAidComicProjectService projectService;

    @Autowired
    private IAidExtractTaskService extractTaskService;

    @Autowired
    private IAidRolePropSceneFormService rpsFormService;

    /** form_image 直读：构建"可引用参考图白名单"喂给分镜画师 LLM，约束 @图片N[name] 只能选现存可用资产。 */
    @Autowired
    private IAidRolePropSceneFormImageService rpsFormImageService;

    @Autowired
    private IAidAgentService aidAgentService;

    @Autowired
    private IAiModelConfigService aiModelConfigService;

    /** 项目级配置统一解析器（3 级兜底：用户传 → 项目配置 → aid_config 默认） */
    @Autowired
    private com.aid.projectgenconfig.service.IProjectGenConfigResolver projectGenConfigResolver;

    @Autowired
    private IExtractBillingService extractBillingService;

    @Autowired
    private BillingAmountCalculator billingAmountCalculator;

    @Autowired
    private IAssetExtractService assetExtractService;

    @Autowired
    private AssetExtractHelper helper;

    /** 项目/剧集级锁的安全获取器（含僵尸锁自愈，与 AssetExtractServiceImpl 提取锁同一机制） */
    @Autowired
    private ProjectGenerateLockGuard projectLockGuard;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private MqTemplateFactory mqTemplateFactory;

    /** 任务排队 / 多维并发调度服务 */
    @Autowired
    private com.aid.rps.queue.TaskQueueService taskQueueService;

    @Autowired
    private AssetExtractSseManager sseManager;

    /** 双模式派发路由器（MQ/本地切换唯一收口） */
    @Autowired
    private com.aid.rps.queue.DualModeTaskDispatcher dualModeTaskDispatcher;

    /** 本地派发终态编排器（与 MQ Consumer 共用同一套终态语义） */
    @Autowired
    private com.aid.rps.queue.BatchTaskLocalOrchestrator batchTaskLocalOrchestrator;

    @Autowired
    private StoryboardStepChainService storyboardStepChainService;

    /** 本地编排规格：分镜图提示词 */
    private static final com.aid.rps.queue.BatchTaskLocalOrchestrator.Spec LOCAL_SPEC =
            new com.aid.rps.queue.BatchTaskLocalOrchestrator.Spec(
                    "storyboard_image_prompt", "初始化分镜图脚本批量生成...",
                    "剩余镜头已取消", "部分镜头生成失败，可续生",
                    TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH);
    @Override
    public AssetExtractTaskVO batchGenerateImagePrompt(Long projectId, Long episodeId, Long userId,
                                                       List<Long> storyboardIds,
                                                       String agentCode, String modelCode,
                                                       Boolean overwrite, Map<String, Object> chainNext)
    {
        if (Objects.isNull(projectId) || Objects.isNull(episodeId) || Objects.isNull(userId))
        {
            throw new ServiceException("参数错误");
        }
        // storyboardIds 必填校验（DTO 层已加 @NotEmpty，服务层做兜底防御）
        if (CollectionUtil.isEmpty(storyboardIds))
        {
            log.info("分镜图脚本拒绝：分镜列表为空, projectId={}, episodeId={}, userId={}",
                    projectId, episodeId, userId);
            throw new ServiceException("分镜不能为空");
        }
        // 去重 + 过滤 null（前端可能传入 [null, null] 或重复值）
        List<Long> uniqueStoryboardIds = storyboardIds.stream()
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (CollectionUtil.isEmpty(uniqueStoryboardIds))
        {
            log.info("分镜图脚本拒绝：分镜 ID 全部为空, projectId={}, episodeId={}, userId={}",
                    projectId, episodeId, userId);
            throw new ServiceException("分镜不能为空");
        }
        boolean overwriteFlag = Boolean.TRUE.equals(overwrite);

        AidComicProject project = projectService.selectAidComicProjectById(projectId);
        if (Objects.isNull(project) || !Objects.equals(userId, project.getUserId())
                || !DEL_FLAG_NORMAL.equals(project.getDelFlag()))
        {
            log.info("分镜图脚本拒绝：项目不存在或无权访问, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("项目不存在");
        }

        // 2&3. 统一解析：项目级配置 → aid_config 兜底（3 级链路）。
        //      解析器内部完成：智能体存在 + status=1 + biz_category=main_storyboard_stylist 校验，
        //      模型存在 + model_type=text + 在该 funcCode 模型池内校验。
        com.aid.projectgenconfig.service.ResolvedSceneConfig resolved =
                projectGenConfigResolver.resolve(projectId, episodeId, userId,
                        com.aid.projectgenconfig.enums.ProjectGenConfigScene.STORYBOARD_STYLIST,
                        agentCode, modelCode, null, null);
        agentCode = resolved.getAgentCode();
        String resolvedModelCode = resolved.getModelCode();
        // 智能体 prompt_content 非空校验（resolver 不返回 agent 实体，按解析后的 agentCode 重载一次）
        AidAgent agent = aidAgentService.getByAgentCode(agentCode);
        if (Objects.isNull(agent) || StrUtil.isBlank(agent.getPromptContent()))
        {
            log.error("分镜图脚本智能体 prompt_content 为空: agentCode={}", agentCode);
            throw new ServiceException("智能体配置异常");
        }
        // 计费需要 modelConfig（resolver 已校验存在 + text + 在池内，此处仅取配置对象供预冻结使用）
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(resolvedModelCode);
        if (Objects.isNull(modelConfig))
        {
            log.warn("分镜图脚本模型不存在: modelCode={}", resolvedModelCode);
            throw new ServiceException("模型不存在");
        }

        // 防漏字段注释：仅查 assetId 用作存在性 count。
        // 按项目维度统计（不按集过滤）：剧集角色形态归属项目级（episode_id=0）、
        // 跨集复用资产形态归属其它集；电影模式全部行 episode_id=0 结果不变
        long visualFormCount = rpsFormService.count(
                Wrappers.<AidRolePropSceneForm>lambdaQuery()
                        .eq(AidRolePropSceneForm::getProjectId, projectId)
                        .eq(AidRolePropSceneForm::getUserId, userId)
                        .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL)
                        .isNotNull(AidRolePropSceneForm::getPromptText)
                        .ne(AidRolePropSceneForm::getPromptText, ""));
        if (visualFormCount <= 0)
        {
            log.warn("分镜图脚本拒绝：视觉资产库为空, projectId={}, episodeId={}, userId={}",
                    projectId, episodeId, userId);
            throw new ServiceException("视觉资产库为空，请先生成");
        }

        List<AidStoryboard> storyboardList = loadTargetStoryboards(projectId, episodeId, userId, uniqueStoryboardIds);
        if (CollectionUtil.isEmpty(storyboardList))
        {
            log.warn("分镜图脚本拒绝：镜头列表为空, projectId={}, episodeId={}, userId={}",
                    projectId, episodeId, userId);
            throw new ServiceException("镜头列表为空");
        }

        List<AidStoryboard> targetList = overwriteFlag ? storyboardList : storyboardList.stream()
                .filter(s -> StrUtil.isBlank(s.getImagePrompt()))
                .collect(Collectors.toList());
        if (CollectionUtil.isEmpty(targetList))
        {
            AssetExtractTaskVO reconnect = findReconnectableTask(projectId, episodeId, userId);
            if (Objects.nonNull(reconnect))
            {
                return reconnect;
            }
            log.info("分镜图脚本拒绝：全部已生成且未指定 overwrite, projectId={}, episodeId={}",
                    projectId, episodeId);
            throw new ServiceException("分镜图脚本已生成");
        }

        // 创建任务前校验分镜引用的参考资产仍可用。
        List<String> referenceAssetNames = loadReferenceableAssetNames(projectId, userId);
        validateReferencedAssetsExist(targetList, referenceAssetNames);

        // 抢锁失败时走僵尸锁自愈：DB 复核 + 锁年龄检查 + CAS 清理 + 重抢，
        // 避免「DB 无活跃任务但 Redis 锁泄漏」时让用户卡 30 分钟才能重新提交
        long lockTtlSeconds = Math.max(1800L, (long) targetList.size() * 30L);
        String lockKey = buildLockKey(projectId, episodeId);
        ProjectGenerateLockGuard.AcquireResult lockResult = projectLockGuard.tryAcquireWithStaleClean(
                lockKey, lockTtlSeconds, TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH, projectId, episodeId);
        if (!lockResult.isAcquired())
        {
            log.info("分镜图脚本任务并发拦截: projectId={}, episodeId={}, lockKey={}",
                    projectId, episodeId, lockKey);
            throw new ServiceException("任务处理中");
        }

        AidExtractTask task = null;
        try
        {
            task = new AidExtractTask();
            task.setProjectId(projectId);
            task.setEpisodeId(episodeId);
            task.setUserId(userId);
            task.setTaskType(TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH);
            task.setStatus(TASK_STATUS_PENDING);
            task.setModelCode(resolvedModelCode);
            task.setTotalCount(targetList.size());

            // 保存任务执行所需的最小输入快照。
            String aspectRatio = StrUtil.blankToDefault(project.getAspectRatio(), DEFAULT_ASPECT_RATIO);
            String videoStyleType = StrUtil.nullToEmpty(project.getVideoStyleType());
            String videoStyleValue = StrUtil.nullToEmpty(project.getVideoStyleValue());
            // 可引用参考图白名单：复用 step 6.5 前置校验已加载的同一份（整批只查 1 次），在此固化进 inputSnapshot，
            // 执行阶段直接用快照，避免排队等待期间用户新增/启用资产导致"实际下发 prompt 比预估更长 → 少冻结"
            Map<String, Object> inputMap = new LinkedHashMap<>();
            inputMap.put("projectId", projectId);
            inputMap.put("episodeId", episodeId);
            inputMap.put("agentCode", agentCode);
            inputMap.put("modelCode", resolvedModelCode);
            inputMap.put("overwrite", overwriteFlag);
            inputMap.put("storyboardIds", targetList.stream().map(AidStoryboard::getId).collect(Collectors.toList()));
            inputMap.put("aspectRatio", aspectRatio);
            inputMap.put("videoStyleType", videoStyleType);
            inputMap.put("videoStyleValue", videoStyleValue);
            // 固化白名单：执行 / 计费同源，保证预估字符数与实际下发一致
            inputMap.put("referenceAssetNames", referenceAssetNames);
            // 合并接口：链式触发下一步（出图）规格，提示词终态后由 StoryboardStepChainService 自动发起
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
                log.error("分镜图脚本 inputSnapshot 序列化失败: projectId={}, episodeId={}", projectId, episodeId, e);
                throw new ServiceException("提交失败，请重试");
            }
            task.setDelFlag(DEL_FLAG_NORMAL);
            task.setCreateTime(DateUtils.getNowDate());
            task.setCreateBy(String.valueOf(userId));
            task.setUpdateTime(DateUtils.getNowDate());
            task.setUpdateBy(String.valueOf(userId));
            extractTaskService.save(task);

            BigDecimal totalFrozen = BigDecimal.ZERO;
            List<Map<String, Object>> itemSnapshots = new ArrayList<>(targetList.size());
            String systemPrompt = helper.loadPromptByName(agentCode);
            // 复用上面已固化进 inputSnapshot 的同一份白名单，保证预估与执行同源
            for (AidStoryboard sb : targetList)
            {
                int inputChars = estimateInputChars(sb, agentCode, aspectRatio, videoStyleType,
                        videoStyleValue, referenceAssetNames, systemPrompt, resolvedModelCode);
                int outputCharsEstimate = ESTIMATED_OUTPUT_CHARS;
                Map<String, Object> params = new HashMap<>();
                params.put("inputChars", inputChars);
                params.put("inputTokens", BillingConstants.charsToTokens(inputChars));
                params.put("outputTokens", BillingConstants.charsToTokens(outputCharsEstimate));
                params.put("estimatedOutputChars", outputCharsEstimate);
                params.put("totalChars", inputChars + outputCharsEstimate);
                BillingInput billingInput = new BillingInput("TEXT", params);
                BillingCalcResult calc = billingAmountCalculator.calculatePreHoldAmount(modelConfig, billingInput);
                if (calc != null && calc.isMatched() && calc.getAmount() != null)
                {
                    totalFrozen = totalFrozen.add(calc.getAmount());
                }
                Map<String, Object> snap = new LinkedHashMap<>();
                snap.put("storyboardId", sb.getId());
                snap.put("inputChars", inputChars);
                snap.put("estimatedAmount", calc != null ? calc.getAmount() : null);
                itemSnapshots.add(snap);
            }
            String billingSnapshotJson = null;
            try
            {
                Map<String, Object> billingSnap = new LinkedHashMap<>();
                billingSnap.put("batchType", TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH);
                billingSnap.put("items", itemSnapshots);
                billingSnapshotJson = OBJECT_MAPPER.writeValueAsString(billingSnap);
            }
            catch (Exception ignore)
            {
                // billingSnapshotJson 仅供排查，序列化失败不阻断
            }
            extractBillingService.prepareBilling(task.getId(), userId, totalFrozen, billingSnapshotJson);
            log.info("分镜图脚本批量预冻结: taskId={}, shotCount={}, frozen={}",
                    task.getId(), targetList.size(), totalFrozen);

            sendMqMessage(task.getId(), projectId, episodeId, userId, resolvedModelCode);

            return AssetExtractTaskVO.builder()
                    .taskId(task.getId())
                    .status(TASK_STATUS_PENDING)
                    .totalShots(targetList.size())
                    .build();
        }
        catch (RuntimeException e)
        {
            // 半态任务清理 + 锁释放（CAS 释放，避免极端尾部场景误删被自愈机制重抢的新锁）
            projectLockGuard.releaseIfMatch(lockKey, lockResult.getToken());
            String origMsg = StrUtil.nullToEmpty(e.getMessage());
            if (task != null && task.getId() != null)
            {
                try
                {
                    LambdaUpdateWrapper<AidExtractTask> upd = Wrappers.lambdaUpdate();
                    upd.eq(AidExtractTask::getId, task.getId());
                    upd.eq(AidExtractTask::getStatus, TASK_STATUS_PENDING);
                    upd.set(AidExtractTask::getStatus, TASK_STATUS_FAILED);
                    upd.set(AidExtractTask::getErrorMessage, "提交失败: " + StrUtil.sub(origMsg, 0, 80));
                    upd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
                    extractTaskService.update(upd);
                }
                catch (Exception cleanupEx)
                {
                    log.warn("分镜图脚本任务清理半态记录异常: taskId={}", task.getId(), cleanupEx);
                }
            }
            log.error("分镜图脚本批量任务创建失败: userId={}", userId, e);
            // ServiceException 直接抛；其它异常包成"提交失败"短文案
            if (e instanceof ServiceException)
            {
                throw e;
            }
            throw new ServiceException("提交失败，请重试");
        }
    }

    private AssetExtractTaskVO findReconnectableTask(Long projectId, Long episodeId, Long userId)
    {
        AidExtractTask latest = extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getStatus, AidExtractTask::getTotalCount,
                                AidExtractTask::getInputSnapshot, AidExtractTask::getResultData)
                        .eq(AidExtractTask::getProjectId, projectId)
                        .eq(AidExtractTask::getEpisodeId, episodeId)
                        .eq(AidExtractTask::getUserId, userId)
                        .eq(AidExtractTask::getTaskType, TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH)
                        .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByDesc(AidExtractTask::getId)
                        .last("limit 1"), false);
        if (Objects.isNull(latest) || StrUtil.isBlank(latest.getStatus()))
        {
            return null;
        }
        String status = latest.getStatus();
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
        log.info("分镜图提示词全部已生成，回传最近任务供前端断线重连: projectId={}, episodeId={}, taskId={}, status={}",
                projectId, episodeId, latest.getId(), status);
        return AssetExtractTaskVO.builder()
                .taskId(latest.getId())
                .status(status)
                .totalShots(latest.getTotalCount())
                .build();
    }

    @Override
    public String doStoryboardImagePromptBatch(Long taskId, Long userId)
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
            log.error("分镜图脚本 inputSnapshot 解析失败: taskId={}", taskId, e);
            throw new ServiceException("解析失败");
        }

        Long projectId = Convert.toLong(input.get("projectId"));
        Long episodeId = Convert.toLong(input.get("episodeId"));
        String agentCode = StrUtil.blankToDefault(
                String.valueOf(input.getOrDefault("agentCode", DEFAULT_AGENT_CODE)),
                DEFAULT_AGENT_CODE);
        String modelCode = String.valueOf(input.getOrDefault("modelCode", task.getModelCode()));
        boolean overwriteFlag = Boolean.TRUE.equals(input.get("overwrite"));
        String aspectRatio = StrUtil.blankToDefault(
                String.valueOf(input.getOrDefault("aspectRatio", DEFAULT_ASPECT_RATIO)),
                DEFAULT_ASPECT_RATIO);
        String videoStyleType = StrUtil.nullToEmpty(String.valueOf(input.getOrDefault("videoStyleType", "")));
        String videoStyleValue = StrUtil.nullToEmpty(String.valueOf(input.getOrDefault("videoStyleValue", "")));

        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) input.get("storyboardIds");
        List<Long> storyboardIds = CollectionUtil.isEmpty(rawIds) ? new ArrayList<>()
                : rawIds.stream().map(Convert::toLong).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (CollectionUtil.isEmpty(storyboardIds))
        {
            log.error("分镜图脚本任务异常: inputSnapshot.storyboardIds 为空, taskId={}", taskId);
            throw new ServiceException("分镜不能为空");
        }

        // 加载智能体提示词模板：复用统一的「文件优先 → 回源 aid_agent → 回写文件」机制，与角色/场景/道具提取一致
        String systemPrompt = helper.loadPromptByName(agentCode);

        // 一次性加载目标分镜
        // 防漏字段：补查 image_prompt 用于判断"已成功"跳过
        List<AidStoryboard> storyboardList = storyboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getProjectId, AidStoryboard::getEpisodeId,
                                AidStoryboard::getUserId, AidStoryboard::getSortOrder, AidStoryboard::getTitle,
                                AidStoryboard::getStoryScript, AidStoryboard::getScriptParams,
                                AidStoryboard::getImagePrompt, AidStoryboard::getImagePromptRaw,
                                AidStoryboard::getGridType, AidStoryboard::getDelFlag)
                        .in(AidStoryboard::getId, storyboardIds)
                        .eq(AidStoryboard::getProjectId, projectId)
                        .eq(AidStoryboard::getEpisodeId, episodeId)
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidStoryboard::getSortOrder));
        if (CollectionUtil.isEmpty(storyboardList))
        {
            throw new ServiceException("镜头列表为空");
        }

        int total = storyboardList.size();
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;
        long totalInputChars = 0;
        long totalOutputChars = 0;
        List<Map<String, Object>> successItems = new ArrayList<>();
        List<Map<String, Object>> failedItems = new ArrayList<>();

        sseManager.sendStepProgress(taskId, "loading_storyboards", 10,
                "load_storyboards", "加载分镜列表", 1, 1);

        // 可引用参考图白名单：优先用提交时固化进 inputSnapshot 的快照（与计费预估同源），
        // 避免排队等待期间用户新增/启用资产导致"实际下发 prompt 比预估更长 → 少冻结"。
        // 仅当快照里完全没有该字段（历史任务）才兜底重建。
        List<String> referenceAssetNames = input.containsKey("referenceAssetNames")
                ? toStringList(input.get("referenceAssetNames"))
                : loadReferenceableAssetNames(projectId, userId);

        for (int i = 0; i < total; i++)
        {
            // 取消检查点
            if (assetExtractService.isTaskCancelled(taskId))
            {
                log.info("分镜图脚本任务被取消: taskId={}, processed={}/{}", taskId, i, total);
                break;
            }

            AidStoryboard sb = storyboardList.get(i);

            // overwrite=false 且已有 image_prompt 则跳过
            if (!overwriteFlag && StrUtil.isNotBlank(sb.getImagePrompt()))
            {
                skipCount++;
                Map<String, Object> skip = new LinkedHashMap<>();
                skip.put("storyboardId", sb.getId());
                skip.put("reason", "已有 image_prompt，跳过");
                skip.put("promptLength", StrUtil.length(sb.getImagePrompt()));
                skip.put("imagePrompt", sb.getImagePrompt());
                skip.put("gridType", sb.getGridType());
                successItems.add(skip);
                int progress = 10 + (i + 1) * 88 / total;
                sseManager.sendStepProgress(taskId, "generating", progress,
                        "skip", "镜头 " + (i + 1) + " 已生成，跳过", i + 1, total);
                continue;
            }

            try
            {
                // 拼装 LLM 用户输入
                // 分叉：宫格画师（agent=aid_storyboard_grid_painter）吃「中文镜头组」script_params（画面说明/镜头脚本…），
                //   走专用装配；标准漫剧画师走原 buildLlmInput（漫剧单镜 key：画面描述/景别/拍摄角度…）。
                String userContent = AGENT_CODE_GRID_PAINTER.equals(agentCode)
                        ? buildLlmInputGrid(sb, aspectRatio, videoStyleType, videoStyleValue, referenceAssetNames)
                        : buildLlmInput(sb, aspectRatio, videoStyleType, videoStyleValue, referenceAssetNames);
                int inputChars = helper.estimateLlmInputChars(systemPrompt, userContent, modelCode);
                totalInputChars += inputChars;

                String llmRaw = helper.callLlmRaw(systemPrompt, userContent, modelCode,
                        taskId, userId, /*taskPromptDigest*/ null, BIZ_TASK_TYPE);
                if (StrUtil.isBlank(llmRaw))
                {
                    throw new ServiceException("模型返回为空");
                }
                totalOutputChars += StrUtil.length(llmRaw);

                String prompt = parseLlmOutput(llmRaw);
                if (StrUtil.isBlank(prompt))
                {
                    throw new ServiceException("模型返回异常");
                }
                // 落库前消毒 @图片N[name] 占位：回退命中（方位后缀/分隔符差异）改写为白名单正名，
                // 白名单外的杜撰引用剥壳成纯文字，避免出图阶段"参考图缺失"硬失败
                prompt = sanitizeOutputImageReferences(prompt, referenceAssetNames, taskId, sb.getId());
                // 宫格画师额外解析宫格类型（四宫格/九宫格）；标准漫剧画师无该字段，解析为 null
                String gridType = parseGridType(llmRaw);

                // 落库（限定 userId 防越权 + 4 个时间/操作字段）
                LambdaUpdateWrapper<AidStoryboard> upd = Wrappers.lambdaUpdate();
                upd.eq(AidStoryboard::getId, sb.getId());
                upd.eq(AidStoryboard::getUserId, userId);
                upd.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
                upd.set(AidStoryboard::getImagePrompt, prompt);
                upd.set(AidStoryboard::getImagePromptRaw, StrUtil.sub(llmRaw, 0, 65535));
                // 宫格类型回填（重新生成时若本次无宫格类型则置 null，避免残留旧值）
                upd.set(AidStoryboard::getGridType, gridType);
                upd.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
                upd.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
                boolean updated = storyboardService.update(upd);
                if (!updated)
                {
                    log.error("分镜图脚本落库失败: taskId={}, storyboardId={}, userId={}", taskId, sb.getId(), userId);
                    throw new ServiceException("写入失败");
                }

                successCount++;
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("storyboardId", sb.getId());
                ok.put("promptLength", StrUtil.length(prompt));
                ok.put("imagePrompt", prompt);
                ok.put("gridType", gridType);
                successItems.add(ok);

                int progress = 10 + (i + 1) * 88 / total;
                sseManager.sendStepProgress(taskId, "generating", progress,
                        "success", "镜头 " + (i + 1) + " 生成完成", i + 1, total);
            }
            catch (Exception e)
            {
                failCount++;
                String errMsg = StrUtil.sub(StrUtil.nullToEmpty(e.getMessage()), 0, 500);
                log.error("分镜图脚本单镜失败: taskId={}, storyboardId={}, err={}",
                        taskId, sb.getId(), errMsg, e);
                Map<String, Object> bad = new LinkedHashMap<>();
                bad.put("storyboardId", sb.getId());
                bad.put("errorMessage", errMsg);
                failedItems.add(bad);

                int progress = 10 + (i + 1) * 88 / total;
                sseManager.sendStepProgress(taskId, "generating", progress,
                        "failed", "镜头 " + (i + 1) + " 失败", i + 1, total);
            }
        }

        // 计费结算：首跑与续生统一走任务计费周期；续生提交前已由 rearmBillingForResume 重置冻结快照。
        try
        {
            if (successCount > 0 || skipCount > 0)
            {
                Map<String, Object> usageData = new HashMap<>();
                usageData.put("input_tokens",
                        BillingConstants.charsToTokens((int) Math.min(totalInputChars, Integer.MAX_VALUE)));
                usageData.put("output_tokens",
                        BillingConstants.charsToTokens((int) Math.min(totalOutputChars, Integer.MAX_VALUE)));
                usageData.put("total_chars_estimate", totalInputChars + totalOutputChars);
                extractBillingService.settleBilling(taskId, userId, usageData);
                log.info("分镜图脚本批量结算: taskId={}, success={}, skip={}, fail={}, totalInputChars={}, totalOutputChars={}",
                        taskId, successCount, skipCount, failCount, totalInputChars, totalOutputChars);
            }
            else
            {
                extractBillingService.refundBilling(taskId, userId);
                log.info("分镜图脚本批量全部失败已退款: taskId={}", taskId);
            }
        }
        catch (Exception e)
        {
            log.error("分镜图脚本计费结算异常（不阻断任务终态）: taskId={}", taskId, e);
        }

        // 释放项目级锁
        try
        {
            redisCache.deleteObject(buildLockKey(task.getProjectId(), task.getEpisodeId()));
        }
        catch (Exception ignore) { /* 锁释放异常不阻断 */ }

        // 终态判定：全部失败 → 抛异常让 Consumer 标 FAILED；部分失败 → Consumer 收到结果后由本方法外层判定 PARTIAL_FAILED
        if (failCount == total && total > 0)
        {
            throw new ServiceException("分镜图脚本生成失败");
        }

        // 返回结果 JSON（供 aid_extract_task.result_data 落盘）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", total);
        result.put("successCount", successCount);
        result.put("skipCount", skipCount);
        result.put("failCount", failCount);
        result.put("successItems", successItems);
        result.put("failedItems", failedItems);
        try
        {
            return OBJECT_MAPPER.writeValueAsString(result);
        }
        catch (Exception e)
        {
            return "{}";
        }
    }

    @Override
    public AssetExtractTaskVO resumeImagePrompt(Long taskId, Long userId)
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
            log.warn("分镜图脚本续生：任务不属于当前用户: taskId={}, owner={}, userId={}",
                    taskId, task.getUserId(), userId);
            throw new ServiceException("任务不存在");
        }
        if (!TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH.equals(task.getTaskType()))
        {
            throw new ServiceException("任务类型不支持续生");
        }
        if (!TASK_STATUS_PARTIAL_FAILED.equals(task.getStatus())
                && !TASK_STATUS_FAILED.equals(task.getStatus())
                && !TASK_STATUS_CANCELLED.equals(task.getStatus()))
        {
            throw new ServiceException("任务状态不支持续生");
        }
        boolean refundedTerminal = (TASK_STATUS_CANCELLED.equals(task.getStatus()) || TASK_STATUS_FAILED.equals(task.getStatus()))
                && BILLING_STATUS_REFUNDED.equals(task.getBillingStatus());
        if (!BILLING_STATUS_SUCCESS.equals(task.getBillingStatus()) && !refundedTerminal)
        {
            log.info("分镜图提示词续生拒绝：旧计费周期未完成, taskId={}, billingStatus={}", taskId, task.getBillingStatus());
            throw new ServiceException("结算未完成");
        }
        if (Objects.nonNull(task.getCreateTime()))
        {
            long ageHours = (System.currentTimeMillis() - task.getCreateTime().getTime()) / 3600_000L;
            if (ageHours > RESUME_WINDOW_HOURS)
            {
                throw new ServiceException("任务已过期，请重新发起");
            }
        }

        // 续生防重锁（任务级 30 分钟）
        String resumeLockKey = "storyboard:image_prompt:resume:lock:" + taskId;
        Boolean resumeLocked = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(resumeLockKey, "1", 30L * 60L, TimeUnit.SECONDS);
        String originalStatus = task.getStatus();
        String originalRemark = task.getRemark();
        String originalInputSnapshot = task.getInputSnapshot();
        if (resumeLocked == null || !resumeLocked)
        {
            throw new ServiceException("任务处理中");
        }

        try
        {
            // 项目级锁（续生场景同样走僵尸锁自愈，避免项目锁泄漏卡死续生入口）
            String projectLockKey = buildLockKey(task.getProjectId(), task.getEpisodeId());
            ProjectGenerateLockGuard.AcquireResult resumeProjectLockResult = projectLockGuard.tryAcquireWithStaleClean(
                    projectLockKey, 30L * 60L, TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH,
                    task.getProjectId(), task.getEpisodeId());
            if (!resumeProjectLockResult.isAcquired())
            {
                throw new ServiceException("项目处理中");
            }

            // 解析 inputSnapshot 拿 modelCode + projectStyle 信息
            Map<String, Object> input;
            try
            {
                input = OBJECT_MAPPER.readValue(task.getInputSnapshot(), Map.class);
            }
            catch (Exception e)
            {
                projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                throw new ServiceException("解析失败");
            }
            String modelCode = StrUtil.blankToDefault(
                    String.valueOf(input.getOrDefault("modelCode", task.getModelCode())),
                    task.getModelCode());
            // 续生同样按 agent 分叉计费估算（宫格画师走镜头组装配），与首跑/执行同源
            String resumeAgentCode = StrUtil.blankToDefault(
                    String.valueOf(input.getOrDefault("agentCode", DEFAULT_AGENT_CODE)), DEFAULT_AGENT_CODE);
            String aspectRatio = StrUtil.blankToDefault(
                    String.valueOf(input.getOrDefault("aspectRatio", DEFAULT_ASPECT_RATIO)),
                    DEFAULT_ASPECT_RATIO);
            String videoStyleType = StrUtil.nullToEmpty(String.valueOf(input.getOrDefault("videoStyleType", "")));
            String videoStyleValue = StrUtil.nullToEmpty(String.valueOf(input.getOrDefault("videoStyleValue", "")));

            // 加载剩余未生成的镜头（image_prompt IS NULL OR ''）+ 严格 userId 归属校验
            @SuppressWarnings("unchecked")
            List<Object> rawIds = (List<Object>) input.get("storyboardIds");
            List<Long> originalIds = CollectionUtil.isEmpty(rawIds) ? new ArrayList<>()
                    : rawIds.stream().map(Convert::toLong).filter(Objects::nonNull).distinct().collect(Collectors.toList());
            if (CollectionUtil.isEmpty(originalIds))
            {
                projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                throw new ServiceException("分镜不能为空");
            }
            // 防漏字段：补查 imagePrompt 用作"已成功"判定 + script_params 用作金额估算；
            // title/sortOrder 用于失效镜号回落链与"前 3 个镜号"按脚本顺序展示（与首跑口径一致）
            // 严格按 userId + projectId + episodeId + del_flag='0' 过滤，防越权
            List<AidStoryboard> remaining = storyboardService.list(
                    Wrappers.<AidStoryboard>lambdaQuery()
                            .select(AidStoryboard::getId, AidStoryboard::getScriptParams,
                                    AidStoryboard::getTitle, AidStoryboard::getSortOrder,
                                    AidStoryboard::getImagePrompt, AidStoryboard::getDelFlag)
                            .in(AidStoryboard::getId, originalIds)
                            .eq(AidStoryboard::getProjectId, task.getProjectId())
                            .eq(AidStoryboard::getEpisodeId, task.getEpisodeId())
                            .eq(AidStoryboard::getUserId, userId)
                            .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                            .orderByAsc(AidStoryboard::getSortOrder))
                    .stream().filter(s -> StrUtil.isBlank(s.getImagePrompt())).collect(Collectors.toList());
            if (CollectionUtil.isEmpty(remaining))
            {
                try
                {
                    AssetExtractTaskVO chainRetry = retryChainAfterPromptsReady(task);
                    if (Objects.nonNull(chainRetry))
                    {
                        return chainRetry;
                    }
                    throw new ServiceException("无可续生镜头");
                }
                finally
                {
                    projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                }
            }

            // 重新估算剩余镜头金额并独立 freeze（首跑结算时已经按差额退过未跑镜头的钱）
            AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(modelCode);
            if (Objects.isNull(modelConfig))
            {
                projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                throw new ServiceException("模型不存在");
            }
            BigDecimal totalRetryFrozen = BigDecimal.ZERO;
            List<Map<String, Object>> itemSnapshots = new ArrayList<>(remaining.size());
            String systemPrompt = helper.loadPromptByName(resumeAgentCode);
            // 可引用参考图白名单（整批共用，建一次）：续生与首跑/执行口径一致
            List<String> referenceAssetNames = loadReferenceableAssetNames(task.getProjectId(), userId);
            // 参考资产失效前置校验（与首跑同口径、同方法）：续生同样在预冻结之前拦掉引用了
            // 已删除/改名/未出图资产的镜头，避免白跑一轮 LLM 才报部分失败。失败时先释放项目锁再抛。
            try
            {
                validateReferencedAssetsExist(remaining, referenceAssetNames);
            }
            catch (ServiceException ve)
            {
                projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                throw ve;
            }
            for (AidStoryboard sb : remaining)
            {
                int inputChars = estimateInputChars(sb, resumeAgentCode, aspectRatio, videoStyleType,
                        videoStyleValue, referenceAssetNames, systemPrompt, modelCode);
                int outputCharsEstimate = ESTIMATED_OUTPUT_CHARS;
                Map<String, Object> params = new HashMap<>();
                params.put("inputChars", inputChars);
                params.put("inputTokens", BillingConstants.charsToTokens(inputChars));
                params.put("outputTokens", BillingConstants.charsToTokens(outputCharsEstimate));
                params.put("estimatedOutputChars", outputCharsEstimate);
                params.put("totalChars", inputChars + outputCharsEstimate);
                BillingInput billingInput = new BillingInput("TEXT", params);
                BillingCalcResult calc = billingAmountCalculator.calculatePreHoldAmount(modelConfig, billingInput);
                if (calc != null && calc.isMatched() && calc.getAmount() != null)
                {
                    totalRetryFrozen = totalRetryFrozen.add(calc.getAmount());
                }
                Map<String, Object> snap = new LinkedHashMap<>();
                snap.put("storyboardId", sb.getId());
                snap.put("inputChars", inputChars);
                snap.put("estimatedAmount", calc != null ? calc.getAmount() : null);
                itemSnapshots.add(snap);
            }
            String billingSnapshotJson = null;
            try
            {
                Map<String, Object> billingSnap = new LinkedHashMap<>();
                billingSnap.put("batchType", TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH);
                billingSnap.put("items", itemSnapshots);
                billingSnapshotJson = OBJECT_MAPPER.writeValueAsString(billingSnap);
            }
            catch (Exception e)
            {
                log.warn("分镜图提示词续生计费快照序列化失败，降级按冻结额结算: taskId={}", taskId, e);
            }

            // 把任务状态从终态改回 PENDING，随后由统一计费服务重置本轮冻结周期。
            // 同时把本轮重建的白名单写回 inputSnapshot：执行阶段统一读快照，保证续生的"预估 = 实际下发"
            input.put("referenceAssetNames", referenceAssetNames);
            String resumedSnapshotJson;
            try
            {
                resumedSnapshotJson = OBJECT_MAPPER.writeValueAsString(input);
            }
            catch (Exception e)
            {
                projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                log.error("分镜图脚本续生 inputSnapshot 序列化失败: taskId={}", taskId, e);
                throw new ServiceException("提交失败，请重试");
            }
            LambdaUpdateWrapper<AidExtractTask> taskUpd = Wrappers.lambdaUpdate();
            taskUpd.eq(AidExtractTask::getId, taskId);
            taskUpd.in(AidExtractTask::getStatus, TASK_STATUS_PARTIAL_FAILED, TASK_STATUS_FAILED, TASK_STATUS_CANCELLED);
            taskUpd.set(AidExtractTask::getStatus, TASK_STATUS_PENDING);
            taskUpd.set(AidExtractTask::getErrorMessage, null);
            taskUpd.set(AidExtractTask::getRemark, null);
            taskUpd.set(AidExtractTask::getInputSnapshot, resumedSnapshotJson);
            taskUpd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            taskUpd.set(AidExtractTask::getUpdateBy, String.valueOf(userId));
            boolean statusUpdated = extractTaskService.update(taskUpd);
            if (!statusUpdated)
            {
                projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                throw new ServiceException("状态不支持");
            }

            String priorBillingStatus = task.getBillingStatus();
            String priorTraceId = task.getBillingTraceId();
            BigDecimal priorFrozen = task.getFrozenAmount();
            String priorSnapshotRef = task.getBillingSnapshotJson();
            String priorSnapshot = extractBillingService.resolveBillingSnapshotJson(taskId, priorSnapshotRef);
            try
            {
                extractBillingService.rearmBillingForResume(taskId, userId, totalRetryFrozen, billingSnapshotJson);
            }
            catch (RuntimeException billingEx)
            {
                rollbackImagePromptResumeTask(taskId, originalStatus, originalRemark,
                        originalInputSnapshot, "续生失败", userId);
                projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                throw (billingEx instanceof ServiceException) ? billingEx : new ServiceException("续生失败，请重试");
            }

            // 重新发 MQ
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
                if (restored)
                {
                    rollbackImagePromptResumeTask(taskId, originalStatus, originalRemark,
                            originalInputSnapshot, "提交失败", userId);
                }
                else
                {
                    rollbackImagePromptResumeTask(taskId, TASK_STATUS_FAILED, null,
                            resumedSnapshotJson, "提交失败", userId);
                }
                projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                log.error("分镜图脚本续生发送MQ失败: taskId={}", taskId, mqEx);
                throw (mqEx instanceof ServiceException) ? mqEx : new ServiceException("续生失败，请重试");
            }

            log.info("分镜图脚本续生提交: taskId={}, retryShotCount={}, totalRetryFrozen={}",
                    taskId, remaining.size(), totalRetryFrozen);
            return AssetExtractTaskVO.builder()
                    .taskId(taskId)
                    .status(TASK_STATUS_PENDING)
                    .totalShots(remaining.size())
                    .build();
        }
        catch (RuntimeException e)
        {
            log.error("分镜图脚本续生失败: taskId={}, userId={}", taskId, userId, e);
            if (e instanceof ServiceException)
            {
                throw e;
            }
            throw new ServiceException("续生失败，请重试");
        }
        finally
        {
            redisCache.deleteObject(resumeLockKey);
        }
    }
    private AssetExtractTaskVO retryChainAfterPromptsReady(AidExtractTask task)
    {
        if (!hasChainNext(task.getInputSnapshot())
                || (hasChainChildTaskId(task.getResultData()) && !isChainFailedResult(task.getResultData())))
        {
            return null;
        }
        ChainTriggerResult chain = storyboardStepChainService.onPromptBatchTerminal(
                task.getId(), TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH, TASK_STATUS_SUCCEEDED);
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
        log.info("分镜图提示词续生重试链式出图成功: taskId={}, childTaskId={}, childTaskType={}",
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
            log.warn("分镜图提示词续生解析chainNext失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean isChainFailedResult(String resultData)
    {
        Map<String, Object> result = parseResultMap(resultData);
        Object val = result.get("chainFailed");
        return Boolean.TRUE.equals(val) || "true".equalsIgnoreCase(String.valueOf(val));
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
            log.warn("分镜图提示词续生解析resultData失败: {}", e.getMessage());
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

    private void rollbackImagePromptResumeTask(Long taskId, String status, String remark,
                                               String inputSnapshot, String errorMessage, Long userId)
    {
        LambdaUpdateWrapper<AidExtractTask> rollback = Wrappers.lambdaUpdate();
        rollback.eq(AidExtractTask::getId, taskId);
        rollback.set(AidExtractTask::getStatus, status);
        rollback.set(AidExtractTask::getRemark, remark);
        rollback.set(AidExtractTask::getInputSnapshot, inputSnapshot);
        rollback.set(AidExtractTask::getErrorMessage, errorMessage);
        rollback.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        rollback.set(AidExtractTask::getUpdateBy, String.valueOf(userId));
        extractTaskService.update(rollback);
    }

    private String buildLockKey(Long projectId, Long episodeId)
    {
        return "storyboard:image_prompt:lock:" + projectId + ":" + episodeId;
    }

    /**
     * 加载目标分镜并严格做归属校验。
     */
    private List<AidStoryboard> loadTargetStoryboards(Long projectId, Long episodeId, Long userId,
                                                     List<Long> uniqueStoryboardIds)
    {
        if (CollectionUtil.isEmpty(uniqueStoryboardIds))
        {
            return new ArrayList<>();
        }
        List<AidStoryboard> matched = storyboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getProjectId, AidStoryboard::getEpisodeId,
                                AidStoryboard::getUserId, AidStoryboard::getSortOrder, AidStoryboard::getTitle,
                                AidStoryboard::getStoryScript, AidStoryboard::getScriptParams,
                                AidStoryboard::getImagePrompt, AidStoryboard::getDelFlag)
                        .in(AidStoryboard::getId, uniqueStoryboardIds)
                        .eq(AidStoryboard::getProjectId, projectId)
                        .eq(AidStoryboard::getEpisodeId, episodeId)
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidStoryboard::getSortOrder));
        // 严格归属校验：传入 N 个 ID，必须全部命中
        if (matched.size() != uniqueStoryboardIds.size())
        {
            log.warn("分镜图脚本 storyboardIds 归属校验失败: requested={}, matched={}, projectId={}, episodeId={}, userId={}",
                    uniqueStoryboardIds.size(), matched.size(), projectId, episodeId, userId);
            throw new ServiceException("镜头存在不可用项");
        }
        // script_params 必须非空（无脚本无法生成 image_prompt）
        boolean hasEmptyScript = matched.stream().anyMatch(s -> StrUtil.isBlank(s.getScriptParams()));
        if (hasEmptyScript)
        {
            log.warn("分镜图脚本拒绝：存在 script_params 为空的镜头, projectId={}, episodeId={}, userId={}",
                    projectId, episodeId, userId);
            throw new ServiceException("镜头脚本未生成");
        }
        return matched;
    }

    /**
     * 估算单镜 LLM 输入的字符数（用于计费预估）。
     */
    private int estimateInputChars(AidStoryboard sb, String agentCode, String aspectRatio,
                                   String videoStyleType, String videoStyleValue,
                                   List<String> referenceAssetNames, String systemPrompt,
                                   String modelCode)
    {
        // 与实际下发同源：宫格画师走镜头组装配，标准漫剧画师走 18 字段装配，避免计费估算与下发不一致
        String input = AGENT_CODE_GRID_PAINTER.equals(agentCode)
                ? buildLlmInputGrid(sb, aspectRatio, videoStyleType, videoStyleValue, referenceAssetNames)
                : buildLlmInput(sb, aspectRatio, videoStyleType, videoStyleValue, referenceAssetNames);
        return helper.estimateLlmInputChars(systemPrompt, input, modelCode);
    }

    /**
     * 加载本项目"可引用参考图"白名单：{@code aid_role_prop_scene_form_image.name}。
     * 过滤口径与出图解析器 {@link com.aid.rps.resolver.StoryboardImageReferenceResolver} 完全一致：
     * {@code projectId + userId + is_use=1 + is_split_source=0 + del_flag=0}，且 {@code image_url} 非空
     * （命中行 image_url 为空在出图侧也视为未匹配）。按 sort_order 升序、去重、保序。
     * 不按集过滤：项目级角色图（episode_id=0）与跨集复用资产图均可引用。
     * 注入给分镜画师 LLM，约束 {@code @图片N[name]} 只能从中精确选取，避免杜撰 / 引用已软删 / 改名资产
     * 导致出图时"参考图缺失"。
     */
    private List<String> loadReferenceableAssetNames(Long projectId, Long userId)
    {
        if (Objects.isNull(projectId) || Objects.isNull(userId))
        {
            return new ArrayList<>();
        }
        List<AidRolePropSceneFormImage> imgs = rpsFormImageService.list(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getName, AidRolePropSceneFormImage::getImageUrl,
                                AidRolePropSceneFormImage::getSortOrder)
                        .eq(AidRolePropSceneFormImage::getProjectId, projectId)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getIsUse, IS_USE_YES)
                        .eq(AidRolePropSceneFormImage::getIsSplitSource, IS_SPLIT_SOURCE_NO)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidRolePropSceneFormImage::getSortOrder));
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

    /**
     * 参考资产失效前置校验（批量、零逐镜查询）。
     *
     * @param targetList          目标镜头（已批量加载、含 scriptParams）
     * @param referenceAssetNames 整批共用的可引用白名单
     */
    private void validateReferencedAssetsExist(List<AidStoryboard> targetList, List<String> referenceAssetNames)
    {
        // 白名单灌进 HashSet：trim + 分隔符归一化、O(1) 查存在性（与 loadReferenceableAssetNames 输出口径一致）
        Set<String> whitelist = new HashSet<>();
        if (CollectionUtil.isNotEmpty(referenceAssetNames))
        {
            for (String nm : referenceAssetNames)
            {
                if (StrUtil.isNotBlank(nm)) { whitelist.add(normalizeAssetRefName(nm)); }
            }
        }

        // 失效镜号集合：LinkedHashSet 保序 + 去重（同镜多处失效只记一次，按 targetList 顺序展示）
        Set<String> invalidShotLabels = new LinkedHashSet<>();
        for (AidStoryboard sb : targetList)
        {
            // 解析本镜结构化脚本（内存数据，不查库）
            Map<String, Object> shot = parseScriptParams(sb.getScriptParams());
            // 引用信息：上游分镜脚本固化的"场景/角色/道具"引用清单，是分镜画师 @图片N 的名称来源
            Object refInfo = shot.get("引用信息");
            String refText = (refInfo == null) ? "" : String.valueOf(refInfo);
            if (StrUtil.isBlank(refText)) { continue; }

            // 抽取所有 [name] → 凡不在白名单（被删除/改名/未出图）即记为失效
            boolean shotInvalid = false;
            Matcher matcher = REFERENCE_NAME_PATTERN.matcher(refText);
            while (matcher.find())
            {
                String bracket = StrUtil.trim(matcher.group(1));
                if (StrUtil.isBlank(bracket)) { continue; }
                // 整个括号即占位 / 无引用记号（[无]/[暂无]/[/]/[None] 等）→ 本处无引用，跳过，避免误杀整批
                if (isReferencePlaceholder(bracket)) { continue; }
                // 整名优先：合法资产名本身可能含 / 、 等分隔符（如「客厅/卧室」），先整名命中白名单则放行，
                // 命不中再退化为拆分逐名校验，避免把含分隔符的合法名拆碎误判失效
                // 候选键匹配：分隔符归一化 + 方位后缀回退（与出图解析器同源），避免可回退解析的引用被误判失效
                if (hitsReferenceWhitelist(bracket, whitelist)) { continue; }
                // 单括号内可能并列多个资产名（顿号/斜杠等分隔），逐名校验，避免 [A、B] 被当成一个名误杀
                for (String token : REFERENCE_NAME_SPLIT_PATTERN.split(bracket))
                {
                    String name = StrUtil.trim(token);
                    if (StrUtil.isBlank(name) || isReferencePlaceholder(name)) { continue; }
                    if (!hitsReferenceWhitelist(name, whitelist))
                    {
                        shotInvalid = true; // 该镜引用了白名单之外（失效）的资产
                        break;
                    }
                }
                if (shotInvalid) { break; } // 该镜已确认失效，无需再扫剩余占位
            }
            if (shotInvalid)
            {
                invalidShotLabels.add(resolveShotLabel(sb, shot));
            }
        }

        if (invalidShotLabels.isEmpty())
        {
            return; // 全部引用命中白名单，校验通过
        }

        // 运行时清洗（不再硬失败）：字典外 / 失效引用会在装配分镜画师 LLM 输入时由
        // ReferenceAssetSanitizer 统一剔除，出图链路对缺失引用亦有兜底。此处仅告警保留诊断，
        // 避免历史脏数据或个别镜头 LLM 杜撰引用导致整批不能出图。
        List<String> labelList = new ArrayList<>(invalidShotLabels);
        log.warn("分镜图脚本存在字典外/失效引用（装配时将自动清洗，不阻断出图）: invalidShots={}", labelList);
    }

    /**
     * 就地清洗单镜/镜头组 {@code script_params} Map 里的「引用信息」：剔除白名单之外的杜撰/失效引用。
     *
     * @param shot                已解析的 script_params Map（就地修改其「引用信息」）
     * @param referenceAssetNames 可引用资产名白名单
     */
    private void sanitizeReferenceInfoInPlace(Map<String, Object> shot, List<String> referenceAssetNames)
    {
        if (Objects.isNull(shot)) { return; }
        Object ref = shot.get("引用信息");
        if (Objects.isNull(ref)) { return; }
        String refText = String.valueOf(ref);
        if (StrUtil.isBlank(refText)) { return; }
        ReferenceAssetSanitizer.Result result = ReferenceAssetSanitizer.sanitize(refText,
                augmentReferenceWhitelistForMedia(refText, referenceAssetNames));
        if (!result.hasRemoval()) { return; }
        log.warn("分镜画师输入清洗：剔除引用信息中的字典外引用 removed={}", result.getRemoved());
        if (StrUtil.isNotBlank(result.getText()))
        {
            shot.put("引用信息", result.getText());
        }
        else
        {
            // 清洗后引用全空 → 移除该字段，画师走纯文字描述
            shot.remove("引用信息");
        }
    }

    /**
     * 引用清洗白名单补充：图片资产仍走 referenceAssetNames，视频 / 音频引用只在「引用信息」对应段内保留。
     */
    private List<String> augmentReferenceWhitelistForMedia(String refText, List<String> referenceAssetNames)
    {
        List<String> result = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(referenceAssetNames))
        {
            result.addAll(referenceAssetNames);
        }
        appendBracketNamesInSection(refText, result, "视频");
        appendBracketNamesInSection(refText, result, "音频");
        return result;
    }

    private void appendBracketNamesInSection(String text, List<String> target, String sectionName)
    {
        if (StrUtil.isBlank(text) || target == null || StrUtil.isBlank(sectionName))
        {
            return;
        }
        int start = text.indexOf(sectionName + "：");
        int labelLength = 1;
        if (start < 0)
        {
            start = text.indexOf(sectionName + ":");
        }
        if (start < 0)
        {
            return;
        }
        int sectionStart = start + sectionName.length() + labelLength;
        int sectionEnd = text.length();
        for (String label : List.of("场景", "角色", "道具", "视频", "音频"))
        {
            if (Objects.equals(label, sectionName))
            {
                continue;
            }
            int fullIdx = text.indexOf(label + "：", sectionStart);
            int halfIdx = text.indexOf(label + ":", sectionStart);
            int idx = fullIdx >= 0 && halfIdx >= 0 ? Math.min(fullIdx, halfIdx) : Math.max(fullIdx, halfIdx);
            if (idx >= 0)
            {
                sectionEnd = Math.min(sectionEnd, idx);
            }
        }
        String section = text.substring(sectionStart, sectionEnd);
        int pos = 0;
        while (pos < section.length())
        {
            int left = section.indexOf('[', pos);
            if (left < 0) { break; }
            int right = section.indexOf(']', left + 1);
            if (right < 0) { break; }
            String name = StrUtil.trim(section.substring(left + 1, right));
            if (StrUtil.isNotBlank(name))
            {
                target.add(name);
            }
            pos = right + 1;
        }
    }

    /**
     * 资产引用名归一化：把资产名结构分隔符的"连字符/全角横线"统一成下划线后再比对。
     * 资产名（{@code aid_role_prop_scene_form_image.name}）规范以下划线 {@code _} 作为「角色名_形态名」「场景名_方位」
     * 的结构分隔符；但上游分镜脚本 LLM 偶发把 {@code _} 写成 {@code -}（如 {@code 旁白-初始形象} vs 资产 {@code 旁白_初始形象}），
     * 精确匹配会误判「资产失效」。本方法仅用于匹配比对（不改写落库名 / 不影响下发 LLM 的规范清单），
     * 把常见横线变体统一为下划线，消除该类误判。
     */
    private static String normalizeAssetRefName(String s)
    {
        if (s == null) { return ""; }
        String t = StrUtil.trim(s);
        // 仅归一化「横线类」分隔符 → 下划线（ASCII 连字符 / 全角横线 / 连接号 / en dash），不动其它字符
        return t.replace('-', '_').replace('－', '_').replace('‐', '_').replace('–', '_');
    }

    /**
     * 引用名是否命中白名单：按候选键（精确 / 方位后缀回退）逐一比对，
     * 口径与 {@link StoryboardImageReferenceResolver#candidateLookupKeys} 完全一致。
     *
     * @param name      待校验的引用名（原始书写）
     * @param whitelist 归一化后的白名单键集合
     */
    private static boolean hitsReferenceWhitelist(String name, Set<String> whitelist)
    {
        for (String key : StoryboardImageReferenceResolver.candidateLookupKeys(name))
        {
            if (whitelist.contains(key)) { return true; }
        }
        return false;
    }

    /**
     * 判断引用信息方括号内的记号是否为"占位 / 无引用"（如 [无]、[暂无]、[/]、[None]），
     * 命中则跳过、不参与失效判定，避免把"本镜无引用"误判为"引用了失效资产"导致整批拦截。
     */
    private boolean isReferencePlaceholder(String token)
    {
        if (StrUtil.isBlank(token)) { return true; }
        return REFERENCE_PLACEHOLDER_TOKENS.contains(StrUtil.trim(token).toLowerCase());
    }

    /**
     * 画师输出落库前的 {@code @图片N[name]} 占位消毒（幂等、纯文本操作）：
     * <ul>
     *   <li>候选键（精确 / 方位后缀回退 / 分隔符归一化）命中白名单但书写不同 → 就地改写为白名单正名，
     *       保证落库 prompt 与出图解析器所见即所得；</li>
     *   <li>全部候选键都不在白名单（LLM 杜撰 / 资产已失效）→ 剥掉占位壳只留纯文字名，
     *       画面语义保留，避免出图阶段"参考图缺失，请补齐"硬失败拦整镜。</li>
     * </ul>
     * 白名单为空时全部剥壳：画师字典此时明确写着"暂无可引用参考图，请勿输出任何 @图片N[] 占位"，
     * 仍出现的占位必属杜撰（画师只能引用字典给它的名字），保留只会让出图阶段必然硬失败。
     *
     * @param prompt              画师产出的 image_prompt
     * @param referenceAssetNames 可引用参考图白名单（form_image 正名，与画师字典同源快照）
     * @return 消毒后的 prompt
     */
    private String sanitizeOutputImageReferences(String prompt, List<String> referenceAssetNames,
                                                 Long taskId, Long storyboardId)
    {
        if (StrUtil.isBlank(prompt))
        {
            return prompt;
        }
        // 归一化键 → 白名单正名（重名取首个，与出图解析器"同名取首张"口径一致）
        Map<String, String> canonicalByKey = new LinkedHashMap<>();
        for (String nm : referenceAssetNames)
        {
            if (StrUtil.isNotBlank(nm))
            {
                canonicalByKey.putIfAbsent(
                        StoryboardImageReferenceResolver.normalizeAssetRefName(nm), StrUtil.trim(nm));
            }
        }
        List<String> rewritten = new ArrayList<>();
        List<String> unwrapped = new ArrayList<>();
        Matcher m = IMAGE_REF_PATTERN.matcher(prompt);
        StringBuffer buf = new StringBuffer();
        while (m.find())
        {
            String rawName = StrUtil.trimToEmpty(m.group(2));
            String canonical = null;
            for (String key : StoryboardImageReferenceResolver.candidateLookupKeys(rawName))
            {
                canonical = canonicalByKey.get(key);
                if (canonical != null) { break; }
            }
            if (canonical == null)
            {
                // 白名单外：剥壳留纯文字名，语义不丢
                unwrapped.add(rawName);
                m.appendReplacement(buf, Matcher.quoteReplacement(rawName));
            }
            else if (!canonical.equals(rawName))
            {
                // 回退命中：改写为正名，保持 N 不变
                rewritten.add(rawName + "→" + canonical);
                m.appendReplacement(buf,
                        Matcher.quoteReplacement("@图片" + m.group(1) + "[" + canonical + "]"));
            }
            else
            {
                m.appendReplacement(buf, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(buf);
        if (!rewritten.isEmpty() || !unwrapped.isEmpty())
        {
            log.warn("分镜图脚本输出引用消毒: taskId={}, storyboardId={}, 改写={}, 剥壳={}",
                    taskId, storyboardId, rewritten, unwrapped);
        }
        return buf.toString();
    }

    /**
     * 取镜头的展示标识：优先 {@code script_params.镜号}（sortOrder 同步镜像），缺失依次回落 sortOrder / title / id。
     *
     * @param sb   镜头实体
     * @param shot 已解析的 script_params Map
     * @return 用于提示文案的镜头标识
     */
    private String resolveShotLabel(AidStoryboard sb, Map<String, Object> shot)
    {
        Object shotNo = shot.get("镜号");
        if (Objects.nonNull(shotNo) && StrUtil.isNotBlank(String.valueOf(shotNo)))
        {
            return StrUtil.trim(String.valueOf(shotNo));
        }
        if (Objects.nonNull(sb.getSortOrder())) { return String.format("%03d", sb.getSortOrder()); }
        if (StrUtil.isNotBlank(sb.getTitle())) { return StrUtil.trim(sb.getTitle()); }
        return String.valueOf(sb.getId());
    }

    /** 把 inputSnapshot 里反序列化出来的对象安全转成 List<String>（每项 trim、去空），非 List 返回空。 */
    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object raw)
    {
        List<String> result = new ArrayList<>();
        if (raw instanceof List<?> list)
        {
            for (Object o : list)
            {
                if (Objects.isNull(o)) { continue; }
                String s = StrUtil.trim(String.valueOf(o));
                if (StrUtil.isNotBlank(s)) { result.add(s); }
            }
        }
        return result;
    }

    /**
     * 拼装单镜 LLM 用户输入（与"分镜画师"提示词约定的"输入来源"段对齐）。
     * 空字段直接跳过该行，与提示词侧的兜底行为一致。
     */
    private String buildLlmInput(AidStoryboard sb, String aspectRatio,
                                 String videoStyleType, String videoStyleValue,
                                 List<String> referenceAssetNames)
    {
        StringBuilder out = new StringBuilder(2048);

        out.append("【全局风格】\n");
        if (StrUtil.isNotBlank(videoStyleType) && StrUtil.isNotBlank(videoStyleValue))
        {
            out.append(videoStyleType).append(": ").append(videoStyleValue).append('\n');
        }
        else if (StrUtil.isNotBlank(videoStyleType))
        {
            out.append(videoStyleType).append('\n');
        }
        else if (StrUtil.isNotBlank(videoStyleValue))
        {
            out.append(videoStyleValue).append('\n');
        }
        else
        {
            out.append("默认动漫风格\n");
        }
        out.append('\n');

        out.append("【画面比例】\n").append(StrUtil.blankToDefault(aspectRatio, DEFAULT_ASPECT_RATIO)).append('\n').append('\n');

        //    画师直接沿用引用信息中的方位图后缀作为机位基准，不再注入"摄影机朝北"等绝对朝向。
        //      口径与出图解析器 StoryboardImageReferenceResolver 完全一致（含 image_url 非空）。
        //      硬约束分镜画师 @图片N[name] 只能从中精确选取，杜绝杜撰 / 改写 / 引用已软删或改名资产 → 出图"参考图缺失"。
        out.append("【可引用参考图清单】\n");
        if (CollectionUtil.isNotEmpty(referenceAssetNames))
        {
            out.append("（@图片N[名称] 的名称只能从下列精确选取，禁止杜撰、改写、加空格或自行拼接；清单之外的一律不得输出 @图片N 占位）\n");
            for (String nm : referenceAssetNames)
            {
                out.append("- ").append(nm).append('\n');
            }
        }
        else
        {
            out.append("（本剧集暂无可引用参考图，请勿输出任何 @图片N[] 占位，仅用纯文字描述画面）\n");
        }
        out.append('\n');

        out.append("【分镜脚本】\n");
        Map<String, Object> shot = parseScriptParams(sb.getScriptParams());
        // 清洗引用信息里的字典外/杜撰引用（历史脏数据兜底 + 与脚本落库清洗形成双保险），再喂给分镜画师
        sanitizeReferenceInfoInPlace(shot, referenceAssetNames);
        // 输出顺序与提示词侧字段顺序一致；"批内位置"是后端排序用的，跳过
        appendIfNotBlank(out, shot, "镜号");
        appendIfNotBlank(out, shot, "场次序号");
        appendIfNotBlank(out, shot, "剧本内容");
        appendIfNotBlank(out, shot, "画面描述");
        appendIfNotBlank(out, shot, "台词");
        appendIfNotBlank(out, shot, "动作状态");
        appendIfNotBlank(out, shot, "叙事功能");
        appendIfNotBlank(out, shot, "时间坐标");
        appendIfNotBlank(out, shot, "年代坐标");
        appendIfNotBlank(out, shot, "日期坐标");
        appendIfNotBlank(out, shot, "气候天象");
        appendIfNotBlank(out, shot, "引用信息");
        appendIfNotBlank(out, shot, "景别");
        appendIfNotBlank(out, shot, "拍摄角度");
        appendIfNotBlank(out, shot, "镜头焦距");
        appendIfNotBlank(out, shot, "镜头运动");
        appendIfNotBlank(out, shot, "构图");
        appendIfNotBlank(out, shot, "画面氛围");
        // 字典枚举字段，喂给画师用作色彩/光线/曝光虚化维度的输入参考
        appendIfNotBlank(out, shot, "色彩倾向");
        appendIfNotBlank(out, shot, "光线");
        appendIfNotBlank(out, shot, "曝光虚化");
        appendIfNotBlank(out, shot, "音效");

        return out.toString();
    }

    /**
     * 宫格画师（{@code aid_storyboard_grid_painter}，专业版/宫格）专用 LLM 入参装配。
     * 上游脚本由专业版编剧产出「中文镜头组」结构，{@code script_params} 的 key 与漫剧单镜版不同，
     * 此处按镜头组字段装配：剧本内容 / 画面说明 / 台词 / 时空环境 / 引用信息 / 镜头脚本（含站位/朝向/景别天花板，
     * 是宫格画师识别分镜数与构图的核心）等；额外拼【全局风格】【画面比例】【可引用参考图清单】（与漫剧版同口径）。
     * 一个镜头组 = 一次调用 = 一张多宫格图 prompt。
     */
    private String buildLlmInputGrid(AidStoryboard sb, String aspectRatio,
                                     String videoStyleType, String videoStyleValue,
                                     List<String> referenceAssetNames)
    {
        StringBuilder out = new StringBuilder(2048);

        out.append("【全局风格】\n");
        if (StrUtil.isNotBlank(videoStyleType) && StrUtil.isNotBlank(videoStyleValue))
        {
            out.append(videoStyleType).append(": ").append(videoStyleValue).append('\n');
        }
        else if (StrUtil.isNotBlank(videoStyleType))
        {
            out.append(videoStyleType).append('\n');
        }
        else if (StrUtil.isNotBlank(videoStyleValue))
        {
            out.append(videoStyleValue).append('\n');
        }
        else
        {
            out.append("默认动漫风格\n");
        }
        out.append('\n');

        out.append("【画面比例】\n").append(StrUtil.blankToDefault(aspectRatio, DEFAULT_ASPECT_RATIO)).append('\n').append('\n');

        out.append("【可引用参考图清单】\n");
        if (CollectionUtil.isNotEmpty(referenceAssetNames))
        {
            out.append("（@图片N[名称] 的名称只能从下列精确选取，禁止杜撰、改写、加空格或自行拼接；清单之外的一律不得输出 @图片N 占位）\n");
            for (String nm : referenceAssetNames)
            {
                out.append("- ").append(nm).append('\n');
            }
        }
        else
        {
            out.append("（本剧集暂无可引用参考图，请勿输出任何 @图片N[] 占位，仅用纯文字描述画面）\n");
        }
        out.append('\n');

        out.append("【分镜脚本（镜头组）】\n");
        Map<String, Object> group = parseScriptParams(sb.getScriptParams());
        // 清洗引用信息里的字典外/杜撰引用（历史脏数据兜底 + 与脚本落库清洗形成双保险），再喂给宫格画师
        sanitizeReferenceInfoInPlace(group, referenceAssetNames);
        appendIfNotBlank(out, group, "镜头组");
        appendIfNotBlank(out, group, "场次序号");
        appendIfNotBlank(out, group, "剧本内容");
        appendIfNotBlank(out, group, "画面说明");
        appendIfNotBlank(out, group, "台词");
        appendIfNotBlank(out, group, "时空环境");
        appendIfNotBlank(out, group, "引用信息");
        appendIfNotBlank(out, group, "镜头模式");
        appendIfNotBlank(out, group, "运镜等级");
        appendIfNotBlank(out, group, "时长估算");
        // 镜头脚本：含分段站位/朝向/景别天花板，宫格画师据此识别分镜数（四宫格/九宫格）与构图
        appendIfNotBlank(out, group, "镜头脚本");

        return out.toString();
    }

    /**
     * 解析 aid_storyboard.script_params JSON 为 Map（中文 key）。失败返回空 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseScriptParams(String scriptParams)
    {
        if (StrUtil.isBlank(scriptParams))
        {
            return new LinkedHashMap<>();
        }
        try
        {
            return OBJECT_MAPPER.readValue(scriptParams, Map.class);
        }
        catch (Exception e)
        {
            log.warn("分镜图脚本 script_params 解析失败（按空处理）: err={}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void appendIfNotBlank(StringBuilder out, Map<String, Object> shot, String key)
    {
        Object v = shot.get(key);
        String s = (v == null) ? "" : String.valueOf(v);
        if (StrUtil.isNotBlank(s))
        {
            out.append(key).append('：').append(s).append('\n');
        }
    }

    /**
     * 解析 LLM 输出，提取 prompt 字符串。
     * 期望格式：{@code [{"prompt": "..."}]}
     * 容错：剥前后空白 + 剥 markdown 代码块包装 + 容错"对象 / 数组"两种顶层结构。
     * 解析得到 prompt 字符串后，进一步走 {@link #beautifyPromptKeyLineBreaks(String)}
     * 在每个 key 前插入换行符，便于落库与人工阅读；不修改任何字面内容。
     */
    private String parseLlmOutput(String llmRaw)
    {
        JsonNode node = parseLlmJsonNode(llmRaw, true);
        JsonNode promptNode = firstTextNode(node, "prompt", "imagePrompt", "image_prompt", "promptText", "prompt_text");
        if (promptNode != null)
        {
            // 仅插入换行符，不动文字内容
            return beautifyPromptKeyLineBreaks(promptNode.asText(""));
        }
        log.error("分镜图脚本 LLM 输出缺少 prompt 字段: head={}", StrUtil.sub(StrUtil.trimToEmpty(llmRaw), 0, 200));
        return "";
    }

    /**
     * 解析宫格画师（{@code aid_storyboard_grid_painter}）LLM 输出里的宫格类型 {@code grid_type}。
     * 宫格画师输出契约为 {@code [{"grid_type":"四宫格/九宫格","prompt":"..."}]}，本方法取首元素的
     * {@code grid_type} 文本。标准漫剧画师输出无该字段，返回 null。解析失败按 null 处理，不影响主流程。
     *
     * @param llmRaw LLM 原始输出（可能带 markdown 包装）
     * @return 宫格类型字符串（四宫格 / 九宫格）；无该字段或解析失败时返回 null
     */
    private String parseGridType(String llmRaw)
    {
        JsonNode node = parseLlmJsonNode(llmRaw, false);
        JsonNode gridNode = firstTextNode(node, "grid_type", "gridType");
        if (gridNode != null)
        {
            String gridType = StrUtil.trimToNull(gridNode.asText(""));
            // 防御性截断，避免异常超长值撑爆列（grid_type 正常仅「四宫格」/「九宫格」）
            return StrUtil.sub(gridType, 0, 20);
        }
        return null;
    }

    private JsonNode parseLlmJsonNode(String llmRaw, boolean logParseError)
    {
        String text = stripMarkdownFence(StrUtil.trimToEmpty(llmRaw));
        if (StrUtil.isBlank(text))
        {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(text);
        String jsonCandidate = extractFirstJsonBlock(text);
        if (StrUtil.isNotBlank(jsonCandidate) && !Objects.equals(jsonCandidate, text))
        {
            candidates.add(jsonCandidate);
        }
        Exception lastError = null;
        for (String candidate : candidates)
        {
            try
            {
                return OBJECT_MAPPER.readTree(candidate);
            }
            catch (Exception parseException)
            {
                lastError = parseException;
            }
        }
        if (logParseError)
        {
            log.error("分镜图脚本 LLM 输出 JSON 解析失败: head={}, err={}",
                    StrUtil.sub(text, 0, 200), Objects.nonNull(lastError) ? lastError.getMessage() : null);
        }
        return null;
    }

    private String stripMarkdownFence(String text)
    {
        String stripped = StrUtil.trimToEmpty(text);
        if (!stripped.startsWith("```"))
        {
            return stripped;
        }
        int firstNewLine = stripped.indexOf('\n');
        int lastFence = stripped.lastIndexOf("```");
        if (firstNewLine > 0 && lastFence > firstNewLine)
        {
            return stripped.substring(firstNewLine + 1, lastFence).trim();
        }
        return stripped;
    }

    private String extractFirstJsonBlock(String text)
    {
        int arrayStart = text.indexOf('[');
        int objectStart = text.indexOf('{');
        int start = minExistingIndex(arrayStart, objectStart);
        if (start < 0)
        {
            return "";
        }
        char rootOpen = text.charAt(start);
        char rootClose = rootOpen == '[' ? ']' : '}';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++)
        {
            char current = text.charAt(index);
            if (inString)
            {
                if (escaped)
                {
                    escaped = false;
                }
                else if (current == '\\')
                {
                    escaped = true;
                }
                else if (current == '"')
                {
                    inString = false;
                }
                continue;
            }
            if (current == '"')
            {
                inString = true;
            }
            else if (current == rootOpen)
            {
                depth++;
            }
            else if (current == rootClose)
            {
                depth--;
                if (depth == 0)
                {
                    return text.substring(start, index + 1).trim();
                }
            }
        }
        return "";
    }

    private int minExistingIndex(int first, int second)
    {
        if (first < 0)
        {
            return second;
        }
        if (second < 0)
        {
            return first;
        }
        return Math.min(first, second);
    }

    private JsonNode firstTextNode(JsonNode root, String... fieldNames)
    {
        if (Objects.isNull(root))
        {
            return null;
        }
        JsonNode item = root;
        if (root.isArray())
        {
            if (root.size() == 0)
            {
                return null;
            }
            item = root.get(0);
        }
        if (!item.isObject())
        {
            return null;
        }
        for (String fieldName : fieldNames)
        {
            JsonNode value = item.path(fieldName);
            if (value.isTextual() && StrUtil.isNotBlank(value.asText()))
            {
                return value;
            }
        }
        return null;
    }

    /**
     * 把 LLM 输出的 prompt 单段字符串按 key 维度换行。
     */
    private String beautifyPromptKeyLineBreaks(String prompt)
    {
        if (StrUtil.isBlank(prompt))
        {
            return prompt;
        }
        String result = prompt;
        for (String key : PROMPT_KEYS_FOR_NEWLINE)
        {
            // 严格匹配 key + 中文冒号 "："（与提示词模板侧的输出口径一致）
            String marker = key + "：";
            int idx = result.indexOf(marker);
            if (idx <= 0)
            {
                // 未出现 / 在最开头（开头说明它就是段首，不需要换行）→ 跳过
                continue;
            }
            // 如果 key 前已经是 \n，避免重复插入
            if (result.charAt(idx - 1) == '\n')
            {
                continue;
            }
            // 仅插入 \n；保留 key 前原有的所有字符不删
            // 例：原文 "...镜头描述。人物表现：xxx" → "...镜头描述。\n人物表现：xxx"
            result = result.substring(0, idx) + "\n" + result.substring(idx);
        }
        return result;
    }

    private void sendMqMessage(Long taskId, Long projectId, Long episodeId, Long userId, String modelCode)
    {
        // 双模式派发统一收口：MQ 开走 MQ；MQ 关走本地线程（共用同一执行体 + 终态编排）
        boolean enqueued = dualModeTaskDispatcher.dispatch(taskId, projectId, episodeId, userId, modelCode,
                TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH,
                () -> batchTaskLocalOrchestrator.run(taskId, userId, LOCAL_SPEC,
                        () -> doStoryboardImagePromptBatch(taskId, userId),
                        () -> assetExtractService.releaseBatchFormLocks(taskId, TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH)));
        if (!enqueued)
        {
            log.warn("分镜图脚本批量入队失败(可能已取消/推进): taskId={}", taskId);
        }
    }
}
