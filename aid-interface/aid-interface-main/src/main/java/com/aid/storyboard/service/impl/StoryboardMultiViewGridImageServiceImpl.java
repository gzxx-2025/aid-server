package com.aid.storyboard.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aid.agent.AgentDefaultParamsApplier;
import com.aid.agent.AgentModelDefault;
import com.aid.agent.IAidAgentService;
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
import com.aid.rps.helper.AssetExtractHelper;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.sse.AssetExtractSseManager;
import com.aid.service.IAiModelConfigService;
import com.aid.storyboard.dto.StoryboardMultiViewGridImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardMultiViewGridImageGenerateVO;
import com.aid.storyboard.service.IStoryboardMultiViewGridImageService;

import org.springframework.data.redis.core.script.DefaultRedisScript;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜机位生图服务实现（单机位 / 九宫格统一入口）。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class StoryboardMultiViewGridImageServiceImpl implements IStoryboardMultiViewGridImageService
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String STATUS_NORMAL = "0";
    private static final String MODEL_TYPE_IMAGE = "image";

    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_CANCELLED = "CANCELLED";

    /** 单机位任务类型（与 AssetExtractServiceImpl 常量保持一致） */
    public static final String TASK_TYPE_STORYBOARD_MULTI_VIEW_IMAGE = "storyboard_multi_view_image";
    /** 九宫格任务类型（与 AssetExtractServiceImpl 常量保持一致） */
    public static final String TASK_TYPE_STORYBOARD_MULTI_GRID_IMAGE = "storyboard_multi_grid_image";

    /** 单机位功能编码 */
    private static final String FUNC_CODE_IMAGE_MULTI_VIEW = "image_multi_view";
    /** 九宫格功能编码（独立池） */
    private static final String FUNC_CODE_IMAGE_MULTI_GRID = "image_multi_grid";

    /** 单机位智能体编码 */
    private static final String AGENT_CODE_MULTI_CAMERA = "aid_multi_camera";
    /** 九宫格智能体编码 */
    private static final String AGENT_CODE_MULTI_CAMERA_GRID = "aid_multi_camera_grid";

    /** gen_type 常量：单图 / 九宫格 */
    private static final String GEN_TYPE_IMAGE = "image";
    private static final String GEN_TYPE_GRID = "grid";

    /** 图片中间态白名单（轮询用）：含回调优先模式的 WAIT_CALLBACK，防止把中间态误判为失败 */
    private static final Set<String> IMAGE_IN_PROGRESS_STATUSES = Set.of(
            "INIT", "PENDING", "QUEUED", "PROCESSING", "WAIT_POLL", "WAIT_CALLBACK");

    /** 图片生成默认尺寸档（媒体主链路装配兜底） */
    private static final String IMAGE_SIZE_DEFAULT = "2K";
    /** 图片默认比例（请求未传时兜底） */
    private static final String IMAGE_RATIO_DEFAULT = "1:1";

    /** Redis 防重锁 Key 前缀（与 AssetExtractServiceImpl.FORM_LOCK_PREFIX 同一命名空间） */
    private static final String FORM_LOCK_PREFIX = "asset:form:lock:";
    /** 防重锁 TTL（秒）：覆盖图片轮询超时 + 余量 */
    private static final long FORM_LOCK_TTL_SECONDS = 10L * 60L;

    /** 图片轮询参数 */
    private static final long IMAGE_POLL_TIMEOUT_SECONDS = 240L;
    private static final long IMAGE_POLL_INTERVAL_SECONDS = 5L;

    /** 内置兜底模板：当数据库未配置 aid_multi_camera_grid 时使用，避免任务直接失败 */
    private static final String FALLBACK_GRID_TEMPLATE =
            "你是专业的九宫格多机位图像生成助手。\n\n你的任务是：基于参考图像和九组机位指令，"
            + "在一张图里输出 3×3 九宫格布局，每一格都是同一主体的不同机位画面（与参考图保持主体外观、"
            + "场景环境、美术风格、色彩氛围一致）。\n\n【整体要求】\n1. 输出 1 张图，3 行 × 3 列共 9 格，"
            + "从左到右、从上到下编号 1~9；2. 主体一致性：每格主体的形态、颜色、材质、服装/配饰必须与参考图严格一致；"
            + "3. 场景与风格一致：背景、光线、色调、画风必须与参考图保持一致；只改机位和镜头距离；"
            + "4. 子格分隔：相邻子格细黑线分隔；整张图比例为 {aspect_ratio}。\n\n【九组机位指令】\n{angles_block}\n";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    @Autowired
    private RedisCache redisCache;

    @Autowired
    private IAidExtractTaskService extractTaskService;

    @Autowired
    private IAidStoryboardService aidStoryboardService;

    @Autowired
    private IAidGenRecordService aidGenRecordService;

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

    /** 智能体加载器：从 aid_agent 取多机位 / 九宫格 prompt_content */
    @Autowired
    private IAidAgentService aidAgentService;

    /** 复用 AssetExtractHelper.substituteVariables 做模板变量注入 */
    @Autowired
    private AssetExtractHelper helper;

    /** 复用"用户优先、默认兜底"的参数装配器，保证与 /form/generate-image 同样的下层参数行为 */
    @Autowired
    private AgentDefaultParamsApplier agentDefaultParamsApplier;

    /** 复用任务系统的取消语义 / 多维并发名额 / 执行租约 */
    @Autowired
    private IAssetExtractService assetExtractService;

    /** 任务排队 + 多维并发调度 */
    @Autowired
    private com.aid.rps.queue.TaskQueueService taskQueueService;

    /** 复用统一 SSE 管理器：进度 / 终态事件推送 + 终态快照写 Redis */
    @Autowired
    private AssetExtractSseManager sseManager;
    @Override
    public StoryboardMultiViewGridImageGenerateVO generateMultiViewGridImage(
            StoryboardMultiViewGridImageGenerateRequest request, Long userId)
    {
        validateUserId(userId);
        validateBasicRequest(request, userId);

        int angleCount = request.getAngles().size();
        boolean isGrid = angleCount == 9;
        // 路由参数（任务类型 / 智能体编码 / 功能池编码）：由 angles.size() 决定
        String taskType = isGrid ? TASK_TYPE_STORYBOARD_MULTI_GRID_IMAGE : TASK_TYPE_STORYBOARD_MULTI_VIEW_IMAGE;
        String funcCode = isGrid ? FUNC_CODE_IMAGE_MULTI_GRID : FUNC_CODE_IMAGE_MULTI_VIEW;
        String agentCode = isGrid ? AGENT_CODE_MULTI_CAMERA_GRID : AGENT_CODE_MULTI_CAMERA;

        AidStoryboard storyboard = loadAndCheckStoryboard(request.getStoryboardId(), userId);

        AidAiModel model = validateModelInPool(request.getModelCode(), funcCode);

        String aspectRatio = StrUtil.blankToDefault(request.getAspectRatio(), IMAGE_RATIO_DEFAULT);
        String anglesBlock = isGrid ? buildAnglesBlock(request.getAngles()) : null;
        String anglePrompt = isGrid ? null : StrUtil.trimToEmpty(request.getAngles().get(0));
        String finalPrompt = isGrid
                ? buildGridPrompt(anglesBlock, aspectRatio, agentCode, funcCode)
                : buildSinglePrompt(anglePrompt, aspectRatio, agentCode, funcCode);
        if (StrUtil.isBlank(finalPrompt))
        {
            log.error("分镜机位生图模板为空: storyboardId={}, angleCount={}, modelCode={}",
                    storyboard.getId(), angleCount, request.getModelCode());
            throw new RuntimeException("模板异常");
        }

        //    单机位 / 九宫格用不同的锁前缀，互不干扰
        String lockKey = FORM_LOCK_PREFIX + taskType + ":" + storyboard.getId();
        // 锁值用 token：释放时 CAS 校验，任务超过锁 TTL 后旧任务收尾不会误删新任务的锁
        String lockToken = IdUtil.fastSimpleUUID();
        Boolean locked = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockToken, FORM_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (locked == null || !locked)
        {
            log.info("分镜机位生图并发拦截: storyboardId={}, lockKey={}", storyboard.getId(), lockKey);
            throw new RuntimeException("任务处理中");
        }

        try
        {
            return submitTask(request, userId, storyboard, model, finalPrompt,
                    aspectRatio, anglesBlock, anglePrompt, taskType, agentCode, isGrid, lockKey, lockToken);
        }
        catch (RuntimeException e)
        {
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
            log.warn("分镜机位生图锁释放失败: key={}, msg={}", key, e.getMessage());
        }
    }
    private void validateUserId(Long userId)
    {
        if (Objects.isNull(userId) || userId <= 0)
        {
            log.error("分镜机位生图登录态缺失: userId={}", userId);
            throw new RuntimeException("未登录");
        }
    }

    private void validateBasicRequest(StoryboardMultiViewGridImageGenerateRequest request, Long userId)
    {
        if (Objects.isNull(request))
        {
            log.info("分镜机位生图入参为空: userId={}", userId);
            throw new RuntimeException("参数异常");
        }
        if (Objects.isNull(request.getStoryboardId()))
        {
            log.info("分镜机位生图storyboardId为空: userId={}", userId);
            throw new RuntimeException("分镜不存在");
        }
        if (StrUtil.isBlank(request.getImageUrl()))
        {
            log.info("分镜机位生图参考图为空: storyboardId={}", request.getStoryboardId());
            throw new RuntimeException("参考图缺失");
        }
        String url = request.getImageUrl().trim();
        // 仅允许本站资源（相对路径或本站域名完整URL），拒绝站外外链
        if (!mediaUrlResolver.isSiteImageUrl(url))
        {
            log.info("分镜机位生图参考图非本站资源: storyboardId={}, userId={}, url={}",
                    request.getStoryboardId(), userId, url);
            throw new RuntimeException("图片无效");
        }
        // 相对路径拼完整URL后再做远程可达性 + Content-Type 校验
        String fullUrl = mediaUrlResolver.toFullUrl(url);
        if (!ImageUrlValidator.isValidRemoteImageUrl(fullUrl))
        {
            log.info("分镜机位生图参考图非法: storyboardId={}, userId={}, url={}",
                    request.getStoryboardId(), userId, fullUrl);
            throw new RuntimeException("图片无效");
        }
        if (StrUtil.isBlank(request.getModelCode()))
        {
            log.info("分镜机位生图modelCode为空: storyboardId={}", request.getStoryboardId());
            throw new RuntimeException("模型不能空");
        }
        // 机位数量校验：必须 1 或 9，其他数量（0 / 2~8 / >9）一律拒绝
        List<String> angles = request.getAngles();
        int angleCount = (angles == null) ? 0 : angles.size();
        if (angleCount != 1 && angleCount != 9)
        {
            log.info("分镜机位生图机位数不合法: storyboardId={}, count={}",
                    request.getStoryboardId(), angleCount);
            throw new RuntimeException("机位必须1或9个");
        }
        // 逐项 trim 检查（防御 DTO @NotBlank 漏过的纯空白字符）
        for (int i = 0; i < angleCount; i++)
        {
            if (StrUtil.isBlank(angles.get(i)))
            {
                log.info("分镜机位生图第{}个机位为空: storyboardId={}", i + 1, request.getStoryboardId());
                throw new RuntimeException("机位不能空");
            }
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
            log.info("分镜机位生图分镜不存在: storyboardId={}", storyboardId);
            throw new RuntimeException("分镜不存在");
        }
        if (!Objects.equals(userId, storyboard.getUserId()))
        {
            log.info("分镜机位生图归属校验失败: storyboardId={}, owner={}, request={}",
                    storyboardId, storyboard.getUserId(), userId);
            throw new RuntimeException("无权访问");
        }
        return storyboard;
    }

    /**
     * 模型可用范围校验：modelCode 必须存在 + 启用 + {@code model_type=image}，
     * 且 ID 在指定 funcCode 的 {@code aid_ai_model_func_config.model_ids} 列表里。
     *
     * @param modelCode 用户指定的模型编码
     * @param funcCode  路由后选定的功能池（{@code image_multi_view} 或 {@code image_multi_grid}）
     */
    private AidAiModel validateModelInPool(String modelCode, String funcCode)
    {
        LambdaQueryWrapper<AidAiModelFuncConfig> cfgQuery = Wrappers.lambdaQuery();
        cfgQuery.select(AidAiModelFuncConfig::getId, AidAiModelFuncConfig::getFuncCode,
                AidAiModelFuncConfig::getModelIds, AidAiModelFuncConfig::getStatus,
                AidAiModelFuncConfig::getDelFlag);
        cfgQuery.eq(AidAiModelFuncConfig::getFuncCode, funcCode);
        cfgQuery.eq(AidAiModelFuncConfig::getStatus, STATUS_NORMAL);
        cfgQuery.eq(AidAiModelFuncConfig::getDelFlag, DEL_FLAG_NORMAL);
        cfgQuery.last("limit 1");
        AidAiModelFuncConfig cfg = aidAiModelFuncConfigService.getOne(cfgQuery, false);
        if (Objects.isNull(cfg))
        {
            log.error("分镜机位生图功能池未配置: funcCode={}", funcCode);
            throw new RuntimeException("功能未开放");
        }
        List<Long> allowedIds = parseModelIdsJson(cfg.getModelIds());
        if (CollectionUtil.isEmpty(allowedIds))
        {
            log.error("分镜机位生图功能池为空: funcCode={}", funcCode);
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
            log.info("分镜机位生图模型不存在或已停用: modelCode={}", modelCode);
            throw new RuntimeException("模型无效");
        }
        if (!Objects.equals(MODEL_TYPE_IMAGE, model.getModelType()))
        {
            log.info("分镜机位生图模型类型不匹配: modelCode={}, type={}", modelCode, model.getModelType());
            throw new RuntimeException("模型不符");
        }
        if (!allowedIds.contains(model.getId()))
        {
            log.info("分镜机位生图模型不在功能池: modelCode={}, modelId={}, funcCode={}, pool={}",
                    modelCode, model.getId(), funcCode, allowedIds);
            throw new RuntimeException("模型不符");
        }
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
                        // 非数字元素跳过，避免脏数据拖垮主流程
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
            log.error("解析分镜机位生图功能池modelIds失败: raw={}, err={}", modelIdsJson, e.getMessage());
        }
        return ordered;
    }
    /** 单机位提示词：从 {@code aid_agent.aid_multi_camera}（biz=image_multi_view）加载，
     *  注入 {@code {angle_prompt}} / {@code {aspect_ratio}}。智能体缺失时直接报错（与形态侧单机位一致）。 */
    private String buildSinglePrompt(String anglePrompt, String aspectRatio,
                                      String agentCode, String funcCode)
    {
        // 强校验智能体存在 + biz_category 匹配 + 启用
        aidAgentService.getByAgentCodeAndAssertBizCategory(agentCode, funcCode);
        // 提示词正文走统一的「文件优先 → 回源 aid_agent → 回写文件」机制，与角色/场景/道具提取一致
        String template = helper.loadPromptByName(agentCode);
        if (StrUtil.isBlank(template))
        {
            log.error("分镜单机位智能体 prompt_content 为空: agentCode={}", agentCode);
            return null;
        }
        Map<String, String> vars = new HashMap<>();
        vars.put("angle_prompt", StrUtil.blankToDefault(anglePrompt, ""));
        vars.put("aspect_ratio", StrUtil.blankToDefault(aspectRatio, IMAGE_RATIO_DEFAULT));
        return helper.substituteVariables(template, vars);
    }

    /** 九宫格提示词：从 {@code aid_agent.aid_multi_camera_grid}（biz=image_multi_grid）加载，
     *  注入 {@code {angles_block}} / {@code {aspect_ratio}}。DB 智能体缺失时使用代码兜底。 */
    private String buildGridPrompt(String anglesBlock, String aspectRatio,
                                    String agentCode, String funcCode)
    {
        String template = null;
        try
        {
            // 强校验智能体存在 + biz_category 匹配 + 启用；正文走统一文件优先机制（与角色/场景/道具提取一致）
            aidAgentService.getByAgentCodeAndAssertBizCategory(agentCode, funcCode);
            template = helper.loadPromptByName(agentCode);
        }
        catch (Exception e)
        {
            log.warn("分镜九宫格智能体加载失败, 使用内置兜底模板: agentCode={}, err={}", agentCode, e.getMessage());
        }
        if (StrUtil.isBlank(template))
        {
            log.warn("分镜九宫格智能体 prompt_content 为空, 使用内置兜底模板: agentCode={}", agentCode);
            template = FALLBACK_GRID_TEMPLATE;
        }
        Map<String, String> vars = new HashMap<>();
        vars.put("angles_block", StrUtil.trimToEmpty(anglesBlock));
        vars.put("aspect_ratio", StrUtil.blankToDefault(aspectRatio, IMAGE_RATIO_DEFAULT));
        return helper.substituteVariables(template, vars);
    }

    /** 9 个机位拼成 angles_block：每行格式 "格N: 提示词" */
    private String buildAnglesBlock(List<String> angles)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9; i++)
        {
            if (i > 0)
            {
                sb.append("\n");
            }
            sb.append("格").append(i + 1).append(": ").append(StrUtil.trimToEmpty(angles.get(i)));
        }
        return sb.toString();
    }

    /** 任务存档摘要（aid_media_task.prompt 列内容）：仅打包动态入参，跳过智能体 prompt_content 正文 */
    private String buildTaskDigest(String anglePrompt, String anglesBlock, String aspectRatio, boolean isGrid)
    {
        StringBuilder sb = new StringBuilder();
        if (isGrid)
        {
            sb.append("[angles_block]\n").append(StrUtil.trimToEmpty(anglesBlock));
        }
        else
        {
            sb.append("[angle_prompt]\n").append(StrUtil.blankToDefault(anglePrompt, ""));
        }
        sb.append("\n[aspect_ratio]\n").append(StrUtil.blankToDefault(aspectRatio, IMAGE_RATIO_DEFAULT));
        return sb.toString();
    }
    /**
     * 写任务记录 + 入队异步执行。锁由调用方获取，异步结束在 {@code finally} 释放；
     * 入队失败由调用方 catch 释放锁。
     */
    private StoryboardMultiViewGridImageGenerateVO submitTask(
            StoryboardMultiViewGridImageGenerateRequest request, Long userId,
            AidStoryboard storyboard, AidAiModel model, String finalPrompt,
            String aspectRatio, String anglesBlock, String anglePrompt,
            String taskType, String agentCode, boolean isGrid, String lockKey, String lockToken)
    {
        String modelCode = model.getModelCode();
        Long modelId = model.getId();
        // 前端可能传相对路径，下游 provider 需完整可访问 URL
        String referenceImageUrl = mediaUrlResolver.toFullUrl(request.getImageUrl().trim());
        List<String> angles = new ArrayList<>(request.getAngles());

        AidExtractTask task = new AidExtractTask();
        task.setProjectId(storyboard.getProjectId());
        task.setEpisodeId(storyboard.getEpisodeId());
        task.setUserId(userId);
        task.setTaskType(taskType);
        task.setModelCode(modelCode);
        try
        {
            Map<String, Object> inputMap = new LinkedHashMap<>();
            inputMap.put("storyboardId", storyboard.getId());
            inputMap.put("modelCode", modelCode);
            inputMap.put("imageUrl", referenceImageUrl);
            inputMap.put("angles", angles);
            inputMap.put("aspectRatio", aspectRatio);
            inputMap.put("agentCode", agentCode);
            inputMap.put("genType", isGrid ? GEN_TYPE_GRID : GEN_TYPE_IMAGE);
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
            genParamsMap.put("angles", angles);
            genParamsMap.put("agentCode", agentCode);
            genParamsMap.put("genType", isGrid ? GEN_TYPE_GRID : GEN_TYPE_IMAGE);
            genParamsMap.put("imageUrl", referenceImageUrl);
            genParamsJson = OBJECT_MAPPER.writeValueAsString(genParamsMap);
        }
        catch (Exception e)
        {
            log.warn("分镜机位生图 genParams 序列化失败, 降级最小快照: storyboardId={}", storyboard.getId(), e);
            genParamsJson = "{\"storyboardId\":" + storyboard.getId() + "}";
        }
        final String genParamsJsonFinal = genParamsJson;

        boolean enqueued = taskQueueService.submitLocalTask(taskId, storyboard.getProjectId(),
                storyboard.getEpisodeId(), userId, modelCode, taskType,
                () -> runAsync(taskId, userId, storyboard, modelCode, modelId,
                        finalPrompt, referenceImageUrl, aspectRatio, anglesBlock, anglePrompt,
                        taskType, isGrid, genParamsJsonFinal, lockKey, lockToken));
        if (!enqueued)
        {
            log.error("分镜机位生图入队失败: taskId={}, storyboardId={}, taskType={}",
                    taskId, storyboard.getId(), taskType);
            updateTaskFailed(taskId, "提交失败");
            releaseLockSafely(lockKey, lockToken);
            throw new RuntimeException("提交失败");
        }

        return StoryboardMultiViewGridImageGenerateVO.builder()
                .taskId(taskId)
                .status(TASK_STATUS_PENDING)
                .build();
    }

    /**
     * 执行分镜机位生图。
     */
    private void runAsync(Long taskId, Long userId, AidStoryboard storyboard,
                          String modelCode, Long modelId, String finalPrompt,
                          String referenceImageUrl, String aspectRatio,
                          String anglesBlock, String anglePrompt,
                          String taskType, boolean isGrid,
                          String genParamsJson, String lockKey, String lockToken)
    {
        try
        {
            // 取消检查点①：异步启动后
            if (assetExtractService.isTaskCancelled(taskId))
            {
                log.info("分镜机位生图启动前检测到取消: taskId={}, storyboardId={}, taskType={}",
                        taskId, storyboard.getId(), taskType);
                if (updateTaskCancelled(taskId))
                {
                    sseManager.sendCancelled(taskId, "用户取消");
                }
                return;
            }
            if (!updateTaskStatus(taskId, TASK_STATUS_PROCESSING, null, TASK_STATUS_PENDING))
            {
                log.warn("分镜机位生图任务已被其他线程处理: taskId={}", taskId);
                return;
            }
            // 登记执行租约（重启自愈据租约判活）
            assetExtractService.markTaskProcessing(taskId);
            // 取消检查点②：CAS 进 PROCESSING 后
            if (assetExtractService.isTaskCancelled(taskId))
            {
                log.info("分镜机位生图进入PROCESSING后检测到取消: taskId={}, storyboardId={}",
                        taskId, storyboard.getId());
                if (updateTaskCancelled(taskId))
                {
                    sseManager.sendCancelled(taskId, "用户取消");
                }
                return;
            }

            // 调用统一图片生成主链路（计费 / 排队 / OSS 持久化由媒体层统一处理）
            String imageUrl = callImageGeneration(taskId, userId, storyboard, modelCode,
                    finalPrompt, referenceImageUrl, aspectRatio, anglesBlock, anglePrompt,
                    isGrid, taskType);
            if (StrUtil.isBlank(imageUrl))
            {
                log.error("分镜机位生图URL为空: taskId={}, storyboardId={}", taskId, storyboard.getId());
                throw new RuntimeException("图片生成失败");
            }

            // 结果落 aid_gen_record（gen_type 由路由决定）
            String genType = isGrid ? GEN_TYPE_GRID : GEN_TYPE_IMAGE;
            Long recordId = persistGenRecord(storyboard, userId, modelId,
                    finalPrompt, imageUrl, genType, genParamsJson);

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("storyboardId", storyboard.getId());
            resultMap.put("recordId", recordId);
            resultMap.put("imageUrl", imageUrl);
            resultMap.put("genType", genType);
            resultMap.put("anglesCount", isGrid ? 9 : 1);
            resultMap.put("aspectRatio", aspectRatio);
            String resultJson = OBJECT_MAPPER.writeValueAsString(resultMap);

            // 取消检查点③：图已落库后；若取消则保留结果（与 image_upscale / 单机位 / 编辑图同语义）
            if (assetExtractService.isTaskCancelled(taskId))
            {
                if (updateTaskCancelledWithResult(taskId, resultJson))
                {
                    sseManager.sendCancelled(taskId, "用户取消");
                    log.info("分镜机位生图完成但检测到取消(resultData已保留): taskId={}, storyboardId={}, recordId={}",
                            taskId, storyboard.getId(), recordId);
                }
                return;
            }

            if (updateTaskSuccess(taskId, 1, resultJson))
            {
                sseManager.sendComplete(taskId, resultMap);
                log.info("分镜机位生图完成: taskId={}, storyboardId={}, recordId={}, taskType={}",
                        taskId, storyboard.getId(), recordId, taskType);
            }
        }
        catch (TaskCancelledException e)
        {
            log.info("分镜机位生图任务执行中被取消: taskId={}, storyboardId={}",
                    taskId, storyboard.getId());
            if (updateTaskCancelled(taskId))
            {
                sseManager.sendCancelled(taskId, "用户取消");
            }
        }
        catch (Exception e)
        {
            log.error("分镜机位生图任务失败: taskId={}, storyboardId={}, taskType={}",
                    taskId, storyboard.getId(), taskType, e);
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

    /** 调用图片生成服务生成机位图。 */
    private String callImageGeneration(Long taskId, Long userId, AidStoryboard storyboard,
                                        String modelCode, String finalPrompt, String referenceImageUrl,
                                        String aspectRatio, String anglesBlock, String anglePrompt,
                                        boolean isGrid, String taskType)
    {
        MediaImageGenerateRequest imageRequest = new MediaImageGenerateRequest();
        imageRequest.setModelName(modelCode);
        imageRequest.setUserId(userId);
        imageRequest.setPrompt(finalPrompt);
        imageRequest.setTaskPromptDigest(buildTaskDigest(anglePrompt, anglesBlock, aspectRatio, isGrid));
        imageRequest.setProjectId(storyboard.getProjectId());
        imageRequest.setEpisodeId(storyboard.getEpisodeId());
        imageRequest.setReferenceImageUrl(referenceImageUrl);

        Map<String, Object> options = new HashMap<>();
        options.put("force_single", true);
        if (StrUtil.isNotBlank(aspectRatio))
        {
            options.put("aspect_ratio", aspectRatio);
        }
        imageRequest.setOptions(options);

        imageRequest.setSize(IMAGE_SIZE_DEFAULT);
        imageRequest.setExpectedImageCount(1);
        imageRequest.setBizTaskId(taskId);
        imageRequest.setBizTaskType(taskType);

        AiModelConfigVo defaultModelConfig = aiModelConfigService.selectByModelCode(modelCode);
        if (Objects.isNull(defaultModelConfig))
        {
            log.error("分镜机位生图模型配置缺失: modelCode={}", modelCode);
            throw new RuntimeException("模型无效");
        }
        AgentModelDefault agentModel = new AgentModelDefault(modelCode);
        agentDefaultParamsApplier.applyToImage(agentModel, imageRequest, defaultModelConfig);

        MediaTaskResponse imageResponse = mediaGenerationService.generateImage(imageRequest);
        return resolveImageUrl(taskId, imageResponse);
    }

    private static final class TaskCancelledException extends RuntimeException
    {
        TaskCancelledException()
        {
            super("用户取消");
        }
    }

    /** 解析图片生成响应：支持同步 / 异步，异步轮询期间响应取消。 */
    private String resolveImageUrl(Long taskId, MediaTaskResponse imageResponse)
    {
        if (Objects.isNull(imageResponse))
        {
            throw new RuntimeException("图片生成失败");
        }
        if (TASK_STATUS_SUCCEEDED.equals(imageResponse.getStatus()))
        {
            if (StrUtil.isBlank(imageResponse.getOssUrl()))
            {
                log.error("分镜机位生图同步成功但 ossUrl 为空: mediaTaskId={}", imageResponse.getTaskId());
                throw new RuntimeException("存储失败");
            }
            return imageResponse.getOssUrl();
        }
        if (!IMAGE_IN_PROGRESS_STATUSES.contains(imageResponse.getStatus()))
        {
            String errorMsg = imageResponse.getErrorMessage();
            log.error("分镜机位生图失败: mediaTaskId={}, status={}, error={}",
                    imageResponse.getTaskId(), imageResponse.getStatus(), errorMsg);
            throw new RuntimeException(StrUtil.isNotBlank(errorMsg) ? errorMsg : "图片生成失败");
        }

        Long mediaTaskId = imageResponse.getTaskId();
        if (Objects.isNull(mediaTaskId))
        {
            log.error("分镜机位生图异步任务缺少 taskId");
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
                log.info("分镜机位生图轮询期间检测到取消, 停止等待: taskId={}, mediaTaskId={}",
                        taskId, mediaTaskId);
                throw new TaskCancelledException();
            }
            MediaTaskResponse polled = mediaGenerationService.queryTaskRefresh(mediaTaskId);
            if (Objects.isNull(polled))
            {
                log.error("分镜机位生图轮询返回空: mediaTaskId={}", mediaTaskId);
                throw new RuntimeException("图片生成失败");
            }
            if (TASK_STATUS_SUCCEEDED.equals(polled.getStatus()))
            {
                if (StrUtil.isBlank(polled.getOssUrl()))
                {
                    log.warn("分镜机位生图成功但 ossUrl 暂空: mediaTaskId={}", mediaTaskId);
                    continue;
                }
                return polled.getOssUrl();
            }
            if (TASK_STATUS_FAILED.equals(polled.getStatus()))
            {
                String errorMsg = polled.getErrorMessage();
                log.error("分镜机位生图异步失败: mediaTaskId={}, error={}", mediaTaskId, errorMsg);
                throw new RuntimeException(StrUtil.isNotBlank(errorMsg) ? errorMsg : "图片生成失败");
            }
        }
        log.error("分镜机位生图异步超时: mediaTaskId={}, timeout={}s", mediaTaskId, IMAGE_POLL_TIMEOUT_SECONDS);
        throw new RuntimeException("图片生成超时");
    }
    /** 落地一条 {@code aid_gen_record}。{@code gen_type} 由路由决定（{@code image} / {@code grid}）。 */
    private Long persistGenRecord(AidStoryboard storyboard, Long userId, Long modelId,
                                   String finalPrompt, String imageUrl, String genType,
                                   String genParamsJson)
    {
        AidGenRecord record = new AidGenRecord();
        record.setUserId(userId);
        // 冗余存项目 / 剧集，便于按 (project, episode) 维度反查 aid_gen_record
        record.setProjectId(storyboard.getProjectId());
        record.setEpisodeId(storyboard.getEpisodeId());
        record.setStoryboardId(storyboard.getId());
        record.setGenType(genType);
        record.setModelId(modelId);
        record.setPromptText(finalPrompt);
        // 本接口无单独"用户输入文本"字段，userInputText 留空；如需补充可前端提供
        record.setFileUrl(imageUrl);
        record.setGenParams(genParamsJson);
        record.setStatus(1); // 1=成功
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
            log.warn("分镜机位生图成功CAS未命中: taskId={}", taskId);
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
            log.warn("分镜机位生图失败CAS未命中: taskId={}", taskId);
            return false;
        }
        return true;
    }

    private boolean updateTaskFailed(Long taskId, com.aid.common.error.TaskErrorResult errorResult)
    {
        String dbMessage = errorResult.getRawMessage() != null
                ? errorResult.getRawMessage() : errorResult.getUserMessage();
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
            log.warn("分镜机位生图取消CAS未命中: taskId={}", taskId);
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
            log.warn("分镜机位生图取消(保留结果)CAS未命中: taskId={}", taskId);
            return false;
        }
        return true;
    }
}
