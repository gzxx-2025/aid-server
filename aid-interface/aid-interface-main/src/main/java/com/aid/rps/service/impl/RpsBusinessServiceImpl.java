package com.aid.rps.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.domain.AidPromptLib;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.domain.AidScenePlot;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidComicScriptService;
import com.aid.aid.service.IAidPromptLibService;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidScenePlotService;
import com.aid.common.utils.DateUtils;
import com.aid.common.vo.BatchOperationResultVO;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.service.IMediaGenerationService;
import com.aid.rps.dto.RpsCreateRequest;
import com.aid.rps.dto.RpsDeleteRequest;
import com.aid.rps.dto.RpsFormCreateRequest;
import com.aid.rps.dto.RpsFormListRequest;
import com.aid.rps.dto.RpsQueryRequest;
import com.aid.rps.dto.RpsUpdateFormRequest;
import com.aid.rps.dto.RpsUpdateMainRequest;
import com.aid.rps.service.IRpsBusinessService;
import com.aid.rps.vo.RpsAssetVO;
import com.aid.rps.vo.RpsFormImageVO;
import com.aid.rps.vo.RpsFormVO;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色道具场景资产业务Service实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class RpsBusinessServiceImpl implements IRpsBusinessService {
    /**
     * 软删除标志：删除
     */
    private static final String DEL_FLAG_DELETED = "2";
    /**
     * 软删除标志：正常
     */
    private static final String DEL_FLAG_NORMAL = "0";
    /**
     * 项目类型：电影
     */
    private static final String PROJECT_TYPE_MOVIE = "movie";
    /**
     * 项目类型：剧集
     */
    private static final String PROJECT_TYPE_SERIES = "series";
    /**
     * 主表资产类型白名单（角色/场景/道具）
     */
    private static final List<String> MAIN_ASSET_TYPES = Arrays.asList("scene", "character", "prop");
    /**
     * 从表来源类型
     */
    private static final List<String> SOURCE_TYPES = Arrays.asList("upload", "official", "ai");
    /**
     * 从表来源类型：仅上传/官方导入
     */
    private static final List<String> UPLOAD_OFFICIAL_TYPES = Arrays.asList("upload", "official");
    /**
     * AI提取任务成功状态
     */
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    /**
     * 资产类型常量
     */
    private static final String ASSET_TYPE_CHARACTER = "character";
    private static final String ASSET_TYPE_SCENE = "scene";
    private static final String ASSET_TYPE_PROP = "prop";
    /**
     * 初始形态固定变更原因
     */
    private static final String INITIAL_FORM_CHANGE_REASON = "初始形象";
    /**
     * 视觉描述状态：待生成
     */
    private static final String VISUAL_DESC_STATUS_PENDING = "pending";
    /**
     * 视觉描述状态：已完成
     */
    private static final String VISUAL_DESC_STATUS_COMPLETED = "completed";

    @Autowired
    private IAidRolePropSceneService rpsService;

    /**
     * 自身代理引用（@Lazy 避免循环依赖）：批量 use/unuse 时借此调用 @Transactional 单条方法，
     * 使每条目走独立事务——单条失败不牵连其它条目（self-invocation 直接调本类方法会绕过 AOP 事务代理）。
     */
    @Lazy
    @Autowired
    private IRpsBusinessService self;
    @Autowired
    private IAidRolePropSceneFormService rpsFormService;

    /** 形态图片实例服务，查询 / 创建需走 form_image 表 */
    @Autowired
    private IAidRolePropSceneFormImageService rpsFormImageService;
    /** OSS 文件清理服务：硬删形态图时先删其 OSS 文件 */
    @Autowired
    private com.aid.media.cleanup.IMediaOssCleanupService mediaOssCleanupService;

    /**
     * 剧情节拍（场次）服务。
     * 删除 scene 资产时需级联软删其名下的 aid_scene_plot 场次，
     * 否则孤儿场次会被 SceneCodeAllocator 误算进"已存量最大场次号"，
     * 导致重新提取时场次号不从 001 接续。
     */
    @Autowired
    private IAidScenePlotService scenePlotService;

    @Autowired
    private IAidComicProjectService projectService;

    @Autowired
    private IAidComicEpisodeService episodeService;

    @Autowired
    private IAidComicScriptService scriptService;

    @Autowired
    private IMediaGenerationService mediaGenerationService;

    @Autowired
    private IAidPromptLibService promptLibService;

    /**
     * 角色音色绑定业务 Service。
     * 主资产列表 / 单条 character 相关返回时顺带填充 voiceBinding，
     * 让前端一次拿齐"人物信息 + 音色信息"，不再二次请求。
     */
    @Autowired
    private com.aid.rps.voice.service.IRoleVoiceBindingBusinessService roleVoiceBindingBusinessService;

    /**
     * 角色音色自动匹配绑定 Service：角色改性别/年龄后即时重绑（仅自动提取角色）。
     */
    @Autowired
    private com.aid.rps.voice.service.IRoleVoiceAutoBindService roleVoiceAutoBindService;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 创建来源：手动 */
    private static final String CREATE_SOURCE_MANUAL = "manual";
    /** 创建来源：自动 */
    private static final String CREATE_SOURCE_AUTO = "auto";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RpsAssetVO createAsset(RpsCreateRequest request, Long userId) {
        // 校验资产类型
        validateMainAssetType(request.getAssetType());

        // 查询项目，判断项目类型
        AidComicProject project = projectService.selectAidComicProjectById(request.getProjectId());
        // 归属校验：项目存在 + 未删除 + 归属当前用户；统一返回"项目不存在"，
        // 不区分"不存在/越权"，避免通过文案差异枚举他人 projectId。
        if (Objects.isNull(project)
                || !DEL_FLAG_NORMAL.equals(project.getDelFlag())
                || !Objects.equals(project.getUserId(), userId)) {
            log.info("资产创建失败，项目不存在或无权访问: projectId={}, userId={}", request.getProjectId(), userId);
            throw new RuntimeException("项目不存在");
        }

        // ---- 手动轻量创建：只校验 name 必填，其余字段可空 ----
        // name 必填校验
        if (StrUtil.isBlank(request.getName())) {
            log.info("手动创建资产失败：name为空, userId={}", userId);
            throw new RuntimeException("名称不能为空");
        }

        // 构建主表实体（轻量壳数据）
        AidRolePropScene entity = new AidRolePropScene();
        entity.setProjectId(request.getProjectId());
        entity.setUserId(userId);
        entity.setAssetType(request.getAssetType());
        // 手动创建标记
        entity.setCreateSource(CREATE_SOURCE_MANUAL);
        // 基础字段：name + aliases
        entity.setName(request.getName());
        if (StrUtil.isNotBlank(request.getAliasesName())) {
            entity.setAliasesName(request.getAliasesName());
        }
        entity.setDelFlag(DEL_FLAG_NORMAL);
        entity.setCreateTime(DateUtils.getNowDate());
        entity.setCreateBy(String.valueOf(userId));

        // 根据项目类型处理episodeId
        if (Objects.equals(PROJECT_TYPE_MOVIE, project.getProjectType())) {
            // 电影模式：episodeId固定为0
            entity.setEpisodeId(0L);
        } else if (Objects.equals(PROJECT_TYPE_SERIES, project.getProjectType())) {
            // 剧集模式：校验episodeId存在且归属于该projectId
            Long episodeId = request.getEpisodeId();
            if (Objects.isNull(episodeId)) {
                log.info("资产创建失败，剧集模式下剧集ID不能为空: projectId={}", request.getProjectId());
                throw new RuntimeException("剧集ID不能为空");
            }
            AidComicEpisode episode = episodeService.selectAidComicEpisodeById(episodeId);
            if (Objects.isNull(episode)) {
                log.info("资产创建失败，剧集不存在: episodeId={}", episodeId);
                throw new RuntimeException("剧集不存在");
            }
            if (!Objects.equals(episode.getProjectId(), request.getProjectId())) {
                log.info("资产创建失败，剧集与项目不匹配: projectId={}, episodeId={}", request.getProjectId(), episodeId);
                throw new RuntimeException("剧集与项目不匹配");
            }
            // 双重防御：即使项目已通过归属校验，仍单独校验剧集归属当前用户，
            // 防止剧集与项目归属不一致的脏数据被利用。
            if (!Objects.equals(episode.getUserId(), userId)) {
                log.info("资产创建失败，剧集不属于当前用户: episodeId={}, userId={}", episodeId, userId);
                throw new RuntimeException("剧集不存在");
            }
            entity.setEpisodeId(episodeId);
        } else {
            log.info("资产创建失败，未知项目类型: projectType={}", project.getProjectType());
            throw new RuntimeException("项目类型异常");
        }

        rpsService.save(entity);

        // 手动创建：三类资产统一行为，主表落库后自动生成一条手动壳 form（与 scene / prop / character 对齐）
        // 该 form 同样标记为 manual，name 与主资产同名，其它描述类字段留空，后续由 form/update 补齐
        AidRolePropSceneForm initialForm = createInitialManualFormForAsset(entity, userId);
        List<RpsFormVO> formVOs = new ArrayList<>();
        formVOs.add(convertToFormVO(initialForm, entity.getAssetType()));
        return convertToAssetVO(entity, formVOs);
    }

    /**
     * 为手动创建的主资产生成一条手动壳 form（三类资产统一走这条路径）。
     * 区别于 {@link #createInitialFormForAsset}：不尝试从主表字段构造 promptText，
     * 一律置为空串并标记 visual_desc_status=pending / create_source=manual。
     * 后续具体内容由 `/api/user/asset/rps/update-form` 补齐。
     */
    private AidRolePropSceneForm createInitialManualFormForAsset(AidRolePropScene asset, Long userId)
    {
        AidRolePropSceneForm form = new AidRolePropSceneForm();
        form.setAssetId(asset.getId());
        form.setProjectId(asset.getProjectId());
        form.setEpisodeId(asset.getEpisodeId());
        form.setUserId(userId);
        // 初始 form 名称与资产同名，可被后续用户重命名
        form.setName(asset.getName());
        form.setChangeReason(INITIAL_FORM_CHANGE_REASON);
        // 手动创建标记（与主资产一致）
        form.setCreateSource(CREATE_SOURCE_MANUAL);
        // 手动壳 form：promptText 为空，视觉描述状态 pending，后续由 update-form 补齐
        form.setVisualDescStatus(VISUAL_DESC_STATUS_PENDING);
        form.setDelFlag(DEL_FLAG_NORMAL);
        form.setCreateTime(DateUtils.getNowDate());
        form.setCreateBy(String.valueOf(userId));
        rpsFormService.save(form);
        return form;
    }

    /**
     * 为刚创建的主资产创建一条缺省初始 form（占位策略）。
     * 后续角色 generateForm 会按 (assetId, changeReason) upsert，不会重复插入。
     */
    private AidRolePropSceneForm createInitialFormForAsset(AidRolePropScene asset, Long userId)
    {
        AidRolePropSceneForm form = new AidRolePropSceneForm();
        form.setAssetId(asset.getId());
        form.setProjectId(asset.getProjectId());
        form.setEpisodeId(asset.getEpisodeId());
        form.setUserId(userId);
        // 初始 form 名称与资产同名，可被后续 AI 生成/用户重命名覆盖
        form.setName(asset.getName());
        form.setChangeReason(INITIAL_FORM_CHANGE_REASON);
        // 自动提取链路标记
        form.setCreateSource(CREATE_SOURCE_AUTO);
        // 创建主表时同步补齐初始 promptText，避免初始 form 因关键字段为空而无法直接生图。
        String promptText = buildInitialFormPromptText(asset);
        form.setPromptText(promptText);
        form.setVisualDescStatus(StrUtil.isNotBlank(promptText)
                ? VISUAL_DESC_STATUS_COMPLETED : VISUAL_DESC_STATUS_PENDING);
        // form.is_use 字段已删除，使用状态由 form_image.is_use 承担
        form.setDelFlag(DEL_FLAG_NORMAL);
        form.setCreateTime(DateUtils.getNowDate());
        form.setCreateBy(String.valueOf(userId));
        rpsFormService.save(form);
        return form;
    }

    /**
     * 为主表创建阶段自动补齐一条可直接用于生图的初始 form promptText。
     * 场景/道具严格对齐主表提取后的结构化 JSON；角色使用主表 introduction 兜底生成单条 descriptions。
     */
    private String buildInitialFormPromptText(AidRolePropScene asset)
    {
        if (Objects.isNull(asset) || StrUtil.isBlank(asset.getAssetType()))
        {
            return null;
        }
        if (Objects.equals(ASSET_TYPE_CHARACTER, asset.getAssetType()))
        {
            return buildStructuredCharacterPromptText(asset, INITIAL_FORM_CHANGE_REASON);
        }
        if (Objects.equals(ASSET_TYPE_SCENE, asset.getAssetType()))
        {
            return buildStructuredScenePromptText(asset, INITIAL_FORM_CHANGE_REASON);
        }
        if (Objects.equals(ASSET_TYPE_PROP, asset.getAssetType()))
        {
            return buildStructuredPropPromptText(asset, INITIAL_FORM_CHANGE_REASON);
        }
        return null;
    }

    /**
     * 构建角色初始 form 的规范化 promptText。
     */
    private String buildStructuredCharacterPromptText(AidRolePropScene asset, String changeReason)
    {
        // descriptions 优先取 introduction，为空则回退到 name，保证不为空
        String assetName = Objects.nonNull(asset) ? StrUtil.blankToDefault(asset.getName(), "") : "";
        String descriptions = Objects.nonNull(asset)
                ? StrUtil.blankToDefault(asset.getIntroduction(), assetName) : assetName;
        try
        {
            com.fasterxml.jackson.databind.node.ObjectNode root = OBJECT_MAPPER.createObjectNode();
            root.put("assetType", ASSET_TYPE_CHARACTER);
            root.put("promptVersion", "v2");
            root.put("name", assetName);
            root.put("appearanceId", 0);
            root.put("changeReason", StrUtil.blankToDefault(changeReason, ""));
            root.put("descriptions", descriptions);
            return OBJECT_MAPPER.writeValueAsString(root);
        }
        catch (Exception e)
        {
            log.warn("创建初始角色 form promptText 失败，降级为纯文本: assetId={}, err={}",
                    Objects.nonNull(asset) ? asset.getId() : null, e.getMessage());
            return descriptions;
        }
    }

    /**
     * 构建场景初始 form 的规范化 promptText（stylist LLM 输出协议骨架）。
     * 初始 form 仅放最小骨架（title / promptType / 空 prompt 等），后续由 form/generate 调
     * aid_scene_stylist LLM 跑出完整 JSON 后覆盖。
     */
    private String buildStructuredScenePromptText(AidRolePropScene asset, String changeReason)
    {
        String assetName = Objects.nonNull(asset) ? StrUtil.blankToDefault(asset.getName(), "") : "";
        try
        {
            com.fasterxml.jackson.databind.node.ObjectNode root = OBJECT_MAPPER.createObjectNode();
            root.put("assetType", ASSET_TYPE_SCENE);
            root.put("promptVersion", "v2");
            root.put("name", assetName);
            root.put("changeReason", StrUtil.blankToDefault(changeReason, ""));
            // stylist LLM 输出格式骨架字段（首次创建时为空，待 form/generate 跑 LLM 后覆盖）
            root.put("title", assetName);
            root.put("sceneId", assetName);
            root.put("promptType", "aid_scene_four_view");
            root.put("aspectRatio", "");
            root.put("prompt", "");
            root.putNull("viewpoints");
            root.put("imageUsage", "");
            root.putNull("reference");
            return OBJECT_MAPPER.writeValueAsString(root);
        }
        catch (Exception e)
        {
            log.warn("创建初始场景 form promptText 失败，降级为空: assetId={}, err={}",
                    Objects.nonNull(asset) ? asset.getId() : null, e.getMessage());
            return "";
        }
    }

    /**
     * 从主资产 profile_data JSON 抽 5 个时空字段拼装为「年代-时间-日期-气候-环境类型」一句话。
     * 例如:「古代-大景王朝-中国,上午,春季-普通工作日,温和-阴-药香浮尘,城市」。
     * 缺失字段自适应跳过,不留空逗号;解析失败返回空串。
     */
    private String buildSceneContextFromProfile(AidRolePropScene asset)
    {
        if (Objects.isNull(asset) || StrUtil.isBlank(asset.getProfileData()))
        {
            return "";
        }
        try
        {
            com.fasterxml.jackson.databind.JsonNode root = OBJECT_MAPPER.readTree(asset.getProfileData());
            if (Objects.isNull(root) || !root.isObject())
            {
                return "";
            }
            String[] keys = new String[]{
                    "era_coordinate",
                    "time_of_day",
                    "date_coordinate",
                    "weather",
                    "environment_type"
            };
            java.util.List<String> parts = new java.util.ArrayList<>(keys.length);
            for (String key : keys)
            {
                com.fasterxml.jackson.databind.JsonNode node = root.get(key);
                if (Objects.nonNull(node) && node.isTextual())
                {
                    String val = node.asText("").trim();
                    if (StrUtil.isNotBlank(val))
                    {
                        parts.add(val);
                    }
                }
            }
            return parts.isEmpty() ? "" : String.join(",", parts);
        }
        catch (Exception e)
        {
            log.warn("拼装 scene_context 失败,降级为空串: assetId={}, err={}",
                    Objects.nonNull(asset) ? asset.getId() : null, e.getMessage());
            return "";
        }
    }

    /**
     * 构建道具初始 form 的规范化 promptText。
     */
    /**
     * 构建道具初始 form 的规范化 promptText（stylist LLM 输出协议骨架）。
     * 初始 form 仅放最小骨架，后续由 form/generate 调 aid_prop_stylist LLM 跑出完整 JSON 后覆盖。
     */
    private String buildStructuredPropPromptText(AidRolePropScene asset, String changeReason)
    {
        String assetName = Objects.nonNull(asset) ? StrUtil.blankToDefault(asset.getName(), "") : "";
        try
        {
            com.fasterxml.jackson.databind.node.ObjectNode root = OBJECT_MAPPER.createObjectNode();
            root.put("assetType", ASSET_TYPE_PROP);
            root.put("promptVersion", "v2");
            root.put("name", assetName);
            root.put("changeReason", StrUtil.blankToDefault(changeReason, ""));
            // stylist LLM 输出格式骨架字段
            root.put("title", assetName);
            root.put("promptType", "text_to_image");
            root.put("prompt", "");
            return OBJECT_MAPPER.writeValueAsString(root);
        }
        catch (Exception e)
        {
            log.warn("创建初始道具 form promptText 失败，降级为空: assetId={}, err={}",
                    Objects.nonNull(asset) ? asset.getId() : null, e.getMessage());
            return "";
        }
    }

    /**
     * 将主表 available_slots JSON 字符串解析为数组节点；非法或空值时降级为空数组。
     */
    private com.fasterxml.jackson.databind.node.ArrayNode parseAvailableSlotsToArrayNode(String availableSlotsJson)
    {
        if (StrUtil.isBlank(availableSlotsJson))
        {
            return OBJECT_MAPPER.createArrayNode();
        }
        try
        {
            JsonNode arr = OBJECT_MAPPER.readTree(availableSlotsJson);
            if (Objects.nonNull(arr) && arr.isArray())
            {
                return (com.fasterxml.jackson.databind.node.ArrayNode) arr;
            }
        }
        catch (Exception e)
        {
            log.warn("解析初始 form available_slots 失败，降级为空数组: err={}", e.getMessage());
        }
        return OBJECT_MAPPER.createArrayNode();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RpsAssetVO createForm(RpsFormCreateRequest request, Long userId) {
        // 本接口职责已收敛为"只创建 form 形态定义"，sourceType 不再参与图片创建逻辑。
        // 仅当 sourceType 非空时校验白名单（兼容保留字段）；为空跳过，不再抛错。
        String sourceType = request.getSourceType();
        if (StrUtil.isNotBlank(sourceType) && !UPLOAD_OFFICIAL_TYPES.contains(sourceType)) {
            log.info("从表创建失败，来源类型错误: sourceType={}", sourceType);
            throw new RuntimeException("来源类型错误");
        }
        // 校验主表存在（归属校验在 getMainAssetOrThrow 内做）
        AidRolePropScene mainAsset = getMainAssetOrThrow(request.getAssetId(), userId);

        // projectId / episodeId 以 mainAsset 为准，不信任前端传入。
        // 防止"把自己的 form 挂到他人主资产下"或"跨项目污染"。
        Long effectiveProjectId = mainAsset.getProjectId();

        // 查询项目，判断项目类型
        AidComicProject project = projectService.selectAidComicProjectById(effectiveProjectId);
        if (Objects.isNull(project)) {
            log.info("从表创建失败，项目不存在: projectId={}", effectiveProjectId);
            throw new RuntimeException("项目不存在");
        }

        // ---- 手动轻量创建形态：只需要 name（可选）+ changeReason（可选），其余字段可空 ----
        // name 兜底：优先用入参 name，否则用 changeReason，最终兜底"未命名形态"
        String formName;
        if (StrUtil.isNotBlank(request.getName())) {
            formName = request.getName();
        } else if (StrUtil.isNotBlank(request.getChangeReason())) {
            formName = request.getChangeReason();
        } else {
            formName = "未命名形态";
        }

        // 构建从表实体（轻量壳数据）
        AidRolePropSceneForm form = new AidRolePropSceneForm();
        form.setAssetId(request.getAssetId());
        // 强制用 mainAsset 的 projectId，不采用 request 的
        form.setProjectId(effectiveProjectId);
        form.setUserId(userId);
        form.setName(formName);
        // 写入变更原因（可空）
        if (StrUtil.isNotBlank(request.getChangeReason())) {
            form.setChangeReason(request.getChangeReason());
        }
        // 手动创建标记
        form.setCreateSource(CREATE_SOURCE_MANUAL);
        // 手动创建时 promptText 为空，visualDescStatus 为 pending，后续通过 update 补齐
        form.setVisualDescStatus(VISUAL_DESC_STATUS_PENDING);
        form.setDelFlag(DEL_FLAG_NORMAL);
        form.setCreateTime(DateUtils.getNowDate());
        form.setCreateBy(String.valueOf(userId));

        // 根据项目类型处理episodeId
        if (Objects.equals(PROJECT_TYPE_MOVIE, project.getProjectType())) {
            form.setEpisodeId(0L);
        } else if (Objects.equals(PROJECT_TYPE_SERIES, project.getProjectType())) {
            // 剧集 ID 以 mainAsset 为准，前端传入仅用于一致性校验
            Long effectiveEpisodeId = mainAsset.getEpisodeId();
            if (Objects.isNull(effectiveEpisodeId) || effectiveEpisodeId == 0L) {
                log.info("从表创建失败，主资产无有效 episodeId: projectId={}, assetId={}",
                        effectiveProjectId, request.getAssetId());
                throw new RuntimeException("主资产剧集信息异常");
            }
            if (request.getEpisodeId() != null
                    && !Objects.equals(request.getEpisodeId(), effectiveEpisodeId)) {
                log.info("从表创建拒绝，episodeId 与主资产不一致: request={}, mainAsset={}",
                        request.getEpisodeId(), effectiveEpisodeId);
                throw new RuntimeException("剧集与主资产不匹配");
            }
            form.setEpisodeId(effectiveEpisodeId);
        } else {
            log.info("从表创建失败，未知项目类型: projectType={}", project.getProjectType());
            throw new RuntimeException("项目类型异常");
        }

        rpsFormService.save(form);

        // 返回完整主表+所有从表
        return buildAssetVO(mainAsset);
    }

    @Override
    public RpsAssetVO createAiForm(RpsFormCreateRequest request, Long userId) {
        // TODO: AI生成从表形态逻辑待实现
        log.info("AI生成从表形态接口待实现, userId={}, assetId={}", userId, request.getAssetId());
        throw new RuntimeException("功能开发中");
    }

    /**
     * 查询主资产列表（消 N+1 + 仅回 is_use=1 图片）。
     */
    @Override
    public List<RpsAssetVO> queryAssetList(RpsQueryRequest request, Long userId) {
        // 查询主表
        LambdaQueryWrapper<AidRolePropScene> wrapper = Wrappers.lambdaQuery();
        if (Objects.nonNull(request.getProjectId())) {
            wrapper.eq(AidRolePropScene::getProjectId, request.getProjectId());
        }
        if (Objects.nonNull(request.getEpisodeId()) && request.getEpisodeId() > 0L) {
            // 按集浏览时项目级资产（剧集角色/复用资产，episodeId=0）恒可见
            wrapper.in(AidRolePropScene::getEpisodeId, 0L, request.getEpisodeId());
        } else if (Objects.nonNull(request.getEpisodeId())) {
            wrapper.eq(AidRolePropScene::getEpisodeId, request.getEpisodeId());
        }
        wrapper.eq(AidRolePropScene::getUserId, userId);
        wrapper.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
        if (StrUtil.isNotBlank(request.getAssetType())) {
            wrapper.eq(AidRolePropScene::getAssetType, request.getAssetType());
        }
        wrapper.orderByDesc(AidRolePropScene::getCreateTime);
        List<AidRolePropScene> mainList = rpsService.list(wrapper);

        if (CollectionUtil.isEmpty(mainList)) {
            return new ArrayList<>();
        }

        // 批量查从表
        List<Long> mainIds = mainList.stream().map(AidRolePropScene::getId).collect(Collectors.toList());

        // isUse 是 form 级筛选语义，不是 form_image 行级直接过滤。
        //   - isUse=1：该 form 当前存在至少一张 is_use=1 的图片
        //   - isUse=0：该 form 当前不存在任何 is_use=1 的图片（即"没有使用中的图片"）
        // 同 form 下既可能有 is_use=1 又可能有 is_use=0 的图片，因此 isUse=0 不能直接查 form_image.is_use=0，
        // 必须先取所有"含使用中图片"的 formId 集合，再用 in / notIn 在 form 表层排除/包含。
        Integer isUseFilter = request.getIsUse();
        java.util.Set<Long> inUseFormIds = null;
        if (Objects.nonNull(isUseFilter)) {
            LambdaQueryWrapper<AidRolePropSceneFormImage> imgWrapper = Wrappers.lambdaQuery();
            imgWrapper.select(AidRolePropSceneFormImage::getFormId);
            imgWrapper.in(AidRolePropSceneFormImage::getAssetId, mainIds);
            // 注意：始终查 is_use=1 拿到"含使用中图片"的 formId 集合，不论入参是 0 还是 1
            imgWrapper.eq(AidRolePropSceneFormImage::getIsUse, 1);
            imgWrapper.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
            List<AidRolePropSceneFormImage> inUseImgs = rpsFormImageService.list(imgWrapper);
            inUseFormIds = inUseImgs.stream()
                    .map(AidRolePropSceneFormImage::getFormId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            // isUse=1 且没有任何使用中图片 → 直接返回空
            if (Objects.equals(isUseFilter, 1) && CollectionUtil.isEmpty(inUseFormIds)) {
                return new ArrayList<>();
            }
        }

        LambdaQueryWrapper<AidRolePropSceneForm> formWrapper = Wrappers.lambdaQuery();
        formWrapper.in(AidRolePropSceneForm::getAssetId, mainIds);
        formWrapper.eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL);
        if (Objects.nonNull(isUseFilter)) {
            if (Objects.equals(isUseFilter, 1)) {
                // 仅返回"含使用中图片"的 form
                formWrapper.in(AidRolePropSceneForm::getId, inUseFormIds);
            } else {
                // isUse=0：仅返回"不含使用中图片"的 form；inUseFormIds 为空表示无需排除
                if (CollectionUtil.isNotEmpty(inUseFormIds)) {
                    formWrapper.notIn(AidRolePropSceneForm::getId, inUseFormIds);
                }
            }
        }
        formWrapper.orderByAsc(AidRolePropSceneForm::getCreateTime);
        List<AidRolePropSceneForm> allForms = rpsFormService.list(formWrapper);

        // 按主表ID分组
        java.util.Map<Long, List<AidRolePropSceneForm>> formMap = allForms.stream()
                .collect(Collectors.groupingBy(AidRolePropSceneForm::getAssetId));

        // 批量查所有 formId 下的 form_image，按 formId 分组到内存 Map，杜绝原 N+1
        java.util.Map<Long, List<AidRolePropSceneFormImage>> imgMap = new java.util.HashMap<>();
        if (CollectionUtil.isNotEmpty(allForms))
        {
            java.util.List<Long> allFormIds = allForms.stream()
                    .map(AidRolePropSceneForm::getId)
                    .collect(Collectors.toList());
            // 主资产列表语义：仅回 is_use=1 的图片，与"使用中集合"语义对齐
            LambdaQueryWrapper<AidRolePropSceneFormImage> imgListQuery = Wrappers.lambdaQuery();
            imgListQuery.in(AidRolePropSceneFormImage::getFormId, allFormIds);
            imgListQuery.eq(AidRolePropSceneFormImage::getIsUse, 1);
            imgListQuery.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
            imgListQuery.orderByAsc(AidRolePropSceneFormImage::getSortOrder);
            imgListQuery.orderByAsc(AidRolePropSceneFormImage::getCreateTime);
            imgMap = rpsFormImageService.list(imgListQuery).stream()
                    .collect(Collectors.groupingBy(AidRolePropSceneFormImage::getFormId));
        }
        final java.util.Map<Long, List<AidRolePropSceneFormImage>> finalImgMap = imgMap;

        // 批查"character 角色"的音色绑定，列表一次性带出音色信息，
        // 避免前端再单独请求 /voice/query；非 character 资产不会出现在 map 中。
        java.util.Set<Long> characterAssetIds = mainList.stream()
                .filter(m -> Objects.equals(ASSET_TYPE_CHARACTER, m.getAssetType()))
                .map(AidRolePropScene::getId)
                .collect(Collectors.toSet());
        final java.util.Map<Long, com.aid.rps.voice.vo.RoleVoiceBindingVO> voiceBindingMap =
                CollectionUtil.isNotEmpty(characterAssetIds)
                        ? roleVoiceBindingBusinessService.queryByAssetIds(characterAssetIds, userId)
                        : java.util.Collections.emptyMap();

        // 当 isUse 过滤生效时，未命中任何 form 的主资产不应再返回（避免 forms=[] 的"空壳资产"）。
        // isUse 为 null 时保持原行为（含无 form 的主资产也返回，前端可见）。
        return mainList.stream()
                .filter(main -> Objects.isNull(isUseFilter) || formMap.containsKey(main.getId()))
                .map(main -> {
                    List<AidRolePropSceneForm> forms = formMap.getOrDefault(main.getId(), new ArrayList<>());
                    String mainAssetType = main.getAssetType();
                    List<RpsFormVO> formVOs = forms.stream()
                            .map(f -> convertToFormVO(f,
                                    finalImgMap.getOrDefault(f.getId(), new ArrayList<>()), mainAssetType))
                            .collect(Collectors.toList());
                    RpsAssetVO vo = convertToAssetVO(main, formVOs);
                    // 仅 character 注入音色绑定；未绑定 → voiceBindingMap.get 返回 null，
                    // RpsAssetVO 上 @JsonInclude(NON_NULL) 确保字段自然不序列化
                    if (Objects.equals(ASSET_TYPE_CHARACTER, main.getAssetType()))
                    {
                        vo.setVoiceBinding(voiceBindingMap.get(main.getId()));
                    }
                    return vo;
                }).collect(Collectors.toList());
    }

    /**
     * 查询形态列表（三层模型形态层视角）。
     * 批量查 form + 批量查 form_image 后内存分组聚合，杜绝 N+1。
     * 与主资产列表不同：本接口返回每个 form 的完整 form_image 列表（不按 is_use 过滤）。
     */
    @Override
    public List<RpsFormVO> queryFormList(RpsFormListRequest request, Long userId)
    {
        if (Objects.isNull(request))
        {
            log.info("形态列表查询失败，参数为空: userId={}", userId);
            throw new RuntimeException("参数缺失");
        }
        Long projectId = request.getProjectId();
        Long episodeId = request.getEpisodeId();
        Long assetId = request.getAssetId();
        String assetType = request.getAssetType();

        // 按查询条件先收敛主资产 ID，避免全表扫描。
        java.util.Set<Long> scopedAssetIds = null;
        if (StrUtil.isNotBlank(assetType) || Objects.nonNull(projectId)
                || Objects.nonNull(episodeId) || Objects.nonNull(assetId))
        {
            LambdaQueryWrapper<AidRolePropScene> assetQ = Wrappers.lambdaQuery();
            assetQ.select(AidRolePropScene::getId, AidRolePropScene::getAssetType);
            assetQ.eq(AidRolePropScene::getUserId, userId);
            assetQ.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
            if (Objects.nonNull(assetId))
            {
                assetQ.eq(AidRolePropScene::getId, assetId);
            }
            if (Objects.nonNull(projectId))
            {
                // projectId 仅做精确匹配；"全局资产 -1" 概念已废弃，不再纳入查询
                assetQ.eq(AidRolePropScene::getProjectId, projectId);
            }
            if (Objects.nonNull(episodeId) && episodeId > 0L)
            {
                // 按集浏览时项目级资产（剧集角色/复用资产，episodeId=0）恒可见
                assetQ.in(AidRolePropScene::getEpisodeId, 0L, episodeId);
            }
            else if (Objects.nonNull(episodeId))
            {
                assetQ.eq(AidRolePropScene::getEpisodeId, episodeId);
            }
            if (StrUtil.isNotBlank(assetType))
            {
                assetQ.eq(AidRolePropScene::getAssetType, assetType);
            }
            List<AidRolePropScene> scopedAssets = rpsService.list(assetQ);
            scopedAssetIds = scopedAssets.stream()
                    .map(AidRolePropScene::getId)
                    .collect(Collectors.toSet());
            if (CollectionUtil.isEmpty(scopedAssetIds))
            {
                return new ArrayList<>();
            }
        }

        LambdaQueryWrapper<AidRolePropSceneForm> formQuery = Wrappers.lambdaQuery();
        formQuery.eq(AidRolePropSceneForm::getUserId, userId);
        formQuery.eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL);
        if (CollectionUtil.isNotEmpty(scopedAssetIds))
        {
            formQuery.in(AidRolePropSceneForm::getAssetId, scopedAssetIds);
        }
        formQuery.orderByAsc(AidRolePropSceneForm::getAssetId);
        formQuery.orderByAsc(AidRolePropSceneForm::getCreateTime);
        List<AidRolePropSceneForm> forms = rpsFormService.list(formQuery);
        if (CollectionUtil.isEmpty(forms))
        {
            return new ArrayList<>();
        }

        java.util.List<Long> formIdList = forms.stream()
                .map(AidRolePropSceneForm::getId)
                .collect(Collectors.toList());
        LambdaQueryWrapper<AidRolePropSceneFormImage> imgQuery = Wrappers.lambdaQuery();
        imgQuery.in(AidRolePropSceneFormImage::getFormId, formIdList);
        imgQuery.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        imgQuery.orderByAsc(AidRolePropSceneFormImage::getSortOrder);
        imgQuery.orderByAsc(AidRolePropSceneFormImage::getCreateTime);
        java.util.Map<Long, List<AidRolePropSceneFormImage>> imgGrouped = rpsFormImageService.list(imgQuery)
                .stream()
                .collect(Collectors.groupingBy(AidRolePropSceneFormImage::getFormId));

        // 收集所有涉及的 assetId
        java.util.Set<Long> allAssetIds = forms.stream()
                .map(AidRolePropSceneForm::getAssetId)
                .collect(Collectors.toSet());
        LambdaQueryWrapper<AidRolePropScene> typeQ = Wrappers.lambdaQuery();
        typeQ.select(AidRolePropScene::getId, AidRolePropScene::getAssetType);
        typeQ.in(AidRolePropScene::getId, allAssetIds);
        java.util.Map<Long, String> assetTypeMap = rpsService.list(typeQ).stream()
                .collect(Collectors.toMap(AidRolePropScene::getId, AidRolePropScene::getAssetType, (a, b) -> a));

        return forms.stream()
                .map(f -> convertToFormVO(f, imgGrouped.getOrDefault(f.getId(), new ArrayList<>()),
                        assetTypeMap.getOrDefault(f.getAssetId(), "")))
                .collect(Collectors.toList());
    }

    @Override
    public RpsAssetVO updateMainAsset(RpsUpdateMainRequest request, Long userId)
    {
        // 校验主表存在且属于自己
        AidRolePropScene asset = getMainAssetOrThrow(request.getId(), userId);
        String assetType = asset.getAssetType();

        // ---- 按创建来源分流：manual 走轻量更新，auto 走完整更新 ----
        if (Objects.equals(CREATE_SOURCE_MANUAL, asset.getCreateSource()))
        {
            // 手动数据：仅允许修改 name / aliasesName，其它字段即使传了也不处理
            // name 强校验：传了就必须非空
            if (Objects.nonNull(request.getName()) && StrUtil.isBlank(request.getName()))
            {
                log.info("手动更新主资产失败：name为空, assetId={}", request.getId());
                throw new RuntimeException("名称不能为空");
            }
            LambdaUpdateWrapper<AidRolePropScene> manualWrapper = Wrappers.lambdaUpdate();
            manualWrapper.eq(AidRolePropScene::getId, request.getId());
            manualWrapper.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
            // name 显式传入（非空）时更新
            if (StrUtil.isNotBlank(request.getName()))
            {
                manualWrapper.set(AidRolePropScene::getName, request.getName());
            }
            // aliases 允许空字符串（显式清空），非 null 即更新
            if (Objects.nonNull(request.getAliasesName()))
            {
                manualWrapper.set(AidRolePropScene::getAliasesName, request.getAliasesName());
            }
            manualWrapper.set(AidRolePropScene::getUpdateTime, DateUtils.getNowDate());
            manualWrapper.set(AidRolePropScene::getUpdateBy, String.valueOf(userId));
            rpsService.update(manualWrapper);
            return buildAssetVO(rpsService.getById(request.getId()));
        }

        // ---- 自动（auto）数据的完整更新逻辑 ----
        // ---- profileData 主定义源：已有字段绝不丢失 ----
        com.fasterxml.jackson.databind.node.ObjectNode profileNode =
                parseStoredProfileDataLenient(assetType, asset.getId(), asset.getProfileData());
        if (StrUtil.isNotBlank(request.getProfileData()))
        {
            com.fasterxml.jackson.databind.node.ObjectNode incoming =
                    parseProfileDataStrict(assetType, request.getProfileData());
            mergeProfileData(profileNode, incoming);
        }
        // 顶层独立列（name / summary 等）非空才写入 profileData，绝不抹掉已有值
        applyUpdateRequestFieldsToProfileData(assetType, request, profileNode);
        validateAvailableSlotsJson(extractAvailableSlotsString(profileNode));

        // 确保更新后主资产不会变成"字段不完整"的状态
        validateUpdateMergedProfileData(assetType, profileNode, request.getId());

        AidRolePropScene synced = new AidRolePropScene();
        syncMainColumnsFromProfileData(assetType, profileNode, synced);

        LambdaUpdateWrapper<AidRolePropScene> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AidRolePropScene::getId, request.getId());
        updateWrapper.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
        updateWrapper.set(AidRolePropScene::getProfileData, profileNode.toString());
        if (StrUtil.isNotBlank(synced.getName())) updateWrapper.set(AidRolePropScene::getName, synced.getName());
        if (StrUtil.isNotBlank(synced.getAliasesName())) updateWrapper.set(AidRolePropScene::getAliasesName, synced.getAliasesName());
        if (StrUtil.isNotBlank(synced.getIntroduction())) updateWrapper.set(AidRolePropScene::getIntroduction, synced.getIntroduction());
        if (StrUtil.isNotBlank(synced.getGender())) updateWrapper.set(AidRolePropScene::getGender, synced.getGender());
        if (StrUtil.isNotBlank(synced.getAgeRange())) updateWrapper.set(AidRolePropScene::getAgeRange, synced.getAgeRange());
        if (StrUtil.isNotBlank(synced.getRoleLevel())) updateWrapper.set(AidRolePropScene::getRoleLevel, synced.getRoleLevel());
        if (StrUtil.isNotBlank(synced.getSummary())) updateWrapper.set(AidRolePropScene::getSummary, synced.getSummary());
        if (StrUtil.isNotBlank(synced.getAvailableSlots())) updateWrapper.set(AidRolePropScene::getAvailableSlots, synced.getAvailableSlots());
        if (Objects.nonNull(synced.getHasCrowd())) updateWrapper.set(AidRolePropScene::getHasCrowd, synced.getHasCrowd());
        // crowd_description 允许空字符串 ""（无人群时前端显式传空串），因此用 nonNull 而非 isNotBlank
        if (Objects.nonNull(synced.getCrowdDescription())) updateWrapper.set(AidRolePropScene::getCrowdDescription, synced.getCrowdDescription());
        // expectedAppearances 仍为独立字段，与 profileData 解耦，只在本次显式传入时更新
        // 注意：null = 未传（不修改），空列表 = 显式传了空数组（需走校验拒绝）
        if (Objects.nonNull(request.getExpectedAppearances()))
        {
            validateExpectedAppearances(request.getExpectedAppearances(), request.getId());
            updateWrapper.set(AidRolePropScene::getExpectedAppearances,
                    serializeExpectedAppearances(request.getExpectedAppearances()));
        }
        updateWrapper.set(AidRolePropScene::getUpdateTime, DateUtils.getNowDate());
        updateWrapper.set(AidRolePropScene::getUpdateBy, String.valueOf(userId));
        rpsService.update(updateWrapper);

        RpsAssetVO vo = buildAssetVO(rpsService.getById(request.getId()));
        // 角色改性别/年龄后即时重绑音色（仅 character 且值确实变化时触发）；重绑成功回传 voiceChanged 提示
        applyVoiceRematchIfNeeded(asset, synced, userId, vo);
        return vo;
    }

    /**
     * 角色改性别/年龄后的音色即时重绑（需求：改性别后提示"部分镜头音色已变，是否重配"）。
     *
     * @param oldAsset 更新前的角色实体（承载旧 gender/age_range）
     * @param synced   本次同步的主表列（仅非空字段有值；空表示未改）
     * @param userId   当前用户
     * @param vo       待回填的返回 VO
     */
    private void applyVoiceRematchIfNeeded(AidRolePropScene oldAsset, AidRolePropScene synced, Long userId, RpsAssetVO vo)
    {
        if (Objects.isNull(vo) || Objects.isNull(oldAsset)
                || !ASSET_TYPE_CHARACTER.equals(oldAsset.getAssetType()))
        {
            return;
        }
        // 新值：本次同步了就用新值，否则维持旧值
        String newGender = StrUtil.isNotBlank(synced.getGender()) ? synced.getGender() : oldAsset.getGender();
        String newAge = StrUtil.isNotBlank(synced.getAgeRange()) ? synced.getAgeRange() : oldAsset.getAgeRange();
        boolean changed = !Objects.equals(StrUtil.trimToEmpty(oldAsset.getGender()), StrUtil.trimToEmpty(newGender))
                || !Objects.equals(StrUtil.trimToEmpty(oldAsset.getAgeRange()), StrUtil.trimToEmpty(newAge));
        if (!changed)
        {
            return;
        }
        try
        {
            boolean voiceChanged = roleVoiceAutoBindService.rematchForCharacter(oldAsset.getId(), userId);
            if (voiceChanged)
            {
                vo.setVoiceChanged(Boolean.TRUE);
                vo.setVoiceChangedTip("部分镜头音色已变，是否重配");
            }
        }
        catch (Exception e)
        {
            log.error("角色改性别/年龄后音色重绑失败（不影响编辑）: assetId={}, err={}",
                    oldAsset.getId(), e.getMessage(), e);
        }
    }

    @Override
    public RpsFormVO updateFormAsset(RpsUpdateFormRequest request, Long userId)
    {
        // 校验从表存在且属于自己（需查出旧 promptText 用于增量合并）
        LambdaQueryWrapper<AidRolePropSceneForm> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.eq(AidRolePropSceneForm::getId, request.getId());
        queryWrapper.eq(AidRolePropSceneForm::getUserId, userId);
        queryWrapper.eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL);
        // 查出旧 promptText / name / changeReason / createSource，平铺增量合并与手动/自动分流都需要
        queryWrapper.select(AidRolePropSceneForm::getId, AidRolePropSceneForm::getAssetId,
                AidRolePropSceneForm::getPromptText, AidRolePropSceneForm::getName,
                AidRolePropSceneForm::getChangeReason, AidRolePropSceneForm::getCreateSource);
        AidRolePropSceneForm form = rpsFormService.getOne(queryWrapper);
        if (Objects.isNull(form))
        {
            log.info("更新从表失败，形态不存在: formId={}", request.getId());
            throw new RuntimeException("形态不存在");
        }

        // 获取主表资产类型（以库存为准，不信任前端传入的 assetType）
        AidRolePropScene mainAsset = getMainAssetOrThrow(form.getAssetId(), userId);
        String assetType = mainAsset.getAssetType();

        // ---- 按创建来源分流：manual 走轻量更新，auto 走完整更新 ----
        if (Objects.equals(CREATE_SOURCE_MANUAL, form.getCreateSource()))
        {
            // 手动数据：仅允许修改 name / changeReason，其它结构化字段 / promptText 即使传了也不处理
            // name 强校验：传了就必须非空
            if (Objects.nonNull(request.getName()) && StrUtil.isBlank(request.getName()))
            {
                log.info("手动更新形态失败：name为空, formId={}", request.getId());
                throw new RuntimeException("名称不能为空");
            }
            LambdaUpdateWrapper<AidRolePropSceneForm> manualWrapper = Wrappers.lambdaUpdate();
            manualWrapper.eq(AidRolePropSceneForm::getId, request.getId());
            manualWrapper.eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL);
            // name 显式传入（非空）时更新
            if (StrUtil.isNotBlank(request.getName()))
            {
                manualWrapper.set(AidRolePropSceneForm::getName, request.getName());
            }
            // changeReason 允许空字符串（显式清空），非 null 即更新
            if (Objects.nonNull(request.getChangeReason()))
            {
                manualWrapper.set(AidRolePropSceneForm::getChangeReason, request.getChangeReason());
            }
            manualWrapper.set(AidRolePropSceneForm::getUpdateTime, DateUtils.getNowDate());
            manualWrapper.set(AidRolePropSceneForm::getUpdateBy, String.valueOf(userId));
            rpsFormService.update(manualWrapper);
            AidRolePropSceneForm manualUpdated = rpsFormService.getById(request.getId());
            return convertToFormVO(manualUpdated, assetType);
        }

        // ---- 自动（auto）数据的完整更新逻辑 ----
        // ---- 平铺字段增量合并 promptText ----
        // 若前端未传 promptText 但传了平铺字段，先读库存 promptText，再做字段级合并
        if (StrUtil.isBlank(request.getPromptText()) && hasStructuredFields(request))
        {
            // 解析库存旧 promptText（非法/空值降级为空对象）
            com.fasterxml.jackson.databind.node.ObjectNode baseNode =
                    parseStoredFormPromptTextLenient(assetType, request.getId(), form.getPromptText());
            // 把本次平铺字段增量合并进旧节点（只覆盖显式传入的字段）
            mergeFormFlatFieldsIntoPromptText(assetType, request, baseNode);
            // 保证 assetType / promptVersion 始终存在
            normalizeFormPromptText(assetType, baseNode);
            // 最终完整 promptText 回写到 request
            request.setPromptText(baseNode.toString());
        }

        // 校验至少传了一个有效更新内容
        if (StrUtil.isBlank(request.getName())
                && StrUtil.isBlank(request.getChangeReason())
                && StrUtil.isBlank(request.getPromptText()))
        {
            log.info("更新从表失败，名称/变更原因/提示词均为空: id={}", request.getId());
            throw new RuntimeException("更新内容不存在");
        }

        // 更新从表元信息
        LambdaUpdateWrapper<AidRolePropSceneForm> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AidRolePropSceneForm::getId, request.getId());
        updateWrapper.eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL);
        if (StrUtil.isNotBlank(request.getName()))
        {
            updateWrapper.set(AidRolePropSceneForm::getName, request.getName());
        }
        if (StrUtil.isNotBlank(request.getChangeReason()))
        {
            updateWrapper.set(AidRolePropSceneForm::getChangeReason, request.getChangeReason());
        }
        if (StrUtil.isNotBlank(request.getPromptText()))
        {
            updateWrapper.set(AidRolePropSceneForm::getPromptText, request.getPromptText());
            // 从最终合并后的 promptText 反解析 name / changeReason，作为顶层未显式传入时的回退值
            syncFormColumnsFromPromptText(request, updateWrapper);
        }
        updateWrapper.set(AidRolePropSceneForm::getUpdateTime, DateUtils.getNowDate());
        // 审计字段：按项目规范补上 updateBy
        updateWrapper.set(AidRolePropSceneForm::getUpdateBy, String.valueOf(userId));
        rpsFormService.update(updateWrapper);

        // 同步把 promptText 中的 prompt 字段回写到主资产 aid_role_prop_scene.introduction 列。
        // 主资产 introduction 在出图链路已不再使用，但仍作为历史排查 / 资产导出的内容载体保持与 prompt 一致。
        syncMainAssetIntroductionFromPromptText(form.getAssetId(), request.getPromptText());

        // 重新查询更新后的 form，组装单个 RpsFormVO（含 images）
        AidRolePropSceneForm updatedForm = rpsFormService.getById(request.getId());
        return convertToFormVO(updatedForm, assetType);
    }

    /**
     * 从 promptText JSON 抽取 prompt 字段，回写到主资产 aid_role_prop_scene.introduction 列。
     * 仅对 stylist LLM 输出格式的 promptText 生效；prompt 字段为空或解析失败时静默跳过。
     */
    private void syncMainAssetIntroductionFromPromptText(Long assetId, String promptText)
    {
        if (Objects.isNull(assetId) || StrUtil.isBlank(promptText))
        {
            return;
        }
        try
        {
            JsonNode tree = OBJECT_MAPPER.readTree(promptText);
            if (Objects.isNull(tree))
            {
                return;
            }
            // 兼容数组协议（道具 stylist 可能返回数组）：取第一个元素
            JsonNode target = tree.isArray() && !tree.isEmpty() ? tree.get(0) : tree;
            if (Objects.isNull(target) || !target.isObject())
            {
                return;
            }
            JsonNode promptNode = target.get("prompt");
            if (Objects.isNull(promptNode) || !promptNode.isTextual())
            {
                return;
            }
            String promptStr = promptNode.asText("");
            if (StrUtil.isBlank(promptStr))
            {
                return;
            }
            LambdaUpdateWrapper<AidRolePropScene> assetWrapper = Wrappers.lambdaUpdate();
            assetWrapper.eq(AidRolePropScene::getId, assetId);
            assetWrapper.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
            assetWrapper.set(AidRolePropScene::getIntroduction, promptStr);
            assetWrapper.set(AidRolePropScene::getUpdateTime, DateUtils.getNowDate());
            rpsService.update(assetWrapper);
        }
        catch (Exception e)
        {
            // promptText 非合法 JSON，跳过同步，不阻塞主流程
            log.info("同步 prompt 到主资产 introduction 失败: assetId={}, err={}", assetId, e.getMessage());
        }
    }

    @Override
    @Transactional
    public void deleteAsset(RpsDeleteRequest request, Long userId)
    {
        Long id = request.getId();
        Long formId = request.getFormId();
        // 校验主表存在（同时拿到 assetType，用于判断是否需级联删场次）
        AidRolePropScene asset = getMainAssetOrThrow(id, userId);

        if (Objects.nonNull(formId))
        {
            // 传了 formId → 仅删该 form。
            // 拦截规则：若作为主资产唯一剩余 form 被删，主资产将陷入"无形态"状态。
            // 为与 createAsset 自动补初始 form 的对称设计一致，这里拦截。
            LambdaQueryWrapper<AidRolePropSceneForm> formCheckWrapper = Wrappers.lambdaQuery();
            formCheckWrapper.select(AidRolePropSceneForm::getId);
            formCheckWrapper.eq(AidRolePropSceneForm::getId, formId);
            formCheckWrapper.eq(AidRolePropSceneForm::getAssetId, id);
            formCheckWrapper.eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL);
            if (Objects.isNull(rpsFormService.getOne(formCheckWrapper)))
            {
                log.info("删除从表失败，形态不存在或不属于该资产: assetId={}, formId={}", id, formId);
                throw new RuntimeException("形态不存在");
            }
            // 拦截删除唯一 form：计数同 assetId 下未删除 form 总数
            LambdaQueryWrapper<AidRolePropSceneForm> aliveFormQuery = Wrappers.lambdaQuery();
            aliveFormQuery.select(AidRolePropSceneForm::getId);
            aliveFormQuery.eq(AidRolePropSceneForm::getAssetId, id);
            aliveFormQuery.eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL);
            long aliveFormCount = rpsFormService.count(aliveFormQuery);
            if (aliveFormCount <= 1)
            {
                log.info("删除拦截，资产仅剩唯一形态: assetId={}, formId={}", id, formId);
                throw new RuntimeException("不能删全部");
            }

            // 先级联清理该 form 下所有 form_image 的 OSS 文件并物理删除，再物理删除 form 本身
            cascadeDeleteFormImagesByForm(formId, userId);
            rpsFormService.getBaseMapper().delete(Wrappers.<AidRolePropSceneForm>lambdaQuery()
                    .eq(AidRolePropSceneForm::getId, formId));
        }
        else
        {
            // 未传formId → 删除主表 + 所有关联从表 + 所有 form_image（硬删 + 清 OSS）
            cascadeDeleteFormImagesByAsset(id, userId);
            rpsFormService.getBaseMapper().delete(Wrappers.<AidRolePropSceneForm>lambdaQuery()
                    .eq(AidRolePropSceneForm::getAssetId, id));
            rpsService.getBaseMapper().delete(Wrappers.<AidRolePropScene>lambdaQuery()
                    .eq(AidRolePropScene::getId, id));
            // scene 资产需级联删除名下场次：不删会导致孤儿场次残留，且被 SceneCodeAllocator 误算进"已存量最大场次号"。
            if (Objects.equals(ASSET_TYPE_SCENE, asset.getAssetType()))
            {
                cascadeDeleteScenePlotsByScene(id, userId);
            }
        }

        log.info("硬删除完成: id={}, formId={}", id, formId);
    }

    /**
     * 级联硬删指定 form 下的所有 form_image：先清理其 OSS 图片，再物理删除（含已软删行，彻底清理残留）。
     * 加 user_id 条件做纵深防御，只删当前用户自己的 form_image 行，防止横向越权。
     */
    private void cascadeDeleteFormImagesByForm(Long formId, Long userId)
    {
        // 收集该 form 下所有 form_image 的 OSS 图片 URL（不过滤 del_flag，连历史软删行一并清理）
        List<AidRolePropSceneFormImage> imgs = rpsFormImageService.list(Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                .select(AidRolePropSceneFormImage::getImageUrl)
                .eq(AidRolePropSceneFormImage::getFormId, formId)
                .eq(AidRolePropSceneFormImage::getUserId, userId));
        List<String> urls = imgs.stream().map(AidRolePropSceneFormImage::getImageUrl).collect(Collectors.toList());
        // 先物理删库，再登记 OSS 清理（afterCommit 后台执行，回滚则不动文件）
        rpsFormImageService.getBaseMapper().delete(Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                .eq(AidRolePropSceneFormImage::getFormId, formId)
                .eq(AidRolePropSceneFormImage::getUserId, userId));
        mediaOssCleanupService.cleanupFiles(urls);
    }

    /**
     * 级联硬删指定主资产下的所有 form_image：先清理其 OSS 图片，再物理删除（含已软删行）。
     * 加 user_id 条件做纵深防御，只删当前用户自己的 form_image 行，防止横向越权。
     */
    private void cascadeDeleteFormImagesByAsset(Long assetId, Long userId)
    {
        // 收集该主资产下所有 form_image 的 OSS 图片 URL（不过滤 del_flag，连历史软删行一并清理）
        List<AidRolePropSceneFormImage> imgs = rpsFormImageService.list(Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                .select(AidRolePropSceneFormImage::getImageUrl)
                .eq(AidRolePropSceneFormImage::getAssetId, assetId)
                .eq(AidRolePropSceneFormImage::getUserId, userId));
        List<String> urls = imgs.stream().map(AidRolePropSceneFormImage::getImageUrl).collect(Collectors.toList());
        // 先物理删库，再登记 OSS 清理（afterCommit 后台执行，回滚则不动文件）
        rpsFormImageService.getBaseMapper().delete(Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                .eq(AidRolePropSceneFormImage::getAssetId, assetId)
                .eq(AidRolePropSceneFormImage::getUserId, userId));
        mediaOssCleanupService.cleanupFiles(urls);
    }

    /**
     * 级联物理删除指定 scene 名下的所有场次 aid_scene_plot。
     *
     * @param sceneId 被删除的 scene 主资产 ID
     * @param userId  当前用户 ID
     */
    private void cascadeDeleteScenePlotsByScene(Long sceneId, Long userId)
    {
        // 场次无 OSS 文件，直接物理删除（加 user_id 纵深防御）
        scenePlotService.getBaseMapper().delete(Wrappers.<AidScenePlot>lambdaQuery()
                .eq(AidScenePlot::getSceneId, sceneId)
                .eq(AidScenePlot::getUserId, userId));
    }

    @Override
    @Transactional
    public void useForm(Long imageId, Long userId)
    {
        // /form/use 为"多选使用中"语义——
        // 仅把目标图设为 is_use=1，不再清空同 form 其它图片，允许多张同时为使用中。
        AidRolePropSceneFormImage img = getFormImageOrThrow(imageId, userId);
        // 已经是使用中：幂等返回
        if (Objects.nonNull(img.getIsUse()) && img.getIsUse() == 1)
        {
            log.info("图片已是使用中，跳过更新: imageId={}, formId={}", imageId, img.getFormId());
            return;
        }
        // 仅更新当前图片为使用中
        LambdaUpdateWrapper<AidRolePropSceneFormImage> setSelf = Wrappers.lambdaUpdate();
        setSelf.eq(AidRolePropSceneFormImage::getId, imageId);
        setSelf.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        setSelf.set(AidRolePropSceneFormImage::getIsUse, 1);
        setSelf.set(AidRolePropSceneFormImage::getUpdateTime, DateUtils.getNowDate());
        setSelf.set(AidRolePropSceneFormImage::getUpdateBy, String.valueOf(userId));
        rpsFormImageService.update(setSelf);
        log.info("图片加入使用中集合: imageId={}, formId={}", imageId, img.getFormId());
    }

    @Override
    @Transactional
    public void unuseForm(Long imageId, Long userId)
    {
        // /form/unuse 为"多选使用中"语义——
        // 仅取消当前图片的 is_use；但必须保证同 form 至少保留 1 张 is_use=1。
        AidRolePropSceneFormImage img = getFormImageOrThrow(imageId, userId);
        // 已是未使用：幂等返回
        if (Objects.isNull(img.getIsUse()) || img.getIsUse() != 1)
        {
            log.info("图片已非使用中，跳过更新: imageId={}, formId={}", imageId, img.getFormId());
            return;
        }
        // count + update 非原子，两个并发取消可能把 is_use 同时打到 0。
        // 改为"条件更新"：UPDATE ... WHERE id=? AND is_use=1 AND EXISTS (
        //   SELECT 1 FROM aid_role_prop_scene_form_image sibling
        //   WHERE sibling.form_id=? AND sibling.is_use=1 AND sibling.id <> ? AND sibling.del_flag='0'
        // )
        // 实现上 MyBatis-Plus 不支持子查询，这里用"先把当前图片 is_use 置 0，再根据影响行数判断是否还能再回退"
        // 更简单的做法：悲观锁——先对 form 下所有 is_use=1 的图片做 FOR UPDATE 锁住行，再判断数量。
        // 但 RpsFormImage 不走 Mapper 自定义 FOR UPDATE，这里保留 count + update 语义，
        // 外层事务确保回滚；并追加"最终验证"条件语句：仅当同 form 已有另外至少一张 is_use=1 时才允许置 0。
        Long formId = img.getFormId();
        LambdaQueryWrapper<AidRolePropSceneFormImage> countQ = Wrappers.lambdaQuery();
        countQ.select(AidRolePropSceneFormImage::getId);
        countQ.eq(AidRolePropSceneFormImage::getFormId, formId);
        countQ.eq(AidRolePropSceneFormImage::getIsUse, 1);
        countQ.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        // 仅加悲观锁在 MyBatis Plus 里要手写 SQL，这里用 count 先做快速失败校验
        long inUseCount = rpsFormImageService.count(countQ);
        if (inUseCount <= 1L)
        {
            log.info("取消使用失败，需至少保留一张: imageId={}, formId={}, inUseCount={}",
                    imageId, formId, inUseCount);
            throw new RuntimeException("不可取消，需保留至少一张");
        }
        // 条件更新：WHERE id=? AND is_use=1
        LambdaUpdateWrapper<AidRolePropSceneFormImage> upd = Wrappers.lambdaUpdate();
        upd.eq(AidRolePropSceneFormImage::getId, imageId);
        upd.eq(AidRolePropSceneFormImage::getIsUse, 1);
        upd.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        upd.set(AidRolePropSceneFormImage::getIsUse, 0);
        upd.set(AidRolePropSceneFormImage::getUpdateTime, DateUtils.getNowDate());
        upd.set(AidRolePropSceneFormImage::getUpdateBy, String.valueOf(userId));
        boolean updated = rpsFormImageService.update(upd);
        if (!updated) {
            // 并发下另一线程已经把 is_use 置 0；再次检查剩余数量，若已归零则回滚本次操作
            long finalCount = rpsFormImageService.count(countQ);
            if (finalCount <= 0L) {
                log.info("unuseForm 并发冲突导致同 form 已无使用中图片, imageId={}, formId={}", imageId, formId);
                throw new RuntimeException("并发冲突");
            }
        }
        log.info("图片移出使用中集合: imageId={}, formId={}", imageId, formId);
    }

    @Override
    public BatchOperationResultVO useFormBatch(Long projectId, List<Long> imageIds, Long userId)
    {
        // 单个 / 批量同接口：controller 已解析出去重有序的 imageIds；逐条独立事务执行，单条失败不牵连其它
        BatchOperationResultVO result = new BatchOperationResultVO();
        if (CollectionUtil.isEmpty(imageIds))
        {
            log.info("批量设置使用中：入参为空, userId={}", userId);
            return result.summarize();
        }
        // 项目范围闸门：先一次性校验项目归属，失败整体抛错（不进入循环、不部分写入）
        validateProjectOwnership(projectId, userId);
        for (Long imageId : imageIds)
        {
            try
            {
                // 项目边界校验：先用 (id + userId + delFlag) 探测图片是否归属本用户，
                // 再断言 image.project_id == 入参 projectId，越权条目单条失败为「项目不匹配」
                AidRolePropSceneFormImage probe = loadFormImageProjectProbe(imageId, userId);
                if (Objects.isNull(probe))
                {
                    result.addFailure(imageId, "图片不存在");
                    continue;
                }
                if (!Objects.equals(probe.getProjectId(), projectId))
                {
                    log.info("批量设置使用中-项目不匹配: imageId={}, imgProjectId={}, reqProjectId={}, userId={}",
                            imageId, probe.getProjectId(), projectId, userId);
                    result.addFailure(imageId, "项目不匹配");
                    continue;
                }
                // 经自身代理调用，确保每条目走独立 @Transactional 事务
                self.useForm(imageId, userId);
                result.addSuccess(imageId);
            }
            catch (RuntimeException e)
            {
                // 单条失败：记录原因并继续处理后续条目
                log.error("批量设置使用中-单条失败: imageId={}, userId={}, err={}", imageId, userId, e.getMessage());
                result.addFailure(imageId, e.getMessage());
            }
        }
        log.info("批量设置使用中完成: userId={}, projectId={}, total={}, success={}, fail={}",
                userId, projectId, imageIds.size(), result.getSuccessIds().size(), result.getFailures().size());
        return result.summarize();
    }

    @Override
    public BatchOperationResultVO unuseFormBatch(Long projectId, List<Long> imageIds, Long userId)
    {
        // 单个 / 批量同接口：逐条独立事务取消使用中，单条失败（如同 form 需保留至少一张）不牵连其它
        BatchOperationResultVO result = new BatchOperationResultVO();
        if (CollectionUtil.isEmpty(imageIds))
        {
            log.info("批量取消使用中：入参为空, userId={}", userId);
            return result.summarize();
        }
        // 项目范围闸门：先一次性校验项目归属，失败整体抛错
        validateProjectOwnership(projectId, userId);
        for (Long imageId : imageIds)
        {
            try
            {
                AidRolePropSceneFormImage probe = loadFormImageProjectProbe(imageId, userId);
                if (Objects.isNull(probe))
                {
                    result.addFailure(imageId, "图片不存在");
                    continue;
                }
                if (!Objects.equals(probe.getProjectId(), projectId))
                {
                    log.info("批量取消使用中-项目不匹配: imageId={}, imgProjectId={}, reqProjectId={}, userId={}",
                            imageId, probe.getProjectId(), projectId, userId);
                    result.addFailure(imageId, "项目不匹配");
                    continue;
                }
                self.unuseForm(imageId, userId);
                result.addSuccess(imageId);
            }
            catch (RuntimeException e)
            {
                log.error("批量取消使用中-单条失败: imageId={}, userId={}, err={}", imageId, userId, e.getMessage());
                result.addFailure(imageId, e.getMessage());
            }
        }
        log.info("批量取消使用中完成: userId={}, projectId={}, total={}, success={}, fail={}",
                userId, projectId, imageIds.size(), result.getSuccessIds().size(), result.getFailures().size());
        return result.summarize();
    }

    /**
     * 项目归属一次性校验（项目存在 / 未删除 / 归属当前用户）。
     * 批量入口处先校验一次，避免对每条目反查 form → asset → project 链路；
     * 校验失败直接整体抛错（不进入条目循环，杜绝"项目不存在但部分图片写入"的歧义）。
     */
    private void validateProjectOwnership(Long projectId, Long userId)
    {
        if (Objects.isNull(projectId) || Objects.isNull(userId))
        {
            log.info("项目范围校验失败：参数缺失, projectId={}, userId={}", projectId, userId);
            throw new RuntimeException("参数缺失");
        }
        AidComicProject project = projectService.getOne(
                Wrappers.<AidComicProject>lambdaQuery()
                        .select(AidComicProject::getId)
                        .eq(AidComicProject::getId, projectId)
                        .eq(AidComicProject::getUserId, userId)
                        .eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL)
                        .last("LIMIT 1"));
        if (Objects.isNull(project))
        {
            log.info("项目范围校验失败：项目不存在或不属于当前用户, projectId={}, userId={}", projectId, userId);
            throw new RuntimeException("项目不存在或无权限操作");
        }
    }

    /**
     * 仅取 id + projectId 的轻量探测（用于批量项目边界校验），不取大字段，杜绝 N+1 + 减少 IO。
     * 同时过滤 userId + delFlag 做双层归属保护。
     */
    private AidRolePropSceneFormImage loadFormImageProjectProbe(Long imageId, Long userId)
    {
        if (Objects.isNull(imageId))
        {
            return null;
        }
        return rpsFormImageService.getOne(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getId, AidRolePropSceneFormImage::getProjectId)
                        .eq(AidRolePropSceneFormImage::getId, imageId)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                        .last("LIMIT 1"),
                false);
    }

    /**
     * 查 form_image 并校验归属与软删状态。
     */
    private AidRolePropSceneFormImage getFormImageOrThrow(Long imageId, Long userId)
    {
        // imageId 为空时统一报"参数缺失"，避免隐式走成"图片不存在"
        if (Objects.isNull(imageId))
        {
            log.info("图片使用状态处理失败，imageId 为空: userId={}", userId);
            throw new RuntimeException("参数缺失");
        }
        LambdaQueryWrapper<AidRolePropSceneFormImage> q = Wrappers.lambdaQuery();
        q.eq(AidRolePropSceneFormImage::getId, imageId);
        q.eq(AidRolePropSceneFormImage::getUserId, userId);
        q.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        AidRolePropSceneFormImage img = rpsFormImageService.getOne(q);
        if (Objects.isNull(img))
        {
            log.info("图片不存在: imageId={}, userId={}", imageId, userId);
            throw new RuntimeException("图片不存在");
        }
        return img;
    }
    /**
     * 校验主表资产类型（scene/character/prop）
     */
    private void validateMainAssetType(String assetType) {
        if (StrUtil.isBlank(assetType) || !MAIN_ASSET_TYPES.contains(assetType)) {
            log.info("资产类型校验失败: assetType={}", assetType);
            throw new RuntimeException("类型不在范围内");
        }
    }

    /**
     * 校验名称非空
     */
    private void validateName(String name) {
        if (StrUtil.isBlank(name)) {
            log.info("名称校验失败：名称为空");
            throw new RuntimeException("名称不能为空");
        }
    }

    /**
     * 校验 expected_appearances 数组（character 专属必填）。
     *
     * @param items   前端传入的多形态定义数组
     * @param assetId 主表 ID（用于日志，创建时传 null）
     */
    private void validateExpectedAppearances(java.util.List<com.aid.rps.dto.ExpectedAppearanceItem> items, Long assetId)
    {
        if (CollectionUtil.isEmpty(items))
        {
            log.info("expected_appearances 校验失败：数组为空, assetId={}", assetId);
            throw new RuntimeException("请填写形态");
        }
        for (int i = 0; i < items.size(); i++)
        {
            com.aid.rps.dto.ExpectedAppearanceItem item = items.get(i);
            if (Objects.isNull(item))
            {
                log.info("expected_appearances[{}] 为 null, assetId={}", i, assetId);
                throw new RuntimeException("形态数据异常");
            }
            if (Objects.isNull(item.getId()))
            {
                log.info("expected_appearances[{}].id 为空, assetId={}", i, assetId);
                throw new RuntimeException("请填写形态ID");
            }
            if (item.getId() < 0)
            {
                log.info("expected_appearances[{}].id 为负数: {}, assetId={}", i, item.getId(), assetId);
                throw new RuntimeException("形态ID无效");
            }
            if (StrUtil.isBlank(item.getName()))
            {
                log.info("expected_appearances[{}].name 为空, assetId={}", i, assetId);
                throw new RuntimeException("请填写形态名");
            }
            if (StrUtil.isBlank(item.getChangeReason()))
            {
                log.info("expected_appearances[{}].changeReason 为空, assetId={}", i, assetId);
                throw new RuntimeException("请填写变更原因");
            }
        }
    }

    /**
     * 将 expected_appearances 列表序列化为 JSON 字符串存库。
     * 输出格式：[{"id":0,"name":"初始形象","change_reason":"..."},...]
     */
    private String serializeExpectedAppearances(java.util.List<com.aid.rps.dto.ExpectedAppearanceItem> items)
    {
        if (CollectionUtil.isEmpty(items))
        {
            return "[]";
        }
        try
        {
            // 按 snake_case 写出，与前端/下游 LLM 约定一致
            com.fasterxml.jackson.databind.node.ArrayNode arr = OBJECT_MAPPER.createArrayNode();
            for (com.aid.rps.dto.ExpectedAppearanceItem item : items)
            {
                com.fasterxml.jackson.databind.node.ObjectNode node = OBJECT_MAPPER.createObjectNode();
                node.put("id", item.getId());
                node.put("name", item.getName());
                node.put("change_reason", item.getChangeReason());
                arr.add(node);
            }
            return OBJECT_MAPPER.writeValueAsString(arr);
        }
        catch (Exception e)
        {
            log.error("expected_appearances 序列化失败: err={}", e.getMessage());
            throw new RuntimeException("形态数据异常");
        }
    }

    /**
     * create 链路：校验合并后的 profileData 必填字段完整性。
     */
    private void validateCreateRequiredFields(String assetType,
                                               com.fasterxml.jackson.databind.node.ObjectNode profileNode)
    {
        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            // 基础字段
            requireProfileText(profileNode, "name", "请填写名称", null);
            requireProfileText(profileNode, "gender", "请填写性别", null);
            requireProfileText(profileNode, "age_range", "请填写年龄", null);
            requireProfileText(profileNode, "introduction", "请填写介绍", null);
            // 扩展档案字段
            requireProfileText(profileNode, "archetype", "请填写原型", null);
            requireProfileText(profileNode, "era_period", "请填写时代", null);
            requireProfileText(profileNode, "occupation", "请填写职业", null);
            requireProfileText(profileNode, "role_level", "请填写层级", null);
            requireProfileNode(profileNode, "costume_tier", "请填写服装", null);
            requireProfileText(profileNode, "social_class", "请填写阶层", null);
            requireProfileArray(profileNode, "visual_keywords", "请填写关键词", null);
            requireProfileArray(profileNode, "personality_tags", "请填写性格", null);
            requireProfileArray(profileNode, "suggested_colors", "请填写配色", null);
            requireProfileText(profileNode, "primary_identifier", "请填写特征", null);
        }
        else if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            requireProfileText(profileNode, "name", "请填写名称", null);
            requireProfileText(profileNode, "summary", "请填写概要", null);
            requireProfileText(profileNode, "introduction", "请填写描述", null);
            requireProfileArray(profileNode, "available_slots", "请填写槽位", null);
            requireProfileNode(profileNode, "has_crowd", "请填写人群", null);
            // crowd_description 必须显式存在（允许空字符串 ""，但 key 必须有）
            if (!profileNode.has("crowd_description") || profileNode.get("crowd_description").isNull())
            {
                log.info("场景创建校验失败：人群描述缺失");
                throw new RuntimeException("请填写人群");
            }
        }
        else if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            requireProfileText(profileNode, "name", "请填写名称", null);
            requireProfileText(profileNode, "summary", "请填写概要", null);
            requireProfileText(profileNode, "introduction", "请填写描述", null);
        }
    }

    /**
     * update-main 链路：校验合并后的 profileData 必填字段完整性。
     */
    private void validateUpdateMergedProfileData(String assetType,
                                                  com.fasterxml.jackson.databind.node.ObjectNode profileNode,
                                                  Long assetId)
    {
        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            // 基础字段
            requireProfileText(profileNode, "name", "请填写名称", assetId);
            requireProfileText(profileNode, "gender", "请填写性别", assetId);
            requireProfileText(profileNode, "age_range", "请填写年龄", assetId);
            requireProfileText(profileNode, "introduction", "请填写介绍", assetId);
            // 扩展档案字段
            requireProfileText(profileNode, "archetype", "请填写原型", assetId);
            requireProfileText(profileNode, "era_period", "请填写时代", assetId);
            requireProfileText(profileNode, "occupation", "请填写职业", assetId);
            requireProfileText(profileNode, "role_level", "请填写层级", assetId);
            requireProfileNode(profileNode, "costume_tier", "请填写服装", assetId);
            requireProfileText(profileNode, "social_class", "请填写阶层", assetId);
            requireProfileArray(profileNode, "visual_keywords", "请填写关键词", assetId);
            requireProfileArray(profileNode, "personality_tags", "请填写性格", assetId);
            requireProfileArray(profileNode, "suggested_colors", "请填写配色", assetId);
            requireProfileText(profileNode, "primary_identifier", "请填写特征", assetId);
        }
        else if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            requireProfileText(profileNode, "name", "请填写名称", assetId);
            requireProfileText(profileNode, "summary", "请填写概要", assetId);
            requireProfileText(profileNode, "introduction", "请填写描述", assetId);
            requireProfileArray(profileNode, "available_slots", "请填写槽位", assetId);
            requireProfileNode(profileNode, "has_crowd", "请填写人群", assetId);
            // crowd_description 必须显式存在（允许空字符串 ""，但 key 必须有）
            if (!profileNode.has("crowd_description") || profileNode.get("crowd_description").isNull())
            {
                log.info("场景更新校验失败：人群描述缺失, assetId={}", assetId);
                throw new RuntimeException("请填写人群");
            }
        }
        else if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            requireProfileText(profileNode, "name", "请填写名称", assetId);
            requireProfileText(profileNode, "summary", "请填写概要", assetId);
            requireProfileText(profileNode, "introduction", "请填写描述", assetId);
        }
    }

    /** update-main 辅助：要求 profileNode 中指定 key 的文本值非空 */
    private void requireProfileText(com.fasterxml.jackson.databind.node.ObjectNode node,
                                     String key, String errorMsg, Long assetId)
    {
        String val = readJsonText(node, key);
        if (StrUtil.isBlank(val))
        {
            log.info("更新校验失败：{}为空, assetId={}", key, assetId);
            throw new RuntimeException(errorMsg);
        }
    }

    /** update-main 辅助：要求 profileNode 中指定 key 存在且非 null（适用于数值、对象等非文本节点） */
    private void requireProfileNode(com.fasterxml.jackson.databind.node.ObjectNode node,
                                     String key, String errorMsg, Long assetId)
    {
        JsonNode v = node.get(key);
        if (Objects.isNull(v) || v.isNull())
        {
            log.info("更新校验失败：{}缺失, assetId={}", key, assetId);
            throw new RuntimeException(errorMsg);
        }
    }

    /** update-main 辅助：要求 profileNode 中指定 key 是非空数组 */
    private void requireProfileArray(com.fasterxml.jackson.databind.node.ObjectNode node,
                                      String key, String errorMsg, Long assetId)
    {
        JsonNode v = node.get(key);
        if (Objects.isNull(v) || v.isNull() || !v.isArray() || v.isEmpty())
        {
            log.info("更新校验失败：{}为空或非数组, assetId={}", key, assetId);
            throw new RuntimeException(errorMsg);
        }
    }

    /**
     * 构建从表实体（projectId赋值 + 根据项目类型处理episodeId）
     */
    private AidRolePropSceneForm buildFormEntity(RpsFormCreateRequest request, Long userId, AidComicProject project) {
        AidRolePropSceneForm form = new AidRolePropSceneForm();
        form.setAssetId(request.getAssetId());
        form.setProjectId(request.getProjectId());
        form.setUserId(userId);
        // form 不再承载图片字段，name 默认值不再依赖 imageUrl。
        // 优先用入参 name，否则用 changeReason 拼接，最终兜底"未命名形态"。
        String defaultName;
        if (StrUtil.isNotBlank(request.getChangeReason())) {
            defaultName = request.getChangeReason();
        } else {
            defaultName = "未命名形态";
        }
        form.setName(StrUtil.isBlank(request.getName()) ? defaultName : request.getName());
        // 写入变更原因
        if (StrUtil.isNotBlank(request.getChangeReason())) {
            form.setChangeReason(request.getChangeReason());
        }
        // 写入 promptText（由 createForm 从平铺字段组装好，或前端直接传入）
        if (StrUtil.isNotBlank(request.getPromptText()))
        {
            form.setPromptText(request.getPromptText());
            form.setVisualDescStatus(VISUAL_DESC_STATUS_COMPLETED);
        }
        else
        {
            form.setVisualDescStatus(VISUAL_DESC_STATUS_PENDING);
        }
        // form.is_use / form.image_url 字段已删除，图片与使用状态全部走 form_image 表
        form.setDelFlag(DEL_FLAG_NORMAL);
        form.setCreateTime(DateUtils.getNowDate());
        form.setCreateBy(String.valueOf(userId));

        // 根据项目类型处理episodeId
        if (Objects.equals(PROJECT_TYPE_MOVIE, project.getProjectType())) {
            form.setEpisodeId(0L);
        } else if (Objects.equals(PROJECT_TYPE_SERIES, project.getProjectType())) {
            Long episodeId = request.getEpisodeId();
            if (Objects.isNull(episodeId)) {
                log.info("从表创建失败，剧集模式下剧集ID不能为空: projectId={}", request.getProjectId());
                throw new RuntimeException("剧集ID不能为空");
            }
            AidComicEpisode episode = episodeService.selectAidComicEpisodeById(episodeId);
            if (Objects.isNull(episode)) {
                log.info("从表创建失败，剧集不存在: episodeId={}", episodeId);
                throw new RuntimeException("剧集不存在");
            }
            if (!Objects.equals(episode.getProjectId(), request.getProjectId())) {
                log.info("从表创建失败，剧集与项目不匹配: projectId={}, episodeId={}", request.getProjectId(), episodeId);
                throw new RuntimeException("剧集与项目不匹配");
            }
            form.setEpisodeId(episodeId);
        } else {
            log.info("从表创建失败，未知项目类型: projectType={}", project.getProjectType());
            throw new RuntimeException("项目类型异常");
        }

        return form;
    }

    /**
     * 获取主表资产，不存在则抛异常
     */
    private AidRolePropScene getMainAssetOrThrow(Long assetId,Long userId) {
        LambdaQueryWrapper<AidRolePropScene> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidRolePropScene::getId, assetId);
        wrapper.eq(AidRolePropScene::getUserId, userId);
        wrapper.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
        AidRolePropScene asset = rpsService.getOne(wrapper);
        if (Objects.isNull(asset)) {
            log.info("主表资产不存在: assetId={}", assetId);
            throw new RuntimeException("资产不存在");
        }
        return asset;
    }

    /**
     * 构建完整资产VO（主表+从表列表）
     */
    private RpsAssetVO buildAssetVO(AidRolePropScene mainAsset) {
        // 查从表
        LambdaQueryWrapper<AidRolePropSceneForm> formWrapper = Wrappers.lambdaQuery();
        formWrapper.eq(AidRolePropSceneForm::getAssetId, mainAsset.getId());
        formWrapper.eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL);
        formWrapper.orderByAsc(AidRolePropSceneForm::getCreateTime);
        List<AidRolePropSceneForm> forms = rpsFormService.list(formWrapper);

        String assetType = mainAsset.getAssetType();
        return convertToAssetVO(mainAsset, forms.stream()
                .map(f -> convertToFormVO(f, assetType)).collect(Collectors.toList()));
    }

    /**
     * 从URL提取图片名称
     */
    private String extractImageName(String imageUrl) {
        if (StrUtil.isBlank(imageUrl)) {
            return "未命名";
        }
        int lastSlash = imageUrl.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < imageUrl.length() - 1) {
            String fileName = imageUrl.substring(lastSlash + 1);
            int dotIndex = fileName.lastIndexOf('.');
            return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        }
        return "未命名";
    }
    // 设计要点：
    // - profileData 是角色/场景/道具的完整定义源数据，给前端与智能体共用（buildVisualStylistInputV2 等）
    // - 主表独立列（name / aliasesName / ... / availableSlots / hasCrowd / crowdDescription）
    //   由后端从 profileData 反向同步而来，便于列查询 / 展示
    // - 更新时：已有 profileData 字段绝不丢失；传入的 profileData 按字段合并覆盖同名 key；
    //   若本次未传 profileData 但传了顶层独立列，则把这些列补写进原 profileData 对应 key，而非重建瘦身 JSON

    /**
     * 严格解析用户本次显式传入的 profileData：必须是 JSON 对象字符串；空串返回新建空对象。
     * 非 JSON 对象 → log.info 后抛 "档案格式错"。
     */
    private com.fasterxml.jackson.databind.node.ObjectNode parseProfileDataStrict(String assetType, String rawProfileData)
    {
        if (StrUtil.isBlank(rawProfileData))
        {
            return OBJECT_MAPPER.createObjectNode();
        }
        try
        {
            JsonNode parsed = OBJECT_MAPPER.readTree(rawProfileData);
            if (Objects.isNull(parsed) || !parsed.isObject())
            {
                log.info("profileData 非 JSON 对象: assetType={}", assetType);
                throw new RuntimeException("档案格式错");
            }
            return (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.info("profileData 解析失败: assetType={}, err={}", assetType, e.getMessage());
            throw new RuntimeException("档案格式错");
        }
    }

    /**
     * 宽松解析库存 profileData：脏数据 → log.warn 标注 assetId / raw 后降级为空对象，绝不阻塞编辑。
     * 仅用于 {@link #updateMainAsset} 读取库存快照作为合并基底。
     */
    private com.fasterxml.jackson.databind.node.ObjectNode parseStoredProfileDataLenient(String assetType, Long assetId, String storedProfileData)
    {
        if (StrUtil.isBlank(storedProfileData))
        {
            return OBJECT_MAPPER.createObjectNode();
        }
        try
        {
            JsonNode parsed = OBJECT_MAPPER.readTree(storedProfileData);
            if (Objects.isNull(parsed) || !parsed.isObject())
            {
                log.warn("库存 profileData 非 JSON 对象，降级为空对象继续编辑: assetId={}, assetType={}, raw={}",
                        assetId, assetType, storedProfileData);
                return OBJECT_MAPPER.createObjectNode();
            }
            return (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
        }
        catch (Exception e)
        {
            log.warn("库存 profileData 解析失败，降级为空对象继续编辑: assetId={}, assetType={}, err={}",
                    assetId, assetType, e.getMessage());
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    /**
     * 字段级合并 profileData：把 overlay 的所有顶层 key 写入 base，同名 key 以 overlay 为准。
     * base 中未出现在 overlay 的 key 原样保留——这是"已有字段不丢"的核心。
     * 当前业务字段均为一层扁平结构，不做递归深入子对象。
     */
    private void mergeProfileData(com.fasterxml.jackson.databind.node.ObjectNode base,
                                   com.fasterxml.jackson.databind.node.ObjectNode overlay)
    {
        if (Objects.isNull(overlay) || overlay.isEmpty())
        {
            return;
        }
        java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = overlay.fields();
        while (it.hasNext())
        {
            java.util.Map.Entry<String, JsonNode> entry = it.next();
            base.set(entry.getKey(), entry.getValue());
        }
    }

    /**
     * create 链路：把 RpsCreateRequest 的顶层独立列按 assetType 映射规则覆盖进 profileData。
     */
    private void applyCreateRequestFieldsToProfileData(String assetType, RpsCreateRequest req,
                                                        com.fasterxml.jackson.databind.node.ObjectNode node)
    {
        if (StrUtil.isNotBlank(req.getName())) node.put("name", req.getName());
        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            // 基础映射字段
            if (StrUtil.isNotBlank(req.getAliasesName())) node.put("aliases", req.getAliasesName());
            if (StrUtil.isNotBlank(req.getIntroduction())) node.put("introduction", req.getIntroduction());
            if (StrUtil.isNotBlank(req.getGender())) node.put("gender", req.getGender());
            if (StrUtil.isNotBlank(req.getAgeRange())) node.put("age_range", req.getAgeRange());
            if (StrUtil.isNotBlank(req.getRoleLevel())) node.put("role_level", req.getRoleLevel());
            // 扩展 profileData 字段（顶层传入时补写进 profileData）
            if (StrUtil.isNotBlank(req.getArchetype())) node.put("archetype", req.getArchetype());
            if (StrUtil.isNotBlank(req.getEraPeriod())) node.put("era_period", req.getEraPeriod());
            if (StrUtil.isNotBlank(req.getOccupation())) node.put("occupation", req.getOccupation());
            if (Objects.nonNull(req.getCostumeTier())) node.put("costume_tier", req.getCostumeTier());
            if (StrUtil.isNotBlank(req.getSocialClass())) node.put("social_class", req.getSocialClass());
            if (StrUtil.isNotBlank(req.getPrimaryIdentifier())) node.put("primary_identifier", req.getPrimaryIdentifier());
            // 数组字段：非空时序列化为 JSON 数组节点
            if (CollectionUtil.isNotEmpty(req.getVisualKeywords()))
            {
                node.set("visual_keywords", OBJECT_MAPPER.valueToTree(req.getVisualKeywords()));
            }
            if (CollectionUtil.isNotEmpty(req.getPersonalityTags()))
            {
                node.set("personality_tags", OBJECT_MAPPER.valueToTree(req.getPersonalityTags()));
            }
            if (CollectionUtil.isNotEmpty(req.getSuggestedColors()))
            {
                node.set("suggested_colors", OBJECT_MAPPER.valueToTree(req.getSuggestedColors()));
            }
        }
        else if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            if (StrUtil.isNotBlank(req.getIntroduction())) node.put("introduction", req.getIntroduction());
            if (StrUtil.isNotBlank(req.getSummary())) node.put("summary", req.getSummary());
            if (StrUtil.isNotBlank(req.getAvailableSlots())) setAvailableSlotsNode(node, req.getAvailableSlots());
            if (Objects.nonNull(req.getHasCrowd())) node.put("has_crowd", req.getHasCrowd());
            // crowd_description 允许空字符串 ""（无人群时前端显式传空串），因此用 nonNull 而非 isNotBlank
            if (Objects.nonNull(req.getCrowdDescription())) node.put("crowd_description", req.getCrowdDescription());
        }
        else if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            if (StrUtil.isNotBlank(req.getIntroduction())) node.put("introduction", req.getIntroduction());
            if (StrUtil.isNotBlank(req.getSummary())) node.put("summary", req.getSummary());
        }
        // asset_type 固定写入，便于下游一次拿全定义
        node.put("asset_type", assetType);
        // 历史兼容：把旧 description 归并到 introduction（仅 scene/prop）
        migrateDescriptionToIntroduction(assetType, node);
    }

    /**
     * update 链路：同 {@link #applyCreateRequestFieldsToProfileData}，针对 RpsUpdateMainRequest。
     * scene 更新时只补写顶层独立列对应的 key，统一使用单个 introduction。
     */
    private void applyUpdateRequestFieldsToProfileData(String assetType, RpsUpdateMainRequest req,
                                                        com.fasterxml.jackson.databind.node.ObjectNode node)
    {
        if (StrUtil.isNotBlank(req.getName())) node.put("name", req.getName());
        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            // 基础映射字段
            if (StrUtil.isNotBlank(req.getAliasesName())) node.put("aliases", req.getAliasesName());
            if (StrUtil.isNotBlank(req.getIntroduction())) node.put("introduction", req.getIntroduction());
            if (StrUtil.isNotBlank(req.getGender())) node.put("gender", req.getGender());
            if (StrUtil.isNotBlank(req.getAgeRange())) node.put("age_range", req.getAgeRange());
            if (StrUtil.isNotBlank(req.getRoleLevel())) node.put("role_level", req.getRoleLevel());
            // 扩展 profileData 字段（顶层传入时补写进 profileData）
            if (StrUtil.isNotBlank(req.getArchetype())) node.put("archetype", req.getArchetype());
            if (StrUtil.isNotBlank(req.getEraPeriod())) node.put("era_period", req.getEraPeriod());
            if (StrUtil.isNotBlank(req.getOccupation())) node.put("occupation", req.getOccupation());
            if (Objects.nonNull(req.getCostumeTier())) node.put("costume_tier", req.getCostumeTier());
            if (StrUtil.isNotBlank(req.getSocialClass())) node.put("social_class", req.getSocialClass());
            if (StrUtil.isNotBlank(req.getPrimaryIdentifier())) node.put("primary_identifier", req.getPrimaryIdentifier());
            // 数组字段：非空时序列化为 JSON 数组节点
            if (CollectionUtil.isNotEmpty(req.getVisualKeywords()))
            {
                node.set("visual_keywords", OBJECT_MAPPER.valueToTree(req.getVisualKeywords()));
            }
            if (CollectionUtil.isNotEmpty(req.getPersonalityTags()))
            {
                node.set("personality_tags", OBJECT_MAPPER.valueToTree(req.getPersonalityTags()));
            }
            if (CollectionUtil.isNotEmpty(req.getSuggestedColors()))
            {
                node.set("suggested_colors", OBJECT_MAPPER.valueToTree(req.getSuggestedColors()));
            }
        }
        else if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            if (StrUtil.isNotBlank(req.getIntroduction())) node.put("introduction", req.getIntroduction());
            if (StrUtil.isNotBlank(req.getSummary())) node.put("summary", req.getSummary());
            if (StrUtil.isNotBlank(req.getAvailableSlots())) setAvailableSlotsNode(node, req.getAvailableSlots());
            if (Objects.nonNull(req.getHasCrowd())) node.put("has_crowd", req.getHasCrowd());
            // crowd_description 允许空字符串 ""（无人群时前端显式传空串），因此用 nonNull 而非 isNotBlank
            if (Objects.nonNull(req.getCrowdDescription())) node.put("crowd_description", req.getCrowdDescription());
        }
        else if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            if (StrUtil.isNotBlank(req.getIntroduction())) node.put("introduction", req.getIntroduction());
            if (StrUtil.isNotBlank(req.getSummary())) node.put("summary", req.getSummary());
        }
        node.put("asset_type", assetType);
        // 历史兼容：把旧 description 归并到 introduction（仅 scene/prop）
        migrateDescriptionToIntroduction(assetType, node);
    }

    /** 把 availableSlots 字符串解析成 JSON 数组节点写入 profileData.available_slots；解析失败抛 "槽位格式错"。 */
    private void setAvailableSlotsNode(com.fasterxml.jackson.databind.node.ObjectNode node, String availableSlotsJson)
    {
        try
        {
            JsonNode arr = OBJECT_MAPPER.readTree(availableSlotsJson);
            if (Objects.isNull(arr) || !arr.isArray())
            {
                log.info("availableSlots 非 JSON 数组: raw={}", availableSlotsJson);
                throw new RuntimeException("槽位格式错");
            }
            node.set("available_slots", arr);
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.info("availableSlots 写入 profileData 时解析失败: err={}", e.getMessage());
            throw new RuntimeException("槽位格式错");
        }
    }

    /**
     * 从合并后的 profileData 反解析主表独立列。
     * 仅当 profileData 中对应 key 非空时才填充到 entity，避免把已有列置空。
     * 映射规则见 {@link #applyCreateRequestFieldsToProfileData} 的反向映射。
     */
    private void syncMainColumnsFromProfileData(String assetType,
                                                 com.fasterxml.jackson.databind.node.ObjectNode profileNode,
                                                 AidRolePropScene entity)
    {
        String name = readJsonText(profileNode, "name");
        if (StrUtil.isNotBlank(name)) entity.setName(name);

        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            String aliases = readJsonText(profileNode, "aliases");
            if (StrUtil.isNotBlank(aliases)) entity.setAliasesName(aliases);
            String introduction = readJsonText(profileNode, "introduction");
            if (StrUtil.isNotBlank(introduction)) entity.setIntroduction(introduction);
            String gender = readJsonText(profileNode, "gender");
            if (StrUtil.isNotBlank(gender)) entity.setGender(gender);
            String ageRange = readJsonText(profileNode, "age_range");
            if (StrUtil.isNotBlank(ageRange)) entity.setAgeRange(ageRange);
            String roleLevel = readJsonText(profileNode, "role_level");
            if (StrUtil.isNotBlank(roleLevel)) entity.setRoleLevel(roleLevel);
        }
        else if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            String introduction = readJsonText(profileNode, "introduction");
            if (StrUtil.isNotBlank(introduction)) entity.setIntroduction(introduction);
            String summary = readJsonText(profileNode, "summary");
            if (StrUtil.isNotBlank(summary)) entity.setSummary(summary);
            // available_slots 允许是数组节点或 JSON 字符串；主表列统一落 JSON 字符串
            JsonNode slots = profileNode.get("available_slots");
            if (Objects.nonNull(slots) && !slots.isNull())
            {
                String slotsStr = slots.isTextual() ? slots.asText() : slots.toString();
                if (StrUtil.isNotBlank(slotsStr)) entity.setAvailableSlots(slotsStr);
            }
            JsonNode crowd = profileNode.get("has_crowd");
            if (Objects.nonNull(crowd) && !crowd.isNull())
            {
                if (crowd.isNumber())
                {
                    entity.setHasCrowd(crowd.asInt());
                }
                else if (crowd.isTextual() && StrUtil.isNotBlank(crowd.asText()))
                {
                    try { entity.setHasCrowd(Integer.parseInt(crowd.asText().trim())); }
                    catch (NumberFormatException ignored) { /* 脏数据忽略，不阻塞编辑 */ }
                }
            }
            // crowd_description 允许空字符串 ""（无人群时前端显式传空串），只要 key 存在就同步
            if (profileNode.has("crowd_description") && !profileNode.get("crowd_description").isNull())
            {
                entity.setCrowdDescription(readJsonText(profileNode, "crowd_description"));
            }
        }
        else if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            String introduction = readJsonText(profileNode, "introduction");
            if (StrUtil.isNotBlank(introduction)) entity.setIntroduction(introduction);
            String summary = readJsonText(profileNode, "summary");
            if (StrUtil.isNotBlank(summary)) entity.setSummary(summary);
        }
    }

    /**
     * 判断请求中是否携带了平铺结构化字段（用于决定是否需要自动组装 promptText）。
     * 只要存在任何一个非空的 promptText 内部字段，即视为"有平铺字段"。
     */
    private boolean hasStructuredFields(RpsUpdateFormRequest req)
    {
        // character
        if (StrUtil.isNotBlank(req.getDescriptions())) return true;
        if (Objects.nonNull(req.getAppearanceId())) return true;
        // scene / prop（stylist LLM 输出格式平铺字段）
        if (StrUtil.isNotBlank(req.getTitle())) return true;
        if (StrUtil.isNotBlank(req.getPrompt())) return true;
        if (StrUtil.isNotBlank(req.getPromptType())) return true;
        if (StrUtil.isNotBlank(req.getAspectRatio())) return true;
        if (StrUtil.isNotBlank(req.getImageUsage())) return true;
        if (StrUtil.isNotBlank(req.getReference())) return true;
        if (Objects.nonNull(req.getViewpoints())) return true;
        return false;
    }

    /**
     * 判断 create-form 请求中是否携带了平铺结构化字段。
     * 只要存在任何一个非空的 promptText 内部字段，即视为"有平铺字段"。
     */
    private boolean hasCreateFormStructuredFields(RpsFormCreateRequest req)
    {
        // character 专属
        if (StrUtil.isNotBlank(req.getDescriptions())) return true;
        if (Objects.nonNull(req.getAppearanceId())) return true;
        // scene / prop 共用
        if (StrUtil.isNotBlank(req.getSummary())) return true;
        if (StrUtil.isNotBlank(req.getIntroduction())) return true;
        // scene 专属
        if (Objects.nonNull(req.getHasCrowd())) return true;
        if (Objects.nonNull(req.getCrowdDescription())) return true;
        if (CollectionUtil.isNotEmpty(req.getAvailableSlots())) return true;
        return false;
    }

    /**
     * create-form 平铺字段模式下按资产类型校验结构化必填字段。
     */
    private void validateCreateFormStructuredFields(String assetType, RpsFormCreateRequest req)
    {
        // 通用必填
        if (StrUtil.isBlank(req.getName()))
        {
            log.info("form创建校验失败：name为空, assetType={}", assetType);
            throw new RuntimeException("请填写名称");
        }
        if (StrUtil.isBlank(req.getChangeReason()))
        {
            log.info("form创建校验失败：changeReason为空, assetType={}", assetType);
            throw new RuntimeException("请填写原因");
        }

        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            if (StrUtil.isBlank(req.getDescriptions()))
            {
                log.info("form创建校验失败：角色descriptions为空");
                throw new RuntimeException("请填写描述");
            }
        }
        else if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            if (StrUtil.isBlank(req.getSummary()))
            {
                log.info("form创建校验失败：场景summary为空");
                throw new RuntimeException("请填写概要");
            }
            if (StrUtil.isBlank(req.getIntroduction()))
            {
                log.info("form创建校验失败：场景introduction为空");
                throw new RuntimeException("请填写描述");
            }
            if (Objects.isNull(req.getHasCrowd()))
            {
                log.info("form创建校验失败：场景hasCrowd为空");
                throw new RuntimeException("请填写人群");
            }
            if (CollectionUtil.isEmpty(req.getAvailableSlots()))
            {
                log.info("form创建校验失败：场景availableSlots为空");
                throw new RuntimeException("请填写槽位");
            }
            // crowdDescription 允许空字符串 ""，但 key 必须存在（nonNull 判断）
            if (Objects.isNull(req.getCrowdDescription()))
            {
                log.info("form创建校验失败：场景crowdDescription缺失");
                throw new RuntimeException("请填写人群");
            }
        }
        else if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            if (StrUtil.isBlank(req.getSummary()))
            {
                log.info("form创建校验失败：道具summary为空");
                throw new RuntimeException("请填写概要");
            }
            if (StrUtil.isBlank(req.getIntroduction()))
            {
                log.info("form创建校验失败：道具introduction为空");
                throw new RuntimeException("请填写描述");
            }
        }
    }

    /**
     * create-form 平铺字段模式：把平铺字段写入空 promptText 节点。
     * 与 update-form 的 {@code mergeFormFlatFieldsIntoPromptText} 对齐，
     * 区别在于 create 没有旧值合并，从空节点开始写入。
     * crowdDescription 用 {@code Objects.nonNull} 判断，允许空字符串写入。
     */
    private void mergeCreateFormFlatFieldsIntoPromptText(String assetType, RpsFormCreateRequest req,
                                                          com.fasterxml.jackson.databind.node.ObjectNode node)
    {
        // 通用字段：name / changeReason
        if (StrUtil.isNotBlank(req.getName())) node.put("name", req.getName());
        if (StrUtil.isNotBlank(req.getChangeReason())) node.put("changeReason", req.getChangeReason());

        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            if (Objects.nonNull(req.getAppearanceId())) node.put("appearanceId", req.getAppearanceId());
            if (StrUtil.isNotBlank(req.getDescriptions())) node.put("descriptions", req.getDescriptions());
        }
        else if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            if (StrUtil.isNotBlank(req.getSummary())) node.put("summary", req.getSummary());
            if (StrUtil.isNotBlank(req.getIntroduction())) node.put("introduction", req.getIntroduction());
            if (Objects.nonNull(req.getHasCrowd())) node.put("has_crowd", req.getHasCrowd());
            // crowdDescription 允许空字符串 ""，用 nonNull 判断
            if (Objects.nonNull(req.getCrowdDescription())) node.put("crowd_description", req.getCrowdDescription());
            // available_slots：数组形式保留
            if (CollectionUtil.isNotEmpty(req.getAvailableSlots()))
            {
                node.set("available_slots", OBJECT_MAPPER.valueToTree(req.getAvailableSlots()));
            }
        }
        else if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            if (StrUtil.isNotBlank(req.getSummary())) node.put("summary", req.getSummary());
            if (StrUtil.isNotBlank(req.getIntroduction())) node.put("introduction", req.getIntroduction());
        }
    }

    /**
     * 宽松解析库存旧 form.prompt_text。
     * 非法 JSON / 空值 → 返回空对象，不阻塞更新。
     */
    private com.fasterxml.jackson.databind.node.ObjectNode parseStoredFormPromptTextLenient(
            String assetType, Long formId, String storedPromptText)
    {
        if (StrUtil.isBlank(storedPromptText))
        {
            return OBJECT_MAPPER.createObjectNode();
        }
        try
        {
            JsonNode parsed = OBJECT_MAPPER.readTree(storedPromptText);
            if (Objects.isNull(parsed) || !parsed.isObject())
            {
                log.info("form.promptText 非 JSON 对象，降级为空对象: formId={}, assetType={}", formId, assetType);
                return OBJECT_MAPPER.createObjectNode();
            }
            return (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
        }
        catch (Exception e)
        {
            log.info("form.promptText 解析失败，降级为空对象: formId={}, assetType={}, err={}", formId, assetType, e.getMessage());
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    /**
     * 把本次平铺字段增量合并进旧 promptText 节点。
     * 只覆盖前端本次显式传入的字段，未传字段保留旧值。
     */
    private void mergeFormFlatFieldsIntoPromptText(String assetType, RpsUpdateFormRequest req,
                                                    com.fasterxml.jackson.databind.node.ObjectNode node)
    {
        // 通用字段：name / changeReason
        if (StrUtil.isNotBlank(req.getName())) node.put("name", req.getName());
        if (StrUtil.isNotBlank(req.getChangeReason())) node.put("changeReason", req.getChangeReason());

        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            if (Objects.nonNull(req.getAppearanceId())) node.put("appearanceId", req.getAppearanceId());
            if (StrUtil.isNotBlank(req.getDescriptions())) node.put("descriptions", req.getDescriptions());
        }
        else if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            // scene：stylist LLM 输出格式平铺字段
            if (StrUtil.isNotBlank(req.getTitle())) node.put("title", req.getTitle());
            if (StrUtil.isNotBlank(req.getPrompt())) node.put("prompt", req.getPrompt());
            if (StrUtil.isNotBlank(req.getPromptType())) node.put("promptType", req.getPromptType());
            if (StrUtil.isNotBlank(req.getAspectRatio())) node.put("aspectRatio", req.getAspectRatio());
            if (StrUtil.isNotBlank(req.getImageUsage())) node.put("imageUsage", req.getImageUsage());
            if (StrUtil.isNotBlank(req.getReference())) node.put("reference", req.getReference());
            if (Objects.nonNull(req.getViewpoints()))
            {
                node.set("viewpoints", OBJECT_MAPPER.valueToTree(req.getViewpoints()));
            }
        }
        else if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            // prop：stylist LLM 输出格式平铺字段
            if (StrUtil.isNotBlank(req.getTitle())) node.put("title", req.getTitle());
            if (StrUtil.isNotBlank(req.getPrompt())) node.put("prompt", req.getPrompt());
            if (StrUtil.isNotBlank(req.getPromptType())) node.put("promptType", req.getPromptType());
        }
    }

    /**
     * 保证最终 promptText 结构完整：强制补 assetType，缺失时补 promptVersion=v2。
     */
    private void normalizeFormPromptText(String assetType, com.fasterxml.jackson.databind.node.ObjectNode node)
    {
        node.put("assetType", assetType);
        if (!node.has("promptVersion") || node.get("promptVersion").isNull())
        {
            node.put("promptVersion", "v2");
        }
    }

    /**
     * 从 promptText JSON 反解析 name / changeReason，回退同步到 form 独立列。
     * 仅当顶层 DTO 未显式传入对应字段时，才使用 promptText 内部的值，避免覆盖已传入的顶层字段。
     * promptText 若非合法 JSON 对象（纯文本历史数据），静默跳过，不阻塞更新。
     */
    private void syncFormColumnsFromPromptText(RpsUpdateFormRequest request,
                                                LambdaUpdateWrapper<AidRolePropSceneForm> updateWrapper)
    {
        try
        {
            JsonNode tree = OBJECT_MAPPER.readTree(request.getPromptText());
            if (Objects.isNull(tree) || !tree.isObject())
            {
                return;
            }
            com.fasterxml.jackson.databind.node.ObjectNode ptNode =
                (com.fasterxml.jackson.databind.node.ObjectNode) tree;
            // name：顶层未传时，从 promptText 回退同步
            if (StrUtil.isBlank(request.getName()))
            {
                String ptName = readJsonText(ptNode, "name");
                if (StrUtil.isNotBlank(ptName))
                {
                    updateWrapper.set(AidRolePropSceneForm::getName, ptName);
                }
                // scene/prop 协议下，name 字段可能叫 title
                else
                {
                    String ptTitle = readJsonText(ptNode, "title");
                    if (StrUtil.isNotBlank(ptTitle))
                    {
                        updateWrapper.set(AidRolePropSceneForm::getName, ptTitle);
                    }
                }
            }
            // changeReason：顶层未传时，从 promptText 回退同步
            if (StrUtil.isBlank(request.getChangeReason()))
            {
                String ptReason = readJsonText(ptNode, "changeReason");
                if (StrUtil.isNotBlank(ptReason))
                {
                    updateWrapper.set(AidRolePropSceneForm::getChangeReason, ptReason);
                }
            }
        }
        catch (Exception e)
        {
            // promptText 非合法 JSON（可能是纯文本历史数据），跳过反解析，不阻塞更新
            log.info("promptText 非 JSON 对象，跳过 form 列反解析: formId={}, err={}", request.getId(), e.getMessage());
        }
    }

    /** 读取 profileData 对象中某 key 的文本值：空节点 / null 节点返回空串；非文本节点以 toString 返回。 */
    private String readJsonText(com.fasterxml.jackson.databind.node.ObjectNode node, String key)
    {
        JsonNode v = node.get(key);
        if (Objects.isNull(v) || v.isNull())
        {
            return "";
        }
        return v.isTextual() ? v.asText() : v.toString();
    }

    /**
     * 历史兼容：scene/prop 的旧 profileData 可能用 `description` 作为详细描述，统一归并到 `introduction`。
     */
    private void migrateDescriptionToIntroduction(String assetType, com.fasterxml.jackson.databind.node.ObjectNode node)
    {
        // 仅 scene / prop 需要迁移
        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            return;
        }
        String oldDesc = readJsonText(node, "description");
        String curIntro = readJsonText(node, "introduction");
        // 即使旧 description 为空也移除 key，保证最终 profileData 不包含废弃字段
        if (node.has("description"))
        {
            node.remove("description");
        }
        // 当前无 introduction 且旧 description 非空→补充
        if (StrUtil.isBlank(curIntro) && StrUtil.isNotBlank(oldDesc))
        {
            node.put("introduction", oldDesc);
        }
    }

    /** 从 profileData 节点抽取 available_slots 的字符串表达，供 {@link #validateAvailableSlotsJson} 校验。 */
    private String extractAvailableSlotsString(com.fasterxml.jackson.databind.node.ObjectNode node)
    {
        JsonNode slots = node.get("available_slots");
        if (Objects.isNull(slots) || slots.isNull())
        {
            return null;
        }
        return slots.isTextual() ? slots.asText() : slots.toString();
    }

    /**
     * 校验 availableSlots 字段：空值放行；非空时必须是合法 JSON 数组字符串。
     * 业务语义固定为"位置数组"，非数组直接报 "槽位格式错"，避免脏数据进入 JSON 列后由数据库报错。
     */
    private void validateAvailableSlotsJson(String availableSlots)
    {
        if (StrUtil.isBlank(availableSlots))
        {
            return;
        }
        try
        {
            JsonNode parsed = OBJECT_MAPPER.readTree(availableSlots);
            if (Objects.isNull(parsed) || !parsed.isArray())
            {
                log.info("availableSlots 非 JSON 数组: raw={}", availableSlots);
                throw new RuntimeException("槽位格式错");
            }
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.info("availableSlots 解析失败: err={}", e.getMessage());
            throw new RuntimeException("槽位格式错");
        }
    }
    /**
     * 按 assetType 分类组装 RpsAssetVO，解析 profileData / expectedAppearances / availableSlots 等 JSON 字段为结构化数据。
     *
     *   - character：返回角色专属字段（gender / ageRange / roleLevel / archetype / eraPeriod 等）+ expectedAppearances 解析列表
     *   - scene：返回场景专属字段（summary / availableSlots 解析列表 / hasCrowd / crowdDescription）
     *   - prop：返回道具专属字段（summary）
     *
     */
    private RpsAssetVO convertToAssetVO(AidRolePropScene entity, List<RpsFormVO> forms) {
        String assetType = entity.getAssetType();
        // 通用字段
        RpsAssetVO.RpsAssetVOBuilder builder = RpsAssetVO.builder()
                .id(entity.getId())
                .assetType(assetType)
                .createSource(entity.getCreateSource())
                .assetName(entity.getName())
                .introduction(entity.getIntroduction())
                .forms(forms);

        // 按 assetType 填充专属字段
        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            builder.aliasesName(entity.getAliasesName())
                    .gender(entity.getGender())
                    .ageRange(entity.getAgeRange())
                    .roleLevel(entity.getRoleLevel());
            // 解析 profileData 中的角色扩展字段
            enrichCharacterProfileFields(builder, entity.getProfileData());
            // 解析 expectedAppearances JSON 数组
            builder.expectedAppearances(parseJsonArray(entity.getExpectedAppearances()));
        }
        else if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            builder.summary(entity.getSummary())
                    .hasCrowd(entity.getHasCrowd())
                    .crowdDescription(entity.getCrowdDescription());
            // 解析 availableSlots JSON 数组为 List<String>
            builder.availableSlots(parseStringArray(entity.getAvailableSlots()));
        }
        else if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            builder.summary(entity.getSummary());
        }

        return builder.build();
    }

    /**
     * 从 profileData JSON 中提取角色扩展字段填充到 VO builder。
     * 解析失败静默降级（warn 日志），不阻塞正常返回。
     */
    private void enrichCharacterProfileFields(RpsAssetVO.RpsAssetVOBuilder builder, String profileData)
    {
        if (StrUtil.isBlank(profileData))
        {
            return;
        }
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(profileData);
            if (Objects.isNull(root) || !root.isObject())
            {
                return;
            }
            // 文本字段
            setIfPresent(root, "archetype", v -> builder.archetype(v.asText()));
            setIfPresent(root, "era_period", v -> builder.eraPeriod(v.asText()));
            setIfPresent(root, "occupation", v -> builder.occupation(v.asText()));
            setIfPresent(root, "social_class", v -> builder.socialClass(v.asText()));
            setIfPresent(root, "primary_identifier", v -> builder.primaryIdentifier(v.asText()));
            // 数值字段
            setIfPresent(root, "costume_tier", v -> {
                if (v.isNumber()) builder.costumeTier(v.asInt());
            });
            // 数组字段
            builder.visualKeywords(parseStringArrayNode(root.get("visual_keywords")));
            builder.personalityTags(parseStringArrayNode(root.get("personality_tags")));
            builder.suggestedColors(parseStringArrayNode(root.get("suggested_colors")));
        }
        catch (Exception e)
        {
            log.warn("profileData 解析失败，降级跳过: err={}", e.getMessage());
        }
    }

    /**
     * 从 JsonNode 中取值，非空且非 null 节点时执行 consumer。
     */
    private void setIfPresent(JsonNode root, String field, java.util.function.Consumer<JsonNode> consumer)
    {
        JsonNode node = root.get(field);
        if (Objects.nonNull(node) && !node.isNull() && !node.isMissingNode())
        {
            consumer.accept(node);
        }
    }

    /**
     * 解析 JSON 数组字符串为 List&lt;Object&gt;（expectedAppearances 等）。
     * 解析失败返回 null。
     */
    @SuppressWarnings("unchecked")
    private List<Object> parseJsonArray(String json)
    {
        if (StrUtil.isBlank(json))
        {
            return null;
        }
        try
        {
            JsonNode arr = OBJECT_MAPPER.readTree(json);
            if (Objects.isNull(arr) || !arr.isArray())
            {
                return null;
            }
            return OBJECT_MAPPER.convertValue(arr, List.class);
        }
        catch (Exception e)
        {
            log.warn("JSON 数组解析失败: err={}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 JSON 数组字符串为 List&lt;String&gt;（availableSlots 等）。
     * 解析失败返回 null。
     */
    private List<String> parseStringArray(String json)
    {
        if (StrUtil.isBlank(json))
        {
            return null;
        }
        try
        {
            JsonNode arr = OBJECT_MAPPER.readTree(json);
            return parseStringArrayNode(arr);
        }
        catch (Exception e)
        {
            log.warn("字符串数组解析失败: err={}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 JsonNode（应为数组）提取 List&lt;String&gt;。
     *
     *   - null / 非数组 → 返回 null（表示缺失/异常）
     *   - 合法空数组 [] → 返回空 List（表示明确无值，是有效业务状态）
     *
     */
    private List<String> parseStringArrayNode(JsonNode arrNode)
    {
        if (Objects.isNull(arrNode) || !arrNode.isArray())
        {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (JsonNode n : arrNode)
        {
            String s = n.asText("");
            if (StrUtil.isNotBlank(s))
            {
                result.add(s);
            }
        }
        // 合法空数组是有效业务值，不应被吞为 null
        return result;
    }

    /**
     * 单参重载：自行查图片列表后委托双参版本。需传入 assetType 以解析 promptText。
     */
    private RpsFormVO convertToFormVO(AidRolePropSceneForm form, String assetType) {
        // 查同 form 下有效图片列表，供 VO 演出主图 / images 数组
        LambdaQueryWrapper<AidRolePropSceneFormImage> imgQuery = Wrappers.lambdaQuery();
        imgQuery.eq(AidRolePropSceneFormImage::getFormId, form.getId());
        imgQuery.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        imgQuery.orderByAsc(AidRolePropSceneFormImage::getSortOrder);
        imgQuery.orderByAsc(AidRolePropSceneFormImage::getCreateTime);
        List<AidRolePropSceneFormImage> imgs = rpsFormImageService.list(imgQuery);
        return convertToFormVO(form, CollectionUtil.isNotEmpty(imgs) ? imgs : new ArrayList<>(), assetType);
    }

    /**
     * 组装 RpsFormVO：合成 imageUrl / images / canAutoGenerateImage 等字段，
     * 并按 assetType 解析 promptText 为结构化字段（不再返回原始 JSON）。
     */
    private RpsFormVO convertToFormVO(AidRolePropSceneForm form, List<AidRolePropSceneFormImage> imgs, String assetType)
    {
        // 主图Url 仅可来自 form_image：优先 is_use=1 的图片 → 其次最新一张；form 表不承载图片字段。
        // form_image 支持"多张同时使用中"，本字段只取首张命中（单图展示口径），完整列表见 images。
        String imageUrl = null;
        Long currentImageId = null;
        if (CollectionUtil.isNotEmpty(imgs))
        {
            for (AidRolePropSceneFormImage i : imgs)
            {
                if (Objects.nonNull(i.getIsUse()) && i.getIsUse() == 1)
                {
                    imageUrl = i.getImageUrl();
                    currentImageId = i.getId();
                    break;
                }
            }
            if (StrUtil.isBlank(imageUrl))
            {
                AidRolePropSceneFormImage latest = imgs.get(imgs.size() - 1);
                imageUrl = latest.getImageUrl();
            }
        }

        int variantCount = parsePromptVariantCount(form.getPromptText());
        boolean canAutoGen = StrUtil.isNotBlank(form.getPromptText()) && variantCount > 0;

        List<RpsFormImageVO> imageVOs = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(imgs))
        {
            for (AidRolePropSceneFormImage i : imgs)
            {
                imageVOs.add(RpsFormImageVO.builder()
                        .id(i.getId())
                        .name(i.getName())
                        .imageUrl(i.getImageUrl())
                        .sourceType(i.getSourceType())
                        .descriptionIndex(i.getDescriptionIndex())
                        .isUse(i.getIsUse())
                        .imageStatus(i.getImageStatus())
                        .referenceImages(deserializeReferenceImages(i.getReferenceImages()))
                        .build());
            }
        }

        RpsFormVO.RpsFormVOBuilder builder = RpsFormVO.builder()
                .id(form.getId())
                .assetType(assetType)
                .name(form.getName())
                .changeReason(form.getChangeReason())
                .createSource(form.getCreateSource())
                .imageUrl(imageUrl)
                .visualDescStatus(form.getVisualDescStatus())
                .canAutoGenerateImage(canAutoGen)
                .promptVariantCount(variantCount)
                .imageCount(imageVOs.size())
                .currentImageId(currentImageId)
                .images(imageVOs);

        enrichFormPromptFields(builder, form.getPromptText(), assetType);

        return builder.build();
    }

    /**
     * 从 promptText JSON 中提取结构化字段填充到 RpsFormVO builder。
     */
    private void enrichFormPromptFields(RpsFormVO.RpsFormVOBuilder builder, String promptText, String assetType)
    {
        if (StrUtil.isBlank(promptText))
        {
            return;
        }
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(promptText);
            if (Objects.isNull(root) || !root.isObject())
            {
                return;
            }
            if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
            {
                // descriptions 字段（角色外观视觉描述）
                setIfPresent(root, "descriptions", v -> builder.descriptions(v.asText()));
                // appearanceId 字段（子形象编号）
                setIfPresent(root, "appearanceId", v -> {
                    if (v.isNumber()) builder.appearanceId(v.asInt());
                });
                // 兼容 snake_case
                setIfPresent(root, "appearance_id", v -> {
                    if (v.isNumber()) builder.appearanceId(v.asInt());
                });
            }
            else if (Objects.equals(ASSET_TYPE_SCENE, assetType))
            {
                setIfPresent(root, "summary", v -> builder.summary(v.asText()));
                setIfPresent(root, "introduction", v -> builder.introduction(v.asText()));
                // hasCrowd：支持 camelCase + snake_case
                setIfPresent(root, "hasCrowd", v -> {
                    if (v.isNumber()) builder.hasCrowd(v.asInt());
                });
                setIfPresent(root, "has_crowd", v -> {
                    if (v.isNumber()) builder.hasCrowd(v.asInt());
                });
                // crowdDescription：支持 camelCase + snake_case
                setIfPresent(root, "crowdDescription", v -> builder.crowdDescription(v.asText()));
                setIfPresent(root, "crowd_description", v -> builder.crowdDescription(v.asText()));
                // availableSlots：支持 camelCase + snake_case，解析为 List<String>
                JsonNode slotsNode = root.has("availableSlots") ? root.get("availableSlots")
                        : root.get("available_slots");
                // 合法空数组是有效业务值，不吞为 null
                builder.availableSlots(parseStringArrayNode(slotsNode));
            }
            else if (Objects.equals(ASSET_TYPE_PROP, assetType))
            {
                setIfPresent(root, "summary", v -> builder.summary(v.asText()));
                setIfPresent(root, "introduction", v -> builder.introduction(v.asText()));
            }
        }
        catch (Exception e)
        {
            log.warn("promptText 解析失败，降级跳过: formAssetType={}, err={}", assetType, e.getMessage());
        }
    }

    /**
     * 解析 promptText 中变体数量。
     */
    private int parsePromptVariantCount(String promptText)
    {
        if (StrUtil.isBlank(promptText))
        {
            return 0;
        }
        // 角色 / 场景均为单描述模型，变体数恒为 1
        return 1;
    }

    /**
     * 反序列化 form_image.reference_images 为 List。失败返 null + warn。
     */
    private List<String> deserializeReferenceImages(String json)
    {
        if (StrUtil.isBlank(json))
        {
            return null;
        }
        try
        {
            JsonNode arr = OBJECT_MAPPER.readTree(json);
            if (!arr.isArray())
            {
                return null;
            }
            List<String> out = new ArrayList<>();
            for (JsonNode n : arr)
            {
                String s = n.asText("");
                if (StrUtil.isNotBlank(s))
                {
                    out.add(s);
                }
            }
            return out;
        }
        catch (Exception e)
        {
            log.warn("reference_images 解析失败: err={}", e.getMessage());
            return null;
        }
    }
}
