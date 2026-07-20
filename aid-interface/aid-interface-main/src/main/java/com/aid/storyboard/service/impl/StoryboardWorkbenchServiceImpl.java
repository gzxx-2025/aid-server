package com.aid.storyboard.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.*;
import com.aid.aid.mapper.AidUserProfileMapper;
import com.aid.aid.service.*;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.billing.service.BillingPriceMultiplierService;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.StringUtils;
import com.aid.common.utils.image.ImageUrlValidator;
import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.domain.dto.GenerationParams;
import com.aid.enums.CreationStepEnum;
import com.aid.enums.GenTypeEnum;
import com.aid.enums.ProjectTypeEnum;
import com.aid.agent.AgentDefaultParamsApplier;
import com.aid.agent.AgentModelDefault;
import com.aid.agent.AgentModelResolver;
import com.aid.agent.AgentScene;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.enums.MediaType;
import com.aid.media.service.IMediaGenerationService;
import com.aid.service.IAiModelConfigService;
import com.aid.step.service.ICreationStepService;
import com.aid.storyboard.dto.*;
import com.aid.storyboard.service.IStoryboardWorkbenchService;
import com.aid.voice.util.DialogueSubtitleFormatter;
import com.aid.voice.util.DialogueTextSanitizer;
import com.aid.voice.util.VoiceEmotionCapability;
import com.aid.rps.resolver.StoryboardAudioReferenceResolver;
import com.aid.rps.resolver.StoryboardAudioReferenceResolver.DialogueSegment;
import com.aid.rps.resolver.StoryboardImageReferenceResolver;
import com.aid.storyboard.vo.AudioTaskVO;
import com.aid.storyboard.vo.GenRecordVO;
import com.aid.storyboard.vo.StoryboardRefImageVO;
import com.aid.storyboard.vo.StoryboardSpeakerVoiceVO;
import com.aid.storyboard.vo.StoryboardVO;
import com.aid.common.vo.BatchOperationResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 分镜工作台核心业务Service实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class StoryboardWorkbenchServiceImpl implements IStoryboardWorkbenchService {

    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";
    /** 删除标志：已删除 */
    private static final String DEL_FLAG_DELETED = "1";
    /** 模型状态：正常 */
    private static final String STATUS_NORMAL = "0";
    /** 选中标记 */
    private static final int SELECTED_YES = 1;
    private static final int SELECTED_NO = 0;
    /** 产物类型常量 */
    private static final String RECORD_TYPE_IMAGE = "image";
    private static final String RECORD_TYPE_VIDEO = "video";
    private static final String RECORD_TYPE_AUDIO = "audio";
    /** AI配音来源 */
    private static final int AUDIO_SOURCE_AI = 1;
    /** 对口型开启标记（lipSyncStatus 派生展示用） */
    private static final int LIP_SYNC_ENABLED = 1;
    /** 单次批量删除分镜的数量上限，防止超大列表锤库 */
    private static final int MAX_BATCH_DELETE = 200;

    /**
     * 分镜脚本 {@code script_params} 中文 key 全集，用于编辑回写时把可读文本反解析为结构化字段、识别字段边界。
     */
    private static final Set<String> SHOT_PARAM_KEYS = new LinkedHashSet<>(Arrays.asList(
            // —— 公共 / 漫剧单镜结构 key ——
            "镜号", "场次序号", "批内位置", "剧本内容", "画面描述", "台词", "动作状态", "叙事功能",
            "时间坐标", "年代坐标", "日期坐标", "气候天象", "引用信息", "景别", "拍摄角度", "镜头焦距",
            "镜头运动", "构图", "画面氛围", "音效", "色彩倾向", "光线", "曝光虚化",
            // —— 中文镜头组结构 key（专业版 multiref / auto_grid 宫格，缺失会导致编辑回写吞字段）——
            // 注意：凡是会渲染进 story_script 的 key 都必须在此登记；缺失的 key 行会被当成上一字段的
            // 多行续接值拼进其值里，触发"脚本已变更"误判 → 误清空 image_prompt/video_prompt 等派生提示词
            "镜头组", "镜头组编号", "镜头组序号", "画面说明", "时空环境", "镜头模式", "运镜等级", "时长估算", "镜头脚本"));

    /**
     * 渲染 {@code story_script} 可读文本时跳过的元数据 key
     * （与 {@code StoryboardScriptServiceImpl.renderShotAsReadableText} 对齐：编号类元数据不进可读文本）。
     */
    private static final Set<String> SHOT_META_SKIP_KEYS = new HashSet<>(Arrays.asList(
            "镜号", "场次序号", "批内位置"));


    /**
     * 配音 TTS 文本最大字符数。
     * 按 MiniMax 异步长文本 T2A V2 的 text 字段上限（5 万字符）统一收敛；豆包异步
     * 长文本同量级，通用安全上限。超过该阈值直接在业务层短文案 `文本超长` 拦截，
     * 不进入预冻结 / 上游提交，避免回滚成本。
     */
    private static final int TTS_TEXT_MAX_LENGTH = 50_000;

    /** 余额变动类型：消费 */
    private static final String CHANGE_TYPE_CONSUME = "consume";
    /** 业务类型：创作 */
    private static final String BIZ_TYPE_CREATE = "create";

    /** 角色资产类型（配音音色绑定按角色资产反查） */
    private static final String ASSET_TYPE_CHARACTER = "character";
    /** 配音类型：纯旁白（含画外音） */
    private static final String VOICE_TYPE_NARRATION = "narration";
    /** 配音类型：纯角色对白 */
    private static final String VOICE_TYPE_DIALOGUE = "dialogue";
    /** 配音类型：旁白+对白混合 */
    private static final String VOICE_TYPE_MIXED = "mixed";
    /** 配音状态：未配音 */
    private static final String AUDIO_STATUS_NONE = "NONE";

    /** 图片类genType集合(排他更新时只在图片之间互斥) */
    private static final List<String> IMAGE_GEN_TYPES = Arrays.asList(
            GenTypeEnum.IMAGE.getValue(), GenTypeEnum.GRID.getValue());
    /** 视频大类genType集合(选中/展示口径，含原视频与配音合成视频；列表查询按 video/compose 分轨) */
    private static final List<String> VIDEO_GEN_TYPES = Arrays.asList(
            GenTypeEnum.I2V.getValue(), GenTypeEnum.MULTI.getValue(), GenTypeEnum.EDGE.getValue(),
            GenTypeEnum.UPLOAD_VIDEO.getValue(),  // 用户上传视频归入视频大类
            GenTypeEnum.COMPOSE.getValue());      // 配音合成视频归入视频大类

    /**
     * 原视频轨 genType 集合（配音前的分镜视频）。
     * 视频大类单选语义：原视频与配音视频(compose)整大类互斥，一个分镜只允许一个视频被选中；
     * 分镜的"分镜视频"（final_video_id / 详情 finalVideoUrl）永远指向原视频轨，
     * 配音视频被选中不改该指针，供成品预览与成片合成消费。
     */
    private static final List<String> ORIGINAL_VIDEO_GEN_TYPES = GenTypeEnum.originalVideoValues();

    /** 配音轨 genType 集合（配音合成视频/对口型视频，列表按 type=compose 查询） */
    private static final List<String> COMPOSE_VIDEO_GEN_TYPES = GenTypeEnum.composeVideoValues();

    @Autowired
    private IAidStoryboardService aidStoryboardService;

    /**
     * 自身代理引用（@Lazy 避免循环依赖）：批量设/取消最终图 / 最终视频时借此调用 @Transactional 单条方法，
     * 使每条目走独立事务——单条失败不牵连其它条目（self-invocation 直接调本类方法会绕过 AOP 事务代理）。
     */
    @Lazy
    @Autowired
    private IStoryboardWorkbenchService self;

    @Autowired
    private IAidGenRecordService aidGenRecordService;
    /** OSS 文件清理服务：硬删生成记录时同步清理其 OSS 文件 */
    @Autowired
    private com.aid.media.cleanup.IMediaOssCleanupService mediaOssCleanupService;
    /** 生成产物级联清理服务：删分镜时级联硬删其下 gen_record / audio_record 并清 OSS */
    @Autowired
    private com.aid.media.cleanup.IGenerationArtifactCleanupService generationArtifactCleanupService;
    @Autowired
    private IAidAudioRecordService aidAudioRecordService;
    @Autowired
    private IAidAiModelService aidAiModelService;
    @Autowired
    private com.aid.aid.service.IAidAiVoiceLibraryService aidAiVoiceLibraryService;
    @Autowired
    private IAidPromptLibService aidPromptLibService;
    @Autowired
    private IAidUserProfileService aidUserProfileService;
    @Autowired
    private IAidBalanceLogService aidBalanceLogService;
    @Autowired
    private AidUserProfileMapper aidUserProfileMapper;
    @Autowired
    private IAccountUpdateService accountUpdateService;
    @Autowired
    private BillingPriceMultiplierService billingPriceMultiplierService;
    @Autowired
    private ICreationStepService creationStepService;
    @Autowired
    private IMediaGenerationService mediaGenerationService;
    @Autowired
    private IAidComicAssetService aidComicAssetService;
    @Autowired
    private IAidComicProjectService aidComicProjectService;
    @Autowired
    private IAidComicEpisodeService aidComicEpisodeService;
    /** Agent 默认模型解析器：用户未传 modelCode 时取默认模型 + 默认参数 */
    @Autowired
    private AgentModelResolver agentModelResolver;
    /** Agent 默认参数应用器：按"用户优先、默认兜底"+ 模型 capability 校验合并到生成请求 */
    @Autowired
    private AgentDefaultParamsApplier agentDefaultParamsApplier;
    /** AI 模型配置查询：用于 capability 校验 */
    @Autowired
    private IAiModelConfigService aiModelConfigService;
    /** 媒体URL拼接器：把DB相对路径拼回完整URL，供上传图真实性远程校验使用 */
    @Autowired
    private MediaUrlResolver mediaUrlResolver;
    /** OSS配置管理器：读取上传模式(oss/local)，决定是否对图片做公网远程探测 */
    @Autowired
    private OssConfigManager ossConfigManager;

    /** 用户图片上传内容安全审查守卫：上传后审，命中违规删 OSS 对象 + 抛业务异常 */
    @Autowired
    private com.aid.aid.moderation.UserImageUploadModerationGuard userImageUploadModerationGuard;

    /** MiniMax 归属判定器：业务层门禁与调度层路由共用同一份三级判定 */
    @Autowired
    private com.aid.media.provider.MinimaxProviderDetector minimaxProviderDetector;

    /** 台词段解析器：列表/详情回填配音类型与发言角色 */
    @Autowired
    private StoryboardAudioReferenceResolver audioReferenceResolver;
    /** 角色资产 Service：发言角色主名 → 角色资产匹配（音色绑定反查用） */
    @Autowired
    private IAidRolePropSceneService aidRolePropSceneService;
    /** 角色音色绑定 Service：角色资产 → 启用音色绑定（列表/详情展示用） */
    @Autowired
    private IAidRoleVoiceBindingService roleVoiceBindingService;
    @Override
    public List<StoryboardVO> listStoryboards(StoryboardListRequest request, Long userId) {
        Long episodeId = validateProjectAndEpisode(request.getProjectId(), request.getEpisodeId(), userId);

        LambdaQueryWrapper<AidStoryboard> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidStoryboard::getProjectId, request.getProjectId());
        wrapper.eq(AidStoryboard::getUserId, userId);
        wrapper.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.eq(AidStoryboard::getEpisodeId, episodeId);
        wrapper.orderByAsc(AidStoryboard::getSortOrder);
        List<AidStoryboard> list = aidStoryboardService.list(wrapper);
        if (cn.hutool.core.collection.CollectionUtil.isEmpty(list)) {
            return java.util.Collections.emptyList();
        }
        // 批量预加载各分镜主图记录（以 is_selected=1 为权威源），避免逐条查导致 N+1。
        // 同次查询顺带取回 gen_params（含参考图快照 referenceManifest），免再查库即可解析参考图。
        List<Long> storyboardIds = list.stream().map(AidStoryboard::getId).collect(Collectors.toList());
        Map<Long, AidGenRecord> finalImageRecordMap = loadFinalImageRecordsByStoryboardIds(storyboardIds, userId);
        // 批量预加载各分镜"分镜视频"记录（final_video_id 指针，恒为配音前原视频，不受配音视频选中影响）
        Map<Long, AidGenRecord> finalVideoRecordMap = loadFinalVideoRecordsByStoryboards(list, userId);
        // 批量预加载各分镜"最终配音视频"记录（compose 类 is_selected=1 主视频，成片合成导出优先取用）
        Map<Long, AidGenRecord> finalComposeVideoRecordMap =
                loadFinalComposeVideoRecordsByStoryboardIds(storyboardIds, userId);
        List<StoryboardVO> vos = list.stream()
                .map(s -> {
                    AidGenRecord rec = finalImageRecordMap.get(s.getId());
                    String finalImageUrl = (Objects.nonNull(rec) && StrUtil.isNotBlank(rec.getFileUrl()))
                            ? rec.getFileUrl() : null;
                    AidGenRecord videoRec = finalVideoRecordMap.get(s.getId());
                    String finalVideoUrl = (Objects.nonNull(videoRec) && StrUtil.isNotBlank(videoRec.getFileUrl()))
                            ? videoRec.getFileUrl() : null;
                    AidGenRecord composeRec = finalComposeVideoRecordMap.get(s.getId());
                    String finalComposeVideoUrl = (Objects.nonNull(composeRec)
                            && StrUtil.isNotBlank(composeRec.getFileUrl())) ? composeRec.getFileUrl() : null;
                    return buildStoryboardVO(s, finalImageUrl, parseReferenceImages(rec),
                            finalVideoUrl, finalComposeVideoUrl);
                })
                .collect(Collectors.toList());
        // 批量回填配音展示信息（配音类型/发言角色/绑定音色/配音状态），固定少量批量查询、无 N+1
        fillVoiceoverInfo(list, vos, userId);
        return vos;
    }

    /**
     * 批量回填分镜配音展示字段：配音类型、发言角色、角色音色绑定、配音状态。
     * 台词解析纯内存；角色资产 / 音色绑定 / 配音记录各最多 1 次批量查询。
     */
    private void fillVoiceoverInfo(List<AidStoryboard> storyboards, List<StoryboardVO> vos, Long userId)
    {
        if (CollectionUtil.isEmpty(storyboards) || CollectionUtil.isEmpty(vos))
        {
            return;
        }
        Map<Long, StoryboardVO> voById = new HashMap<>();
        for (StoryboardVO vo : vos)
        {
            if (Objects.nonNull(vo) && Objects.nonNull(vo.getId()))
            {
                voById.put(vo.getId(), vo);
            }
        }

        // 1) 解析台词 → 配音类型 + 发言角色主名
        Map<Long, String> voiceTypeBySb = new HashMap<>();
        Map<Long, List<String>> rolesBySb = new HashMap<>();
        Set<String> allRoleKeys = new LinkedHashSet<>();
        for (AidStoryboard sb : storyboards)
        {
            List<DialogueSegment> segments = audioReferenceResolver.parse(sb.getDialogueText());
            if (CollectionUtil.isEmpty(segments))
            {
                continue;
            }
            boolean hasNarration = false;
            boolean hasDialogue = false;
            LinkedHashSet<String> roles = new LinkedHashSet<>();
            for (DialogueSegment segment : segments)
            {
                if (segment.isNarration())
                {
                    hasNarration = true;
                    continue;
                }
                hasDialogue = true;
                if (StrUtil.isNotBlank(segment.getRoleName()))
                {
                    String roleName = segment.getRoleName().trim();
                    roles.add(roleName);
                    allRoleKeys.add(StoryboardImageReferenceResolver.normalizeAssetRefName(roleName));
                    if (StrUtil.isNotBlank(segment.getRoleRef()))
                    {
                        allRoleKeys.add(StoryboardImageReferenceResolver.normalizeAssetRefName(segment.getRoleRef()));
                    }
                }
            }
            if (hasNarration && hasDialogue)
            {
                voiceTypeBySb.put(sb.getId(), VOICE_TYPE_MIXED);
            }
            else if (hasDialogue)
            {
                voiceTypeBySb.put(sb.getId(), VOICE_TYPE_DIALOGUE);
            }
            else if (hasNarration)
            {
                voiceTypeBySb.put(sb.getId(), VOICE_TYPE_NARRATION);
            }
            if (!roles.isEmpty())
            {
                rolesBySb.put(sb.getId(), new ArrayList<>(roles));
            }
        }

        // 2) 角色主名 → 本项目角色资产（一次查询）
        Map<String, AidRolePropScene> assetByKey = loadCharacterAssetsForVoiceover(
                storyboards.get(0).getProjectId(), userId, allRoleKeys);

        // 3) 角色资产 → 启用音色绑定（剧集精确优先于全局）
        Set<Long> assetIds = assetByKey.values().stream()
                .map(AidRolePropScene::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Long episodeId = storyboards.get(0).getEpisodeId();
        Map<Long, AidRoleVoiceBinding> bindingByAssetId = loadEnabledVoiceBindings(assetIds, episodeId, userId);

        // 4) 配音状态：优先 final_audio_id；无选定则看是否有进行中任务
        Map<Long, String> audioStatusBySb = resolveAudioStatusBatch(storyboards, userId);

        // 5) 回填 VO
        for (AidStoryboard sb : storyboards)
        {
            StoryboardVO vo = voById.get(sb.getId());
            if (Objects.isNull(vo))
            {
                continue;
            }
            vo.setVoiceType(voiceTypeBySb.get(sb.getId()));
            List<String> roles = rolesBySb.get(sb.getId());
            vo.setSpeakerRoles(roles);
            vo.setSpeakerVoices(buildSpeakerVoices(roles, assetByKey, bindingByAssetId));
            vo.setAudioStatus(audioStatusBySb.getOrDefault(sb.getId(), AUDIO_STATUS_NONE));
        }
    }

    /**
     * 按项目批量加载角色资产，建立归一化名 → 资产索引（仅角色类型）。
     */
    private Map<String, AidRolePropScene> loadCharacterAssetsForVoiceover(Long projectId, Long userId,
            Set<String> neededKeys)
    {
        Map<String, AidRolePropScene> byKey = new HashMap<>();
        if (Objects.isNull(projectId) || Objects.isNull(userId) || CollectionUtil.isEmpty(neededKeys))
        {
            return byKey;
        }
        List<AidRolePropScene> assets = aidRolePropSceneService.list(
                Wrappers.<AidRolePropScene>lambdaQuery()
                        .select(AidRolePropScene::getId, AidRolePropScene::getName)
                        .eq(AidRolePropScene::getProjectId, projectId)
                        .eq(AidRolePropScene::getUserId, userId)
                        .eq(AidRolePropScene::getAssetType, ASSET_TYPE_CHARACTER)
                        .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL));
        if (CollectionUtil.isEmpty(assets))
        {
            return byKey;
        }
        for (AidRolePropScene asset : assets)
        {
            if (StrUtil.isBlank(asset.getName()))
            {
                continue;
            }
            byKey.putIfAbsent(StoryboardImageReferenceResolver.normalizeAssetRefName(asset.getName()), asset);
        }
        return byKey;
    }

    /**
     * 批量加载启用音色绑定；剧集精确绑定优先于全局绑定。
     */
    private Map<Long, AidRoleVoiceBinding> loadEnabledVoiceBindings(Set<Long> assetIds, Long episodeId, Long userId)
    {
        Map<Long, AidRoleVoiceBinding> byAssetId = new HashMap<>();
        if (CollectionUtil.isEmpty(assetIds) || Objects.isNull(userId))
        {
            return byAssetId;
        }
        List<AidRoleVoiceBinding> bindings = roleVoiceBindingService.list(
                Wrappers.<AidRoleVoiceBinding>lambdaQuery()
                        .select(AidRoleVoiceBinding::getAssetId, AidRoleVoiceBinding::getEpisodeId,
                                AidRoleVoiceBinding::getVoiceLibraryId, AidRoleVoiceBinding::getVoiceCode,
                                AidRoleVoiceBinding::getVoiceName)
                        .in(AidRoleVoiceBinding::getAssetId, assetIds)
                        .and(Objects.nonNull(episodeId), w -> w
                                .eq(AidRoleVoiceBinding::getEpisodeId, episodeId)
                                .or()
                                .isNull(AidRoleVoiceBinding::getEpisodeId)
                                .or()
                                .eq(AidRoleVoiceBinding::getEpisodeId, 0L))
                        .eq(AidRoleVoiceBinding::getUserId, userId)
                        .eq(AidRoleVoiceBinding::getStatus, STATUS_NORMAL)
                        .eq(AidRoleVoiceBinding::getDelFlag, DEL_FLAG_NORMAL));
        if (CollectionUtil.isEmpty(bindings))
        {
            return byAssetId;
        }
        for (AidRoleVoiceBinding binding : bindings)
        {
            if (Objects.isNull(binding.getAssetId()) || Objects.isNull(binding.getVoiceLibraryId()))
            {
                continue;
            }
            AidRoleVoiceBinding existing = byAssetId.get(binding.getAssetId());
            if (Objects.isNull(existing))
            {
                byAssetId.put(binding.getAssetId(), binding);
                continue;
            }
            boolean candidateExact = Objects.nonNull(episodeId)
                    && Objects.equals(binding.getEpisodeId(), episodeId);
            boolean existingExact = Objects.nonNull(episodeId)
                    && Objects.equals(existing.getEpisodeId(), episodeId);
            if (candidateExact && !existingExact)
            {
                byAssetId.put(binding.getAssetId(), binding);
            }
        }
        return byAssetId;
    }

    /**
     * 组装发言角色音色绑定列表（与 speakerRoles 同序同长）；无角色对白返回 null。
     */
    private List<StoryboardSpeakerVoiceVO> buildSpeakerVoices(List<String> roles,
            Map<String, AidRolePropScene> assetByKey, Map<Long, AidRoleVoiceBinding> bindingByAssetId)
    {
        if (CollectionUtil.isEmpty(roles))
        {
            return null;
        }
        List<StoryboardSpeakerVoiceVO> result = new ArrayList<>(roles.size());
        for (String roleName : roles)
        {
            AidRolePropScene asset = assetByKey.get(
                    StoryboardImageReferenceResolver.normalizeAssetRefName(roleName));
            if (Objects.isNull(asset))
            {
                result.add(StoryboardSpeakerVoiceVO.builder()
                        .roleName(roleName)
                        .voiceBound(false)
                        .build());
                continue;
            }
            AidRoleVoiceBinding binding = bindingByAssetId.get(asset.getId());
            if (Objects.isNull(binding))
            {
                result.add(StoryboardSpeakerVoiceVO.builder()
                        .roleName(roleName)
                        .assetId(asset.getId())
                        .voiceBound(false)
                        .build());
                continue;
            }
            result.add(StoryboardSpeakerVoiceVO.builder()
                    .roleName(roleName)
                    .assetId(asset.getId())
                    .voiceBound(true)
                    .voiceLibraryId(binding.getVoiceLibraryId())
                    .voiceCode(binding.getVoiceCode())
                    .voiceName(binding.getVoiceName())
                    .build());
        }
        return result;
    }

    /**
     * 批量解析配音状态：有 final_audio_id 取该记录状态；否则若存在 PROCESSING 则为配音中；否则未配音。
     */
    private Map<Long, String> resolveAudioStatusBatch(List<AidStoryboard> storyboards, Long userId)
    {
        Map<Long, String> statusBySb = new HashMap<>();
        if (CollectionUtil.isEmpty(storyboards))
        {
            return statusBySb;
        }
        Set<Long> finalAudioIds = new LinkedHashSet<>();
        List<Long> needProcessingCheck = new ArrayList<>();
        for (AidStoryboard sb : storyboards)
        {
            if (Objects.nonNull(sb.getFinalAudioId()))
            {
                finalAudioIds.add(sb.getFinalAudioId());
            }
            else
            {
                needProcessingCheck.add(sb.getId());
            }
        }
        Map<Long, AidAudioRecord> audioById = new HashMap<>();
        if (CollectionUtil.isNotEmpty(finalAudioIds))
        {
            List<AidAudioRecord> records = aidAudioRecordService.list(
                    Wrappers.<AidAudioRecord>lambdaQuery()
                            .select(AidAudioRecord::getId, AidAudioRecord::getStatus)
                            .in(AidAudioRecord::getId, finalAudioIds)
                            .eq(AidAudioRecord::getUserId, userId)
                            .eq(AidAudioRecord::getDelFlag, DEL_FLAG_NORMAL));
            if (CollectionUtil.isNotEmpty(records))
            {
                for (AidAudioRecord r : records)
                {
                    audioById.put(r.getId(), r);
                }
            }
        }
        for (AidStoryboard sb : storyboards)
        {
            if (Objects.isNull(sb.getFinalAudioId()))
            {
                continue;
            }
            AidAudioRecord rec = audioById.get(sb.getFinalAudioId());
            if (Objects.isNull(rec) || StrUtil.isBlank(rec.getStatus()))
            {
                statusBySb.put(sb.getId(), AUDIO_STATUS_NONE);
            }
            else
            {
                statusBySb.put(sb.getId(), rec.getStatus());
            }
        }
        if (CollectionUtil.isNotEmpty(needProcessingCheck))
        {
            List<AidAudioRecord> processing = aidAudioRecordService.list(
                    Wrappers.<AidAudioRecord>lambdaQuery()
                            .select(AidAudioRecord::getStoryboardId)
                            .in(AidAudioRecord::getStoryboardId, needProcessingCheck)
                            .eq(AidAudioRecord::getUserId, userId)
                            .eq(AidAudioRecord::getDelFlag, DEL_FLAG_NORMAL)
                            .eq(AidAudioRecord::getStatus, MediaTaskStatus.PROCESSING.name()));
            Set<Long> processingSbIds = CollectionUtil.isEmpty(processing)
                    ? Collections.emptySet()
                    : processing.stream().map(AidAudioRecord::getStoryboardId)
                            .filter(Objects::nonNull).collect(Collectors.toSet());
            for (Long sbId : needProcessingCheck)
            {
                statusBySb.put(sbId, processingSbIds.contains(sbId)
                        ? MediaTaskStatus.PROCESSING.name() : AUDIO_STATUS_NONE);
            }
        }
        return statusBySb;
    }

    /**
     * 批量加载各分镜的主图记录。
     * 以 {@code aid_gen_record.is_selected=1} 为权威源，过滤图片类（image/grid）、未删除、归属当前用户；
     * 同分镜存在多条选中记录时取最新一条。返回 storyboardId → AidGenRecord 映射。
     * 防漏字段：仅 select 业务必需列（storyboard_id / file_url / id / gen_params）；
     * 其中 gen_params 承载参考图快照（referenceManifest），供 {@link #parseReferenceImages} 解析，
     * 与主图 URL 同源同一次查询取回，零额外 IO。后续若需扩展取数请同步增列。
     */
    private Map<Long, AidGenRecord> loadFinalImageRecordsByStoryboardIds(List<Long> storyboardIds, Long userId) {
        if (cn.hutool.core.collection.CollectionUtil.isEmpty(storyboardIds)) {
            return java.util.Collections.emptyMap();
        }
        LambdaQueryWrapper<AidGenRecord> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidGenRecord::getStoryboardId, AidGenRecord::getFileUrl, AidGenRecord::getId,
                AidGenRecord::getGenParams);
        wrapper.in(AidGenRecord::getStoryboardId, storyboardIds);
        wrapper.eq(AidGenRecord::getUserId, userId);
        wrapper.eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.eq(AidGenRecord::getIsSelected, SELECTED_YES);
        wrapper.in(AidGenRecord::getGenType, IMAGE_GEN_TYPES);
        wrapper.orderByDesc(AidGenRecord::getId);
        List<AidGenRecord> selectedImages = aidGenRecordService.list(wrapper);
        Map<Long, AidGenRecord> map = new java.util.HashMap<>();
        for (AidGenRecord r : selectedImages) {
            // 自动生成可能让同分镜多条记录 is_selected=1；保留 id 最新一条，与生成后 final_image_id 回写口径一致。
            if (StrUtil.isNotBlank(r.getFileUrl())) {
                map.putIfAbsent(r.getStoryboardId(), r);
            }
        }
        return map;
    }

    /**
     * 批量加载各分镜的"分镜视频"记录（<strong>final_video_id 指针</strong>：恒为配音前原视频，
     * 视频大类单选下配音视频抢占选中不影响本指针——配音视频只出现在配音视频列表，
     * 不作为详情"分镜视频"返回）。
     * 防漏字段：仅 select 业务必需列（storyboard_id / file_url / id / gen_type），后续扩展取数请同步增列。
     */
    private Map<Long, AidGenRecord> loadFinalVideoRecordsByStoryboards(List<AidStoryboard> storyboards, Long userId) {
        if (cn.hutool.core.collection.CollectionUtil.isEmpty(storyboards)) {
            return java.util.Collections.emptyMap();
        }
        Set<Long> finalVideoIds = new LinkedHashSet<>();
        for (AidStoryboard sb : storyboards) {
            if (Objects.nonNull(sb.getFinalVideoId())) {
                finalVideoIds.add(sb.getFinalVideoId());
            }
        }
        if (finalVideoIds.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        LambdaQueryWrapper<AidGenRecord> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidGenRecord::getStoryboardId, AidGenRecord::getFileUrl, AidGenRecord::getId,
                AidGenRecord::getGenType);
        wrapper.in(AidGenRecord::getId, finalVideoIds);
        wrapper.eq(AidGenRecord::getUserId, userId);
        wrapper.eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL);
        // 历史脏数据兜底：final_video_id 误指配音视频时不作为"分镜视频"返回
        wrapper.in(AidGenRecord::getGenType, ORIGINAL_VIDEO_GEN_TYPES);
        List<AidGenRecord> records = aidGenRecordService.list(wrapper);
        Map<Long, AidGenRecord> recordById = new java.util.HashMap<>();
        for (AidGenRecord r : records) {
            if (StrUtil.isNotBlank(r.getFileUrl())) {
                recordById.put(r.getId(), r);
            }
        }
        Map<Long, AidGenRecord> map = new java.util.HashMap<>();
        for (AidStoryboard sb : storyboards) {
            AidGenRecord record = Objects.isNull(sb.getFinalVideoId())
                    ? null : recordById.get(sb.getFinalVideoId());
            if (Objects.nonNull(record)) {
                map.put(sb.getId(), record);
            }
        }
        return map;
    }

    /**
     * 批量加载各分镜的"最终配音视频"记录（compose 类主视频：genType=compose 且 is_selected=1，
     * 同分镜多条选中时取 id 最新一条）。成片合成导出优先取用本视频（配音已合进画面）。
     * 防漏字段：仅 select 业务必需列（storyboard_id / file_url / id），后续扩展取数请同步增列。
     */
    private Map<Long, AidGenRecord> loadFinalComposeVideoRecordsByStoryboardIds(List<Long> storyboardIds,
                                                                                Long userId) {
        if (cn.hutool.core.collection.CollectionUtil.isEmpty(storyboardIds)) {
            return java.util.Collections.emptyMap();
        }
        LambdaQueryWrapper<AidGenRecord> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidGenRecord::getStoryboardId, AidGenRecord::getFileUrl, AidGenRecord::getId);
        wrapper.in(AidGenRecord::getStoryboardId, storyboardIds);
        wrapper.eq(AidGenRecord::getUserId, userId);
        wrapper.eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.eq(AidGenRecord::getIsSelected, SELECTED_YES);
        wrapper.in(AidGenRecord::getGenType, COMPOSE_VIDEO_GEN_TYPES);
        wrapper.orderByDesc(AidGenRecord::getId);
        List<AidGenRecord> selectedComposeVideos = aidGenRecordService.list(wrapper);
        Map<Long, AidGenRecord> map = new java.util.HashMap<>();
        for (AidGenRecord r : selectedComposeVideos) {
            // 同分镜多条选中时保留 id 最新一条（倒序遍历 putIfAbsent）
            if (StrUtil.isNotBlank(r.getFileUrl())) {
                map.putIfAbsent(r.getStoryboardId(), r);
            }
        }
        return map;
    }

    /**
     * 单分镜"最终配音视频"记录解析（detail 等单条场景用）：compose 类 is_selected=1 的最新一条；无则返回 null。
     * 防漏字段：仅 select 业务必需列（file_url / id），后续扩展取数请同步增列。
     */
    private AidGenRecord resolveFinalComposeVideoRecord(Long storyboardId, Long userId) {
        if (Objects.isNull(storyboardId) || Objects.isNull(userId)) {
            return null;
        }
        LambdaQueryWrapper<AidGenRecord> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidGenRecord::getFileUrl, AidGenRecord::getId);
        wrapper.eq(AidGenRecord::getStoryboardId, storyboardId);
        wrapper.eq(AidGenRecord::getUserId, userId);
        wrapper.eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.eq(AidGenRecord::getIsSelected, SELECTED_YES);
        wrapper.in(AidGenRecord::getGenType, COMPOSE_VIDEO_GEN_TYPES);
        wrapper.orderByDesc(AidGenRecord::getId);
        wrapper.last("LIMIT 1");
        return aidGenRecordService.getOne(wrapper, false);
    }

    /**
     * 解析一条主图 gen_record 里的参考图快照（{@code gen_params.referenceManifest}）为前端 VO 列表。
     * 无记录 / 无 gen_params / 无快照 / 解析失败 → 返回 null（前端按"无参考图"处理）；
     * 不阻断主流程。快照来自出图当时的富化引用清单，按 {@code @图片N} 编号升序。
     */
    private List<StoryboardRefImageVO> parseReferenceImages(AidGenRecord record) {
        if (Objects.isNull(record) || StrUtil.isBlank(record.getGenParams())) {
            return null;
        }
        try {
            com.alibaba.fastjson2.JSONObject root = JSON.parseObject(record.getGenParams());
            if (Objects.isNull(root)) {
                return null;
            }
            com.alibaba.fastjson2.JSONArray manifest = root.getJSONArray("referenceManifest");
            if (CollectionUtil.isEmpty(manifest)) {
                return null;
            }
            List<StoryboardRefImageVO> result = new java.util.ArrayList<>();
            for (int i = 0; i < manifest.size(); i++) {
                com.alibaba.fastjson2.JSONObject ref = manifest.getJSONObject(i);
                if (Objects.isNull(ref)) {
                    continue;
                }
                result.add(StoryboardRefImageVO.builder()
                        .n(ref.getInteger("n"))
                        .name(ref.getString("name"))
                        .assetKind(ref.getString("assetKind"))
                        .assetName(ref.getString("assetName"))
                        .url(ref.getString("url"))
                        .type(ref.getString("type"))
                        .build());
            }
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            // 解析失败不影响列表主流程，仅记录便于排查
            log.warn("分镜参考图快照解析失败(降级为无参考图): recordId={}, err={}", record.getId(), e.getMessage());
            return null;
        }
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public StoryboardVO createStoryboard(StoryboardCreateRequest request, Long userId) {
        Long episodeId = validateProjectAndEpisode(request.getProjectId(), request.getEpisodeId(), userId);

        // 步骤校验：分镜脚本需要步骤4已解锁
        creationStepService.checkStepUnlocked(request.getProjectId(), episodeId, userId,
                CreationStepEnum.STORYBOARD.getValue());

        Long insertAfterSort = request.getInsertAfterSort();
        long nextSort;
        boolean insertedAtPosition = Objects.nonNull(insertAfterSort) && insertAfterSort >= 0;

        if (insertedAtPosition) {
            // 指定位置插入——所有 sort_order > insertAfterSort 的现有分镜整体后移
            //   外层 createStoryboard 已带 @Transactional，UPDATE + INSERT 在同一事务内原子执行
            LambdaUpdateWrapper<AidStoryboard> shiftWrapper = Wrappers.lambdaUpdate();
            shiftWrapper.eq(AidStoryboard::getProjectId, request.getProjectId());
            shiftWrapper.eq(AidStoryboard::getEpisodeId, episodeId);
            shiftWrapper.eq(AidStoryboard::getUserId, userId);
            shiftWrapper.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
            shiftWrapper.gt(AidStoryboard::getSortOrder, insertAfterSort);
            shiftWrapper.setSql("sort_order = sort_order + 1");
            shiftWrapper.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
            shiftWrapper.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
            aidStoryboardService.update(shiftWrapper);
            nextSort = insertAfterSort + 1;
        } else {
            // 追加到末尾（未指定插入位置时）
            LambdaQueryWrapper<AidStoryboard> maxWrapper = Wrappers.lambdaQuery();
            maxWrapper.eq(AidStoryboard::getProjectId, request.getProjectId());
            maxWrapper.eq(AidStoryboard::getUserId, userId);
            maxWrapper.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
            maxWrapper.eq(AidStoryboard::getEpisodeId, episodeId);
            maxWrapper.orderByDesc(AidStoryboard::getSortOrder);
            maxWrapper.last("LIMIT 1");
            AidStoryboard last = aidStoryboardService.getOne(maxWrapper);
            nextSort = (last != null && last.getSortOrder() != null) ? last.getSortOrder() + 1 : 1;
        }

        AidStoryboard storyboard = new AidStoryboard();
        storyboard.setProjectId(request.getProjectId());
        storyboard.setEpisodeId(episodeId);
        storyboard.setUserId(userId);
        storyboard.setSortOrder(nextSort);
        storyboard.setScriptParams(synchronizeScriptParamsShotNumber(null, nextSort));
        // 默认标题
        storyboard.setTitle(StrUtil.isNotBlank(request.getTitle())
                ? request.getTitle() : buildDefaultStoryboardTitle(nextSort));
        storyboard.setDelFlag(DEL_FLAG_NORMAL);
        storyboard.setCreateBy(String.valueOf(userId));
        storyboard.setCreateTime(DateUtils.getNowDate());
        aidStoryboardService.save(storyboard);
        if (insertedAtPosition) {
            // 插入导致后续分镜整体后移，必须同步修正这些分镜的全局镜号镜像和默认标题。
            synchronizeStoryboardNumberFieldsByScope(request.getProjectId(), episodeId, userId);
        }
        // 新建分镜此刻必然没有主图（尚未生成/选定），直接传 null，省去一次无意义的 gen_record 查询
        return buildStoryboardVO(storyboard, null);
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteStoryboard(StoryboardDeleteRequest request, Long userId) {
        List<Long> ids = request.getIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (CollectionUtil.isEmpty(ids)) {
            log.error("分镜批量删除拒绝：ids 去空后为空, rawIds={}, userId={}", request.getIds(), userId);
            throw new ServiceException("分镜不能为空");
        }
        if (ids.size() > MAX_BATCH_DELETE) {
            log.error("分镜批量删除超过上限: size={}, max={}, userId={}", ids.size(), MAX_BATCH_DELETE, userId);
            throw new ServiceException("删除数量超限");
        }
        List<AidStoryboard> list = aidStoryboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getUserId)
                        .in(AidStoryboard::getId, ids)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL));
        // 存在不存在或已删除的分镜时整批拒绝。
        if (list.size() != ids.size()) {
            log.error("分镜批量删除校验失败-数量不符: requested={}, found={}, ids={}, userId={}",
                    ids.size(), list.size(), ids, userId);
            throw new ServiceException("分镜不存在");
        }
        // 任一分镜不归属当前用户时整批拒绝。
        boolean allOwned = list.stream().allMatch(s -> Objects.equals(s.getUserId(), userId));
        if (!allOwned) {
            log.error("分镜批量删除校验失败-越权: ids={}, userId={}", ids, userId);
            throw new ServiceException("无权操作");
        }
        generationArtifactCleanupService.cleanupByStoryboardIds(ids);
        int affected = aidStoryboardService.getBaseMapper().delete(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .in(AidStoryboard::getId, ids)
                        .eq(AidStoryboard::getUserId, userId));
        if (affected != ids.size()) {
            log.error("分镜批量删除并发冲突：affected={}, expected={}, ids={}, userId={}",
                    affected, ids.size(), ids, userId);
            throw new ServiceException("删除失败，请重试"); // @Transactional 触发回滚，保证全成功或全回滚
        }
        return affected;
    }
    /** 单次拖拽排序的分镜数量上限，防止超大列表逐条 UPDATE 锤库 */
    private static final int MAX_BATCH_SORT = 500;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void sortStoryboards(StoryboardSortRequest request, Long userId) {
        List<Long> sortedIds = request.getSortedIds();
        if (CollectionUtil.isEmpty(sortedIds)) {
            log.info("分镜排序列表为空, userId={}", userId);
            throw new ServiceException("参数有误");
        }
        if (sortedIds.size() > MAX_BATCH_SORT) {
            log.info("分镜排序数量超上限, size={}, max={}", sortedIds.size(), MAX_BATCH_SORT);
            throw new ServiceException("排序数量过多");
        }
        // 空元素/重复元素直接拒绝：混入 null 或重复 ID 会导致部分序号丢失或互相覆盖
        List<Long> distinctIds = sortedIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (distinctIds.size() != sortedIds.size()) {
            log.info("分镜排序列表含空元素或重复ID, raw={}, distinct={}", sortedIds.size(), distinctIds.size());
            throw new ServiceException("参数有误");
        }
        // 归属与同域校验：全部分镜必须属于本人且同一项目+剧集，跨剧集混传会互相打乱排序
        // 防漏字段：校验只依赖 id/project_id/episode_id，后续扩展取数请同步增列
        List<AidStoryboard> storyboards = aidStoryboardService.list(Wrappers.<AidStoryboard>lambdaQuery()
                .select(AidStoryboard::getId, AidStoryboard::getProjectId, AidStoryboard::getEpisodeId)
                .in(AidStoryboard::getId, sortedIds)
                .eq(AidStoryboard::getUserId, userId)
                .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL));
        if (storyboards.size() != distinctIds.size()) {
            log.info("分镜排序存在无效ID, expect={}, actual={}", distinctIds.size(), storyboards.size());
            throw new ServiceException("分镜不存在");
        }
        long scopeCount = storyboards.stream()
                .map(s -> s.getProjectId() + ":" + s.getEpisodeId())
                .distinct().count();
        if (scopeCount > 1) {
            log.info("分镜排序跨项目/剧集混传被拒绝, scopes={}", scopeCount);
            throw new ServiceException("参数有误");
        }
        for (int i = 0; i < sortedIds.size(); i++) {
            LambdaUpdateWrapper<AidStoryboard> wrapper = Wrappers.lambdaUpdate();
            wrapper.eq(AidStoryboard::getId, sortedIds.get(i));
            wrapper.eq(AidStoryboard::getUserId, userId);
            wrapper.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
            wrapper.set(AidStoryboard::getSortOrder, (long) (i + 1));
            wrapper.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
            wrapper.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
            aidStoryboardService.update(wrapper);
        }
        synchronizeStoryboardNumberFieldsByIds(sortedIds, userId);
    }

    /** 按项目/剧集同步全部分镜编号字段，用于中间插入后修正整体后移的分镜。 */
    private void synchronizeStoryboardNumberFieldsByScope(Long projectId, Long episodeId, Long userId) {
        // 防漏字段：编号同步只依赖 id/title/sort_order/script_params，后续增加同步字段时需同步增列。
        List<AidStoryboard> storyboards = aidStoryboardService.list(Wrappers.<AidStoryboard>lambdaQuery()
                .select(AidStoryboard::getId, AidStoryboard::getTitle,
                        AidStoryboard::getSortOrder, AidStoryboard::getScriptParams)
                .eq(AidStoryboard::getProjectId, projectId)
                .eq(AidStoryboard::getEpisodeId, episodeId)
                .eq(AidStoryboard::getUserId, userId)
                .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL));
        synchronizeStoryboardNumberFields(storyboards, userId);
    }

    /** 按指定分镜同步编号字段，用于拖拽排序完成后的统一收口。 */
    private void synchronizeStoryboardNumberFieldsByIds(List<Long> storyboardIds, Long userId) {
        if (CollectionUtil.isEmpty(storyboardIds)) {
            return;
        }
        // 防漏字段：编号同步只依赖 id/title/sort_order/script_params，后续增加同步字段时需同步增列。
        List<AidStoryboard> storyboards = aidStoryboardService.list(Wrappers.<AidStoryboard>lambdaQuery()
                .select(AidStoryboard::getId, AidStoryboard::getTitle,
                        AidStoryboard::getSortOrder, AidStoryboard::getScriptParams)
                .in(AidStoryboard::getId, storyboardIds)
                .eq(AidStoryboard::getUserId, userId)
                .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL));
        synchronizeStoryboardNumberFields(storyboards, userId);
    }

    /**
     * 把 {@code sort_order} 同步到 {@code script_params.镜号}；默认格式标题同时跟随，用户自定义标题保持不变。
     */
    private void synchronizeStoryboardNumberFields(List<AidStoryboard> storyboards, Long userId) {
        if (CollectionUtil.isEmpty(storyboards)) {
            return;
        }
        for (AidStoryboard storyboard : storyboards) {
            if (Objects.isNull(storyboard.getId()) || Objects.isNull(storyboard.getSortOrder())) {
                continue;
            }
            String synchronizedParams = synchronizeScriptParamsShotNumber(
                    storyboard.getScriptParams(), storyboard.getSortOrder());
            String synchronizedTitle = isDefaultStoryboardTitle(storyboard.getTitle())
                    ? buildDefaultStoryboardTitle(storyboard.getSortOrder()) : storyboard.getTitle();
            boolean paramsChanged = !Objects.equals(storyboard.getScriptParams(), synchronizedParams);
            boolean titleChanged = !Objects.equals(storyboard.getTitle(), synchronizedTitle);
            if (!paramsChanged && !titleChanged) {
                continue;
            }
            LambdaUpdateWrapper<AidStoryboard> wrapper = Wrappers.lambdaUpdate();
            wrapper.eq(AidStoryboard::getId, storyboard.getId());
            wrapper.eq(AidStoryboard::getUserId, userId);
            wrapper.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
            if (paramsChanged) {
                wrapper.set(AidStoryboard::getScriptParams, synchronizedParams);
            }
            if (titleChanged) {
                wrapper.set(AidStoryboard::getTitle, synchronizedTitle);
            }
            wrapper.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
            wrapper.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
            aidStoryboardService.update(wrapper);
        }
    }

    /** 将分镜全局排序号写入 script_params.镜号；解析失败时拒绝覆盖，防止丢失已有脚本字段。 */
    private String synchronizeScriptParamsShotNumber(String scriptParams, Long sortOrder) {
        if (Objects.isNull(sortOrder)) {
            return scriptParams;
        }
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(scriptParams)) {
            try {
                LinkedHashMap<String, Object> parsed = JSON.parseObject(scriptParams,
                        new com.alibaba.fastjson2.TypeReference<LinkedHashMap<String, Object>>() {});
                if (Objects.nonNull(parsed)) {
                    params.putAll(parsed);
                }
            } catch (Exception e) {
                log.error("分镜编号同步失败, sortOrder={}, err={}", sortOrder, e.getMessage(), e);
                throw new ServiceException("分镜编号失败");
            }
        }
        String expectedShotNumber = formatGlobalShotNumber(sortOrder);
        Object currentShotNumber = params.get("镜号");
        if (Objects.nonNull(currentShotNumber)
                && Objects.equals(expectedShotNumber, StrUtil.trim(String.valueOf(currentShotNumber)))) {
            return scriptParams;
        }
        params.put("镜号", expectedShotNumber);
        return JSON.toJSONString(params);
    }

    /** 全局分镜编号统一补齐三位数，超过三位时保留完整数值。 */
    private String formatGlobalShotNumber(Long sortOrder) {
        return String.format("%03d", sortOrder);
    }

    /** 默认分镜标题与全局编号保持一致。 */
    private String buildDefaultStoryboardTitle(Long sortOrder) {
        return "分镜脚本" + formatGlobalShotNumber(sortOrder);
    }

    /** 仅默认数字标题跟随排序更新，用户自定义标题不得覆盖。 */
    private boolean isDefaultStoryboardTitle(String title) {
        String prefix = "分镜脚本";
        if (StrUtil.isBlank(title) || !title.startsWith(prefix)) {
            return false;
        }
        String suffix = title.substring(prefix.length());
        return StrUtil.isNotBlank(suffix) && suffix.chars().allMatch(Character::isDigit);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveStoryboard(StoryboardSaveRequest request, Long userId) {
        AidStoryboard storyboard = getStoryboardWithOwnerCheck(request.getId(), userId);
        // 步骤校验：分镜脚本需要步骤4已解锁
        creationStepService.checkStepUnlocked(storyboard.getProjectId(), storyboard.getEpisodeId(), userId,
                CreationStepEnum.STORYBOARD.getValue());

        // 可选字段更新
        if (request.getTitle() != null) {
            storyboard.setTitle(request.getTitle());
        }
        boolean scriptChanged = false;
        if (request.getStoryScript() != null) {
            // 编辑回写（单一结构化权威）：把编辑后的可读文本同步进结构化 script_params，
            // 再以合并后的 script_params 规范化重渲染 story_script，保证两者一致；
            // 下游（分镜画师 image_prompt / 视觉导演 video_prompt / 多参视觉导演）统一读 script_params，永远拿到最新编辑值。
            scriptChanged = syncEditedStoryScript(storyboard, request.getStoryScript());
        }
        if (request.getDialogueText() != null) {
            storyboard.setDialogueText(request.getDialogueText());
        }
        if (request.getSortOrder() != null) {
            Long newSortOrder = Long.valueOf(request.getSortOrder());
            storyboard.setSortOrder(newSortOrder);
            storyboard.setScriptParams(synchronizeScriptParamsShotNumber(
                    storyboard.getScriptParams(), newSortOrder));
            if (isDefaultStoryboardTitle(storyboard.getTitle())) {
                storyboard.setTitle(buildDefaultStoryboardTitle(newSortOrder));
            }
        }

        storyboard.setUpdateTime(DateUtils.getNowDate());
        storyboard.setUpdateBy(String.valueOf(userId));
        aidStoryboardService.updateById(storyboard);

        // 内容变更 → 清空过期派生物：编辑后旧的 image_prompt/video_prompt/video_prompt_image
        // 已与最新分镜不符，置空使其变"未生成"，下次生成自然重生。
        // 注意：updateById 默认忽略 null 字段，必须用显式 update wrapper 才能把列真正置 NULL。
        if (scriptChanged) {
            LambdaUpdateWrapper<AidStoryboard> clear = Wrappers.lambdaUpdate();
            clear.eq(AidStoryboard::getId, storyboard.getId());
            clear.set(AidStoryboard::getImagePrompt, null);
            clear.set(AidStoryboard::getImagePromptRaw, null);
            // 宫格类型随图提示词一起失效（图提示词重生时会按新输出重新解析）
            clear.set(AidStoryboard::getGridType, null);
            clear.set(AidStoryboard::getVideoPrompt, null);
            // 图生/宫格方向视频提示词同属脚本派生物：与 video_prompt 同口径一起失效，
            // 避免脚本已变更但旧提示词残留、被图生/宫格出片链路继续消费
            clear.set(AidStoryboard::getVideoPromptImage, null);
            clear.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
            clear.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
            aidStoryboardService.update(clear);
        }
    }

    /**
     * 编辑回写：把编辑后的可读文本（story_script）同步进结构化 {@code script_params}，
     * 并以合并后的 {@code script_params} 规范化重渲染 {@code story_script}，保证两者一致。
     *
     * @param storyboard     待更新分镜（原地修改 scriptParams / storyScript，不落库）
     * @param editedReadable 用户编辑后的可读文本
     * @return 内容是否发生实际变更（用于决定是否清空过期派生物）
     */
    private boolean syncEditedStoryScript(AidStoryboard storyboard, String editedReadable) {
        LinkedHashMap<String, Object> params = parseScriptParamsOrdered(storyboard.getScriptParams());
        LinkedHashMap<String, String> edited = parseReadableToFields(editedReadable);
        // 自由文本编辑（未识别到结构化字段）只更新展示用 story_script：script_params 未变，
        // 下游提示词仍读结构化值，若此时清空派生提示词会造成"编辑未生效"的错觉，故返回 false 不触发清空。
        if (edited.isEmpty()) {
            storyboard.setStoryScript(editedReadable);
            log.info("分镜编辑未识别到结构化字段，按自由文本仅存 story_script、不清空派生提示词（script_params 未变）: storyboardId={}",
                    storyboard.getId());
            return false;
        }
        // 镜号/场次序号/批内位置是后端维护的元数据，禁止通过可读脚本文本覆盖。
        edited.keySet().removeAll(SHOT_META_SKIP_KEYS);
        if (edited.isEmpty()) {
            storyboard.setStoryScript(renderShotReadable(params));
            log.info("分镜编辑仅包含编号元数据，已忽略: storyboardId={}", storyboard.getId());
            return false;
        }
        //    不能用序列化后的 JSON 字符串直接比较——生成侧用 Jackson、编辑侧用 Fastjson2，
        //    两者 JSON 格式（转义/空白/数字）差异会造成"未改也判变更"，进而误清空 image_prompt/video_prompt。
        boolean changed = false;
        String changedKey = null;
        for (Map.Entry<String, String> e : edited.entrySet()) {
            Object oldVal = params.get(e.getKey());                       // 原字段值（可能为 null=新增字段）
            String oldStr = normalizeScriptFieldValue(oldVal);
            String newStr = normalizeScriptFieldValue(e.getValue());
            if (!Objects.equals(oldStr, newStr)) {                        // 任一字段语义变化即视为变更
                changed = true;
                changedKey = e.getKey();
                break;
            }
        }
        if (changed) {
            log.info("分镜脚本结构化字段已变更，派生提示词将失效: storyboardId={}, field={}",
                    storyboard.getId(), changedKey);
        }
        // 编辑值覆盖（元数据 / 未编辑字段保留原值），script_params 成为最新结构化权威
        params.putAll(edited);
        if (Objects.nonNull(storyboard.getSortOrder())) {
            params.put("镜号", formatGlobalShotNumber(storyboard.getSortOrder()));
        }
        storyboard.setScriptParams(JSON.toJSONString(params));
        storyboard.setStoryScript(renderShotReadable(params));
        return changed;
    }

    /**
     * 解析 {@code script_params} JSON 为有序 Map（保留字段顺序）。空 / 解析失败返回空 Map。
     */
    private LinkedHashMap<String, Object> parseScriptParamsOrdered(String json) {
        if (StrUtil.isBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            LinkedHashMap<String, Object> map = JSON.parseObject(json,
                    new com.alibaba.fastjson2.TypeReference<LinkedHashMap<String, Object>>() {});
            return map == null ? new LinkedHashMap<>() : map;
        } catch (Exception e) {
            log.warn("分镜 script_params 解析失败（按空处理）: err={}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 把可读文本反解析为"中文key → 值"。按行扫描，行首命中已知 key + 冒号（全角"："或半角":"）则起新字段，
     * 否则视为上一字段的多行值续接。识别不到任何已知 key 时返回空 Map。
     */
    private LinkedHashMap<String, String> parseReadableToFields(String text) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (StrUtil.isBlank(text)) {
            return out;
        }
        String currentKey = null;
        StringBuilder currentVal = new StringBuilder();
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.replace("\r", "");
            String[] kv = matchLeadingKey(line);
            if (kv != null) {
                if (currentKey != null) {
                    out.put(currentKey, currentVal.toString().trim());
                }
                currentKey = kv[0];
                currentVal = new StringBuilder(kv[1]);
            } else if (currentKey != null) {
                // 多行值续接
                currentVal.append('\n').append(line);
            }
            // 否则：起始的非字段行直接忽略
        }
        if (currentKey != null) {
            out.put(currentKey, currentVal.toString().trim());
        }
        return out;
    }

    /**
     * 判断行是否以"已知中文 key + 冒号"开头。命中返回 [key, 冒号后的值]，否则返回 null。
     */
    private String[] matchLeadingKey(String line) {
        if (StrUtil.isBlank(line)) {
            return null;
        }
        for (String key : SHOT_PARAM_KEYS) {
            if (line.startsWith(key + "：")) {
                return new String[]{key, line.substring(key.length() + 1)};
            }
            if (line.startsWith(key + ":")) {
                return new String[]{key, line.substring(key.length() + 1)};
            }
        }
        return null;
    }

    /**
     * 规范化结构化脚本字段值，避免换行符、首尾空白、JSON 容器字符串差异导致未修改也被判定为变更。
     */
    private String normalizeScriptFieldValue(Object value) {
        if (Objects.isNull(value)) {
            return "";
        }
        String text;
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
            text = JSON.toJSONString(value);
        } else {
            text = String.valueOf(value);
        }
        if (StrUtil.isBlank(text)) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int start = 0;
        int end = lines.length - 1;
        while (start <= end && StrUtil.isBlank(lines[start])) {
            start++;
        }
        while (end >= start && StrUtil.isBlank(lines[end])) {
            end--;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(StrUtil.trim(lines[i]));
        }
        return sb.toString();
    }

    /**
     * 把结构化字段渲染为人类可读文本（跳过元数据，"key：值"逐行），与生成侧 renderShotAsReadableText 同口径。
     */
    private String renderShotReadable(LinkedHashMap<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (SHOT_META_SKIP_KEYS.contains(entry.getKey())) {
                continue;
            }
            String val = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            if (StrUtil.isBlank(val)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(entry.getKey()).append('：').append(val);
        }
        return sb.toString();
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GenRecordVO generateMedia(GenerateMediaRequest request, Long userId) {
        //校验分镜的所属
        AidStoryboard storyboard = getStoryboardWithOwnerCheck(request.getStoryboardId(), userId);

        GenTypeEnum genTypeEnum = GenTypeEnum.getByValue(request.getGenType());
        if (Objects.isNull(genTypeEnum)) {
            log.error("生成类型不合法, genType={}", request.getGenType());
            throw new ServiceException("生成类型不支持");
        }

        // 步骤校验：生图需要步骤4(分镜脚本)，生视频需要步骤5(分镜视频)
        boolean isImageType = Objects.equals(GenTypeEnum.IMAGE, genTypeEnum) || Objects.equals(GenTypeEnum.GRID, genTypeEnum);
        int requiredStep = isImageType ? CreationStepEnum.STORYBOARD.getValue() : CreationStepEnum.VIDEO.getValue();
        creationStepService.checkStepUnlocked(storyboard.getProjectId(), storyboard.getEpisodeId(), userId, requiredStep);

        AidAiModel model = getAvailableModel(request.getModelId());

        // 获取项目信息，判断生成模式
        AidComicProject project = aidComicProjectService.getById(storyboard.getProjectId());
        if (project == null) {
            log.error("生成媒体项目缺失, projectId={}, storyboardId={}", storyboard.getProjectId(), storyboard.getId());
            throw new ServiceException("项目不存在");
        }
        boolean isEconomyMode = "economy".equals(project.getDefaultGenMode());

        // 按genType分支校验
        switch (genTypeEnum) {
            case IMAGE:
            case GRID:
                if (request.getGenParams() == null || StringUtils.isEmpty(request.getGenParams().getImagePrompt())) {
                    log.info("生图缺少画面描述, storyboardId={}", request.getStoryboardId());
                    throw new ServiceException("画面描述不能为空");
                }
                break;
            case I2V:
                if (Objects.isNull(request.getBaseImageId())) {
                    log.error("图生视频缺少底图, storyboardId={}", request.getStoryboardId());
                    throw new ServiceException("请选择底图");
                }
                if (request.getGenParams() == null || StringUtils.isEmpty(request.getGenParams().getVideoPrompt())) {
                    log.info("图生视频缺少动作描述, storyboardId={}", request.getStoryboardId());
                    throw new ServiceException("动作描述不能为空");
                }
                break;
            case MULTI:
                if (request.getGenParams() == null || StringUtils.isEmpty(request.getGenParams().getVideoPrompt())) {
                    log.info("多参视频缺少动作描述, storyboardId={}", request.getStoryboardId());
                    throw new ServiceException("动作描述不能为空");
                }
                break;
            case EDGE:
                if (Objects.isNull(request.getFirstImageId()) || Objects.isNull(request.getLastImageId())) {
                    log.error("首尾帧视频缺少首尾图, storyboardId={}", request.getStoryboardId());
                    throw new ServiceException("请选择首尾帧");
                }
                if (request.getGenParams() == null || StringUtils.isEmpty(request.getGenParams().getVideoPrompt())) {
                    log.info("首尾帧视频缺少动作描述, storyboardId={}", request.getStoryboardId());
                    throw new ServiceException("动作描述不能为空");
                }
                break;
            default:
                break;
        }

        // 根据生成模式校验参数
        validateGenParamsForProject(request.getGenParams(), isEconomyMode, isImageType);

        // 后端内部组装提示词(防泄露)
        String assembledPrompt = assemblePrompt(request, genTypeEnum);

        // 组装参考图列表（角色/道具/场景/姿态/表情/特效/草图）
        List<String> referenceImages = fetchAllAssetUrlsFromRequest(request);

        // 请求级幂等 traceId 由稳定输入 + 10 秒时间桶派生：桶内双击/重试幂等防重复扣费，
        // 跨桶同参数重抽为新一次生成正常计费（accountUpdateService 侧按 traceId+changeType 幂等）。
        BigDecimal costCredits = billingPriceMultiplierService.apply(
                model.getCostCredits(), model.getBillingMultiplier());
        String stableTraceId = buildStableBillingTraceId(userId, request, assembledPrompt);

        // 扣费走 freeze → settle/refund 两阶段，保证任何异常路径都能退回冻结资金。
        if (costCredits != null && costCredits.compareTo(BigDecimal.ZERO) > 0) {
            String operation = isImageType ? "分镜图片生成" : "分镜视频生成";
            String bizName = project.getProjectName() + "：" + operation;
            accountUpdateService.freeze(userId, costCredits, stableTraceId, BIZ_TYPE_CREATE,
                    bizName, model.getModelCode());
        }

        boolean needCompensate = true;
        try {
        // 组装 gen_params JSON（存储前端传入的原始参数）
        String genParamsJson = JSON.toJSONString(request.getGenParams());

        // 组装 Payload JSON 快照（存入数据库用于排错对账）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", assembledPrompt);
        payload.put("referenceImages", referenceImages);
        payload.put("genParams", request.getGenParams());
        payload.put("storyboardId", storyboard.getId());
        payload.put("userId", userId);
        String payloadJson = JSON.toJSONString(payload);

        // 准备生成记录（先不入库，等服务调用成功后再插入，避免调用报错产生脏数据）
        AidGenRecord record = new AidGenRecord();
        record.setUserId(userId);
        // 冗余存项目 / 剧集，便于按 (project, episode) 维度反查 aid_gen_record，
        // 避免 list-by-storyboard 接口走"先 IN 子查询 aid_storyboard"两段式 SQL
        record.setProjectId(storyboard.getProjectId());
        record.setEpisodeId(storyboard.getEpisodeId());
        record.setStoryboardId(request.getStoryboardId());
        record.setGenType(request.getGenType());
        record.setModelId(request.getModelId());
        record.setUserInputText(request.getUserInputText());
        record.setBaseImageId(request.getBaseImageId());
        record.setFirstImageId(request.getFirstImageId());
        record.setLastImageId(request.getLastImageId());
        record.setVideoDuration(request.getVideoDuration() != null ? Long.valueOf(request.getVideoDuration()) : null);
        record.setSoundDesc(request.getSoundDesc());
        record.setCostCredits(costCredits);
        record.setIsSelected(SELECTED_NO);
        record.setPromptText(assembledPrompt);
        record.setGenParams(genParamsJson);
        record.setRemark(payloadJson);
        record.setDelFlag(DEL_FLAG_NORMAL);
        record.setCreateTime(DateUtils.getNowDate());

        // 按 genType 分支调用大模型
        MediaTaskResponse taskResponse;
        if (isImageType) {
            String finalPrompt = buildImageFinalPrompt(request, assembledPrompt);
            if (request.getGenParams() != null) {
                payload.put("shotSize", request.getGenParams().getShotSize());
                payload.put("cameraAngle", request.getGenParams().getCameraAngle());
                payload.put("colorTone", request.getGenParams().getColorTone());
                payload.put("focalLength", request.getGenParams().getFocalLength());
                payload.put("lighting", request.getGenParams().getLighting());
                payload.put("exposureBlur", request.getGenParams().getExposureBlur());
            }
            record.setRemark(JSON.toJSONString(payload));

            // 业务含义：工作台大模型生图与分镜 Biz 链路一致，referenceImages 承载多资产参考 URL，供豆包/Vidu 多图融合。
            MediaImageGenerateRequest imageRequest = new MediaImageGenerateRequest();
            // 业务含义：最终生图提示词，含用户参数与组装的画面描述。
            imageRequest.setPrompt(finalPrompt);
            // 业务含义：首张参考图写入顶层字段以兼容单图语义；其余参考图仍在 options.referenceImages 全量传递。
            if (!referenceImages.isEmpty()) {
                imageRequest.setReferenceImageUrl(referenceImages.get(0));
            }
            // 业务含义：referenceImages 有序列表对应「图1/图2」类提示词；payloadSnapshot 记录本次请求快照。
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("referenceImages", referenceImages);
            options.put("payloadSnapshot", JSON.toJSONString(payload));
            imageRequest.setOptions(options);

            // 业务含义：提交媒体任务，由默认/指定模型路由至具体 ImageProvider。
            taskResponse = mediaGenerationService.generateImage(imageRequest);
            log.info("大模型生图任务已提交, taskId={}, status={}",
                    taskResponse.getTaskId(), taskResponse.getStatus());
        } else {
            // 校验底图记录（i2v 必须有底图）：存在 + 归属本人本分镜（防越权引用他人产物）+ 文件已生成
            AidGenRecord baseImageRecord = null;
            String baseImageUrl = null;
            if (Objects.equals(GenTypeEnum.I2V, genTypeEnum) || Objects.equals(GenTypeEnum.EDGE, genTypeEnum)) {
                Long baseId = Objects.equals(GenTypeEnum.I2V, genTypeEnum) ? request.getBaseImageId() : request.getFirstImageId();
                baseImageRecord = aidGenRecordService.getById(baseId);
                if (baseImageRecord == null) {
                    log.info("底图记录不存在, baseId={}, storyboardId={}", baseId, request.getStoryboardId());
                    throw new ServiceException("底图数据不存在");
                }
                if (!Objects.equals(userId, baseImageRecord.getUserId())
                        || !Objects.equals(request.getStoryboardId(), baseImageRecord.getStoryboardId())) {
                    log.error("底图记录归属校验失败, baseId={}, ownerId={}, userId={}, recordStoryboardId={}, storyboardId={}",
                            baseId, baseImageRecord.getUserId(), userId,
                            baseImageRecord.getStoryboardId(), request.getStoryboardId());
                    throw new ServiceException("底图数据不存在");
                }
                if (StringUtils.isEmpty(baseImageRecord.getFileUrl())) {
                    log.info("底图未生成完成, baseId={}", baseId);
                    throw new ServiceException("请等待底图生成完成");
                }
                baseImageUrl = baseImageRecord.getFileUrl();
            }
            String finalPrompt = buildVideoFinalPrompt(request, assembledPrompt);
            if (request.getGenParams() != null) {
                payload.put("cameraMovement", request.getGenParams().getCameraMovement());
                payload.put("shootingTechnique", request.getGenParams().getShootingTechnique());
            }
            payload.put("baseImageUrl", baseImageUrl);
            record.setRemark(JSON.toJSONString(payload));

            MediaVideoGenerateRequest videoRequest = new MediaVideoGenerateRequest();
            videoRequest.setPrompt(finalPrompt);
            videoRequest.setImageUrl(baseImageUrl);
            if (request.getVideoDuration() != null) {
                videoRequest.setDurationSeconds(request.getVideoDuration());
            }
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("referenceImages", referenceImages);
            options.put("payloadSnapshot", JSON.toJSONString(payload));
            videoRequest.setOptions(options);

            // Agent 默认模型 + 默认参数兜底：本接口未透传 modelCode，统一走默认模型路径
            // applier 内部按"用户优先、默认兜底"+ capability 校验，已写入字段（如 durationSeconds）不会被覆盖
            AgentModelDefault videoAgentModel = agentModelResolver.resolveDefault(storyboard.getProjectId(), AgentScene.STORYBOARD_VIDEO);
            if (StringUtils.isEmpty(videoRequest.getModelName())) {
                videoRequest.setModelName(videoAgentModel.getModelCode());
            }
            AiModelConfigVo videoModelConfig = aiModelConfigService.selectByModelCode(videoAgentModel.getModelCode());
            agentDefaultParamsApplier.applyToVideo(videoAgentModel, videoRequest, videoModelConfig);

            taskResponse = mediaGenerationService.generateVideo(videoRequest);
            log.info("大模型生视频任务已提交, taskId={}, status={}",
                    taskResponse.getTaskId(), taskResponse.getStatus());
        }

        // 根据调用结果写入生成记录（DB操作放在服务调用之后，防止调用报错产生脏数据）
        if (taskResponse.getTaskId() != null) {
            record.setTaskId(taskResponse.getTaskId().toString());
        }
        if (MediaTaskStatus.SUCCEEDED.name().equals(taskResponse.getStatus())) {
            // 直出：业务表只允许写入本系统已持久化的 ossUrl，禁止回落上游临时签名 URL（会过期）。
            // 若 ossUrl 为空表示 persistOssIfNeeded 上传失败，此处直接抛出异常让用户重新生成。
            if (StringUtils.isEmpty(taskResponse.getOssUrl())) {
                log.error("直出生成成功但 ossUrl 为空, taskId={}, originUrl={}",
                        taskResponse.getTaskId(), taskResponse.getOriginUrl());
                throw new ServiceException("保存失败，请重试");
            }
            record.setFileUrl(taskResponse.getOssUrl());
            record.setStatus(1); // 成功
        } else if (MediaTaskStatus.FAILED.name().equals(taskResponse.getStatus())) {
            log.error("大模型生成失败, taskId={}, error={}", taskResponse.getTaskId(), taskResponse.getErrorMessage());
            throw new ServiceException("生成失败，请重新生成");
        } else {
            record.setStatus(0); // 处理中
        }
        aidGenRecordService.save(record);

        log.info("生成记录已创建, recordId={}, storyboardId={}, userId={}, status={}",
                record.getId(), request.getStoryboardId(), userId, record.getStatus());

        // 记录已入库 + 调用成功 → 结算冻结资金（成功/处理中都结算，上游已受理任务、成本已产生）。
        if (costCredits != null && costCredits.compareTo(BigDecimal.ZERO) > 0) {
            accountUpdateService.settle(userId, costCredits, stableTraceId, BIZ_TYPE_CREATE, "画面生成结算");
        }
        needCompensate = false;

        return buildGenRecordVO(record, taskResponse);
        }
        finally {
            // 任何异常路径（参数校验/上游失败/OSS 缺失/事务回滚）都要退回预冻结资金
            if (needCompensate && costCredits != null && costCredits.compareTo(BigDecimal.ZERO) > 0) {
                try {
                    accountUpdateService.refund(userId, costCredits, stableTraceId, BIZ_TYPE_CREATE, "画面生成失败退款");
                } catch (Exception refundEx) {
                    // 退款失败只打日志，不吞原始异常；定时补偿兜底由运维侧扫 balance_log 完成
                    log.error("画面生成失败退款异常, userId={}, amount={}, traceId={}",
                            userId, costCredits, stableTraceId, refundEx);
                }
            }
        }
    }
    /**
     * 发起配音任务。
     * 不加 {@code @Transactional}：TTS 采用请求内同步合成（HTTP 流式 + 上传 OSS，耗时秒级），
     * 若包在事务里会让 DB 连接在整个合成期间被占用，高并发下易耗尽连接池。本方法无跨语句原子性需求
     * （仅「预插 aid_audio_record → 同步合成 → 更新终态」三步），故预插与终态更新各自 autocommit，
     * 合成在事务外执行；合成失败由 catch 落 FAILED（不再被事务回滚），与 aid_media_task 的失败留痕保持一致。
     */
    @Override
    public AudioTaskVO generateAudio(GenerateAudioRequest request, Long userId) {
        AidStoryboard storyboard = getStoryboardWithOwnerCheck(request.getStoryboardId(), userId);
        // 步骤校验：配音需要步骤6已解锁
        creationStepService.checkStepUnlocked(storyboard.getProjectId(), storyboard.getEpisodeId(), userId,
                CreationStepEnum.AUDIO.getValue());

        ResolvedVoice resolved = resolveVoice(request);

        //      台词标记统一清洗：dialogue_text 带入的「[角色_形象]：」「@音频N[...]」「|」等结构标记
        //      对 TTS 是噪声（会被朗读出来且按字符计费），先剥掉只留可朗读正文；
        //      清洗幂等，用户手输的纯文本不受影响。清洗后为空说明全是标记，直接短文案拦截。
        String ttsText = DialogueTextSanitizer.sanitize(request.getTtsText());
        if (StrUtil.isBlank(ttsText)) {
            log.info("generateAudio 台词清洗后为空: userId={}, rawLen={}",
                    userId, StrUtil.length(request.getTtsText()));
            throw new ServiceException("配音文字无效");
        }
        //      - MiniMax 异步长文本 T2A V2：text ≤ 5 万字符；
        //      - 豆包异步长文本同量级；
        //      统一在业务层按常量拦截，避免超长文本走到预冻结 / 上游提交再失败，省掉回滚成本。
        //      校验和后续提交/落库都基于清洗后的同一份 ttsText，保证长度/计费一致。
        if (ttsText.length() > TTS_TEXT_MAX_LENGTH) {
            log.info("generateAudio 文本超长: userId={}, modelCode={}, textLen={}, max={}",
                    userId, resolved.modelCode, ttsText.length(), TTS_TEXT_MAX_LENGTH);
            throw new ServiceException("文本过长");
        }

        //      按"模型 capability_json.emotions"声明的白名单拦截，避免 speech-2.8-* 传 fluent/whisper
        //      绕过 SQL 能力声明最终被上游报错。白名单为空 → 视为"模型未声明能力"不拦截。
        String requestedEmotion = StrUtil.trimToEmpty(request.getEmotion());
        if (StrUtil.isNotBlank(requestedEmotion)
                && cn.hutool.core.collection.CollectionUtil.isNotEmpty(resolved.supportedEmotions)
                && !resolved.supportedEmotions.contains(requestedEmotion)) {
            log.info("generateAudio 情感不在模型白名单, userId={}, modelCode={}, emotion={}, supported={}",
                    userId, resolved.modelCode, requestedEmotion, resolved.supportedEmotions);
            throw new ServiceException("情感不支持");
        }

        // MiniMax 特有参数在建记录 / 计费前先行校验：校验失败直接短文案拦截，不落 aid_audio_record 行
        boolean isMinimax = isMiniMaxProvider(resolved);
        if (isMinimax) {
            validateMinimaxParams(request);
            //  MiniMax 走同步 /v1/t2a_v2，官方 text 上限 1 万字符（低于通用 5 万上限），单独收紧拦截
            if (ttsText.length() > com.aid.media.constants.MinimaxTtsConstants.SYNC_TEXT_MAX_LENGTH) {
                log.info("generateAudio MiniMax同步接口文本超长: userId={}, modelCode={}, textLen={}, max={}",
                        userId, resolved.modelCode, ttsText.length(),
                        com.aid.media.constants.MinimaxTtsConstants.SYNC_TEXT_MAX_LENGTH);
                throw new ServiceException("文本过长");
            }
        }

        //    落库和后续提交都用 trim 后的 ttsText，和长度校验同一份数据。
        AidAudioRecord task = new AidAudioRecord();
        task.setUserId(userId);
        task.setProjectId(storyboard.getProjectId());
        task.setEpisodeId(storyboard.getEpisodeId());
        task.setStoryboardId(request.getStoryboardId());
        task.setAudioSource(AUDIO_SOURCE_AI);
        task.setAudioUrl(null);
        task.setTtsText(ttsText);
        task.setVoiceModelId(resolved.modelId);
        task.setTimbreCode(resolved.voiceCode);
        task.setEnableLipSync(0);
        task.setStatus(MediaTaskStatus.PROCESSING.name());
        task.setVoiceLibraryId(resolved.voiceLibraryId);
        task.setDelFlag(DEL_FLAG_NORMAL);
        task.setCreateTime(DateUtils.getNowDate());
        aidAudioRecordService.save(task);

        MediaAudioGenerateRequest mediaReq = new MediaAudioGenerateRequest();
        mediaReq.setUserId(userId);
        mediaReq.setProjectId(storyboard.getProjectId());
        mediaReq.setEpisodeId(storyboard.getEpisodeId());
        mediaReq.setModelName(resolved.modelCode);
        mediaReq.setTtsText(ttsText);
        mediaReq.setVoiceCode(resolved.voiceCode);
        mediaReq.setLanguage(resolved.language);
        mediaReq.setEmotion(StrUtil.isNotBlank(requestedEmotion) ? requestedEmotion : resolved.defaultEmotion);
        mediaReq.setEmotionScale(request.getEmotionScale());
        mediaReq.setSpeechRate(request.getSpeechRate());
        mediaReq.setLoudnessRate(request.getLoudnessRate());
        mediaReq.setPitch(request.getPitch() != null ? request.getPitch() : resolved.defaultPitch);
        mediaReq.setAudioFormat(StrUtil.isNotBlank(request.getAudioFormat()) ? request.getAudioFormat() : resolved.audioFormat);
        mediaReq.setSampleRate(request.getSampleRate() != null ? request.getSampleRate() : resolved.sampleRate);
        mediaReq.setBizTaskId(task.getId());
        mediaReq.setBizTaskType("audio_record");

        //      只有 isMinimax=true 时才写入 options；豆包保持 options=null。
        //      所有值使用 validate 后的 trim/过滤结果。
        if (isMinimax) {
            java.util.Map<String, Object> minimaxOptions = new java.util.LinkedHashMap<>();
            // languageBoost：已在 validateMinimaxParams 校验过白名单
            if (StrUtil.isNotBlank(request.getLanguageBoost())) {
                minimaxOptions.put(com.aid.media.constants.MinimaxTtsConstants.OPTIONS_LANGUAGE_BOOST,
                        request.getLanguageBoost().trim());
            }
            if (Objects.nonNull(request.getEnglishNormalization())) {
                minimaxOptions.put(com.aid.media.constants.MinimaxTtsConstants.OPTIONS_ENGLISH_NORMALIZATION,
                        request.getEnglishNormalization());
            }
            if (Objects.nonNull(request.getAigcWatermark())) {
                minimaxOptions.put(com.aid.media.constants.MinimaxTtsConstants.OPTIONS_AIGC_WATERMARK,
                        request.getAigcWatermark());
            }
            if (Objects.nonNull(request.getChannel())) {
                minimaxOptions.put(com.aid.media.constants.MinimaxTtsConstants.OPTIONS_CHANNEL,
                        request.getChannel());
            }
            if (Objects.nonNull(request.getBitrate())) {
                minimaxOptions.put(com.aid.media.constants.MinimaxTtsConstants.OPTIONS_BITRATE,
                        request.getBitrate());
            }
            // pronunciationTone：复用 normalizeMinimaxToneList（和校验共用同一份归一化结果）
            if (cn.hutool.core.collection.CollectionUtil.isNotEmpty(request.getPronunciationTone())) {
                java.util.List<String> filteredTone = normalizeMinimaxToneList(request.getPronunciationTone());
                if (!filteredTone.isEmpty()) {
                    minimaxOptions.put(com.aid.media.constants.MinimaxTtsConstants.OPTIONS_PRONUNCIATION_TONE,
                            filteredTone);
                }
            }
            // soundEffect：写入 trim 后的值
            if (StrUtil.isNotBlank(request.getSoundEffect())) {
                minimaxOptions.put(com.aid.media.constants.MinimaxTtsConstants.OPTIONS_SOUND_EFFECT,
                        request.getSoundEffect().trim());
            }
            if (Objects.nonNull(request.getVoiceModifyIntensity())) {
                minimaxOptions.put(com.aid.media.constants.MinimaxTtsConstants.OPTIONS_VOICE_MODIFY_INTENSITY,
                        request.getVoiceModifyIntensity());
            }
            if (Objects.nonNull(request.getVoiceModifyTimbre())) {
                minimaxOptions.put(com.aid.media.constants.MinimaxTtsConstants.OPTIONS_VOICE_MODIFY_TIMBRE,
                        request.getVoiceModifyTimbre());
            }
            if (Objects.nonNull(request.getVoiceModifyPitch())) {
                minimaxOptions.put("voiceModifyPitch", request.getVoiceModifyPitch());
            }
            if (!minimaxOptions.isEmpty()) {
                mediaReq.setOptions(minimaxOptions);
            }
        }

        MediaTaskResponse mediaResp;
        try {
            mediaResp = mediaGenerationService.generateAudio(mediaReq);
        } catch (Exception ex) {
            log.error("配音提交失败, audioRecordId={}", task.getId(), ex);
            task.setStatus(MediaTaskStatus.FAILED.name());
            task.setErrorMessage("配音失败");
            task.setUpdateTime(DateUtils.getNowDate());
            aidAudioRecordService.updateById(task);
            throw new ServiceException("配音失败，请重试");
        }

        //    避免把豆包 TTS 的临时签名 originUrl 当成最终结果落业务表（1 小时后失效）。
        task.setTtsMediaTaskId(mediaResp.getTaskId());
        // 音频时长回填（秒→毫秒，任务侧已向上取整）：供合成对齐 / 对口型时长校验 / 前端展示消费
        if (Objects.nonNull(mediaResp.getOutputDurationSeconds()) && mediaResp.getOutputDurationSeconds() > 0) {
            task.setDurationMs((int) (mediaResp.getOutputDurationSeconds() * 1000));
        }
        if (MediaTaskStatus.SUCCEEDED.name().equals(mediaResp.getStatus())) {
            if (StrUtil.isNotBlank(mediaResp.getOssUrl())) {
                task.setAudioUrl(mediaResp.getOssUrl());
                task.setStatus(MediaTaskStatus.SUCCEEDED.name());
            } else {
                // ossUrl 还未持久化：保持 PROCESSING，audio_url 由 MediaTaskOssPersistedEvent 事件回填。
                log.info("generateAudio 同步返回 SUCCEEDED 但 ossUrl 未就绪, 保持 PROCESSING 等事件回填, audioRecordId={}, mediaTaskId={}",
                        task.getId(), mediaResp.getTaskId());
                task.setStatus(MediaTaskStatus.PROCESSING.name());
            }
        } else if (MediaTaskStatus.FAILED.name().equals(mediaResp.getStatus())) {
            task.setStatus(MediaTaskStatus.FAILED.name());
            task.setErrorMessage("配音失败");
        } else {
            task.setStatus(MediaTaskStatus.PROCESSING.name());
        }
        task.setUpdateTime(DateUtils.getNowDate());
        aidAudioRecordService.updateById(task);

        return buildAudioTaskVO(task);
    }
    /**
     * 查询音频业务记录（仅查 aid_audio_record，不穿透统一任务表）。
     */
    @Override
    public AudioTaskVO queryAudioTask(Long taskId, Long userId) {
        if (Objects.isNull(taskId) || Objects.isNull(userId)) {
            log.info("queryAudioTask 参数缺失, taskId={}, userId={}", taskId, userId);
            throw new ServiceException("参数错误");
        }
        AidAudioRecord task = aidAudioRecordService.getById(taskId);
        if (Objects.isNull(task) || !Objects.equals(DEL_FLAG_NORMAL, task.getDelFlag())) {
            log.info("queryAudioTask 记录不存在, taskId={}", taskId);
            throw new ServiceException("数据不存在");
        }
        if (!Objects.equals(task.getUserId(), userId)) {
            log.info("queryAudioTask 无权访问, taskId={}, owner={}, reqUser={}",
                    taskId, task.getUserId(), userId);
            throw new ServiceException("无权访问");
        }
        return buildAudioTaskVO(task);
    }

    /**
     * 配音音色解析结果（generateAudio 内部用）
     */
    private static class ResolvedVoice {
        Long voiceLibraryId;
        Long modelId;
        String modelCode;
        String voiceCode;
        String language;
        String defaultEmotion;
        Integer defaultPitch;
        String audioFormat;
        Integer sampleRate;

        /**
         * 模型归属 provider_code（第一优先级判断来源）。
         */
        String providerCode;

        /**
         * capability_json 中声明的 provider 标识（第二优先级判断来源）。
         */
        String capabilityProvider;

        /**
         * 模型 capability_json 中声明的情感白名单（用于情感入参校验）。
         * 解析自 {@code aid_ai_model.capability_json.emotions}；解析失败为空列表表示"无声明"。
         */
        java.util.List<String> supportedEmotions;
    }

    /**
     * 解析音色：voiceLibraryId 优先，否则退化到 voiceModelId + timbreCode。
     * 返回的 {@link ResolvedVoice} 承载最终 model / voice_code / 默认能力参数，
     * 供 {@link com.aid.media.dto.MediaAudioGenerateRequest} 使用。
     */
    private ResolvedVoice resolveVoice(GenerateAudioRequest request) {
        ResolvedVoice r = new ResolvedVoice();
        if (Objects.nonNull(request.getVoiceLibraryId())) {
            com.aid.aid.domain.AidAiVoiceLibrary voice = aidAiVoiceLibraryService.getById(request.getVoiceLibraryId());
            if (Objects.isNull(voice) || !Objects.equals(STATUS_NORMAL, voice.getStatus())
                    || !Objects.equals(DEL_FLAG_NORMAL, voice.getDelFlag())) {
                log.info("generateAudio 音色库记录不可用, voiceLibraryId={}", request.getVoiceLibraryId());
                throw new ServiceException("音色不可用");
            }
            if (Objects.nonNull(voice.getOfflineTime())
                    && voice.getOfflineTime().getTime() <= System.currentTimeMillis()) {
                log.info("generateAudio 音色已下架, voiceLibraryId={}, offlineTime={}",
                        request.getVoiceLibraryId(), voice.getOfflineTime());
                throw new ServiceException("音色已下架");
            }
            AidAiModel model = aidAiModelService.getById(voice.getModelId());
            if (Objects.isNull(model) || !Objects.equals(STATUS_NORMAL, model.getStatus())) {
                log.info("generateAudio 音色绑定模型不可用, modelId={}", voice.getModelId());
                throw new ServiceException("模型已停用");
            }
            r.voiceLibraryId = voice.getId();
            r.modelId = model.getId();
            r.modelCode = model.getModelCode();
            r.voiceCode = voice.getVoiceCode();
            r.language = voice.getLanguage();
            r.defaultPitch = voice.getDefaultPitch() == null ? null : voice.getDefaultPitch().intValue();
            r.audioFormat = voice.getAudioFormat();
            r.sampleRate = voice.getSampleRate();
            enrichProviderAndEmotions(r, model);
            return r;
        }
        if (Objects.isNull(request.getVoiceModelId()) || StrUtil.isBlank(request.getTimbreCode())) {
            log.info("generateAudio 音色参数缺失, voiceModelId={}, timbreCode={}",
                    request.getVoiceModelId(), request.getTimbreCode());
            throw new ServiceException("音色不可用");
        }
        AidAiModel model = getAvailableModel(request.getVoiceModelId());
        r.modelId = model.getId();
        r.modelCode = model.getModelCode();
        r.voiceCode = request.getTimbreCode();
        enrichProviderAndEmotions(r, model);
        return r;
    }

    /**
     * 解析模型所属 provider_code + capability_json.emotions。
     * 拿到后存入 {@link ResolvedVoice}，供 generateAudio 内部做"按协议选择性透传 options"
     * 和"按模型能力校验 emotion"使用。解析失败不抛异常，仅记录日志；emotion 校验分支按
     * "白名单为空" 处理（不拦截，保留原行为）。
     */
    private void enrichProviderAndEmotions(ResolvedVoice r, AidAiModel model) {
        if (Objects.isNull(model)) {
            return;
        }
        try {
            AiModelConfigVo cfg = aiModelConfigService.selectByModelId(model.getId());
            if (Objects.nonNull(cfg)) {
                r.providerCode = cfg.getProviderCode();
            }
        } catch (Exception e) {
            log.warn("resolveVoice 获取 providerCode 失败: modelId={}, err={}", model.getId(), e.getMessage());
        }
        r.supportedEmotions = parseSupportedEmotions(model.getCapabilityJson());
        r.capabilityProvider = parseCapabilityProvider(model.getCapabilityJson());
    }

    /**
     * 从 {@code capability_json} 解析 {@code provider} 字段。
     * JSON 缺失 / 解析失败 / 字段不存在 → 返回 null（调用方视为"无声明"）。
     */
    private String parseCapabilityProvider(String capabilityJson) {
        return com.aid.media.provider.MinimaxProviderDetector.parseCapabilityProvider(capabilityJson);
    }

    /**
     * 从 {@code capability_json} 解析 {@code emotions} 数组。
     * 统一委托 {@link VoiceEmotionCapability#parseModelEmotions}，与试听接口保持同一白名单解析口径。
     */
    private java.util.List<String> parseSupportedEmotions(String capabilityJson) {
        return VoiceEmotionCapability.parseModelEmotions(capabilityJson);
    }
    /** MiniMax channel 合法值白名单 */
    private static final java.util.Set<Integer> MINIMAX_CHANNEL_WHITELIST = java.util.Set.of(1, 2);
    /** MiniMax bitrate 合法值白名单 */
    private static final java.util.Set<Integer> MINIMAX_BITRATE_WHITELIST = java.util.Set.of(32000, 64000, 128000, 256000);
    /** MiniMax soundEffect 合法值白名单 */
    private static final java.util.Set<String> MINIMAX_SOUND_EFFECT_WHITELIST = java.util.Set.of(
            "spacious_echo", "auditorium_echo", "lofi_telephone", "robotic");
    /** MiniMax languageBoost 合法值白名单（auto + 40 种语言 = 41 个合法值，按官方文档枚举） */
    private static final java.util.Set<String> MINIMAX_LANGUAGE_BOOST_WHITELIST = java.util.Set.of(
            "auto",
            "Chinese", "Chinese,Yue", "English", "Arabic", "Russian", "Spanish",
            "French", "Portuguese", "German", "Turkish", "Dutch", "Ukrainian",
            "Vietnamese", "Indonesian", "Japanese", "Italian", "Korean", "Thai",
            "Polish", "Romanian", "Greek", "Czech", "Finnish", "Hindi",
            "Bulgarian", "Danish", "Hebrew", "Malay", "Persian", "Slovak",
            "Swedish", "Croatian", "Filipino", "Hungarian", "Norwegian",
            "Slovenian", "Catalan", "Nynorsk", "Tamil", "Afrikaans");

    /**
     * 判定当前解析到的模型是否属于 MiniMax provider。
     */
    private boolean isMiniMaxProvider(ResolvedVoice resolved) {
        if (Objects.isNull(resolved)) {
            return false;
        }
        return minimaxProviderDetector.isMinimax(resolved.providerCode,
                resolved.capabilityProvider, resolved.modelCode);
    }

    /**
     * MiniMax 特有参数前置合法性校验。
     * 在创建 {@code aid_audio_record} 和进入计费链路 之前 按 MiniMax 文档白名单拦截非法值，
     * 避免"先扣费再被上游拒绝"的体验问题。不传（null）的字段跳过校验，走默认值。
     */
    private void validateMinimaxParams(GenerateAudioRequest request) {
        // languageBoost：auto + 40 种语言 = 41 个合法值（按 MiniMax 文档枚举）
        if (StrUtil.isNotBlank(request.getLanguageBoost())
                && !MINIMAX_LANGUAGE_BOOST_WHITELIST.contains(request.getLanguageBoost().trim())) {
            log.info("generateAudio MiniMax languageBoost 非法: languageBoost={}", request.getLanguageBoost());
            throw new ServiceException("参数错误");
        }
        // channel：1 / 2
        if (Objects.nonNull(request.getChannel()) && !MINIMAX_CHANNEL_WHITELIST.contains(request.getChannel())) {
            log.info("generateAudio MiniMax channel 非法: channel={}", request.getChannel());
            throw new ServiceException("参数错误");
        }
        // bitrate：32000 / 64000 / 128000 / 256000
        if (Objects.nonNull(request.getBitrate()) && !MINIMAX_BITRATE_WHITELIST.contains(request.getBitrate())) {
            log.info("generateAudio MiniMax bitrate 非法: bitrate={}", request.getBitrate());
            throw new ServiceException("参数错误");
        }
        // soundEffect：白名单 4 种（trim 后比对）
        if (StrUtil.isNotBlank(request.getSoundEffect())
                && !MINIMAX_SOUND_EFFECT_WHITELIST.contains(request.getSoundEffect().trim())) {
            log.info("generateAudio MiniMax soundEffect 非法: soundEffect={}", request.getSoundEffect());
            throw new ServiceException("参数错误");
        }
        // voiceModifyIntensity / voiceModifyTimbre / voiceModifyPitch：[-100, 100]
        if (Objects.nonNull(request.getVoiceModifyIntensity())
                && (request.getVoiceModifyIntensity() < -100 || request.getVoiceModifyIntensity() > 100)) {
            log.info("generateAudio MiniMax voiceModifyIntensity 越界: {}", request.getVoiceModifyIntensity());
            throw new ServiceException("参数错误");
        }
        if (Objects.nonNull(request.getVoiceModifyTimbre())
                && (request.getVoiceModifyTimbre() < -100 || request.getVoiceModifyTimbre() > 100)) {
            log.info("generateAudio MiniMax voiceModifyTimbre 越界: {}", request.getVoiceModifyTimbre());
            throw new ServiceException("参数错误");
        }
        if (Objects.nonNull(request.getVoiceModifyPitch())
                && (request.getVoiceModifyPitch() < -100 || request.getVoiceModifyPitch() > 100)) {
            log.info("generateAudio MiniMax voiceModifyPitch 越界: {}", request.getVoiceModifyPitch());
            throw new ServiceException("参数错误");
        }
        // pronunciationTone：归一化（过滤空白 + trim）后校验长度；
        // 写入 options 时直接复用同一份归一化结果，避免两套清洗逻辑漂移。
        if (cn.hutool.core.collection.CollectionUtil.isNotEmpty(request.getPronunciationTone())) {
            for (String tone : normalizeMinimaxToneList(request.getPronunciationTone())) {
                if (tone.length() > 200) {
                    log.info("generateAudio MiniMax pronunciationTone 单项过长: len={}", tone.length());
                    throw new ServiceException("参数错误");
                }
            }
        }
    }

    /**
     * 归一化 pronunciationTone 列表：过滤空白项 + trim。
     * 校验和 options 写入共用同一份结果，避免两套清洗逻辑分开维护后漂移。
     */
    private java.util.List<String> normalizeMinimaxToneList(java.util.List<String> raw) {
        if (cn.hutool.core.collection.CollectionUtil.isEmpty(raw)) {
            return java.util.Collections.emptyList();
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String t : raw) {
            if (StrUtil.isNotBlank(t)) {
                result.add(t.trim());
            }
        }
        return result;
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setFinalSelection(SetFinalSelectionRequest request, Long userId) {
        AidStoryboard storyboard = getStoryboardWithOwnerCheck(request.getStoryboardId(), userId);
        String recordType = request.getRecordType();
        Long recordId = request.getRecordId();

        if (Objects.equals(RECORD_TYPE_IMAGE, recordType) || Objects.equals(RECORD_TYPE_VIDEO, recordType)) {
            // 校验记录存在且属于该分镜（含软删过滤：项目标准为手工 eq(DelFlag, '0')，
            // 不依赖 @TableLogic，因为项目内不同实体的 del_flag "已删除"值不统一）
            AidGenRecord targetRecord = aidGenRecordService.getById(recordId);
            if (Objects.isNull(targetRecord)
                    || !Objects.equals(DEL_FLAG_NORMAL, targetRecord.getDelFlag())
                    || !Objects.equals(targetRecord.getStoryboardId(), request.getStoryboardId())) {
                log.error("生成记录不存在或不属于该分镜, recordId={}, storyboardId={}, delFlag={}",
                        recordId, request.getStoryboardId(),
                        Objects.nonNull(targetRecord) ? targetRecord.getDelFlag() : null);
                throw new ServiceException("数据不存在");
            }
            // 防御性归属校验：storyboard 已属于当前用户，这里显式校验 record 归属，
            //              避免脏数据（record.user_id 与 storyboard.user_id 不一致）被利用。
            if (!Objects.equals(targetRecord.getUserId(), userId)) {
                log.error("生成记录归属异常, recordId={}, owner={}, requester={}",
                        recordId, targetRecord.getUserId(), userId);
                throw new ServiceException("数据不存在");
            }

            // 大类合法性：图片类=image/grid，视频类=i2v/multi/edge/upload_video/compose
            List<String> validGenTypes = Objects.equals(RECORD_TYPE_IMAGE, recordType) ? IMAGE_GEN_TYPES : VIDEO_GEN_TYPES;
            if (!validGenTypes.contains(targetRecord.getGenType())) {
                log.error("生成记录类型与产物类型不匹配, recordId={}, genType={}, expectedTypes={}",
                        recordId, targetRecord.getGenType(), validGenTypes);
                throw new ServiceException("类型不匹配");
            }

            // 校验文件是否已生成
            if (StrUtil.isBlank(targetRecord.getFileUrl())) {
                log.error("产物尚未生成完成, recordId={}, fileUrl为空", recordId);
                throw new ServiceException("生成未完成");
            }

            // 互斥范围（双类各自单选）：图片类整类互斥；分镜视频（video 类：i2v/multi/edge/upload_video）
            // 与配音视频（compose 类）不是同一类，各自独立互斥——每个分镜每类只能设置一个主视频，
            // 选配音视频不影响分镜视频的选中，反之亦然
            boolean composeTarget = GenTypeEnum.COMPOSE.getValue().equals(targetRecord.getGenType());
            List<String> mutexGenTypes;
            if (Objects.equals(RECORD_TYPE_IMAGE, recordType)) {
                mutexGenTypes = IMAGE_GEN_TYPES;
            } else {
                mutexGenTypes = composeTarget ? COMPOSE_VIDEO_GEN_TYPES : ORIGINAL_VIDEO_GEN_TYPES;
            }

            // 批量重置同分镜下同类的is_selected
            LambdaUpdateWrapper<AidGenRecord> resetWrapper = Wrappers.lambdaUpdate();
            resetWrapper.eq(AidGenRecord::getStoryboardId, request.getStoryboardId());
            resetWrapper.eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL);
            resetWrapper.in(AidGenRecord::getGenType, mutexGenTypes);
            resetWrapper.set(AidGenRecord::getIsSelected, SELECTED_NO);
            resetWrapper.set(AidGenRecord::getUpdateTime, DateUtils.getNowDate());
            aidGenRecordService.update(resetWrapper);

            // 将当前记录设为选中
            LambdaUpdateWrapper<AidGenRecord> selectWrapper = Wrappers.lambdaUpdate();
            selectWrapper.eq(AidGenRecord::getId, recordId);
            selectWrapper.set(AidGenRecord::getIsSelected, SELECTED_YES);
            selectWrapper.set(AidGenRecord::getUpdateTime, DateUtils.getNowDate());
            aidGenRecordService.update(selectWrapper);

            // 更新分镜表的final字段：finalVideoId 恒指分镜视频（video 类主视频，配音/对口型/打包的素材源），
            // 配音视频设为主视频不改 finalVideoId（配音视频主选中以 compose 类 is_selected=1 为权威源）
            if (Objects.equals(RECORD_TYPE_IMAGE, recordType)) {
                storyboard.setFinalImageId(recordId);
            } else if (!composeTarget) {
                storyboard.setFinalVideoId(recordId);
            }
        } else if (Objects.equals(RECORD_TYPE_AUDIO, recordType)) {
            AidAudioRecord targetAudio = aidAudioRecordService.getById(recordId);
            if (Objects.isNull(targetAudio)
                    || !Objects.equals(DEL_FLAG_NORMAL, targetAudio.getDelFlag())
                    || !Objects.equals(targetAudio.getStoryboardId(), request.getStoryboardId())) {
                log.error("配音记录不存在或不属于该分镜, recordId={}, storyboardId={}, delFlag={}",
                        recordId, request.getStoryboardId(),
                        Objects.nonNull(targetAudio) ? targetAudio.getDelFlag() : null);
                throw new ServiceException("数据不存在");
            }
            // 防御性校验配音记录归属当前用户。
            if (!Objects.equals(targetAudio.getUserId(), userId)) {
                log.error("配音记录归属异常, recordId={}, owner={}, requester={}",
                        recordId, targetAudio.getUserId(), userId);
                throw new ServiceException("数据不存在");
            }
            // 与图片/视频口径一致：配音文件未生成（失败/进行中）不允许设为主配音
            if (StrUtil.isBlank(targetAudio.getAudioUrl())) {
                log.info("配音未生成完成，不可选定, recordId={}, status={}", recordId, targetAudio.getStatus());
                throw new ServiceException("请等待配音完成");
            }
            storyboard.setFinalAudioId(recordId);
        } else {
            log.error("产物类型不合法, recordType={}", recordType);
            throw new ServiceException("类型不支持");
        }

        storyboard.setUpdateTime(DateUtils.getNowDate());
        aidStoryboardService.updateById(storyboard);

        // 尝试自动推进步骤（静默：仅作为选定操作的附带副作用，绝不因推进失败而回滚本次选定）
        if (Objects.equals(RECORD_TYPE_VIDEO, recordType)) {
            // 选定视频后尝试推进步骤5→6（用户回溯改选/其它分镜未完成时不应报错回滚）
            creationStepService.tryAdvanceStepQuietly(storyboard.getProjectId(), storyboard.getEpisodeId(), userId,
                    CreationStepEnum.VIDEO.getValue());
        } else if (Objects.equals(RECORD_TYPE_AUDIO, recordType)) {
            // 选定配音后尝试推进步骤6→7（同上，静默推进）
            creationStepService.tryAdvanceStepQuietly(storyboard.getProjectId(), storyboard.getEpisodeId(), userId,
                    CreationStepEnum.AUDIO.getValue());
        }
    }
    /** 查询分镜并校验用户归属 */
    private AidStoryboard getStoryboardWithOwnerCheck(Long storyboardId, Long userId) {
        AidStoryboard storyboard = aidStoryboardService.getById(storyboardId);
        if (Objects.isNull(storyboard) || !Objects.equals(DEL_FLAG_NORMAL, storyboard.getDelFlag())) {
            log.error("分镜不存在, storyboardId={}", storyboardId);
            throw new ServiceException("分镜不存在");
        }
        if (!Objects.equals(storyboard.getUserId(), userId)) {
            log.error("无权操作该分镜, storyboardId={}, userId={}", storyboardId, userId);
            throw new ServiceException("无权操作");
        }
        return storyboard;
    }

    /** 校验AI模型是否可用 */
    private AidAiModel getAvailableModel(Long modelId) {
        AidAiModel model = aidAiModelService.getById(modelId);
        if (Objects.isNull(model) || !Objects.equals(STATUS_NORMAL, model.getStatus())
                || !Objects.equals(DEL_FLAG_NORMAL, model.getDelFlag())) {
            log.error("模型不可用, modelId={}", modelId);
            throw new ServiceException("模型不可用");
        }
        return model;
    }

    /**
     * 后端内部组装提示词(核心安全：官方提示词明文不返回前端)
     */
    private String assemblePrompt(GenerateMediaRequest request, GenTypeEnum genType) {
        StringBuilder prompt = new StringBuilder();
        GenerationParams genParams = request.getGenParams();

        if (genParams == null) {
            // 拼接用户补充文本
            if (StrUtil.isNotBlank(request.getUserInputText())) {
                prompt.append("[用户描述] ").append(request.getUserInputText()).append("; ");
            }
            return prompt.toString();
        }

        // 生图类型拼接静态摄影参数
        if (Objects.equals(GenTypeEnum.IMAGE, genType) || Objects.equals(GenTypeEnum.GRID, genType)) {
            appendIfNotBlank(prompt, "景别", genParams.getShotSize());
            appendIfNotBlank(prompt, "角度", genParams.getCameraAngle());
            appendIfNotBlank(prompt, "焦距", genParams.getFocalLength());
            appendIfNotBlank(prompt, "色调", genParams.getColorTone());
            appendIfNotBlank(prompt, "光线", genParams.getLighting());
            appendIfNotBlank(prompt, "曝光", genParams.getExposureBlur());
            appendIfNotBlank(prompt, "画面描述", genParams.getImagePrompt());
        }
        // 视频类型拼接运镜参数
        if (Objects.equals(GenTypeEnum.I2V, genType) || Objects.equals(GenTypeEnum.MULTI, genType)
                || Objects.equals(GenTypeEnum.EDGE, genType)) {
            appendIfNotBlank(prompt, "运镜", genParams.getCameraMovement());
            appendIfNotBlank(prompt, "拍摄手法", genParams.getShootingTechnique());
            appendIfNotBlank(prompt, "动作描述", genParams.getVideoPrompt());
        }
        // 拼接用户补充文本
        if (StrUtil.isNotBlank(request.getUserInputText())) {
            prompt.append("[用户描述] ").append(request.getUserInputText()).append("; ");
        }
        return prompt.toString();
    }

    private void appendIfNotBlank(StringBuilder sb, String label, String value) {
        if (StrUtil.isNotBlank(value)) {
            sb.append("[").append(label).append("] ").append(value).append("; ");
        }
    }
    /**
     * 拼接图片最终 Prompt：image_prompt + 景别/角度/色调/焦距/光线/曝光 对应的提示词库英文咒语
     */
    private String buildImageFinalPrompt(GenerateMediaRequest request, String assembledPrompt) {
        List<String> parts = new ArrayList<>();
        parts.add(assembledPrompt);
        if (request.getGenParams() != null) {
            appendPromptLibContent(parts, request.getGenParams().getShotSize());
            appendPromptLibContent(parts, request.getGenParams().getCameraAngle());
            appendPromptLibContent(parts, request.getGenParams().getColorTone());
            appendPromptLibContent(parts, request.getGenParams().getFocalLength());
            appendPromptLibContent(parts, request.getGenParams().getLighting());
            appendPromptLibContent(parts, request.getGenParams().getExposureBlur());
        }
        return parts.stream().filter(StringUtils::isNotEmpty).collect(Collectors.joining(", "));
    }

    /**
     * 拼接视频最终 Prompt：video_prompt + 运镜 + 拍摄手法 对应的提示词库英文咒语
     */
    private String buildVideoFinalPrompt(GenerateMediaRequest request, String assembledPrompt) {
        List<String> parts = new ArrayList<>();
        parts.add(assembledPrompt);
        if (request.getGenParams() != null) {
            appendPromptLibContent(parts, request.getGenParams().getCameraMovement());
            appendPromptLibContent(parts, request.getGenParams().getShootingTechnique());
        }
        return parts.stream().filter(StringUtils::isNotEmpty).collect(Collectors.joining(", "));
    }

    /**
     * 提示词库内容本地内存缓存（promptName → promptContent）。
     * 该库更新频率极低（运营维护），本地缓存可将高并发生成下的 N 次 DB 命中降为 1 次；
     * 条目按 10 分钟 TTL 过期，避免运营更新后长时间命中老数据。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, CachedPromptValue> PROMPT_LIB_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final long PROMPT_LIB_CACHE_TTL_MILLIS = 10L * 60L * 1000L; // 10 分钟

    private static final int PROMPT_LIB_CACHE_MAX_SIZE = 1024;

    private record CachedPromptValue(String content, long expireAt) {}

    /**
     * 按 promptName 查提示词库，取出英文咒语内容追加到 parts
     * 使用本地缓存，避免高并发下对 aid_prompt_lib 造成热点。
     */
    private void appendPromptLibContent(List<String> parts, String promptName) {
        if (StringUtils.isEmpty(promptName)) {
            return;
        }
        long now = System.currentTimeMillis();
        CachedPromptValue cached = PROMPT_LIB_CACHE.get(promptName);
        if (cached != null && cached.expireAt > now) {
            if (StringUtils.isNotEmpty(cached.content)) {
                parts.add(cached.content);
            }
            return;
        }
        LambdaQueryWrapper<AidPromptLib> wrapper = Wrappers.<AidPromptLib>lambdaQuery()
                .eq(AidPromptLib::getPromptName, promptName)
                .eq(AidPromptLib::getDelFlag, "0")
                .eq(AidPromptLib::getStatus, "0")
                .last("LIMIT 1");
        AidPromptLib promptLib = aidPromptLibService.getOne(wrapper, false);
        String content = (promptLib != null && StringUtils.isNotEmpty(promptLib.getPromptContent()))
                ? promptLib.getPromptContent() : "";
        // 容量保护：超过上限整体清空重建，避免长期占用内存（实现简单优先于命中率）
        if (PROMPT_LIB_CACHE.size() > PROMPT_LIB_CACHE_MAX_SIZE) {
            PROMPT_LIB_CACHE.clear();
        }
        PROMPT_LIB_CACHE.put(promptName, new CachedPromptValue(content, now + PROMPT_LIB_CACHE_TTL_MILLIS));
        if (StringUtils.isNotEmpty(content)) {
            parts.add(content);
        }
    }
    /**
     * 从请求对象中提取所有资产（角色/道具/场景/姿态/表情/特效/草图）的参考图 URL 列表
     */
    private List<String> fetchAllAssetUrlsFromRequest(GenerateMediaRequest request) {
        if (request.getGenParams() == null) {
            return Collections.emptyList();
        }
        Set<Long> allAssetIds = new LinkedHashSet<>();
        parseAndCollectIds(allAssetIds, request.getGenParams().getCharacterIds());
        parseAndCollectIds(allAssetIds, request.getGenParams().getPropIds());
        parseAndCollectIds(allAssetIds, request.getGenParams().getSceneIds());
        parseAndCollectIds(allAssetIds, request.getGenParams().getPoseIds());
        parseAndCollectIds(allAssetIds, request.getGenParams().getExpressionIds());
        parseAndCollectIds(allAssetIds, request.getGenParams().getEffectIds());
        parseAndCollectIds(allAssetIds, request.getGenParams().getSketchIds());
        if (allAssetIds.isEmpty()) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<AidComicAsset> wrapper = Wrappers.<AidComicAsset>lambdaQuery()
                .in(AidComicAsset::getId, allAssetIds)
                .eq(AidComicAsset::getDelFlag, "0");
        return aidComicAssetService.list(wrapper).stream()
                .map(AidComicAsset::getImageUrl)
                .filter(StringUtils::isNotEmpty)
                .collect(Collectors.toList());
    }

    /**
     * 根据项目生成模式校验生成参数
     *
     * @param genParams 生成参数对象
     * @param isEconomyMode 是否为经济模式
     * @param isImageGen 是否为图片生成
     */
    private void validateGenParamsForProject(com.aid.domain.dto.GenerationParams genParams, boolean isEconomyMode, boolean isImageGen) {
        if (genParams == null) {
            return;
        }

        // 经济模式：不校验资产和摄影参数
        if (isEconomyMode) {
            log.info("经济模式：跳过资产和摄影参数校验");
            return;
        }

        // 性能模式：校验所有参数
        log.info("性能模式：开始校验所有生成参数");

        // 校验资产ID是否存在
        validateAssetIdsExistForProject(genParams);

        // 校验摄影参数是否在提示词库中存在
        validatePromptParamsExistForProject(genParams);
    }

    /**
     * 校验资产ID是否存在（用于 StoryboardWorkbenchServiceImpl）
     */
    private void validateAssetIdsExistForProject(com.aid.domain.dto.GenerationParams genParams) {
        Set<Long> allAssetIds = new LinkedHashSet<>();
        parseAndCollectIds(allAssetIds, genParams.getSceneIds());
        parseAndCollectIds(allAssetIds, genParams.getCharacterIds());
        parseAndCollectIds(allAssetIds, genParams.getPropIds());
        parseAndCollectIds(allAssetIds, genParams.getPoseIds());
        parseAndCollectIds(allAssetIds, genParams.getExpressionIds());
        parseAndCollectIds(allAssetIds, genParams.getEffectIds());
        parseAndCollectIds(allAssetIds, genParams.getSketchIds());

        if (allAssetIds.isEmpty()) {
            return;
        }

        // 查询存在的资产
        LambdaQueryWrapper<AidComicAsset> wrapper = Wrappers.<AidComicAsset>lambdaQuery()
                .in(AidComicAsset::getId, allAssetIds)
                .eq(AidComicAsset::getDelFlag, "0");
        List<AidComicAsset> existAssets = aidComicAssetService.list(wrapper);
        Set<Long> existIds = existAssets.stream().map(AidComicAsset::getId).collect(Collectors.toSet());

        // 找出不存在的ID
        Set<Long> missingIds = new LinkedHashSet<>(allAssetIds);
        missingIds.removeAll(existIds);
        if (!missingIds.isEmpty()) {
            log.error("生成引用的资产不存在或已删除, missingIds={}", missingIds);
            throw new ServiceException("资产不存在");
        }
    }

    /**
     * 校验摄影参数是否在提示词库中存在（用于 StoryboardWorkbenchServiceImpl）
     */
    private void validatePromptParamsExistForProject(com.aid.domain.dto.GenerationParams genParams) {
        Map<String, String> fieldNameMap = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(genParams.getShotSize())) {
            fieldNameMap.put(genParams.getShotSize(), "景别");
        }
        if (StrUtil.isNotBlank(genParams.getCameraAngle())) {
            fieldNameMap.put(genParams.getCameraAngle(), "拍摄角度");
        }
        if (StrUtil.isNotBlank(genParams.getFocalLength())) {
            fieldNameMap.put(genParams.getFocalLength(), "焦距");
        }
        if (StrUtil.isNotBlank(genParams.getColorTone())) {
            fieldNameMap.put(genParams.getColorTone(), "色彩色调");
        }
        if (StrUtil.isNotBlank(genParams.getLighting())) {
            fieldNameMap.put(genParams.getLighting(), "光线");
        }
        if (StrUtil.isNotBlank(genParams.getExposureBlur())) {
            fieldNameMap.put(genParams.getExposureBlur(), "曝光虚化");
        }
        if (StrUtil.isNotBlank(genParams.getCameraMovement())) {
            fieldNameMap.put(genParams.getCameraMovement(), "运镜");
        }
        if (StrUtil.isNotBlank(genParams.getShootingTechnique())) {
            fieldNameMap.put(genParams.getShootingTechnique(), "拍摄技法");
        }

        if (fieldNameMap.isEmpty()) {
            return;
        }

        // 批量查询提示词库
        Set<String> promptNames = fieldNameMap.keySet();
        LambdaQueryWrapper<AidPromptLib> wrapper = Wrappers.<AidPromptLib>lambdaQuery()
                .in(AidPromptLib::getPromptName, promptNames)
                .eq(AidPromptLib::getDelFlag, DEL_FLAG_NORMAL)
                .eq(AidPromptLib::getStatus, STATUS_NORMAL);
        List<AidPromptLib> existPrompts = aidPromptLibService.list(wrapper);
        Set<String> existNames = existPrompts.stream()
                .map(AidPromptLib::getPromptName)
                .collect(Collectors.toSet());

        // 找出不存在的参数
        List<String> invalidItems = new ArrayList<>();
        for (Map.Entry<String, String> entry : fieldNameMap.entrySet()) {
            if (!existNames.contains(entry.getKey())) {
                invalidItems.add(entry.getValue() + "「" + entry.getKey() + "」");
            }
        }
        if (!invalidItems.isEmpty()) {
            log.error("生成引用的摄影参数不在提示词库中, invalidItems={}", invalidItems);
            throw new ServiceException("参数不支持");
        }
    }

    private void parseAndCollectIds(Set<Long> target, String idsStr) {
        if (StringUtils.isEmpty(idsStr)) {
            return;
        }
        for (String idStr : idsStr.split(",")) {
            String trimmed = idStr.trim();
            if (StringUtils.isNotEmpty(trimmed)) {
                try {
                    target.add(Long.parseLong(trimmed));
                } catch (NumberFormatException e) {
                    log.warn("资产ID解析失败，跳过: {}", trimmed);
                }
            }
        }
    }
    /**
     * 校验项目类型与episodeId的关系：
     * series(剧集) → episodeId 必传且剧集存在
     * movie(电影) → episodeId 不传或传0
     *
     * @return 规范化后的 episodeId（电影返回0，剧集返回原值）
     */
    private Long validateProjectAndEpisode(Long projectId, Long episodeId, Long userId) {
        AidComicProject project = aidComicProjectService.getOne(
                Wrappers.<AidComicProject>lambdaQuery()
                        .eq(AidComicProject::getId, projectId)
                        .eq(AidComicProject::getUserId, userId)
                        .eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL));
        if (project == null) {
            throw new ServiceException("项目不存在或无权限操作");
        }
        boolean isMovie = ProjectTypeEnum.MOVIE.getValue().equals(project.getProjectType());
        if (isMovie) {
            // 电影：episodeId 忽略，统一返回0
            return 0L;
        }
        // 剧集：episodeId 必传
        if (episodeId == null || episodeId <= 0) {
            throw new ServiceException("剧集不能为空");
        }
        AidComicEpisode episode = aidComicEpisodeService.getOne(
                Wrappers.<AidComicEpisode>lambdaQuery()
                        .eq(AidComicEpisode::getId, episodeId)
                        .eq(AidComicEpisode::getProjectId, projectId)
                        .eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL));
        if (episode == null) {
            throw new ServiceException("剧集不存在或不属于该项目");
        }
        return episodeId;
    }

    /** 计费幂等 traceId 时间桶宽度（毫秒）：桶内同参数重复请求（双击/网络重试）幂等防重复扣费 */
    private static final long BILLING_TRACE_BUCKET_MS = 10_000L;

    /**
     * 构造请求级幂等 traceId。
     * 以（userId + storyboardId + genType + modelId + genParams + baseImage/firstImage/lastImage + videoDuration）
     * 组合成稳定哈希，并叠加 10 秒时间桶：桶内同参数双击/重试 traceId 相同，
     * accountUpdateService 层按 traceId+changeType 幂等跳过重复扣费；
     * 跨桶的同参数请求（用户主动重抽）视为新一次生成，正常计费，避免"同参重抽永久免费"漏账。
     */
    private String buildStableBillingTraceId(Long userId, GenerateMediaRequest request, String assembledPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("sb-gen:")
                .append(userId).append('|')
                .append(request.getStoryboardId()).append('|')
                .append(request.getGenType()).append('|')
                .append(request.getModelId()).append('|')
                .append(assembledPrompt == null ? "" : assembledPrompt).append('|')
                .append(request.getBaseImageId() == null ? "" : request.getBaseImageId()).append('|')
                .append(request.getFirstImageId() == null ? "" : request.getFirstImageId()).append('|')
                .append(request.getLastImageId() == null ? "" : request.getLastImageId()).append('|')
                .append(request.getVideoDuration() == null ? "" : request.getVideoDuration()).append('|')
                .append(JSON.toJSONString(request.getGenParams())).append('|')
                .append(System.currentTimeMillis() / BILLING_TRACE_BUCKET_MS);
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(sb.toString());
    }

    /** 构建分镜VO（内部单条场景用：主图按 is_selected 反查、分镜视频按 final_video_id 反查、配音视频按 compose 类 is_selected 反查，并回填参考图快照） */
    private StoryboardVO buildStoryboardVO(AidStoryboard s) {
        AidGenRecord rec = resolveFinalImageRecord(s.getId(), s.getUserId());
        String finalImageUrl = (Objects.nonNull(rec) && StrUtil.isNotBlank(rec.getFileUrl())) ? rec.getFileUrl() : null;
        AidGenRecord videoRec = resolveFinalVideoRecord(s);
        String finalVideoUrl = (Objects.nonNull(videoRec) && StrUtil.isNotBlank(videoRec.getFileUrl()))
                ? videoRec.getFileUrl() : null;
        AidGenRecord composeRec = resolveFinalComposeVideoRecord(s.getId(), s.getUserId());
        String finalComposeVideoUrl = (Objects.nonNull(composeRec) && StrUtil.isNotBlank(composeRec.getFileUrl()))
                ? composeRec.getFileUrl() : null;
        return buildStoryboardVO(s, finalImageUrl, parseReferenceImages(rec), finalVideoUrl, finalComposeVideoUrl);
    }

    /**
     * 构建分镜VO（带主图 URL，不带参考图/最终视频；新建分镜等必然无产物的场景用）。
     *
     * @param s             分镜实体
     * @param finalImageUrl 主图相对路径（由调用方批量/单条解析，可为 null）
     */
    private StoryboardVO buildStoryboardVO(AidStoryboard s, String finalImageUrl) {
        return buildStoryboardVO(s, finalImageUrl, null, null, null);
    }

    /**
     * 构建分镜VO（带主图 URL + 参考图快照 + 分镜视频 URL + 最终配音视频 URL）。
     *
     * @param s                    分镜实体
     * @param finalImageUrl        主图相对路径（可为 null）
     * @param referenceImages      该最终图实际引用的参考图快照（可为 null）
     * @param finalVideoUrl        分镜视频相对路径（final_video_id 指针，恒为配音前原视频；可为 null）
     * @param finalComposeVideoUrl 最终配音视频相对路径（compose 类 is_selected=1 主视频；可为 null）
     */
    private StoryboardVO buildStoryboardVO(AidStoryboard s, String finalImageUrl,
                                           List<StoryboardRefImageVO> referenceImages, String finalVideoUrl,
                                           String finalComposeVideoUrl) {
        return StoryboardVO.builder()
                .id(s.getId()).projectId(s.getProjectId()).episodeId(s.getEpisodeId())
                .sortOrder(s.getSortOrder()).title(s.getTitle())
                .storyScript(s.getStoryScript()).dialogueText(s.getDialogueText())
                // 字幕展示口径：台词原文格式化为「人物：说的话」，供预览时间轴字幕轨直接使用
                .subtitleText(DialogueSubtitleFormatter.format(s.getDialogueText()))
                .finalImageId(s.getFinalImageId()).finalVideoId(s.getFinalVideoId())
                .finalAudioId(s.getFinalAudioId())
                .finalImageUrl(finalImageUrl)
                .finalVideoUrl(finalVideoUrl)
                .finalComposeVideoUrl(finalComposeVideoUrl)
                .referenceImages(referenceImages)
                .createTime(s.getCreateTime())
                .build();
    }

    /**
     * 单分镜主图记录解析（detail 等单条场景用）。
     * 以 {@code aid_gen_record.is_selected=1} 为权威源（图片类、未删除、归属当前用户），取最新一条；无则返回 null。
     * 防漏字段：仅 select 业务必需列（file_url / id / gen_params）；gen_params 承载参考图快照供解析，同一次查询取回。
     */
    private AidGenRecord resolveFinalImageRecord(Long storyboardId, Long userId) {
        if (Objects.isNull(storyboardId) || Objects.isNull(userId)) {
            return null;
        }
        LambdaQueryWrapper<AidGenRecord> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidGenRecord::getFileUrl, AidGenRecord::getId, AidGenRecord::getGenParams);
        wrapper.eq(AidGenRecord::getStoryboardId, storyboardId);
        wrapper.eq(AidGenRecord::getUserId, userId);
        wrapper.eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.eq(AidGenRecord::getIsSelected, SELECTED_YES);
        wrapper.in(AidGenRecord::getGenType, IMAGE_GEN_TYPES);
        wrapper.orderByDesc(AidGenRecord::getId);
        wrapper.last("LIMIT 1");
        return aidGenRecordService.getOne(wrapper, false);
    }

    /**
     * 单分镜"分镜视频"记录解析（detail 等单条场景用，<strong>final_video_id 指针</strong>：
     * 恒为配音前原视频，视频大类单选下配音视频抢占选中不影响本指针）。
     * 防漏字段：仅 select 业务必需列（file_url / id / gen_type），后续扩展取数请同步增列。
     */
    private AidGenRecord resolveFinalVideoRecord(AidStoryboard storyboard) {
        if (Objects.isNull(storyboard) || Objects.isNull(storyboard.getFinalVideoId())
                || Objects.isNull(storyboard.getUserId())) {
            return null;
        }
        LambdaQueryWrapper<AidGenRecord> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidGenRecord::getFileUrl, AidGenRecord::getId, AidGenRecord::getGenType);
        wrapper.eq(AidGenRecord::getId, storyboard.getFinalVideoId());
        wrapper.eq(AidGenRecord::getUserId, storyboard.getUserId());
        wrapper.eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL);
        // 历史脏数据兜底：final_video_id 误指配音视频时不作为"分镜视频"返回
        wrapper.in(AidGenRecord::getGenType, ORIGINAL_VIDEO_GEN_TYPES);
        wrapper.last("LIMIT 1");
        return aidGenRecordService.getOne(wrapper, false);
    }
    @Override
    public StoryboardVO getStoryboardDetail(StoryboardDetailRequest request, Long userId) {
        AidStoryboard storyboard = getStoryboardWithOwnerCheck(request.getId(), userId);
        StoryboardVO vo = buildStoryboardVO(storyboard);
        // 明细接口专属：回填图/视频生成提示词（列表接口不返回，避免大数据量传输）
        vo.setImagePrompt(storyboard.getImagePrompt());
        vo.setVideoPrompt(storyboard.getVideoPrompt());
        // 图生方向（漫剧版）视频提示词与多参方向物理隔离，明细接口一并回填
        vo.setVideoPromptImage(storyboard.getVideoPromptImage());
        // 宫格类型（四宫格/九宫格）：仅宫格画师产出，标准漫剧画师为 null
        vo.setGridType(storyboard.getGridType());
        // 配音展示字段与列表同口径
        fillVoiceoverInfo(Collections.singletonList(storyboard), Collections.singletonList(vo), userId);
        return vo;
    }
    @Override
    public List<GenRecordVO> listGenRecords(GenRecordListRequest request, Long userId) {
        // 校验分镜归属
        AidStoryboard storyboard = getStoryboardWithOwnerCheck(request.getStoryboardId(), userId);

        // 构建查询条件
        LambdaQueryWrapper<AidGenRecord> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidGenRecord::getStoryboardId, request.getStoryboardId());
        wrapper.eq(AidGenRecord::getUserId, userId);
        wrapper.eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL);

        // 可选：按genType过滤（支持大类查询）
        if (StrUtil.isNotBlank(request.getGenType())) {
            String genType = request.getGenType();
            // 大类查询（分轨展示）：image=图片类；video=原视频轨（不含配音视频）；compose=配音轨（配音/对口型视频）
            if (TYPE_IMAGE.equals(genType)) {
                wrapper.in(AidGenRecord::getGenType, IMAGE_GEN_TYPES);
            } else if (TYPE_VIDEO.equals(genType)) {
                wrapper.in(AidGenRecord::getGenType, ORIGINAL_VIDEO_GEN_TYPES);
            } else if (TYPE_COMPOSE.equals(genType)) {
                wrapper.in(AidGenRecord::getGenType, COMPOSE_VIDEO_GEN_TYPES);
            } else {
                // 精确查询：按具体genType查询
                wrapper.eq(AidGenRecord::getGenType, genType);
            }
        }

        // 按创建时间倒序
        wrapper.orderByDesc(AidGenRecord::getCreateTime);

        List<AidGenRecord> list = aidGenRecordService.list(wrapper);
        // 批量计算展示名（零 N+1）：分镜镜号 + 同分镜同类别整类别 rank（与详情口径一致）
        Map<Long, String> nameMap = computeDisplayNames(list, userId);
        return list.stream().map(r -> buildGenRecordVO(r, null, nameMap.get(r.getId())))
                .collect(Collectors.toList());
    }
    @Override
    public GenRecordVO getGenRecordDetail(GenRecordDetailRequest request, Long userId) {
        AidGenRecord record = aidGenRecordService.getById(request.getId());
        if (Objects.isNull(record) || !Objects.equals(DEL_FLAG_NORMAL, record.getDelFlag())) {
            log.error("生成记录不存在, recordId={}", request.getId());
            throw new ServiceException("数据不存在");
        }
        if (!Objects.equals(record.getUserId(), userId)) {
            log.error("无权查看该记录, recordId={}, userId={}", request.getId(), userId);
            throw new ServiceException("无权操作");
        }
        return buildGenRecordVO(record);
    }
    /** 类型常量：图片大类 / 原视频大类（与 SetFinalSelectionRequest.recordType 同口径） / 配音视频大类 */
    private static final String TYPE_IMAGE = "image";
    private static final String TYPE_VIDEO = "video";
    private static final String TYPE_COMPOSE = "compose";

    @Override
    public List<GenRecordVO> listGenRecordsByStoryboard(StoryboardGenRecordListRequest request, Long userId) {
        //    - 电影项目：episodeId 强制 0
        //    - 剧集项目：episodeId 必须 > 0 且属于该项目
        //    - 项目必须归属当前用户
        Long normalizedEpisodeId = validateProjectAndEpisode(
                request.getProjectId(), request.getEpisodeId(), userId);

        // 类型分轨（C端两个入口）：分镜视频页 type=video 只看原视频轨；音画同步页 type=compose 只看配音/对口型视频
        String type = request.getType();
        List<String> genTypes;
        if (TYPE_IMAGE.equals(type)) {
            genTypes = IMAGE_GEN_TYPES;
        } else if (TYPE_VIDEO.equals(type)) {
            genTypes = ORIGINAL_VIDEO_GEN_TYPES;
        } else if (TYPE_COMPOSE.equals(type)) {
            genTypes = COMPOSE_VIDEO_GEN_TYPES;
        } else {
            log.info("分镜内容列表类型不合法, projectId={}, episodeId={}, type={}",
                    request.getProjectId(), normalizedEpisodeId, type);
            throw new ServiceException("类型不支持");
        }

        // aid_gen_record 冗余存有 project/episode，可直接按维度查询，无需先反查 aid_storyboard 取 ID 集合；
        // is_selected 由 setFinal 系列接口维护（同分镜+同类型互斥），最终产物标识完全以 is_selected=1 为准。
        LambdaQueryWrapper<AidGenRecord> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidGenRecord::getId, AidGenRecord::getStoryboardId, AidGenRecord::getUserId,
                AidGenRecord::getGenType, AidGenRecord::getFileUrl, AidGenRecord::getModelId,
                AidGenRecord::getUserInputText, AidGenRecord::getBaseImageId,
                AidGenRecord::getFirstImageId, AidGenRecord::getLastImageId,
                AidGenRecord::getVideoDuration, AidGenRecord::getSoundDesc,
                AidGenRecord::getCostCredits, AidGenRecord::getIsSelected,
                AidGenRecord::getStatus, AidGenRecord::getTaskId, AidGenRecord::getCreateTime,
                AidGenRecord::getDelFlag);
        wrapper.eq(AidGenRecord::getProjectId, request.getProjectId());
        wrapper.eq(AidGenRecord::getEpisodeId, normalizedEpisodeId);
        wrapper.eq(AidGenRecord::getUserId, userId);
        wrapper.eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.in(AidGenRecord::getGenType, genTypes);
        // 数据库先按创建时间稳定取数，返回前再按分镜业务顺序统一排序。
        wrapper.orderByDesc(AidGenRecord::getCreateTime);
        List<AidGenRecord> list = aidGenRecordService.list(wrapper);

        sortGenRecordsByStoryboardOrder(list, userId);
        Map<Long, String> nameMap = computeDisplayNames(list, userId);
        return list.stream().map(r -> buildGenRecordVO(r, null, nameMap.get(r.getId())))
                .collect(Collectors.toList());
    }

    /**
     * 按分镜业务顺序整理生成记录：分镜 {@code sort_order} 升序，同一分镜内按创建时间倒序。
     * {@code storyboardId} 仅用于重复排序值的稳定兜底，不能代替可被用户调整的分镜排序。
     */
    private void sortGenRecordsByStoryboardOrder(List<AidGenRecord> records, Long userId) {
        if (CollectionUtil.isEmpty(records)) {
            return;
        }
        Set<Long> storyboardIds = records.stream()
                .map(AidGenRecord::getStoryboardId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (CollectionUtil.isEmpty(storyboardIds)) {
            return;
        }

        // 防漏字段：排序只依赖分镜主键和 sort_order，后续增加排序条件时需同步增列。
        List<AidStoryboard> storyboards = aidStoryboardService.list(Wrappers.<AidStoryboard>lambdaQuery()
                .select(AidStoryboard::getId, AidStoryboard::getSortOrder)
                .in(AidStoryboard::getId, storyboardIds)
                .eq(AidStoryboard::getUserId, userId)
                .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL));
        Map<Long, Long> sortOrderByStoryboardId = new HashMap<>();
        for (AidStoryboard storyboard : storyboards) {
            if (Objects.nonNull(storyboard.getId()) && Objects.nonNull(storyboard.getSortOrder())) {
                sortOrderByStoryboardId.put(storyboard.getId(), storyboard.getSortOrder());
            }
        }

        Comparator<AidGenRecord> comparator = Comparator
                .comparing((AidGenRecord record) -> sortOrderByStoryboardId.get(record.getStoryboardId()),
                        Comparator.nullsLast(Long::compareTo))
                .thenComparing(AidGenRecord::getStoryboardId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(AidGenRecord::getCreateTime,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AidGenRecord::getId,
                        Comparator.nullsLast(Comparator.reverseOrder()));
        records.sort(comparator);
    }

    private GenRecordVO buildGenRecordVO(AidGenRecord record) {
        return buildGenRecordVO(record, null, computeDisplayNameSingle(record));
    }

    private GenRecordVO buildGenRecordVO(AidGenRecord record, MediaTaskResponse taskResponse) {
        return buildGenRecordVO(record, taskResponse, computeDisplayNameSingle(record));
    }

    /**
     * 组装生成记录 VO（核心）。{@code displayName} 由调用方计算后传入：
     * 列表场景批量计算（{@link #computeDisplayNames}，零 N+1）、单条场景轻量计算（{@link #computeDisplayNameSingle}）。
     */
    private GenRecordVO buildGenRecordVO(AidGenRecord record, MediaTaskResponse taskResponse, String displayName) {
        GenRecordVO.GenRecordVOBuilder builder = GenRecordVO.builder()
                .id(record.getId()).storyboardId(record.getStoryboardId())
                .displayName(displayName)
                .genType(record.getGenType()).fileUrl(record.getFileUrl())
                .modelId(record.getModelId()).userInputText(record.getUserInputText())
                .baseImageId(record.getBaseImageId()).firstImageId(record.getFirstImageId())
                .lastImageId(record.getLastImageId()).videoDuration(record.getVideoDuration())
                .soundDesc(record.getSoundDesc()).costCredits(record.getCostCredits())
                .isSelected(record.getIsSelected()).createTime(record.getCreateTime());
        if (taskResponse != null) {
            builder.taskId(taskResponse.getTaskId());
            builder.status(taskResponse.getStatus());
        }
        return builder.build();
    }
    /** 展示名类别词：视频大类→「视频」，其余(图片/九宫格/兜底)→「图片」。 */
    private String displayNoun(String genType) {
        return VIDEO_GEN_TYPES.contains(genType) ? "视频" : "图片";
    }

    /** 取分镜全局镜号：script_params.镜号（sortOrder 同步镜像）→ 回落 sortOrder → id。 */
    private String resolveShotNo(AidStoryboard sb) {
        if (Objects.isNull(sb)) {
            return null;
        }
        LinkedHashMap<String, Object> params = parseScriptParamsOrdered(sb.getScriptParams());
        Object no = params.get("镜号");
        if (Objects.nonNull(no) && StrUtil.isNotBlank(String.valueOf(no))) {
            return StrUtil.trim(String.valueOf(no));
        }
        if (Objects.nonNull(sb.getSortOrder())) {
            return formatGlobalShotNumber(sb.getSortOrder());
        }
        return String.valueOf(sb.getId());
    }

    /** 拼展示名：`分镜{镜号}-{类别}{序号}`；镜号缺失时退化为 `{类别}{序号}`。 */
    private String buildDisplayName(String shotNo, String noun, int index) {
        return StrUtil.isNotBlank(shotNo) ? ("分镜" + shotNo + "-" + noun + index) : (noun + index);
    }

    /**
     * 批量计算展示名（列表场景，零 N+1）。
     *
     * @param records 本次要展示的记录（可能是某类别的子集 / 分页片段）
     * @param userId  当前用户（rank 统计按 userId + del_flag 限定，与列表口径一致）
     * @return Map&lt;recordId, displayName&gt;
     */
    private Map<Long, String> computeDisplayNames(List<AidGenRecord> records, Long userId) {
        Map<Long, String> out = new HashMap<>();
        if (CollectionUtil.isEmpty(records)) {
            return out;
        }
        Set<Long> sbIds = records.stream().map(AidGenRecord::getStoryboardId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> shotNoById = new HashMap<>();
        if (CollectionUtil.isNotEmpty(sbIds)) {
            List<AidStoryboard> sbs = aidStoryboardService.list(Wrappers.<AidStoryboard>lambdaQuery()
                    .select(AidStoryboard::getId, AidStoryboard::getScriptParams,
                            AidStoryboard::getSortOrder)
                    .in(AidStoryboard::getId, sbIds));
            for (AidStoryboard sb : sbs) {
                shotNoById.put(sb.getId(), resolveShotNo(sb));
            }
        }
        Map<Long, Integer> rankById = new HashMap<>();
        if (CollectionUtil.isNotEmpty(sbIds)) {
            List<AidGenRecord> all = aidGenRecordService.list(Wrappers.<AidGenRecord>lambdaQuery()
                    .select(AidGenRecord::getId, AidGenRecord::getStoryboardId, AidGenRecord::getGenType)
                    .in(AidGenRecord::getStoryboardId, sbIds)
                    .eq(AidGenRecord::getUserId, userId)
                    .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL));
            Map<String, List<AidGenRecord>> groups = new HashMap<>();
            for (AidGenRecord r : all) {
                String key = r.getStoryboardId() + "|" + displayNoun(r.getGenType());
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }
            for (List<AidGenRecord> group : groups.values()) {
                group.sort(Comparator.comparing(AidGenRecord::getId));
                int idx = 1;
                for (AidGenRecord r : group) {
                    rankById.put(r.getId(), idx++);
                }
            }
        }
        for (AidGenRecord r : records) {
            String shotNo = Objects.isNull(r.getStoryboardId()) ? null : shotNoById.get(r.getStoryboardId());
            out.put(r.getId(), buildDisplayName(shotNo, displayNoun(r.getGenType()),
                    rankById.getOrDefault(r.getId(), 1)));
        }
        return out;
    }

    /**
     * 单条计算展示名（详情/新建场景）：查所属分镜镜号 + 同分镜同类别中本条的序号(id ≤ 本条的计数)。
     */
    private String computeDisplayNameSingle(AidGenRecord record) {
        if (Objects.isNull(record)) {
            return null;
        }
        String noun = displayNoun(record.getGenType());
        Long sbId = record.getStoryboardId();
        if (Objects.isNull(sbId)) {
            return noun + "1";
        }
        AidStoryboard sb = aidStoryboardService.getOne(Wrappers.<AidStoryboard>lambdaQuery()
                .select(AidStoryboard::getId, AidStoryboard::getScriptParams, AidStoryboard::getSortOrder)
                .eq(AidStoryboard::getId, sbId).last("limit 1"), false);
        String shotNo = resolveShotNo(sb);
        List<String> category = VIDEO_GEN_TYPES.contains(record.getGenType()) ? VIDEO_GEN_TYPES : IMAGE_GEN_TYPES;
        long index = aidGenRecordService.count(Wrappers.<AidGenRecord>lambdaQuery()
                .eq(AidGenRecord::getStoryboardId, sbId)
                .eq(AidGenRecord::getUserId, record.getUserId())
                .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL)
                .in(AidGenRecord::getGenType, category)
                .le(AidGenRecord::getId, record.getId()));
        return buildDisplayName(shotNo, noun, (int) Math.max(1L, index));
    }

    private AudioTaskVO buildAudioTaskVO(AidAudioRecord task) {
        return AudioTaskVO.builder()
                .id(task.getId()).storyboardId(task.getStoryboardId())
                .audioSource(task.getAudioSource()).audioUrl(task.getAudioUrl())
                .durationMs(task.getDurationMs())
                .ttsText(task.getTtsText()).voiceModelId(task.getVoiceModelId())
                .timbreCode(task.getTimbreCode()).enableLipSync(task.getEnableLipSync())
                .status(task.getStatus())
                .errorMessage(task.getErrorMessage())
                .voiceLibraryId(task.getVoiceLibraryId())
                .syncVideoUrl(task.getSyncVideoUrl())
                .lipSyncStatus(deriveLipSyncStatus(task))
                .createTime(task.getCreateTime())
                .build();
    }

    /**
     * 派生对口型任务状态（只读投影，权威源为 aid_media_task）：
     * 未开启对口型返回 null；sync_video_url 已回填视为 SUCCEEDED；
     * 否则按关联统一任务状态归一化为 PROCESSING / FAILED（QUEUED/PENDING 等非终态一律归 PROCESSING）。
     */
    private String deriveLipSyncStatus(AidAudioRecord task) {
        if (!Objects.equals(LIP_SYNC_ENABLED, task.getEnableLipSync())) {
            return null;
        }
        // 业务表已有对口型结果 URL：直接判成功，无需再查任务表
        if (StrUtil.isNotBlank(task.getSyncVideoUrl())) {
            return MediaTaskStatus.SUCCEEDED.name();
        }
        if (Objects.isNull(task.getSyncMediaTaskId())) {
            // 已标记开启但无关联任务（异常数据）：按处理中展示，等待人工/重试修复
            return MediaTaskStatus.PROCESSING.name();
        }
        // 仅读本地任务快照，不触发远端轮询（远端轮询由统一调度中心驱动）
        MediaTaskResponse mediaTask = mediaGenerationService.queryTaskLocal(task.getSyncMediaTaskId());
        if (Objects.isNull(mediaTask)) {
            return MediaTaskStatus.PROCESSING.name();
        }
        if (MediaTaskStatus.FAILED.name().equals(mediaTask.getStatus())) {
            return MediaTaskStatus.FAILED.name();
        }
        if (MediaTaskStatus.SUCCEEDED.name().equals(mediaTask.getStatus())
                && StrUtil.isNotBlank(mediaTask.getOssUrl())) {
            // 任务已成功且 OSS 就绪，业务表回填可能仍在途：按成功展示（URL 以业务表回填为准）
            return MediaTaskStatus.SUCCEEDED.name();
        }
        return MediaTaskStatus.PROCESSING.name();
    }

    /**
     * 用户自行上传分镜媒体（图片 / 视频）。
     * 落库到 {@code aid_gen_record}，{@code status=1}（成功）、{@code is_selected=0}（默认未被选择）、
     * {@code prompt_text="用户自行上传"}、{@code cost_credits=0}（用户上传不扣费）。
     * {@code mediaType=image} → {@code gen_type=image}（可设为主图）；
     * {@code mediaType=video} → {@code gen_type=upload_video}（归入视频大类，可设为主视频）。
     */
    @Override
    public GenRecordVO uploadStoryboardImage(UploadStoryboardImageRequest request, Long userId) {
        Long normalizedEpisodeId = validateProjectAndEpisode(
                request.getProjectId(), request.getEpisodeId(), userId);

        AidStoryboard storyboard = getStoryboardWithOwnerCheck(request.getStoryboardId(), userId);

        if (!Objects.equals(storyboard.getProjectId(), request.getProjectId())) {
            log.error("分镜与项目不匹配, storyboardId={}, storyboard.projectId={}, request.projectId={}",
                    request.getStoryboardId(), storyboard.getProjectId(), request.getProjectId());
            throw new ServiceException("分镜不匹配");
        }
        if (!Objects.equals(storyboard.getEpisodeId(), normalizedEpisodeId)) {
            log.error("分镜与剧集不匹配, storyboardId={}, storyboard.episodeId={}, normalizedEpisodeId={}",
                    request.getStoryboardId(), storyboard.getEpisodeId(), normalizedEpisodeId);
            throw new ServiceException("剧集不匹配");
        }

        // 仅允许本站已上传资源，拒绝站外外链。
        String relativeUrl = request.getImageUrl();
        if (StrUtil.startWithIgnoreCase(relativeUrl, "http://")
                || StrUtil.startWithIgnoreCase(relativeUrl, "https://")) {
            log.error("用户上传分镜图非本站资源, storyboardId={}, userId={}, imageUrl={}",
                    request.getStoryboardId(), userId, relativeUrl);
            throw new ServiceException("图片格式有误");
        }
        // 仅接受规范相对路径，防止协议相对地址与路径穿越。
        boolean illegalPath = !StrUtil.startWith(relativeUrl, "/")      // 必须是相对路径
                || StrUtil.startWith(relativeUrl, "//")                  // 协议相对URL（//evil.com）
                || StrUtil.startWith(relativeUrl, "/\\")                 // 反斜杠变体（/\evil.com）
                || relativeUrl.contains("..");                          // 路径穿越（/../）
        if (illegalPath) {
            log.error("用户上传分镜图路径非法(非本站相对路径/疑似穿越), storyboardId={}, userId={}, imageUrl={}",
                    request.getStoryboardId(), userId, relativeUrl);
            throw new ServiceException("图片格式有误");
        }
        String fullImageUrl = mediaUrlResolver.toFullUrl(relativeUrl); // 相对路径拼回完整URL
        // 媒体类型：默认图片，兼容旧调用；video 走上传视频链路
        boolean isVideo = Objects.equals("video", request.getMediaType());
        if (!isVideo) {
            // 云存储图片执行远程真实性探测，本地模式仅做来源与路径校验。
            OssProperties ossProperties = ossConfigManager.getOssProperties();
            String uploadMode = Objects.nonNull(ossProperties) ? ossProperties.getUploadMode() : null;
            boolean cloudMode = "oss".equalsIgnoreCase(uploadMode) || "cos".equalsIgnoreCase(uploadMode);
            if (cloudMode && !ImageUrlValidator.isValidRemoteImageUrl(fullImageUrl)) {
                log.error("用户上传分镜图校验不通过, storyboardId={}, userId={}, imageUrl={}",
                        request.getStoryboardId(), userId, fullImageUrl);
                throw new ServiceException("图片不可用");
            }

            // 图片上传后执行内容安全审查。
            userImageUploadModerationGuard.checkUploadedOrThrow(fullImageUrl, "storyboard_upload", userId);
        }

        AidGenRecord record = new AidGenRecord();
        record.setUserId(userId);
        record.setProjectId(storyboard.getProjectId());
        record.setEpisodeId(storyboard.getEpisodeId());
        record.setStoryboardId(storyboard.getId());
        // 用户上传：图片归为单图(image)，视频归为上传视频(upload_video)——后者可被设为分镜主视频
        record.setGenType(isVideo ? GenTypeEnum.UPLOAD_VIDEO.getValue() : GenTypeEnum.IMAGE.getValue());
        record.setFileUrl(request.getImageUrl());        // @MediaUrl 已剥离域名为相对路径
        record.setStatus(1);                              // 1=成功（用户上传无需异步处理）
        record.setIsSelected(SELECTED_NO);                // 默认未被选择
        record.setPromptText("用户自行上传");                // 提示词协商展示文案
        record.setCostCredits(BigDecimal.ZERO);           // 用户上传不扣费
        record.setDelFlag(DEL_FLAG_NORMAL);
        record.setCreateBy(String.valueOf(userId));
        record.setCreateTime(DateUtils.getNowDate());
        aidGenRecordService.save(record);

        log.info("用户上传分镜媒体, mediaType={}, recordId={}, storyboardId={}, userId={}, fileUrl={}",
                isVideo ? "video" : "image", record.getId(), storyboard.getId(), userId, record.getFileUrl());

        return buildGenRecordVO(record);
    }

    /**
     * 取消分镜最终图片选中。
     * 仅当 {@code aid_storyboard.final_image_id} == 入参 {@code recordId} 时才清除，
     * 避免误清掉别的最终图。同时把同分镜下图片类（image / grid）的 {@code is_selected}
     * 全部归零，与 {@link #setFinalSelection} 的互斥范围保持一致。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unsetFinalImage(SetFinalImageRequest request, Long userId) {
        AidStoryboard storyboard = getStoryboardWithOwnerCheck(request.getStoryboardId(), userId);

        AidGenRecord targetRecord = aidGenRecordService.getById(request.getRecordId());
        if (Objects.isNull(targetRecord)
                || !Objects.equals(DEL_FLAG_NORMAL, targetRecord.getDelFlag())
                || !Objects.equals(targetRecord.getStoryboardId(), request.getStoryboardId())) {
            log.error("生成记录不存在或不属于该分镜, recordId={}, storyboardId={}, delFlag={}",
                    request.getRecordId(), request.getStoryboardId(),
                    Objects.nonNull(targetRecord) ? targetRecord.getDelFlag() : null);
            throw new ServiceException("数据不存在");
        }
        // 防御性归属校验，避免脏数据被利用
        if (!Objects.equals(targetRecord.getUserId(), userId)) {
            log.error("生成记录归属异常, recordId={}, owner={}, requester={}",
                    request.getRecordId(), targetRecord.getUserId(), userId);
            throw new ServiceException("数据不存在");
        }
        // 仅图片类（image / grid）允许走本接口，避免误用到视频/音频
        if (!IMAGE_GEN_TYPES.contains(targetRecord.getGenType())) {
            log.error("生成记录类型不是图片, recordId={}, genType={}",
                    request.getRecordId(), targetRecord.getGenType());
            throw new ServiceException("类型不匹配");
        }

        if (!Objects.equals(storyboard.getFinalImageId(), request.getRecordId())) {
            log.info("分镜当前最终图不是该记录, 忽略取消, storyboardId={}, finalImageId={}, recordId={}",
                    request.getStoryboardId(), storyboard.getFinalImageId(), request.getRecordId());
            // 直接返回，不抛异常：前端连续点击 / 已被别处覆盖时保持幂等
            return;
        }

        LambdaUpdateWrapper<AidGenRecord> resetWrapper = Wrappers.lambdaUpdate();
        resetWrapper.eq(AidGenRecord::getStoryboardId, request.getStoryboardId());
        resetWrapper.eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL);
        resetWrapper.in(AidGenRecord::getGenType, IMAGE_GEN_TYPES);
        resetWrapper.set(AidGenRecord::getIsSelected, SELECTED_NO);
        resetWrapper.set(AidGenRecord::getUpdateBy, String.valueOf(userId));
        resetWrapper.set(AidGenRecord::getUpdateTime, DateUtils.getNowDate());
        aidGenRecordService.update(resetWrapper);

        LambdaUpdateWrapper<AidStoryboard> sbWrapper = Wrappers.lambdaUpdate();
        sbWrapper.eq(AidStoryboard::getId, request.getStoryboardId());
        sbWrapper.set(AidStoryboard::getFinalImageId, null);
        sbWrapper.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
        sbWrapper.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
        aidStoryboardService.update(sbWrapper);

        log.info("取消分镜最终图, storyboardId={}, recordId={}, userId={}",
                request.getStoryboardId(), request.getRecordId(), userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unsetFinalVideo(SetFinalImageRequest request, Long userId) {
        AidStoryboard storyboard = getStoryboardWithOwnerCheck(request.getStoryboardId(), userId);

        AidGenRecord targetRecord = aidGenRecordService.getById(request.getRecordId());
        if (Objects.isNull(targetRecord)
                || !Objects.equals(DEL_FLAG_NORMAL, targetRecord.getDelFlag())
                || !Objects.equals(targetRecord.getStoryboardId(), request.getStoryboardId())) {
            log.error("生成记录不存在或不属于该分镜, recordId={}, storyboardId={}, delFlag={}",
                    request.getRecordId(), request.getStoryboardId(),
                    Objects.nonNull(targetRecord) ? targetRecord.getDelFlag() : null);
            throw new ServiceException("数据不存在");
        }
        // 防御性归属校验，避免脏数据被利用
        if (!Objects.equals(targetRecord.getUserId(), userId)) {
            log.error("生成记录归属异常, recordId={}, owner={}, requester={}",
                    request.getRecordId(), targetRecord.getUserId(), userId);
            throw new ServiceException("数据不存在");
        }
        // 仅视频大类（i2v / multi / edge / upload_video / compose）允许走本接口，避免误用到图片/音频
        if (!VIDEO_GEN_TYPES.contains(targetRecord.getGenType())) {
            log.error("生成记录类型不是视频, recordId={}, genType={}",
                    request.getRecordId(), targetRecord.getGenType());
            throw new ServiceException("类型不匹配");
        }

        // 配音视频（compose）：仅在配音轨内取消选中，不涉及 finalVideoId（其由原视频轨独立维护）
        if (GenTypeEnum.COMPOSE.getValue().equals(targetRecord.getGenType())) {
            LambdaUpdateWrapper<AidGenRecord> composeReset = Wrappers.lambdaUpdate();
            composeReset.eq(AidGenRecord::getId, request.getRecordId());
            composeReset.set(AidGenRecord::getIsSelected, SELECTED_NO);
            composeReset.set(AidGenRecord::getUpdateBy, String.valueOf(userId));
            composeReset.set(AidGenRecord::getUpdateTime, DateUtils.getNowDate());
            aidGenRecordService.update(composeReset);
            log.info("取消分镜配音视频选中, storyboardId={}, recordId={}, userId={}",
                    request.getStoryboardId(), request.getRecordId(), userId);
            return;
        }

        if (!Objects.equals(storyboard.getFinalVideoId(), request.getRecordId())) {
            log.info("分镜当前最终视频不是该记录, 忽略取消, storyboardId={}, finalVideoId={}, recordId={}",
                    request.getStoryboardId(), storyboard.getFinalVideoId(), request.getRecordId());
            // 直接返回，不抛异常：前端连续点击 / 已被别处覆盖时保持幂等
            return;
        }

        // 原视频轨取消：仅重置原视频轨选中（不动配音轨），并清空 finalVideoId
        LambdaUpdateWrapper<AidGenRecord> resetWrapper = Wrappers.lambdaUpdate();
        resetWrapper.eq(AidGenRecord::getStoryboardId, request.getStoryboardId());
        resetWrapper.eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL);
        resetWrapper.in(AidGenRecord::getGenType, ORIGINAL_VIDEO_GEN_TYPES);
        resetWrapper.set(AidGenRecord::getIsSelected, SELECTED_NO);
        resetWrapper.set(AidGenRecord::getUpdateBy, String.valueOf(userId));
        resetWrapper.set(AidGenRecord::getUpdateTime, DateUtils.getNowDate());
        aidGenRecordService.update(resetWrapper);

        LambdaUpdateWrapper<AidStoryboard> sbWrapper = Wrappers.lambdaUpdate();
        sbWrapper.eq(AidStoryboard::getId, request.getStoryboardId());
        sbWrapper.set(AidStoryboard::getFinalVideoId, null);
        sbWrapper.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
        sbWrapper.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
        aidStoryboardService.update(sbWrapper);

        log.info("取消分镜最终视频, storyboardId={}, recordId={}, userId={}",
                request.getStoryboardId(), request.getRecordId(), userId);
    }
    @Override
    public BatchOperationResultVO setFinalImageBatch(Long projectId, Long episodeId,
                                                     List<SetFinalImageRequest.Item> items, Long userId) {
        return runFinalBatch(projectId, episodeId, items, userId, true, RECORD_TYPE_IMAGE);
    }

    @Override
    public BatchOperationResultVO setFinalVideoBatch(Long projectId, Long episodeId,
                                                     List<SetFinalImageRequest.Item> items, Long userId) {
        return runFinalBatch(projectId, episodeId, items, userId, true, RECORD_TYPE_VIDEO);
    }

    @Override
    public BatchOperationResultVO unsetFinalImageBatch(Long projectId, Long episodeId,
                                                       List<SetFinalImageRequest.Item> items, Long userId) {
        return runFinalBatch(projectId, episodeId, items, userId, false, RECORD_TYPE_IMAGE);
    }

    @Override
    public BatchOperationResultVO unsetFinalVideoBatch(Long projectId, Long episodeId,
                                                       List<SetFinalImageRequest.Item> items, Long userId) {
        return runFinalBatch(projectId, episodeId, items, userId, false, RECORD_TYPE_VIDEO);
    }

    /**
     * 设置 / 取消最终产物的通用批量流程（图片 / 视频 + 设置 / 取消 共 4 套）。
     *
     * @param projectId   项目 ID（范围闸门）
     * @param episodeId   剧集 ID（电影传 0，剧集传 &gt; 0）
     * @param items       待操作条目（已去重）
     * @param userId      当前用户
     * @param isSet       true=设置类，false=取消类
     * @param recordType  {@link #RECORD_TYPE_IMAGE} / {@link #RECORD_TYPE_VIDEO}
     * @return 批量汇总
     */
    private BatchOperationResultVO runFinalBatch(Long projectId, Long episodeId,
                                                 List<SetFinalImageRequest.Item> items, Long userId,
                                                 boolean isSet, String recordType) {
        BatchOperationResultVO result = new BatchOperationResultVO();
        if (CollectionUtil.isEmpty(items)) {
            log.info("批量最终产物：入参为空, userId={}, isSet={}, recordType={}", userId, isSet, recordType);
            return result.summarize();
        }
        // 项目 / 剧集一次性归属校验（电影会把 episodeId 归一为 0）
        Long normalizedEpisodeId = validateProjectAndEpisode(projectId, episodeId, userId);
        for (SetFinalImageRequest.Item it : items) {
            Long sbId = it.getStoryboardId();
            Long recId = it.getRecordId();
            try {
                // 项目边界校验：轻量探测分镜的 project_id / episode_id，越权条目"项目不匹配"单条失败
                AidStoryboard probe = aidStoryboardService.getOne(
                        Wrappers.<AidStoryboard>lambdaQuery()
                                .select(AidStoryboard::getId, AidStoryboard::getProjectId,
                                        AidStoryboard::getEpisodeId, AidStoryboard::getUserId,
                                        AidStoryboard::getDelFlag)
                                .eq(AidStoryboard::getId, sbId)
                                .eq(AidStoryboard::getUserId, userId)
                                .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                                .last("LIMIT 1"));
                if (Objects.isNull(probe)) {
                    result.addFailure(recId, "分镜不存在");
                    continue;
                }
                if (!Objects.equals(probe.getProjectId(), projectId)
                        || !Objects.equals(probe.getEpisodeId(), normalizedEpisodeId)) {
                    log.info("批量最终产物-项目不匹配: sbId={}, recId={}, sbProjectId={}, sbEpisodeId={}, " +
                                    "reqProjectId={}, reqEpisodeId={}",
                            sbId, recId, probe.getProjectId(), probe.getEpisodeId(),
                            projectId, normalizedEpisodeId);
                    result.addFailure(recId, "项目不匹配");
                    continue;
                }
                if (isSet) {
                    // 复用单条 setFinalSelection（含完整校验 + 强制锁类型）
                    SetFinalSelectionRequest delegated = new SetFinalSelectionRequest();
                    delegated.setStoryboardId(sbId);
                    delegated.setRecordId(recId);
                    delegated.setRecordType(recordType);
                    // 经自身代理调用，确保每条目走独立 @Transactional 事务
                    self.setFinalSelection(delegated, userId);
                } else {
                    SetFinalImageRequest oneReq = new SetFinalImageRequest();
                    oneReq.setStoryboardId(sbId);
                    oneReq.setRecordId(recId);
                    if (Objects.equals(RECORD_TYPE_IMAGE, recordType)) {
                        self.unsetFinalImage(oneReq, userId);
                    } else {
                        self.unsetFinalVideo(oneReq, userId);
                    }
                }
                result.addSuccess(recId);
            } catch (RuntimeException e) {
                log.error("批量最终产物-单条失败: sbId={}, recId={}, isSet={}, recordType={}, userId={}, err={}",
                        sbId, recId, isSet, recordType, userId, e.getMessage());
                result.addFailure(recId, e.getMessage());
            }
        }
        log.info("批量最终产物完成: userId={}, projectId={}, episodeId={}, isSet={}, recordType={}, " +
                        "total={}, success={}, fail={}",
                userId, projectId, normalizedEpisodeId, isSet, recordType,
                items.size(), result.getSuccessIds().size(), result.getFailures().size());
        return result.summarize();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteGenRecord(DeleteGenRecordRequest request, Long userId) {
        AidStoryboard storyboard = getStoryboardWithOwnerCheck(request.getStoryboardId(), userId);

        AidGenRecord targetRecord = aidGenRecordService.getById(request.getRecordId());
        if (Objects.isNull(targetRecord)
                || !Objects.equals(DEL_FLAG_NORMAL, targetRecord.getDelFlag())
                || !Objects.equals(targetRecord.getStoryboardId(), request.getStoryboardId())) {
            log.error("生成记录不存在或不属于该分镜, recordId={}, storyboardId={}, delFlag={}",
                    request.getRecordId(), request.getStoryboardId(),
                    Objects.nonNull(targetRecord) ? targetRecord.getDelFlag() : null);
            throw new ServiceException("数据不存在");
        }
        // 防御性归属校验，避免脏数据被利用
        if (!Objects.equals(targetRecord.getUserId(), userId)) {
            log.error("生成记录归属异常, recordId={}, owner={}, requester={}",
                    request.getRecordId(), targetRecord.getUserId(), userId);
            throw new ServiceException("数据不存在");
        }

        boolean isFinalImage = Objects.equals(storyboard.getFinalImageId(), request.getRecordId());
        boolean isFinalVideo = Objects.equals(storyboard.getFinalVideoId(), request.getRecordId());
        if (isFinalImage || isFinalVideo) {
            LambdaUpdateWrapper<AidStoryboard> sbWrapper = Wrappers.lambdaUpdate();
            sbWrapper.eq(AidStoryboard::getId, storyboard.getId());
            if (isFinalImage) {
                sbWrapper.set(AidStoryboard::getFinalImageId, null); // 删的是最终图 → 置空 final_image_id
            }
            if (isFinalVideo) {
                sbWrapper.set(AidStoryboard::getFinalVideoId, null); // 删的是最终视频 → 置空 final_video_id
            }
            sbWrapper.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
            sbWrapper.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
            aidStoryboardService.update(sbWrapper);
        }

        aidGenRecordService.removeById(request.getRecordId());

        // 共享对象保护：同一文件被其他未删记录引用（同一上传复用到多分镜）时跳过物理删除；
        // 本事务内可见本次删除，计数为 0 才登记清理（cleanupFiles 内部 afterCommit 后台执行，回滚不误删）
        String fileUrl = targetRecord.getFileUrl();
        if (StrUtil.isNotBlank(fileUrl)) {
            long refCount = aidGenRecordService.count(Wrappers.<AidGenRecord>lambdaQuery()
                    .eq(AidGenRecord::getFileUrl, fileUrl)
                    .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL));
            if (refCount > 0) {
                log.info("OSS 文件仍被 {} 条记录引用，跳过物理删除, fileUrl={}", refCount, fileUrl);
            } else {
                mediaOssCleanupService.cleanupFiles(java.util.Collections.singletonList(fileUrl));
            }
        }

        log.info("物理删除分镜生成记录, recordId={}, storyboardId={}, userId={}, isFinalImage={}, isFinalVideo={}",
                request.getRecordId(), request.getStoryboardId(), userId, isFinalImage, isFinalVideo);
    }
}
