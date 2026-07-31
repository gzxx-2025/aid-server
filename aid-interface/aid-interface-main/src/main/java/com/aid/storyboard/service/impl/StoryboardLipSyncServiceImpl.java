package com.aid.storyboard.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiVoiceLibrary;
import com.aid.aid.domain.AidAudioRecord;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRoleVoiceBinding;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiVoiceLibraryService;
import com.aid.aid.service.IAidAudioRecordService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidGenRecordService;
import com.aid.aid.service.IAidMediaTaskService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidRoleVoiceBindingService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.error.TaskErrorPresentation;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.enums.CreationStepEnum;
import com.aid.enums.GenTypeEnum;
import com.aid.media.constants.MinimaxTtsConstants;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.enums.MediaType;
import com.aid.media.provider.MinimaxProviderDetector;
import com.aid.media.service.AudioSilencePaddingService;
import com.aid.media.service.IMediaGenerationService;
import com.aid.media.util.WavAudioSupport;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.resolver.StoryboardAudioReferenceResolver;
import com.aid.rps.resolver.StoryboardAudioReferenceResolver.DialogueSegment;
import com.aid.rps.resolver.StoryboardImageReferenceResolver;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.sse.AssetExtractSseManager;
import com.aid.service.IAiModelConfigService;
import com.aid.step.service.ICreationStepService;
import com.aid.storyboard.dto.GenerateAudioRequest;
import com.aid.storyboard.dto.LipSyncRequest;
import com.aid.storyboard.dto.SetFinalSelectionRequest;
import com.aid.storyboard.dto.StoryboardLipSyncBatchRequest;
import com.aid.storyboard.service.IStoryboardLipSyncService;
import com.aid.storyboard.service.IStoryboardWorkbenchService;
import com.aid.storyboard.vo.AudioTaskVO;
import com.aid.voice.util.DialogueTextSanitizer;

import org.springframework.data.redis.core.script.DefaultRedisScript;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜对口型服务实现（单个 + 批量）：台词现场 TTS 配音后与分镜视频一并提交对口型模型，
 * 全程复用统一任务与扣费流程；两种模式均为父任务受理、SSE 推进度，
 * 批量模式产物自动设为 compose 类主视频，单个模式由用户手选。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class StoryboardLipSyncServiceImpl implements IStoryboardLipSyncService {

    /** 批量父任务类型（aid_extract_task.task_type） */
    private static final String TASK_TYPE_LIP_SYNC_BATCH = "storyboard_lip_sync_generate";

    /** 单个父任务类型（aid_extract_task.task_type）：与批量隔离，避免活跃任务互相挡 */
    private static final String TASK_TYPE_LIP_SYNC_SINGLE = "storyboard_lip_sync_single";

    /** 任务状态（与其它批量任务字符串口径一致） */
    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";

    /** 编排父任务不直接计费：以 SUCCESS + 0 金额明确闭合，真实费用由 TTS/视频子任务承担。 */
    private static final String BILLING_STATUS_SUCCESS = "SUCCESS";

    /** 单批分镜上限（与批量配音同口径） */
    private static final int MAX_BATCH_SIZE = 50;

    /** 进度：配音+提交阶段区间 [5,60)，对口型等待阶段区间 [60,100) */
    private static final int PROGRESS_DUB_BASE = 5;
    private static final int PROGRESS_DUB_SPAN = 55;
    private static final int PROGRESS_LIP_SYNC_BASE = 60;
    private static final int PROGRESS_LIP_SYNC_SPAN = 40;

    /** SSE 步骤标识：配音生成 */
    private static final String STEP_ID_DUB = "dub";

    /** SSE 步骤标识：对口型合成 */
    private static final String STEP_ID_LIP_SYNC = "lipSync";

    /** SSE 步骤标题：配音生成 */
    private static final String STEP_TITLE_DUB = "配音生成";

    /** SSE 步骤标题：对口型合成 */
    private static final String STEP_TITLE_LIP_SYNC = "对口型合成";

    /** SSE 步骤总数（配音生成 + 对口型合成） */
    private static final int STEP_TOTAL = 2;

    /** setFinalSelection 的视频产物类型 */
    private static final String RECORD_TYPE_VIDEO = "video";

    /** 对口型开启标记 */
    private static final int LIP_SYNC_ENABLED = 1;

    /** 对口型媒体任务业务类型：LipSyncEventListener 按此过滤回填 */
    private static final String BIZ_TASK_TYPE_LIP_SYNC = "lip_sync_record";

    /** 对口型模型能力标识：aid_ai_model.capability_json 声明 {"lipSync":true} 的视频模型才可承接 */
    private static final String CAPABILITY_LIP_SYNC = "lipSync";

    /** 视频模型类型（aid_ai_model.model_type） */
    private static final String MODEL_TYPE_VIDEO = "video";

    /** 对口型任务存档提示词：仅用于 aid_media_task.prompt 列展示 */
    private static final String LIP_SYNC_TASK_PROMPT = "分镜对口型合成";

    /** 对口型 options 契约 key：源视频 URL（Provider 按各自协议读取） */
    private static final String OPTIONS_KEY_VIDEO_URL = "video_url";

    /** 对口型 options 契约 key：驱动音频 URL（Provider 按各自协议读取） */
    private static final String OPTIONS_KEY_AUDIO_URL = "audio_url";

    /** 对口型官方计价粒度（秒）：计费时长向上取整到该粒度 */
    private static final int LIP_SYNC_BILLING_GRANULARITY_SECONDS = 5;

    /** 驱动音频补静音阈值（毫秒）：短于该差值不补，避免为不可感知的误差多产生一份 OSS 对象 */
    private static final int LIP_SYNC_PAD_THRESHOLD_MS = 200;

    /** 毫秒 → 秒换算 */
    private static final double MS_PER_SECOND = 1000.0;

    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 状态：启用 */
    private static final String STATUS_NORMAL = "0";

    /** 分镜级对口型进行中标记前缀：覆盖「受理→TTS→提交→合成终态」全过程，单个与批量共用 */
    private static final String LIP_SYNC_RUNNING_PREFIX = "storyboard:lip_sync:running:";

    /**
     * 进行中标记安全 TTL（秒）：覆盖排队、TTS、视频长尾与重启对账。
     * 正常终态会按持有者 CAS 立即释放；TTL 只作为 Redis/数据库同时不可用时的最终保险。
     */
    private static final long LIP_SYNC_RUNNING_TTL_SECONDS = 6L * 60L * 60L;

    /** 进行中标记持有者前缀（父任务持有）：值形如 task:{taskId}，用于重复受理时幂等重连 */
    private static final String RUNNING_HOLDER_TASK_PREFIX = "task:";

    /** 进行中标记持有者前缀（受理期临时持有）：建父任务前的极短窗口 */
    private static final String RUNNING_HOLDER_PENDING_PREFIX = "pending:";

    /** 持久化工作流阶段：配音提交前。 */
    private static final String WORKFLOW_DUB_SUBMITTING = "DUB_SUBMITTING";

    /** 持久化工作流阶段：等待公共 TTS 任务终态/OSS。 */
    private static final String WORKFLOW_DUB_PROCESSING = "DUB_PROCESSING";

    /** 持久化工作流阶段：对口型视频提交前。 */
    private static final String WORKFLOW_VIDEO_SUBMITTING = "VIDEO_SUBMITTING";

    /** 持久化工作流阶段：等待公共视频任务终态/OSS。 */
    private static final String WORKFLOW_VIDEO_PROCESSING = "VIDEO_PROCESSING";

    /** 持久化工作流阶段：业务产物收尾。 */
    private static final String WORKFLOW_FINALIZING = "FINALIZING";

    /** 父任务工作流分布式锁前缀。 */
    private static final String WORKFLOW_LOCK_PREFIX = "storyboard:lip_sync:workflow:";

    /** 工作流锁等待与租期：锁内只做短 DB 操作，远程提交一律在锁外。 */
    private static final long WORKFLOW_LOCK_WAIT_SECONDS = 5L;
    private static final long WORKFLOW_LOCK_LEASE_SECONDS = 30L;

    /** 角色资产类型 */
    private static final String ASSET_TYPE_CHARACTER = "character";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Resource
    private IAidStoryboardService aidStoryboardService;

    @Resource
    private IAidExtractTaskService extractTaskService;

    @Resource
    private IAidAudioRecordService aidAudioRecordService;

    @Resource
    private IAidGenRecordService aidGenRecordService;

    @Resource
    private IAidMediaTaskService mediaTaskService;

    @Resource
    private IAidRolePropSceneService rpsService;

    @Resource
    private IAidRoleVoiceBindingService roleVoiceBindingService;

    @Resource
    private IAidAiVoiceLibraryService aidAiVoiceLibraryService;

    @Resource
    private IAidAiModelService aidAiModelService;

    /** 模型配置查询：MiniMax 归属判定第一优先级取 providerCode */
    @Resource
    private IAiModelConfigService aiModelConfigService;

    /** MiniMax 归属判定器：与单分镜配音门禁、调度层路由共用同一份三级判定 */
    @Resource
    private MinimaxProviderDetector minimaxProviderDetector;

    /** 单分镜配音链路（统一任务 + 统一计费）与产物选中（setFinalSelection） */
    @Resource
    private IStoryboardWorkbenchService storyboardWorkbenchService;

    @Resource
    private ICreationStepService creationStepService;

    @Resource
    private StoryboardAudioReferenceResolver audioReferenceResolver;

    @Resource
    private AssetExtractSseManager sseManager;

    @Resource
    private IWechatNotifyService wechatNotifyService;

    /** 统一媒体生成服务：对口型任务提交（统一任务 + 统一计费）与任务快照查询 */
    @Resource
    private IMediaGenerationService mediaGenerationService;

    /** 音频补静音服务：驱动音频短于源视频时补齐到源视频时长 */
    @Resource
    private AudioSilencePaddingService audioSilencePaddingService;

    /** 媒体 URL 解析器：DB 相对路径 → 完整可访问 URL（下游 Provider 需完整 URL） */
    @Resource
    private MediaUrlResolver mediaUrlResolver;

    /** 任务执行租约登记/心跳（僵尸回收按租约判活，PROCESSING 期间必须持有租约） */
    @Resource
    private IAssetExtractService assetExtractService;

    /** 通用线程池：承载单个与批量对口型的异步执行 */
    @Resource
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    /** Redis 缓存：分镜级对口型进行中标记（SETNX + 持有者 CAS 释放） */
    @Resource
    private RedisCache redisCache;

    /** 父任务结果快照并发更新锁：防同步事件、OSS 事件和重启对账互相覆盖。 */
    @Resource
    private RedissonClient redissonClient;

    /** 对口型执行模式：父任务类型（同时作为 SSE 阶段标识）与产物是否自动设为 compose 主视频 */
    private enum LipSyncMode {

        /** 单分镜：产物不自动选中，由用户手选 */
        SINGLE(TASK_TYPE_LIP_SYNC_SINGLE, false),

        /** 批量：产物自动设为该分镜配音视频主视频 */
        BATCH(TASK_TYPE_LIP_SYNC_BATCH, true);

        private final String taskType;

        private final boolean autoSelect;

        LipSyncMode(String taskType, boolean autoSelect) {
            this.taskType = taskType;
            this.autoSelect = autoSelect;
        }

        String taskType() {
            return taskType;
        }

        /** SSE 阶段标识与父任务类型同值，前端按 stage 区分单个/批量 */
        String sseStage() {
            return taskType;
        }

        boolean autoSelect() {
            return autoSelect;
        }
    }

    /** TTS 参数载体：单个与批量请求的配音参数口径一致，执行器只依赖本载体 */
    private record LipSyncTtsParams(Long voiceLibraryId, Long voiceModelId, String timbreCode,
                                    String emotion, Integer emotionScale, Integer speechRate,
                                    Integer loudnessRate, Integer pitch) {

        /** 是否具备兜底音色（新入参音色库 或 老入参模型+音色编码成对） */
        boolean hasFallbackVoice() {
            return Objects.nonNull(voiceLibraryId)
                    || (Objects.nonNull(voiceModelId) && StrUtil.isNotBlank(timbreCode));
        }
    }

    /** 单分镜执行上下文（配音→对口型→切换 三步的中间态） */
    private static final class ItemContext {
        /** 分镜ID */
        Long storyboardId;
        /** 分镜视频记录ID（对口型源视频） */
        Long sourceVideoRecordId;
        /** 发言角色主名（台词解析，按出现顺序去重；空=无角色标记/纯旁白） */
        List<String> speakerRoles;
        /** 该分镜实际使用的音色库ID（角色绑定优先，其次请求兜底；老入参兜底路径为 null） */
        Long voiceLibraryId;
        /** 配音记录ID */
        Long audioRecordId;
        /** TTS 媒体任务ID（aid_media_task.id） */
        Long audioMediaTaskId;
        /** 配音音频 URL（相对路径） */
        String audioUrl;
        /** 配音时长（毫秒） */
        Integer audioDurationMs;
        /** 对口型媒体任务ID（aid_media_task.id） */
        Long lipSyncMediaTaskId;
        /** 持久化工作流阶段 */
        String workflowStage;
        /** 对口型视频生成记录ID（aid_gen_record.id，genType=compose） */
        Long lipSyncVideoRecordId;
        /** 对口型视频 URL（相对路径） */
        String lipSyncVideoUrl;
        /** 终态：SUCCEEDED / FAILED；执行中为 null */
        String status;
        /** 失败原因短文案 */
        String errorMessage;

        boolean finished() {
            return Objects.nonNull(status);
        }
    }

    @Override
    public AssetExtractTaskVO lipSync(LipSyncRequest request, Long userId) {
        // 前置快校验（零 I/O）：兜底音色老入参必须成对，前端乱传直接拒绝
        assertFallbackVoicePaired(request.getVoiceLibraryId(), request.getVoiceModelId(), request.getTimbreCode());
        AidStoryboard storyboard = getStoryboardWithOwnerCheck(request.getStoryboardId(), userId);
        // 步骤校验：对口型需要步骤6已解锁
        creationStepService.checkStepUnlocked(storyboard.getProjectId(), storyboard.getEpisodeId(), userId,
                CreationStepEnum.AUDIO.getValue());

        // 台词校验：对口型的驱动音频来自台词现场 TTS，无可朗读台词直接拒绝
        String sanitized = DialogueTextSanitizer.sanitize(storyboard.getDialogueText());
        if (StrUtil.isBlank(sanitized)) {
            log.info("对口型分镜无可朗读台词, storyboardId={}", storyboard.getId());
            throw new ServiceException("暂无台词");
        }

        // 分镜视频（final_video_id 指向的配音前原视频）：存在、归属、文件、时长齐全
        AidGenRecord videoRecord = loadStoryboardVideo(storyboard, userId);

        List<AidStoryboard> targets = Collections.singletonList(storyboard);
        LipSyncTtsParams tts = toTtsParams(request);

        // 音色解析：台词角色绑定音色优先 → 请求兜底音色 → 双空拒绝
        Map<Long, Long> voiceByStoryboardId = resolveVoiceBindings(targets,
                storyboard.getProjectId(), storyboard.getEpisodeId(), userId);
        if (!voiceByStoryboardId.containsKey(storyboard.getId()) && !tts.hasFallbackVoice()) {
            log.info("对口型分镜未绑定音色且无兜底音色, storyboardId={}", storyboard.getId());
            throw new ServiceException("请先绑定音色");
        }
        // 音色可用性与 MiniMax 文本上限：与批量同口径，建任务之前拦截
        validateVoicesUsable(voiceByStoryboardId, tts.voiceLibraryId(), tts.voiceModelId());
        validateSingleSpeaker(storyboard);
        validateMinimaxTextLimit(targets, voiceByStoryboardId, tts.voiceLibraryId(), tts.voiceModelId());

        // 对口型模型前置解析：未配置时在建任务之前拒绝，避免白扣配音费用
        AidAiModel lipSyncModel = resolveLipSyncModel();

        // 分镜级进行中标记：占位失败说明该分镜已有在跑的对口型任务，幂等返回其 taskId 供前端重连 SSE
        String pendingHolder = RUNNING_HOLDER_PENDING_PREFIX + IdUtil.fastSimpleUUID();
        if (!tryMarkStoryboardRunning(storyboard.getId(), pendingHolder)) {
            AssetExtractTaskVO reconnect = resolveRunningTaskForReconnect(storyboard.getId(), userId);
            if (Objects.nonNull(reconnect)) {
                log.info("单个对口型重连活跃任务: taskId={}, storyboardId={}",
                        reconnect.getTaskId(), storyboard.getId());
                return reconnect;
            }
            // 上一步已清理终态任务的残留标记，再占一次；仍失败说明确有并发受理在跑
            if (!tryMarkStoryboardRunning(storyboard.getId(), pendingHolder)) {
                log.info("对口型进行中标记占位失败, storyboardId={}", storyboard.getId());
                throw new ServiceException("对口型进行中");
            }
        }

        Long taskId = null;
        try {
            // 防重兜底（Redis 标记丢失场景）：该分镜最新对口型任务仍在进行中时拒绝重复提交
            assertNoRunningLipSync(storyboard.getId(), userId);

            Map<Long, AidGenRecord> sourceVideoByStoryboardId = new HashMap<>();
            sourceVideoByStoryboardId.put(storyboard.getId(), videoRecord);

            // 创建父任务（SSE 锚点）；配音与对口型的计费随各自统一任务冻结/结算，父级不另设扣费
            taskId = createParentTask(LipSyncMode.SINGLE, storyboard.getProjectId(), storyboard.getEpisodeId(),
                    userId, lipSyncModel, targets, tts, false, voiceByStoryboardId, sourceVideoByStoryboardId);
            // 标记改由父任务持有：重复受理时可据此幂等重连
            bindStoryboardRunningToTask(storyboard.getId(), taskId);

            Long dispatchTaskId = taskId;
            threadPoolTaskExecutor.execute(() -> executeLipSync(dispatchTaskId, LipSyncMode.SINGLE, tts, targets,
                    voiceByStoryboardId, sourceVideoByStoryboardId, lipSyncModel, userId));
        } catch (ServiceException se) {
            releaseStoryboardRunning(storyboard.getId(), taskId, pendingHolder);
            if (Objects.nonNull(taskId)) {
                failTask(taskId, TaskErrorPresentation.toUserMessage(
                        StrUtil.blankToDefault(se.getDetailMessage(), se.getMessage()), "对口型失败"));
            }
            throw se;
        } catch (Exception ex) {
            // 线程池拒绝 / 建任务异常：释放标记并把已建父任务置失败，避免永久 PENDING
            log.error("单个对口型派发失败, storyboardId={}, taskId={}", storyboard.getId(), taskId, ex);
            releaseStoryboardRunning(storyboard.getId(), taskId, pendingHolder);
            if (Objects.nonNull(taskId)) {
                failTask(taskId, "提交失败，请重试");
            }
            throw new ServiceException("提交失败，请重试");
        }

        log.info("单个对口型受理: taskId={}, storyboardId={}", taskId, storyboard.getId());
        return AssetExtractTaskVO.builder()
                .taskId(taskId)
                .status(TASK_STATUS_PENDING)
                .totalCount(targets.size())
                .build();
    }

    /** 请求 TTS 参数 → 执行器载体（单个入口） */
    private LipSyncTtsParams toTtsParams(LipSyncRequest request) {
        return new LipSyncTtsParams(request.getVoiceLibraryId(), request.getVoiceModelId(),
                request.getTimbreCode(), request.getEmotion(), request.getEmotionScale(),
                request.getSpeechRate(), request.getLoudnessRate(), request.getPitch());
    }

    /** 请求 TTS 参数 → 执行器载体（批量入口） */
    private LipSyncTtsParams toTtsParams(StoryboardLipSyncBatchRequest request) {
        return new LipSyncTtsParams(request.getVoiceLibraryId(), request.getVoiceModelId(),
                request.getTimbreCode(), request.getEmotion(), request.getEmotionScale(),
                request.getSpeechRate(), request.getLoudnessRate(), request.getPitch());
    }

    // ==================== 分镜级进行中标记 ====================

    /**
     * 抢占分镜级对口型进行中标记（SETNX，带 TTL 防死锁）。
     * Redis 不可用时直接拒绝：防重失效下放行会导致同分镜多任务多扣费。
     *
     * @param storyboardId 分镜ID
     * @param holder       持有者标识（释放时 CAS 校验，防误删他人标记）
     * @return true=抢占成功
     */
    private boolean tryMarkStoryboardRunning(Long storyboardId, String holder) {
        try {
            Boolean ok = redisCache.redisTemplate.opsForValue()
                    .setIfAbsent(LIP_SYNC_RUNNING_PREFIX + storyboardId, holder,
                            LIP_SYNC_RUNNING_TTL_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.error("对口型进行中标记占位失败, storyboardId={}", storyboardId, e);
            throw new ServiceException("提交失败，请重试");
        }
    }

    /**
     * 抢占分镜级标记；已被本父任务持有（单个模式受理期已占位）时同样视为持有成功。
     *
     * @param storyboardId 分镜ID
     * @param taskId       父任务ID
     * @return true=本任务持有该标记
     */
    private boolean acquireStoryboardRunning(Long storyboardId, Long taskId) {
        String holder = RUNNING_HOLDER_TASK_PREFIX + taskId;
        if (tryMarkStoryboardRunning(storyboardId, holder)) {
            return true;
        }
        return Objects.equals(holder, getStoryboardRunningHolder(storyboardId));
    }

    /** 读取分镜级标记的当前持有者标识；无标记或 Redis 异常返回 null。 */
    private String getStoryboardRunningHolder(Long storyboardId) {
        try {
            Object holder = redisCache.redisTemplate.opsForValue().get(LIP_SYNC_RUNNING_PREFIX + storyboardId);
            return Objects.isNull(holder) ? null : String.valueOf(holder);
        } catch (Exception e) {
            log.warn("对口型进行中标记读取失败, storyboardId={}, msg={}", storyboardId, e.getMessage());
            return null;
        }
    }

    /**
     * 标记持有者移交给父任务（受理成功后），并重置 TTL 覆盖完整合成时长。
     * 移交失败则受理失败：执行器按 task 持有者判活，标记停留在临时持有会让本任务立即单条失败。
     */
    private void bindStoryboardRunningToTask(Long storyboardId, Long taskId) {
        try {
            redisCache.redisTemplate.opsForValue().set(LIP_SYNC_RUNNING_PREFIX + storyboardId,
                    RUNNING_HOLDER_TASK_PREFIX + taskId, LIP_SYNC_RUNNING_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("对口型进行中标记移交失败, storyboardId={}, taskId={}", storyboardId, taskId, e);
            throw new ServiceException("提交失败，请重试");
        }
    }

    /**
     * 释放分镜级标记：持有者一致才删除（Lua CAS），标记已过期被他人持有时不误删。
     * 受理链路可能处于「临时持有」或「已移交父任务」两种状态，两个候选值都尝试。
     *
     * @param storyboardId  分镜ID
     * @param taskId        父任务ID（可空：尚未建任务）
     * @param pendingHolder 受理期临时持有标识（可空）
     */
    private void releaseStoryboardRunning(Long storyboardId, Long taskId, String pendingHolder) {
        if (Objects.nonNull(taskId)) {
            releaseStoryboardRunningByHolder(storyboardId, RUNNING_HOLDER_TASK_PREFIX + taskId);
        }
        if (StrUtil.isNotBlank(pendingHolder)) {
            releaseStoryboardRunningByHolder(storyboardId, pendingHolder);
        }
    }

    /** 按持有者标识 CAS 删除分镜级标记。 */
    private void releaseStoryboardRunningByHolder(Long storyboardId, String holder) {
        try {
            String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
            redisCache.redisTemplate.execute(
                    new DefaultRedisScript<>(script, Long.class),
                    Collections.singletonList(LIP_SYNC_RUNNING_PREFIX + storyboardId), holder);
        } catch (Exception e) {
            log.warn("对口型进行中标记释放失败, storyboardId={}, msg={}", storyboardId, e.getMessage());
        }
    }

    /**
     * 标记占位失败时解析可重连的父任务：标记由父任务持有且该任务归属本人、仍非终态时返回其受理VO；
     * 任务已终态（标记未及时释放的残留）则清理标记后返回 null，由调用方重新占位。
     *
     * @param storyboardId 分镜ID
     * @param userId       当前用户ID
     * @return 可重连的父任务VO；无则 null
     */
    private AssetExtractTaskVO resolveRunningTaskForReconnect(Long storyboardId, Long userId) {
        String holder = getStoryboardRunningHolder(storyboardId);
        if (StrUtil.isBlank(holder) || !holder.startsWith(RUNNING_HOLDER_TASK_PREFIX)) {
            return null;
        }
        Long runningTaskId;
        try {
            runningTaskId = Long.valueOf(holder.substring(RUNNING_HOLDER_TASK_PREFIX.length()));
        } catch (NumberFormatException e) {
            log.warn("对口型进行中标记持有者异常, storyboardId={}, holder={}", storyboardId, holder);
            return null;
        }
        // 查询字段精简：重连只需任务状态与总数
        AidExtractTask task = extractTaskService.getOne(Wrappers.<AidExtractTask>lambdaQuery()
                .select(AidExtractTask::getId, AidExtractTask::getStatus,
                        AidExtractTask::getTotalCount, AidExtractTask::getUserId)
                .eq(AidExtractTask::getId, runningTaskId)
                .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)
                .last("LIMIT 1"), false);
        if (Objects.isNull(task) || !Objects.equals(userId, task.getUserId())) {
            return null;
        }
        boolean running = TASK_STATUS_PENDING.equals(task.getStatus())
                || TASK_STATUS_PROCESSING.equals(task.getStatus());
        if (!running) {
            // 任务已终态但标记残留：清理后放行新任务
            log.warn("对口型进行中标记残留已清理, storyboardId={}, taskId={}, status={}",
                    storyboardId, runningTaskId, task.getStatus());
            releaseStoryboardRunningByHolder(storyboardId, holder);
            return null;
        }
        return AssetExtractTaskVO.builder()
                .taskId(task.getId())
                .status(task.getStatus())
                .totalCount(task.getTotalCount())
                .build();
    }

    @Override
    public AssetExtractTaskVO batchLipSync(StoryboardLipSyncBatchRequest request, Long userId) {
        if (Objects.isNull(request) || Objects.isNull(request.getProjectId())
                || Objects.isNull(request.getEpisodeId()) || Objects.isNull(userId)) {
            log.info("批量对口型入参缺失, userId={}", userId);
            throw new ServiceException("参数错误");
        }
        Long projectId = request.getProjectId();
        Long episodeId = request.getEpisodeId();

        // 前置快校验（零 I/O）：兜底音色老入参必须成对，前端乱传直接拒绝
        assertFallbackVoicePaired(request.getVoiceLibraryId(), request.getVoiceModelId(), request.getTimbreCode());

        // 步骤校验：对口型需步骤6已解锁
        creationStepService.checkStepUnlocked(projectId, episodeId, userId, CreationStepEnum.AUDIO.getValue());

        // 防重：同项目+剧集已有活跃批量对口型任务时幂等返回，前端重连 SSE。
        // 父任务是否失活由统一租约 + 子任务对账判定，业务层不得按本地静默时间直接宣告失败。
        AidExtractTask active = findActiveTask(projectId, episodeId, userId);
        if (Objects.nonNull(active)) {
            log.info("批量对口型重连活跃任务: taskId={}, projectId={}, episodeId={}",
                    active.getId(), projectId, episodeId);
            return AssetExtractTaskVO.builder()
                    .taskId(active.getId())
                    .status(active.getStatus())
                    .totalCount(active.getTotalCount())
                    .build();
        }

        // 加载目标分镜：仅「有台词」的分镜（清洗后非空）
        List<AidStoryboard> targets = loadDialogueStoryboards(projectId, episodeId,
                request.getStoryboardIds(), userId);
        if (CollectionUtil.isEmpty(targets)) {
            log.info("批量对口型无可处理分镜, projectId={}, episodeId={}", projectId, episodeId);
            throw new ServiceException("无可对口型分镜");
        }

        // overwrite=false：已有对口型产物（sync_video_url 非空）的分镜跳过；true=重做（只新增不覆盖）
        if (!Boolean.TRUE.equals(request.getOverwrite())) {
            Set<Long> lipSyncedIds = loadLipSyncedStoryboardIds(targets, userId);
            targets = targets.stream()
                    .filter(s -> !lipSyncedIds.contains(s.getId()))
                    .collect(Collectors.toList());
            if (CollectionUtil.isEmpty(targets)) {
                log.info("批量对口型全部分镜已完成(overwrite=false), projectId={}, episodeId={}",
                        projectId, episodeId);
                throw new ServiceException("已全部对口型");
            }
        }
        if (targets.size() > MAX_BATCH_SIZE) {
            log.info("批量对口型超上限, size={}, max={}", targets.size(), MAX_BATCH_SIZE);
            throw new ServiceException("批量过多");
        }

        // 前置校验分镜视频（对口型源视频，恒取原视频轨）：存在、归属本人、文件已生成、时长已回填
        Map<Long, AidGenRecord> finalVideoByStoryboardId = loadAndValidateFinalVideos(targets, userId);

        // 逐分镜解析音色：角色绑定优先 → 请求兜底；双空整批拒绝（前置校验，不产生任务记录）
        Map<Long, Long> voiceByStoryboardId = resolveVoiceBindings(targets, projectId, episodeId, userId);
        boolean hasFallback = Objects.nonNull(request.getVoiceLibraryId())
                || (Objects.nonNull(request.getVoiceModelId()) && StrUtil.isNotBlank(request.getTimbreCode()));
        List<Long> unresolved = targets.stream()
                .map(AidStoryboard::getId)
                .filter(id -> !voiceByStoryboardId.containsKey(id))
                .collect(Collectors.toList());
        if (!hasFallback && CollectionUtil.isNotEmpty(unresolved)) {
            log.info("批量对口型存在未绑定音色的分镜, projectId={}, episodeId={}, unresolved={}",
                    projectId, episodeId, unresolved);
            throw new ServiceException("请先绑定音色");
        }

        // 前置校验本批音色可用性（建任务之前）：绑定音色 + 兜底音色须启用未删未下架、所属模型启用
        LipSyncTtsParams tts = toTtsParams(request);
        validateVoicesUsable(voiceByStoryboardId, tts.voiceLibraryId(), tts.voiceModelId());

        for (AidStoryboard target : targets) {
            validateSingleSpeaker(target);
        }
        // MiniMax 音色单条文本上限：与单个同口径，超限整批拒绝
        validateMinimaxTextLimit(targets, voiceByStoryboardId, tts.voiceLibraryId(), tts.voiceModelId());

        // 对口型模型前置解析：未配置整批拒绝；父任务 model_code 记对口型模型编码
        AidAiModel lipSyncModel = resolveLipSyncModel();

        // 创建父任务（SSE / 微信推送锚点）；配音与对口型的计费随各自统一任务逐条冻结/结算，父级不另设扣费
        Long taskId = createParentTask(LipSyncMode.BATCH, projectId, episodeId, userId, lipSyncModel,
                targets, tts, Boolean.TRUE.equals(request.getOverwrite()),
                voiceByStoryboardId, finalVideoByStoryboardId);

        List<AidStoryboard> finalTargets = targets;
        try {
            threadPoolTaskExecutor.execute(() -> executeLipSync(taskId, LipSyncMode.BATCH, tts, finalTargets,
                    voiceByStoryboardId, finalVideoByStoryboardId, lipSyncModel, userId));
        } catch (Exception rejectEx) {
            // 线程池拒绝：父任务置失败，避免永久 PENDING
            log.error("批量对口型派发被拒绝, taskId={}", taskId, rejectEx);
            failTask(taskId, "提交失败，请重试");
            throw new ServiceException("提交失败，请重试");
        }

        log.info("批量对口型受理: taskId={}, projectId={}, episodeId={}, shots={}",
                taskId, projectId, episodeId, targets.size());
        return AssetExtractTaskVO.builder()
                .taskId(taskId)
                .status(TASK_STATUS_PENDING)
                .totalCount(targets.size())
                .build();
    }

    /**
     * 创建对口型父任务（SSE / 微信推送锚点，单个与批量共用）。
     *
     * @param mode                     执行模式（决定 task_type）
     * @param projectId                项目ID
     * @param episodeId                剧集ID
     * @param userId                   用户ID
     * @param lipSyncModel             对口型模型（记入 model_code）
     * @param targets                  目标分镜
     * @param tts                      TTS 参数
     * @param overwrite                是否重做（仅批量有意义）
     * @param voiceByStoryboardId      分镜 → 绑定音色
     * @param sourceVideoByStoryboardId 分镜 → 源视频记录
     * @return 父任务ID
     */
    private Long createParentTask(LipSyncMode mode, Long projectId, Long episodeId, Long userId,
                                  AidAiModel lipSyncModel, List<AidStoryboard> targets, LipSyncTtsParams tts,
                                  boolean overwrite, Map<Long, Long> voiceByStoryboardId,
                                  Map<Long, AidGenRecord> sourceVideoByStoryboardId) {
        AidExtractTask task = new AidExtractTask();
        task.setProjectId(projectId);
        task.setEpisodeId(episodeId);
        task.setUserId(userId);
        task.setTaskType(mode.taskType());
        task.setStatus(TASK_STATUS_PENDING);
        task.setModelCode(lipSyncModel.getModelCode());
        task.setTotalCount(targets.size());
        task.setBillingStatus(BILLING_STATUS_SUCCESS);
        task.setFrozenAmount(java.math.BigDecimal.ZERO);
        task.setActualCost(java.math.BigDecimal.ZERO);
        task.setInputSnapshot(buildInputSnapshot(projectId, episodeId, targets, tts, overwrite,
                voiceByStoryboardId, sourceVideoByStoryboardId));
        task.setResultData(serializeItems(buildInitialItems(
                targets, tts, voiceByStoryboardId, sourceVideoByStoryboardId)));
        task.setDelFlag(DEL_FLAG_NORMAL);
        task.setCreateTime(DateUtils.getNowDate());
        task.setCreateBy(String.valueOf(userId));
        task.setUpdateTime(DateUtils.getNowDate());
        task.setUpdateBy(String.valueOf(userId));
        extractTaskService.save(task);
        return task.getId();
    }

    /** 建父任务时同步落完整工作流骨架，消除“父任务已存在但 result_data 为空”的崩溃窗口。 */
    private List<ItemContext> buildInitialItems(List<AidStoryboard> targets, LipSyncTtsParams tts,
                                                Map<Long, Long> voiceByStoryboardId,
                                                Map<Long, AidGenRecord> sourceVideoByStoryboardId) {
        List<ItemContext> items = new ArrayList<>(targets.size());
        for (AidStoryboard storyboard : targets) {
            ItemContext item = new ItemContext();
            item.storyboardId = storyboard.getId();
            AidGenRecord sourceVideo = sourceVideoByStoryboardId.get(storyboard.getId());
            item.sourceVideoRecordId = Objects.isNull(sourceVideo) ? null : sourceVideo.getId();
            item.speakerRoles = resolveSpeakerRoles(storyboard.getDialogueText());
            Long boundVoice = voiceByStoryboardId.get(storyboard.getId());
            item.voiceLibraryId = Objects.nonNull(boundVoice) ? boundVoice : tts.voiceLibraryId();
            item.workflowStage = WORKFLOW_DUB_SUBMITTING;
            items.add(item);
        }
        return items;
    }

    /**
     * 异步执行（单个与批量共用）：只负责持久化明细并提交公共 TTS 子任务。
     * 后续「TTS 终态 → 对口型视频提交 → 视频终态 → 父任务汇总」全部由统一媒体事件推进；
     * 本线程不轮询、不睡眠，也不以本地等待时长判定媒体任务失败。
     */
    private void executeLipSync(Long taskId, LipSyncMode mode, LipSyncTtsParams tts, List<AidStoryboard> targets,
                                Map<Long, Long> voiceByStoryboardId,
                                Map<Long, AidGenRecord> sourceVideoByStoryboardId,
                                AidAiModel lipSyncModel, Long userId) {
        // PENDING → PROCESSING（CAS，防重复执行）
        boolean started = extractTaskService.update(Wrappers.<AidExtractTask>lambdaUpdate()
                .eq(AidExtractTask::getId, taskId)
                .eq(AidExtractTask::getStatus, TASK_STATUS_PENDING)
                .set(AidExtractTask::getStatus, TASK_STATUS_PROCESSING)
                .set(AidExtractTask::getUpdateTime, DateUtils.getNowDate())
                .set(AidExtractTask::getUpdateBy, "system"));
        if (!started) {
            log.info("对口型任务状态已变化，跳过执行: taskId={}", taskId);
            // 受理阶段占位的标记必须释放，否则该分镜到 TTL 前都无法再发起
            releaseTargetRunningMarks(targets, taskId);
            return;
        }
        int total = targets.size();
        List<ItemContext> items = new ArrayList<>(total);
        // 初始提交阶段由父任务心跳判活；线程退出后由 aid_media_task.parent_task_id 关联的子任务续租。
        assetExtractService.markTaskProcessing(taskId);
        try {
            // 先把全部明细持久化，再提交任何子任务，保证同步 Provider 在调用栈内发事件时也能恢复上下文。
            for (int i = 0; i < total; i++) {
                AidStoryboard storyboard = targets.get(i);
                ItemContext ctx = new ItemContext();
                ctx.storyboardId = storyboard.getId();
                AidGenRecord sourceVideo = sourceVideoByStoryboardId.get(storyboard.getId());
                ctx.sourceVideoRecordId = Objects.isNull(sourceVideo) ? null : sourceVideo.getId();
                ctx.speakerRoles = resolveSpeakerRoles(storyboard.getDialogueText());
                Long boundVoice = voiceByStoryboardId.get(storyboard.getId());
                ctx.voiceLibraryId = Objects.nonNull(boundVoice) ? boundVoice : tts.voiceLibraryId();
                items.add(ctx);
                try {
                    if (!acquireStoryboardRunning(storyboard.getId(), taskId)) {
                        log.info("对口型分镜标记被占用: taskId={}, storyboardId={}", taskId, storyboard.getId());
                        throw new ServiceException("对口型进行中");
                    }
                    assertNoRunningLipSync(storyboard.getId(), userId);
                    ctx.workflowStage = WORKFLOW_DUB_SUBMITTING;
                } catch (Exception e) {
                    String rawReason = e instanceof ServiceException serviceException
                            ? StrUtil.blankToDefault(serviceException.getDetailMessage(), serviceException.getMessage())
                            : e.getMessage();
                    String reason = TaskErrorPresentation.toUserMessage(rawReason, "对口型失败");
                    ctx.status = TASK_STATUS_FAILED;
                    ctx.errorMessage = reason;
                    log.error("对口型单条失败: taskId={}, storyboardId={}, error={}",
                            taskId, storyboard.getId(), rawReason, e);
                }
            }
            writeResultData(taskId, TASK_STATUS_PROCESSING, items, null);

            // 逐条提交 TTS；同一用户的统一计费锁仍按现有机制串行，媒体任务本身可异步执行。
            for (int i = 0; i < total; i++) {
                ItemContext ctx = items.get(i);
                if (ctx.finished()) {
                    releaseStoryboardRunningByHolder(ctx.storyboardId, RUNNING_HOLDER_TASK_PREFIX + taskId);
                    continue;
                }
                AidStoryboard storyboard = targets.get(i);
                Long boundVoice = voiceByStoryboardId.get(storyboard.getId());
                try {
                    AidAudioRecord audioRecord = submitDubForLipSync(
                            taskId, storyboard, boundVoice, tts, userId);
                    recordAudioSubmission(taskId, storyboard.getId(), audioRecord);
                    if (Objects.nonNull(audioRecord.getTtsMediaTaskId())) {
                        // 同步 Provider 的事件可能先于 generateAudio 返回；显式重放一次，幂等补齐所有时序。
                        onChildMediaTaskChanged(audioRecord.getTtsMediaTaskId());
                    }
                } catch (Exception e) {
                    String rawReason = e instanceof ServiceException serviceException
                            ? StrUtil.blankToDefault(serviceException.getDetailMessage(), serviceException.getMessage())
                            : e.getMessage();
                    markItemFailed(taskId, storyboard.getId(),
                            TaskErrorPresentation.toUserMessage(rawReason, "配音失败"));
                    log.error("对口型配音子任务提交失败: taskId={}, storyboardId={}",
                            taskId, storyboard.getId(), e);
                }
            }
            finalizeParentIfComplete(taskId);
        } catch (Exception ex) {
            // 未提交明细按编排中断失败；已落媒体子任务继续由公共调度和重启对账收尾。
            log.error("对口型执行异常: taskId={}", taskId, ex);
            failUnsubmittedItems(taskId, "提交中断");
            finalizeParentIfComplete(taskId);
        } finally {
            // 初始提交线程退出后停止本地心跳；在途媒体子任务由公共调度中心按 parent_task_id 续租。
            assetExtractService.deactivateTaskProcessingHeartbeat(taskId);
        }
    }

    /**
     * 推送配音阶段进度：单个模式带配音记录字段（前端据此播放配音，断线重连从 Redis 快照补发）；
     * 批量模式沿用百分比 + 计数文案。
     *
     * @param taskId 父任务ID
     * @param mode   执行模式
     * @param ctx    当前分镜执行上下文
     * @param index  当前分镜下标（0-based）
     * @param total  分镜总数
     */
    private void sendDubProgress(Long taskId, LipSyncMode mode, ItemContext ctx, int index, int total) {
        int progress = PROGRESS_DUB_BASE + (index + 1) * PROGRESS_DUB_SPAN / total;
        if (LipSyncMode.SINGLE == mode && Objects.nonNull(ctx.audioRecordId)) {
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("storyboardId", ctx.storyboardId);
            extras.put("audioRecordId", ctx.audioRecordId);
            extras.put("audioUrl", ctx.audioUrl);
            extras.put("durationMs", ctx.audioDurationMs);
            sseManager.sendStepProgressWithData(taskId, mode.sseStage(), progress,
                    STEP_ID_DUB, STEP_TITLE_DUB, 1, STEP_TOTAL, extras);
            return;
        }
        if (LipSyncMode.SINGLE == mode) {
            sseManager.sendStepProgress(taskId, mode.sseStage(), progress,
                    STEP_ID_DUB, STEP_TITLE_DUB, 1, STEP_TOTAL);
            return;
        }
        sseManager.sendProgress(taskId, mode.sseStage(), progress,
                String.format("配音中 %d/%d", index + 1, total));
    }

    /** 释放本任务在受理阶段占位的分镜级标记（执行器未真正启动时的退出路径）。 */
    private void releaseTargetRunningMarks(List<AidStoryboard> targets, Long taskId) {
        for (AidStoryboard storyboard : targets) {
            releaseStoryboardRunningByHolder(storyboard.getId(), RUNNING_HOLDER_TASK_PREFIX + taskId);
        }
    }

    /**
     * 台词现场 TTS 配音：复用单分镜配音链路（统一任务 + 统一计费），提交后立即返回业务记录。
     * 音频终态和 OSS 就绪由公共媒体事件推进，不在业务线程内轮询等待。
     * 台词原文直传，generateAudio 入口统一做台词标记清洗（剥 [角色_形象]：/@音频N/竖线等，仅保留可朗读正文）。
     *
     * @param storyboard          分镜（含台词）
     * @param boundVoiceLibraryId 角色绑定音色（可空，空则用请求兜底音色）
     * @param tts                 请求 TTS 参数（兜底音色 + 情感语速等）
     * @param userId              用户ID
     * @return 已提交的配音记录
     */
    private AidAudioRecord submitDubForLipSync(Long parentTaskId, AidStoryboard storyboard,
                                               Long boundVoiceLibraryId,
                                               LipSyncTtsParams tts, Long userId) {
        GenerateAudioRequest single = new GenerateAudioRequest();
        single.setStoryboardId(storyboard.getId());
        single.setTtsText(storyboard.getDialogueText());
        if (Objects.nonNull(boundVoiceLibraryId)) {
            single.setVoiceLibraryId(boundVoiceLibraryId);
        } else if (Objects.nonNull(tts.voiceLibraryId())) {
            single.setVoiceLibraryId(tts.voiceLibraryId());
        } else {
            // 兼容老入参兜底（前置校验已保证 voiceModelId+timbreCode 成对存在）
            single.setVoiceModelId(tts.voiceModelId());
            single.setTimbreCode(tts.timbreCode());
        }
        single.setEmotion(tts.emotion());
        single.setEmotionScale(tts.emotionScale());
        single.setSpeechRate(tts.speechRate());
        single.setLoudnessRate(tts.loudnessRate());
        single.setPitch(tts.pitch());
        // 驱动音频固定 wav：对口型成片长度跟随音频，短于源视频时需在提交前补静音对齐，wav 是可无损追加静音的容器
        single.setAudioFormat(WavAudioSupport.FORMAT_WAV);
        AudioTaskVO vo = storyboardWorkbenchService.generateAudioForParent(single, userId, parentTaskId);
        if (Objects.isNull(vo) || Objects.isNull(vo.getId())) {
            log.error("对口型 TTS 无返回, storyboardId={}", storyboard.getId());
            throw new ServiceException("配音失败");
        }
        AidAudioRecord record = aidAudioRecordService.getById(vo.getId());
        if (Objects.isNull(record)) {
            log.error("对口型 TTS 业务记录缺失, storyboardId={}, audioRecordId={}", storyboard.getId(), vo.getId());
            throw new ServiceException("配音失败");
        }
        return record;
    }

    /**
     * 提交对口型任务（统一媒体任务 + 统一计费，SKU 按秒预冻结、失败自动退款），并回写配音记录关联。
     * 成功结果由既有 LipSyncEventListener 回填 sync_video_url 并落 compose 生成记录。
     *
     * @return 对口型媒体任务ID（aid_media_task.id）
     */
    private Long submitLipSyncTask(Long parentTaskId, AidStoryboard storyboard, AidGenRecord videoRecord,
                                   AidAudioRecord audioRecord, AidAiModel lipSyncModel, Long userId) {
        // DB 存相对路径，下游 provider 需完整可访问 URL
        String videoUrl = mediaUrlResolver.toFullUrl(videoRecord.getFileUrl());
        // 驱动音频：短于源视频时补静音对齐，避免成片被截到台词长度
        LipSyncDrivingAudio driving = resolveDrivingAudio(videoRecord, audioRecord);

        MediaVideoGenerateRequest mediaReq = new MediaVideoGenerateRequest();
        mediaReq.setUserId(userId);
        mediaReq.setProjectId(storyboard.getProjectId());
        mediaReq.setEpisodeId(storyboard.getEpisodeId());
        mediaReq.setModelName(lipSyncModel.getModelCode());
        mediaReq.setPrompt(LIP_SYNC_TASK_PROMPT);
        // 计费时长（秒）：成片长度跟随驱动音频，故以实际提交的驱动音频时长为准
        mediaReq.setDurationSeconds(resolveLipSyncDurationSeconds(videoRecord, driving.durationMs()));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put(OPTIONS_KEY_VIDEO_URL, videoUrl);
        options.put(OPTIONS_KEY_AUDIO_URL, driving.url());
        mediaReq.setOptions(options);
        // 业务任务关联：LipSyncEventListener 按 biz_task_type + biz_task_id 回填结果
        mediaReq.setBizTaskId(audioRecord.getId());
        mediaReq.setBizTaskType(BIZ_TASK_TYPE_LIP_SYNC);
        mediaReq.setParentTaskId(parentTaskId);

        MediaTaskResponse mediaResp;
        try {
            mediaResp = mediaGenerationService.generateVideo(mediaReq);
        } catch (ServiceException se) {
            // 业务短文案（余额不足/并发超限等）原样透出
            throw se;
        } catch (Exception ex) {
            log.error("对口型任务提交失败, audioRecordId={}", audioRecord.getId(), ex);
            throw new ServiceException("对口型失败，请重试");
        }

        // 回写业务记录：标记已开启对口型 + 关联统一任务ID；成功结果由事件监听回填
        audioRecord.setEnableLipSync(LIP_SYNC_ENABLED);
        audioRecord.setSyncMediaTaskId(mediaResp.getTaskId());
        if (MediaTaskStatus.SUCCEEDED.name().equals(mediaResp.getStatus())
                && StrUtil.isNotBlank(mediaResp.getOssUrl())) {
            // 同步成功（幂等命中历史成功任务等场景）：直接回填对口型视频 URL
            audioRecord.setSyncVideoUrl(mediaResp.getOssUrl());
        }
        audioRecord.setUpdateTime(DateUtils.getNowDate());
        audioRecord.setUpdateBy(String.valueOf(userId));
        aidAudioRecordService.updateById(audioRecord);
        return mediaResp.getTaskId();
    }

    // ==================== 持久化事件编排 ====================

    @Override
    public void onChildMediaTaskChanged(Long mediaTaskId) {
        if (Objects.isNull(mediaTaskId)) {
            return;
        }
        // 查询字段精简：事件推进只读取父子关联、终态、结果和错误展示字段。
        AidMediaTask mediaTask = mediaTaskService.getOne(Wrappers.<AidMediaTask>lambdaQuery()
                .select(AidMediaTask::getId, AidMediaTask::getParentTaskId,
                        AidMediaTask::getMediaType, AidMediaTask::getBizTaskId,
                        AidMediaTask::getBizTaskType, AidMediaTask::getStatus,
                        AidMediaTask::getOssUrl, AidMediaTask::getModelName,
                        AidMediaTask::getErrorMessage)
                .eq(AidMediaTask::getId, mediaTaskId)
                .last("LIMIT 1"), false);
        if (Objects.isNull(mediaTask) || Objects.isNull(mediaTask.getParentTaskId())) {
            return;
        }
        AidExtractTask parent = loadWorkflowParent(mediaTask.getParentTaskId());
        if (!isActiveLipSyncParent(parent)) {
            return;
        }
        if (Objects.equals(MediaType.AUDIO.name(), mediaTask.getMediaType())
                && Objects.equals("audio_record", mediaTask.getBizTaskType())) {
            advanceAudioTask(parent, mediaTask);
            return;
        }
        if (Objects.equals(MediaType.VIDEO.name(), mediaTask.getMediaType())
                && Objects.equals(BIZ_TASK_TYPE_LIP_SYNC, mediaTask.getBizTaskType())) {
            advanceVideoTask(parent, mediaTask);
        }
    }

    /** 公共 TTS 子任务终态驱动对口型视频提交。 */
    private void advanceAudioTask(AidExtractTask parent, AidMediaTask mediaTask) {
        // 查询字段精简：编排只需分镜归属、媒体关联、URL、时长与终态。
        AidAudioRecord audioRecord = aidAudioRecordService.getOne(Wrappers.<AidAudioRecord>lambdaQuery()
                .select(AidAudioRecord::getId, AidAudioRecord::getUserId,
                        AidAudioRecord::getProjectId, AidAudioRecord::getEpisodeId,
                        AidAudioRecord::getStoryboardId, AidAudioRecord::getAudioUrl,
                        AidAudioRecord::getDurationMs, AidAudioRecord::getTtsMediaTaskId,
                        AidAudioRecord::getSyncMediaTaskId, AidAudioRecord::getStatus,
                        AidAudioRecord::getErrorMessage)
                .eq(AidAudioRecord::getId, mediaTask.getBizTaskId())
                .last("LIMIT 1"), false);
        if (Objects.isNull(audioRecord) || Objects.isNull(audioRecord.getStoryboardId())) {
            log.error("对口型配音业务记录缺失, parentTaskId={}, mediaTaskId={}",
                    parent.getId(), mediaTask.getId());
            return;
        }

        Long existingVideoTaskId = null;
        boolean submitVideo = false;
        boolean audioFailed = false;
        RLock lock = acquireWorkflowLock(parent.getId());
        try {
            AidExtractTask current = loadWorkflowParent(parent.getId());
            if (!isActiveLipSyncParent(current)) {
                return;
            }
            List<ItemContext> items = parseItems(current.getResultData());
            ItemContext item = findItem(items, audioRecord.getStoryboardId());
            if (Objects.isNull(item) || item.finished()) {
                return;
            }
            item.audioRecordId = audioRecord.getId();
            item.audioMediaTaskId = mediaTask.getId();
            item.audioUrl = StrUtil.blankToDefault(audioRecord.getAudioUrl(), mediaTask.getOssUrl());
            item.audioDurationMs = audioRecord.getDurationMs();

            if (MediaTaskStatus.FAILED.name().equals(mediaTask.getStatus())) {
                item.status = TASK_STATUS_FAILED;
                item.errorMessage = TaskErrorPresentation.toUserMessage(
                        mediaTask.getModelName(), mediaTask.getErrorMessage(), "配音失败");
                audioFailed = true;
                persistProcessingItems(current.getId(), items);
            } else if (!MediaTaskStatus.SUCCEEDED.name().equals(mediaTask.getStatus())
                    || StrUtil.isBlank(mediaTask.getOssUrl())) {
                item.workflowStage = WORKFLOW_DUB_PROCESSING;
                persistProcessingItems(current.getId(), items);
                return;
            } else {
                AidMediaTask existingVideo = findVideoChild(current.getId(), audioRecord.getId());
                if (Objects.isNull(existingVideo) && Objects.nonNull(audioRecord.getSyncMediaTaskId())) {
                    attachMediaChild(current.getId(), audioRecord.getSyncMediaTaskId());
                    existingVideo = mediaTaskService.getOne(Wrappers.<AidMediaTask>lambdaQuery()
                            .select(AidMediaTask::getId, AidMediaTask::getStatus)
                            .eq(AidMediaTask::getId, audioRecord.getSyncMediaTaskId())
                            .last("LIMIT 1"), false);
                }
                if (Objects.nonNull(existingVideo)) {
                    item.lipSyncMediaTaskId = existingVideo.getId();
                    item.workflowStage = WORKFLOW_VIDEO_PROCESSING;
                    existingVideoTaskId = existingVideo.getId();
                } else if (!WORKFLOW_VIDEO_SUBMITTING.equals(item.workflowStage)) {
                    item.workflowStage = WORKFLOW_VIDEO_SUBMITTING;
                    submitVideo = true;
                }
                persistProcessingItems(current.getId(), items);
            }
        } finally {
            releaseWorkflowLock(lock);
        }

        if (audioFailed) {
            releaseStoryboardRunningByHolder(audioRecord.getStoryboardId(),
                    RUNNING_HOLDER_TASK_PREFIX + parent.getId());
            finalizeParentIfComplete(parent.getId());
            return;
        }
        sendDubSuccessProgress(parent.getId(), audioRecord);
        if (Objects.nonNull(existingVideoTaskId)) {
            onChildMediaTaskChanged(existingVideoTaskId);
            return;
        }
        if (!submitVideo) {
            return;
        }
        submitVideoChild(parent, audioRecord);
    }

    /** 锁外提交公共视频任务，避免远程调用占用工作流锁。 */
    private void submitVideoChild(AidExtractTask parent, AidAudioRecord audioRecord) {
        try {
            AidExtractTask currentParent = loadWorkflowParent(parent.getId());
            if (!isActiveLipSyncParent(currentParent)) {
                return;
            }
            parent = currentParent;
            AidStoryboard storyboard = aidStoryboardService.getById(audioRecord.getStoryboardId());
            ItemContext item = loadItem(parent.getId(), audioRecord.getStoryboardId());
            AidGenRecord sourceVideo = Objects.isNull(item) || Objects.isNull(item.sourceVideoRecordId)
                    ? null : aidGenRecordService.getById(item.sourceVideoRecordId);
            if (Objects.isNull(storyboard) || Objects.isNull(sourceVideo)) {
                log.error("对口型源视频上下文缺失, taskId={}, storyboardId={}",
                        parent.getId(), audioRecord.getStoryboardId());
                markItemFailed(parent.getId(), audioRecord.getStoryboardId(), "源视频缺失");
                return;
            }
            if (StrUtil.isBlank(audioRecord.getAudioUrl())) {
                // 事件顺序异常时回读一次业务记录；仍未就绪则等 OSS 事件/重启对账，不宣告失败。
                AidAudioRecord latest = aidAudioRecordService.getById(audioRecord.getId());
                if (Objects.isNull(latest) || StrUtil.isBlank(latest.getAudioUrl())) {
                    return;
                }
                audioRecord = latest;
            }
            AidAiModel model = new AidAiModel();
            model.setModelCode(parent.getModelCode());
            Long videoTaskId = submitLipSyncTask(parent.getId(), storyboard, sourceVideo,
                    audioRecord, model, parent.getUserId());
            recordVideoSubmission(parent.getId(), audioRecord.getStoryboardId(), videoTaskId);
            onChildMediaTaskChanged(videoTaskId);
        } catch (Exception ex) {
            String rawReason = ex instanceof ServiceException serviceException
                    ? StrUtil.blankToDefault(serviceException.getDetailMessage(), serviceException.getMessage())
                    : ex.getMessage();
            log.error("对口型视频子任务提交失败, taskId={}, audioRecordId={}",
                    parent.getId(), audioRecord.getId(), ex);
            markItemFailed(parent.getId(), audioRecord.getStoryboardId(),
                    TaskErrorPresentation.toUserMessage(rawReason, "对口型失败"));
        }
    }

    /** 公共视频任务终态驱动业务产物收尾与父任务汇总。 */
    private void advanceVideoTask(AidExtractTask parent, AidMediaTask mediaTask) {
        AidAudioRecord audioRecord = aidAudioRecordService.getOne(Wrappers.<AidAudioRecord>lambdaQuery()
                .select(AidAudioRecord::getId, AidAudioRecord::getStoryboardId)
                .eq(AidAudioRecord::getId, mediaTask.getBizTaskId())
                .last("LIMIT 1"), false);
        if (Objects.isNull(audioRecord) || Objects.isNull(audioRecord.getStoryboardId())) {
            log.error("对口型视频关联配音记录缺失, taskId={}, mediaTaskId={}", parent.getId(), mediaTask.getId());
            return;
        }

        boolean readyToFinalize = false;
        RLock lock = acquireWorkflowLock(parent.getId());
        try {
            AidExtractTask current = loadWorkflowParent(parent.getId());
            if (!isActiveLipSyncParent(current)) {
                return;
            }
            List<ItemContext> items = parseItems(current.getResultData());
            ItemContext item = findItem(items, audioRecord.getStoryboardId());
            if (Objects.isNull(item) || item.finished()) {
                return;
            }
            item.audioRecordId = audioRecord.getId();
            item.lipSyncMediaTaskId = mediaTask.getId();
            if (MediaTaskStatus.FAILED.name().equals(mediaTask.getStatus())) {
                item.status = TASK_STATUS_FAILED;
                item.errorMessage = TaskErrorPresentation.toUserMessage(
                        mediaTask.getModelName(), mediaTask.getErrorMessage(), "对口型失败");
            } else if (MediaTaskStatus.SUCCEEDED.name().equals(mediaTask.getStatus())
                    && StrUtil.isNotBlank(mediaTask.getOssUrl())) {
                item.workflowStage = WORKFLOW_FINALIZING;
                readyToFinalize = true;
            } else {
                item.workflowStage = WORKFLOW_VIDEO_PROCESSING;
            }
            persistProcessingItems(current.getId(), items);
        } finally {
            releaseWorkflowLock(lock);
        }

        if (MediaTaskStatus.FAILED.name().equals(mediaTask.getStatus())) {
            releaseStoryboardRunningByHolder(audioRecord.getStoryboardId(),
                    RUNNING_HOLDER_TASK_PREFIX + parent.getId());
            sendLipSyncProgress(parent.getId());
            finalizeParentIfComplete(parent.getId());
            return;
        }
        if (!readyToFinalize) {
            return;
        }

        // LipSyncEventListener(Order=220) 已先幂等落 compose 记录；这里只读取并完成父任务业务收尾。
        AidGenRecord lipSyncRecord = aidGenRecordService.getOne(Wrappers.<AidGenRecord>lambdaQuery()
                .select(AidGenRecord::getId, AidGenRecord::getFileUrl)
                .eq(AidGenRecord::getStoryboardId, audioRecord.getStoryboardId())
                .eq(AidGenRecord::getGenType, GenTypeEnum.COMPOSE.getValue())
                .eq(AidGenRecord::getFileUrl, mediaTask.getOssUrl())
                .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL)
                .orderByDesc(AidGenRecord::getId)
                .last("LIMIT 1"), false);
        if (Objects.isNull(lipSyncRecord)) {
            log.warn("对口型成片记录暂未就绪, taskId={}, mediaTaskId={}", parent.getId(), mediaTask.getId());
            return;
        }

        try {
            if (Objects.equals(TASK_TYPE_LIP_SYNC_BATCH, parent.getTaskType())) {
                SetFinalSelectionRequest select = new SetFinalSelectionRequest();
                select.setStoryboardId(audioRecord.getStoryboardId());
                select.setRecordId(lipSyncRecord.getId());
                select.setRecordType(RECORD_TYPE_VIDEO);
                storyboardWorkbenchService.setFinalSelection(select, parent.getUserId());
            }
            markItemSucceeded(parent.getId(), audioRecord.getStoryboardId(), mediaTask.getId(), lipSyncRecord);
        } catch (Exception ex) {
            log.error("对口型业务产物收尾失败, taskId={}, storyboardId={}",
                    parent.getId(), audioRecord.getStoryboardId(), ex);
            markItemFailed(parent.getId(), audioRecord.getStoryboardId(), "切换主视频失败");
        }
    }

    /** 重启/租约失活对账：重放所有子任务终态，并恢复提交中断的确定性步骤。 */
    @Override
    public void reconcileParentTask(Long parentTaskId) {
        AidExtractTask parent = loadWorkflowParent(parentTaskId);
        if (!isActiveLipSyncParent(parent)) {
            return;
        }
        List<AidMediaTask> children = mediaTaskService.list(Wrappers.<AidMediaTask>lambdaQuery()
                .select(AidMediaTask::getId)
                .eq(AidMediaTask::getParentTaskId, parentTaskId)
                .orderByAsc(AidMediaTask::getId));
        for (AidMediaTask child : children) {
            onChildMediaTaskChanged(child.getId());
        }

        parent = loadWorkflowParent(parentTaskId);
        if (!isActiveLipSyncParent(parent)) {
            return;
        }
        List<ItemContext> items = parseItems(parent.getResultData());
        for (ItemContext item : items) {
            if (item.finished()) {
                continue;
            }
            if (Objects.nonNull(item.lipSyncMediaTaskId)) {
                onChildMediaTaskChanged(item.lipSyncMediaTaskId);
                continue;
            }
            if (Objects.nonNull(item.audioMediaTaskId)) {
                if (WORKFLOW_VIDEO_SUBMITTING.equals(item.workflowStage)
                        && Objects.isNull(item.lipSyncMediaTaskId)) {
                    resetVideoSubmissionForRecovery(parentTaskId, item.storyboardId);
                }
                onChildMediaTaskChanged(item.audioMediaTaskId);
                continue;
            }
            if (Objects.nonNull(item.audioRecordId)) {
                AidAudioRecord audio = aidAudioRecordService.getById(item.audioRecordId);
                if (Objects.nonNull(audio) && Objects.nonNull(audio.getTtsMediaTaskId())) {
                    attachMediaChild(parentTaskId, audio.getTtsMediaTaskId());
                    if (Objects.nonNull(audio.getSyncMediaTaskId())) {
                        attachMediaChild(parentTaskId, audio.getSyncMediaTaskId());
                    }
                    onChildMediaTaskChanged(audio.getTtsMediaTaskId());
                    continue;
                }
            }
            // 没有任何已落库子任务，说明进程在提交公共媒体任务之前中断；未发生媒体扣费，可安全失败。
            markItemFailed(parentTaskId, item.storyboardId, "提交中断");
        }
        finalizeParentIfComplete(parentTaskId);
    }

    /**
     * 进程可能在写入 VIDEO_SUBMITTING 后、媒体任务落库前退出。
     * 重启对账持有父任务锁且已确认无对应视频子任务时，把阶段退回可重放点；
     * 随后的公共 TTS 成功事件会按相同 requestHash 幂等提交视频。
     */
    private void resetVideoSubmissionForRecovery(Long taskId, Long storyboardId) {
        RLock lock = acquireWorkflowLock(taskId);
        try {
            AidExtractTask parent = loadWorkflowParent(taskId);
            if (!isActiveLipSyncParent(parent)) {
                return;
            }
            List<ItemContext> items = parseItems(parent.getResultData());
            ItemContext item = findItem(items, storyboardId);
            if (Objects.nonNull(item) && !item.finished()
                    && WORKFLOW_VIDEO_SUBMITTING.equals(item.workflowStage)
                    && Objects.isNull(item.lipSyncMediaTaskId)
                    && (Objects.isNull(item.audioRecordId)
                            || Objects.isNull(findVideoChild(taskId, item.audioRecordId)))) {
                item.workflowStage = WORKFLOW_DUB_PROCESSING;
                persistProcessingItems(taskId, items);
            }
        } finally {
            releaseWorkflowLock(lock);
        }
    }

    private void recordAudioSubmission(Long taskId, Long storyboardId, AidAudioRecord audioRecord) {
        RLock lock = acquireWorkflowLock(taskId);
        try {
            AidExtractTask parent = loadWorkflowParent(taskId);
            if (!isActiveLipSyncParent(parent)) {
                return;
            }
            List<ItemContext> items = parseItems(parent.getResultData());
            ItemContext item = findItem(items, storyboardId);
            if (Objects.isNull(item) || item.finished()) {
                return;
            }
            item.audioRecordId = audioRecord.getId();
            item.audioMediaTaskId = audioRecord.getTtsMediaTaskId();
            item.audioUrl = audioRecord.getAudioUrl();
            item.audioDurationMs = audioRecord.getDurationMs();
            if (WORKFLOW_DUB_SUBMITTING.equals(item.workflowStage)) {
                item.workflowStage = WORKFLOW_DUB_PROCESSING;
            }
            persistProcessingItems(taskId, items);
        } finally {
            releaseWorkflowLock(lock);
        }
    }

    private void recordVideoSubmission(Long taskId, Long storyboardId, Long mediaTaskId) {
        RLock lock = acquireWorkflowLock(taskId);
        try {
            AidExtractTask parent = loadWorkflowParent(taskId);
            if (!isActiveLipSyncParent(parent)) {
                return;
            }
            List<ItemContext> items = parseItems(parent.getResultData());
            ItemContext item = findItem(items, storyboardId);
            if (Objects.nonNull(item) && !item.finished()) {
                item.lipSyncMediaTaskId = mediaTaskId;
                item.workflowStage = WORKFLOW_VIDEO_PROCESSING;
                persistProcessingItems(taskId, items);
            }
        } finally {
            releaseWorkflowLock(lock);
        }
    }

    private void markItemSucceeded(Long taskId, Long storyboardId, Long mediaTaskId, AidGenRecord record) {
        RLock lock = acquireWorkflowLock(taskId);
        try {
            AidExtractTask parent = loadWorkflowParent(taskId);
            if (!isActiveLipSyncParent(parent)) {
                return;
            }
            List<ItemContext> items = parseItems(parent.getResultData());
            ItemContext item = findItem(items, storyboardId);
            if (Objects.nonNull(item) && !item.finished()) {
                item.lipSyncMediaTaskId = mediaTaskId;
                item.lipSyncVideoRecordId = record.getId();
                item.lipSyncVideoUrl = record.getFileUrl();
                item.status = TASK_STATUS_SUCCEEDED;
                item.errorMessage = null;
                persistProcessingItems(taskId, items);
            }
        } finally {
            releaseWorkflowLock(lock);
        }
        releaseStoryboardRunningByHolder(storyboardId, RUNNING_HOLDER_TASK_PREFIX + taskId);
        sendLipSyncProgress(taskId);
        finalizeParentIfComplete(taskId);
    }

    private void markItemFailed(Long taskId, Long storyboardId, String errorMessage) {
        RLock lock = acquireWorkflowLock(taskId);
        try {
            AidExtractTask parent = loadWorkflowParent(taskId);
            if (!isActiveLipSyncParent(parent)) {
                return;
            }
            List<ItemContext> items = parseItems(parent.getResultData());
            ItemContext item = findItem(items, storyboardId);
            if (Objects.nonNull(item) && !item.finished()) {
                item.status = TASK_STATUS_FAILED;
                item.errorMessage = TaskErrorPresentation.toUserMessage(errorMessage, "对口型失败");
                persistProcessingItems(taskId, items);
            }
        } finally {
            releaseWorkflowLock(lock);
        }
        releaseStoryboardRunningByHolder(storyboardId, RUNNING_HOLDER_TASK_PREFIX + taskId);
        sendLipSyncProgress(taskId);
        finalizeParentIfComplete(taskId);
    }

    private void failUnsubmittedItems(Long taskId, String errorMessage) {
        reconcileParentTask(taskId);
        log.warn("对口型初始提交中断，已转持久化对账: taskId={}, reason={}", taskId, errorMessage);
    }

    /** 全部明细终态后 CAS 收口父任务；只有首次 CAS 成功者发送终态通知。 */
    private void finalizeParentIfComplete(Long taskId) {
        List<ItemContext> terminalItems;
        String finalStatus;
        String errorMessage = null;
        long succeeded;
        long failed;
        RLock lock = acquireWorkflowLock(taskId);
        try {
            AidExtractTask parent = loadWorkflowParent(taskId);
            if (!isActiveLipSyncParent(parent)) {
                return;
            }
            terminalItems = parseItems(parent.getResultData());
            if (CollectionUtil.isEmpty(terminalItems)
                    || terminalItems.stream().anyMatch(item -> !item.finished())) {
                return;
            }
            succeeded = terminalItems.stream()
                    .filter(item -> TASK_STATUS_SUCCEEDED.equals(item.status)).count();
            failed = terminalItems.size() - succeeded;
            if (failed == 0) {
                finalStatus = TASK_STATUS_SUCCEEDED;
            } else if (succeeded > 0) {
                finalStatus = TASK_STATUS_PARTIAL_FAILED;
                errorMessage = String.format("%d 条对口型失败", failed);
            } else {
                finalStatus = TASK_STATUS_FAILED;
                errorMessage = terminalItems.stream().map(item -> item.errorMessage)
                        .filter(StrUtil::isNotBlank).findFirst().orElse("对口型失败");
            }
            boolean updated = extractTaskService.update(Wrappers.<AidExtractTask>lambdaUpdate()
                    .eq(AidExtractTask::getId, taskId)
                    .in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING)
                    .set(AidExtractTask::getResultData, serializeItems(terminalItems))
                    .set(AidExtractTask::getStatus, finalStatus)
                    .set(AidExtractTask::getErrorMessage, errorMessage)
                    .set(AidExtractTask::getUpdateTime, DateUtils.getNowDate())
                    .set(AidExtractTask::getUpdateBy, "system"));
            if (!updated) {
                return;
            }
        } finally {
            releaseWorkflowLock(lock);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalCount", terminalItems.size());
        payload.put("successCount", (int) succeeded);
        payload.put("failCount", (int) failed);
        payload.put("items", toItemMaps(terminalItems));
        try {
            if (TASK_STATUS_SUCCEEDED.equals(finalStatus)) {
                sseManager.sendComplete(taskId, payload);
            } else if (TASK_STATUS_PARTIAL_FAILED.equals(finalStatus)) {
                sseManager.sendPartialFailed(taskId, payload, errorMessage);
            } else {
                sseManager.sendError(taskId, errorMessage);
            }
        } catch (Exception ex) {
            // DB 终态已经 CAS 成功，SSE 发送失败不回滚终态；客户端重连可从任务表读取最终结果。
            log.warn("对口型终态 SSE 发送异常, taskId={}, msg={}", taskId, ex.getMessage());
        }
        try {
            wechatNotifyService.notifyTaskTerminal(taskId);
        } catch (Exception ex) {
            log.warn("对口型终态微信通知异常, taskId={}, msg={}", taskId, ex.getMessage());
        }
        log.info("对口型父任务完成: taskId={}, status={}, success={}, fail={}",
                taskId, finalStatus, succeeded, failed);
    }

    private void sendLipSyncProgress(Long taskId) {
        AidExtractTask parent = loadWorkflowParent(taskId);
        if (!isActiveLipSyncParent(parent)) {
            return;
        }
        List<ItemContext> items = parseItems(parent.getResultData());
        int total = items.size();
        long done = items.stream().filter(ItemContext::finished).count();
        int progress = total == 0 ? PROGRESS_LIP_SYNC_BASE
                : PROGRESS_LIP_SYNC_BASE + (int) (done * PROGRESS_LIP_SYNC_SPAN / total);
        LipSyncMode mode = Objects.equals(TASK_TYPE_LIP_SYNC_SINGLE, parent.getTaskType())
                ? LipSyncMode.SINGLE : LipSyncMode.BATCH;
        if (LipSyncMode.SINGLE == mode) {
            sseManager.sendStepProgress(taskId, mode.sseStage(), progress,
                    STEP_ID_LIP_SYNC, STEP_TITLE_LIP_SYNC, STEP_TOTAL, STEP_TOTAL);
        } else {
            sseManager.sendProgress(taskId, mode.sseStage(), progress,
                    String.format("对口型合成中 %d/%d", done, total));
        }
    }

    /** TTS 成功且 OSS 就绪后再发送配音阶段进度，保证 audioUrl/durationMs 是可用业务数据。 */
    private void sendDubSuccessProgress(Long taskId, AidAudioRecord audioRecord) {
        AidExtractTask parent = loadWorkflowParent(taskId);
        if (!isActiveLipSyncParent(parent)) {
            return;
        }
        List<ItemContext> items = parseItems(parent.getResultData());
        ItemContext item = findItem(items, audioRecord.getStoryboardId());
        if (Objects.isNull(item)) {
            return;
        }
        LipSyncMode mode = Objects.equals(TASK_TYPE_LIP_SYNC_SINGLE, parent.getTaskType())
                ? LipSyncMode.SINGLE : LipSyncMode.BATCH;
        int completedDubCount = (int) items.stream()
                .filter(current -> StrUtil.isNotBlank(current.audioUrl)
                        || (current.finished() && Objects.nonNull(current.audioMediaTaskId)))
                .count();
        int index = LipSyncMode.SINGLE == mode ? 0 : Math.max(completedDubCount - 1, 0);
        sendDubProgress(taskId, mode, item, index, items.size());
    }

    private AidMediaTask findVideoChild(Long parentTaskId, Long audioRecordId) {
        return mediaTaskService.getOne(Wrappers.<AidMediaTask>lambdaQuery()
                .select(AidMediaTask::getId, AidMediaTask::getStatus)
                .eq(AidMediaTask::getParentTaskId, parentTaskId)
                .eq(AidMediaTask::getBizTaskType, BIZ_TASK_TYPE_LIP_SYNC)
                .eq(AidMediaTask::getBizTaskId, audioRecordId)
                .orderByDesc(AidMediaTask::getId)
                .last("LIMIT 1"), false);
    }

    /** 兼容升级前已创建的子任务：仅在关联为空时补齐，不覆盖其它父任务归属。 */
    private void attachMediaChild(Long parentTaskId, Long mediaTaskId) {
        if (Objects.isNull(parentTaskId) || Objects.isNull(mediaTaskId)) {
            return;
        }
        mediaTaskService.update(Wrappers.<AidMediaTask>lambdaUpdate()
                .eq(AidMediaTask::getId, mediaTaskId)
                .isNull(AidMediaTask::getParentTaskId)
                .set(AidMediaTask::getParentTaskId, parentTaskId)
                .set(AidMediaTask::getUpdateTime, DateUtils.getNowDate())
                .set(AidMediaTask::getUpdateBy, "system"));
    }

    private AidExtractTask loadWorkflowParent(Long taskId) {
        if (Objects.isNull(taskId)) {
            return null;
        }
        return extractTaskService.getOne(Wrappers.<AidExtractTask>lambdaQuery()
                .select(AidExtractTask::getId, AidExtractTask::getUserId,
                        AidExtractTask::getProjectId, AidExtractTask::getEpisodeId,
                        AidExtractTask::getTaskType, AidExtractTask::getStatus,
                        AidExtractTask::getModelCode, AidExtractTask::getResultData,
                        AidExtractTask::getTotalCount)
                .eq(AidExtractTask::getId, taskId)
                .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)
                .last("LIMIT 1"), false);
    }

    private boolean isActiveLipSyncParent(AidExtractTask parent) {
        return Objects.nonNull(parent)
                && (Objects.equals(TASK_TYPE_LIP_SYNC_SINGLE, parent.getTaskType())
                        || Objects.equals(TASK_TYPE_LIP_SYNC_BATCH, parent.getTaskType()))
                && (Objects.equals(TASK_STATUS_PENDING, parent.getStatus())
                        || Objects.equals(TASK_STATUS_PROCESSING, parent.getStatus()));
    }

    private ItemContext loadItem(Long taskId, Long storyboardId) {
        AidExtractTask parent = loadWorkflowParent(taskId);
        return Objects.isNull(parent) ? null : findItem(parseItems(parent.getResultData()), storyboardId);
    }

    private ItemContext findItem(List<ItemContext> items, Long storyboardId) {
        for (ItemContext item : items) {
            if (Objects.equals(item.storyboardId, storyboardId)) {
                return item;
            }
        }
        return null;
    }

    private List<ItemContext> parseItems(String resultData) {
        List<ItemContext> items = new ArrayList<>();
        if (StrUtil.isBlank(resultData)) {
            return items;
        }
        try {
            JSONArray array = JSON.parseObject(resultData).getJSONArray("items");
            if (Objects.isNull(array)) {
                return items;
            }
            for (int i = 0; i < array.size(); i++) {
                JSONObject raw = array.getJSONObject(i);
                ItemContext item = new ItemContext();
                item.storyboardId = raw.getLong("storyboardId");
                item.sourceVideoRecordId = raw.getLong("sourceVideoRecordId");
                item.speakerRoles = raw.getList("speakerRoles", String.class);
                item.voiceLibraryId = raw.getLong("voiceLibraryId");
                item.audioRecordId = raw.getLong("audioRecordId");
                item.audioMediaTaskId = raw.getLong("audioMediaTaskId");
                item.audioUrl = raw.getString("audioUrl");
                item.audioDurationMs = raw.getInteger("durationMs");
                item.lipSyncMediaTaskId = raw.getLong("lipSyncMediaTaskId");
                item.lipSyncVideoRecordId = raw.getLong("lipSyncVideoRecordId");
                item.lipSyncVideoUrl = raw.getString("lipSyncVideoUrl");
                item.workflowStage = raw.getString("workflowStage");
                String status = raw.getString("status");
                item.status = TASK_STATUS_PROCESSING.equals(status) ? null : status;
                item.errorMessage = raw.getString("errorMessage");
                items.add(item);
            }
        } catch (Exception ex) {
            log.error("对口型 resultData 解析失败", ex);
        }
        return items;
    }

    private String serializeItems(List<ItemContext> items) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", toItemMaps(items));
            return OBJECT_MAPPER.writeValueAsString(data);
        } catch (Exception ex) {
            log.error("对口型 resultData 序列化失败", ex);
            throw new ServiceException("任务保存失败");
        }
    }

    private void persistProcessingItems(Long taskId, List<ItemContext> items) {
        extractTaskService.update(Wrappers.<AidExtractTask>lambdaUpdate()
                .eq(AidExtractTask::getId, taskId)
                .in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING)
                .set(AidExtractTask::getResultData, serializeItems(items))
                .set(AidExtractTask::getUpdateTime, DateUtils.getNowDate())
                .set(AidExtractTask::getUpdateBy, "system"));
    }

    private RLock acquireWorkflowLock(Long taskId) {
        RLock lock = redissonClient.getLock(WORKFLOW_LOCK_PREFIX + taskId);
        try {
            if (!lock.tryLock(WORKFLOW_LOCK_WAIT_SECONDS, WORKFLOW_LOCK_LEASE_SECONDS, TimeUnit.SECONDS)) {
                log.info("对口型工作流锁繁忙, taskId={}", taskId);
                throw new ServiceException("任务处理中");
            }
            return lock;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ServiceException("任务处理中");
        }
    }

    private void releaseWorkflowLock(RLock lock) {
        if (Objects.nonNull(lock) && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    // ==================== 校验与解析 ====================

    /** 查询分镜并校验用户归属 */
    private AidStoryboard getStoryboardWithOwnerCheck(Long storyboardId, Long userId) {
        AidStoryboard storyboard = aidStoryboardService.getById(storyboardId);
        if (Objects.isNull(storyboard) || !Objects.equals(DEL_FLAG_NORMAL, storyboard.getDelFlag())) {
            log.error("对口型分镜不存在, storyboardId={}", storyboardId);
            throw new ServiceException("分镜不存在");
        }
        if (!Objects.equals(storyboard.getUserId(), userId)) {
            log.error("对口型分镜越权, storyboardId={}, userId={}", storyboardId, userId);
            throw new ServiceException("无权操作");
        }
        return storyboard;
    }

    /**
     * 加载单分镜的分镜视频（对口型源视频，final_video_id 指向的配音前原视频）：
     * 存在、归属本人、原视频轨类型、文件已生成，任一缺失拒绝。
     * 查询字段精简：仅 select id/user_id/gen_type/file_url/video_duration/del_flag，后续扩展取数请同步增列。
     */
    private AidGenRecord loadStoryboardVideo(AidStoryboard storyboard, Long userId) {
        if (Objects.isNull(storyboard.getFinalVideoId())) {
            log.info("对口型分镜未选定视频, storyboardId={}", storyboard.getId());
            throw new ServiceException("请先选定视频");
        }
        AidGenRecord record = aidGenRecordService.getOne(Wrappers.<AidGenRecord>lambdaQuery()
                .select(AidGenRecord::getId, AidGenRecord::getUserId, AidGenRecord::getGenType,
                        AidGenRecord::getFileUrl, AidGenRecord::getVideoDuration, AidGenRecord::getDelFlag)
                .eq(AidGenRecord::getId, storyboard.getFinalVideoId())
                .last("LIMIT 1"), false);
        if (Objects.isNull(record) || !Objects.equals(DEL_FLAG_NORMAL, record.getDelFlag())
                || !Objects.equals(record.getUserId(), userId)
                || !GenTypeEnum.originalVideoValues().contains(record.getGenType())
                || StrUtil.isBlank(record.getFileUrl())) {
            log.error("对口型分镜视频不可用, storyboardId={}, finalVideoId={}",
                    storyboard.getId(), storyboard.getFinalVideoId());
            throw new ServiceException("请先选定视频");
        }
        return record;
    }

    /**
     * 防重：该分镜最新关联的对口型任务仍在进行中（非终态）时拒绝重复提交，
     * 避免同分镜并发挂多个对口型任务、后完成的旧任务覆盖新结果。
     */
    private void assertNoRunningLipSync(Long storyboardId, Long userId) {
        // 查询字段精简：防重只需最新记录的任务关联
        AidAudioRecord latest = aidAudioRecordService.getOne(Wrappers.<AidAudioRecord>lambdaQuery()
                .select(AidAudioRecord::getId, AidAudioRecord::getSyncMediaTaskId)
                .eq(AidAudioRecord::getStoryboardId, storyboardId)
                .eq(AidAudioRecord::getUserId, userId)
                .eq(AidAudioRecord::getDelFlag, DEL_FLAG_NORMAL)
                .isNotNull(AidAudioRecord::getSyncMediaTaskId)
                .orderByDesc(AidAudioRecord::getId)
                .last("LIMIT 1"), false);
        if (Objects.isNull(latest) || Objects.isNull(latest.getSyncMediaTaskId())) {
            return;
        }
        // 仅读本地任务快照，不触发远端轮询
        MediaTaskResponse prevTask = mediaGenerationService.queryTaskLocal(latest.getSyncMediaTaskId());
        if (Objects.nonNull(prevTask)
                && !MediaTaskStatus.SUCCEEDED.name().equals(prevTask.getStatus())
                && !MediaTaskStatus.FAILED.name().equals(prevTask.getStatus())) {
            log.info("对口型任务进行中拒绝重复提交, storyboardId={}, syncMediaTaskId={}, status={}",
                    storyboardId, latest.getSyncMediaTaskId(), prevTask.getStatus());
            throw new ServiceException("对口型进行中");
        }
    }

    /**
     * 解析对口型模型：取启用视频模型中 capability_json 声明 {@code lipSync=true} 的最高优先级者。
     * 模型完全由 aid_ai_model 配置驱动，新增/替换对口型厂商只需配置模型行，业务零改动。
     * 查询字段精简：仅 select 路由必需列（id/model_code/model_name/priority/capability_json）。
     */
    private AidAiModel resolveLipSyncModel() {
        LambdaQueryWrapper<AidAiModel> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidAiModel::getId, AidAiModel::getModelCode, AidAiModel::getModelName,
                AidAiModel::getPriority, AidAiModel::getCapabilityJson);
        wrapper.eq(AidAiModel::getModelType, MODEL_TYPE_VIDEO);
        wrapper.eq(AidAiModel::getStatus, STATUS_NORMAL);
        wrapper.eq(AidAiModel::getDelFlag, DEL_FLAG_NORMAL);
        // 粗筛：capability_json 含 lipSync 关键字；精确布尔判断在代码内解析（避免误命中 "lipSync":false）
        wrapper.like(AidAiModel::getCapabilityJson, CAPABILITY_LIP_SYNC);
        wrapper.orderByDesc(AidAiModel::getPriority);
        List<AidAiModel> candidates = aidAiModelService.list(wrapper);
        for (AidAiModel model : candidates) {
            try {
                JSONObject capability = JSON.parseObject(model.getCapabilityJson());
                if (Objects.nonNull(capability) && capability.getBooleanValue(CAPABILITY_LIP_SYNC)) {
                    return model;
                }
            } catch (Exception ex) {
                // capability_json 解析失败仅跳过该模型，不阻断候选遍历
                log.warn("对口型模型 capability_json 解析失败, modelId={}, err={}", model.getId(), ex.getMessage());
            }
        }
        log.error("对口型模型未配置：无 capability_json.lipSync=true 的启用视频模型");
        throw new ServiceException("对口型未配置");
    }

    /**
     * 解析实际提交给对口型模型的驱动音频。
     * 上游按驱动音频长度出片且无时长参数可控，台词短于源视频时成片会被截短（15 秒源视频出 3 秒成片），
     * 故在提交前把驱动音频尾部补静音到源视频时长，得到「说完后画面继续」的完整成片。
     * 补齐产物是独立的 OSS 对象，配音记录自身的 URL 与真实时长不变。
     *
     * @param videoRecord 源视频生成记录
     * @param audioRecord 驱动配音记录
     * @return 驱动音频 URL 与时长；补齐失败时为原音频
     */
    private LipSyncDrivingAudio resolveDrivingAudio(AidGenRecord videoRecord, AidAudioRecord audioRecord) {
        String originUrl = mediaUrlResolver.toFullUrl(audioRecord.getAudioUrl());
        Integer audioDurationMs = audioRecord.getDurationMs();
        if (Objects.isNull(audioDurationMs) || audioDurationMs <= 0
                || Objects.isNull(videoRecord.getVideoDuration()) || videoRecord.getVideoDuration() <= 0) {
            return new LipSyncDrivingAudio(originUrl, audioDurationMs);
        }
        int targetMs = (int) (videoRecord.getVideoDuration() * MS_PER_SECOND);
        if (targetMs - audioDurationMs < LIP_SYNC_PAD_THRESHOLD_MS) {
            return new LipSyncDrivingAudio(originUrl, audioDurationMs);
        }
        String paddedUrl = audioSilencePaddingService.padWithSilence(originUrl, targetMs);
        if (StrUtil.isBlank(paddedUrl)) {
            // 补齐失败不阻断对口型：按原音频提交，成片退化为台词长度
            log.warn("对口型驱动音频补静音失败, 按原音频提交, audioRecordId={}, audioMs={}, targetMs={}",
                    audioRecord.getId(), audioDurationMs, targetMs);
            return new LipSyncDrivingAudio(originUrl, audioDurationMs);
        }
        log.info("对口型驱动音频已补静音, audioRecordId={}, audioMs={}, targetMs={}",
                audioRecord.getId(), audioDurationMs, targetMs);
        return new LipSyncDrivingAudio(paddedUrl, targetMs);
    }

    /** 对口型驱动音频：实际提交给上游的音频 URL 与其时长（毫秒）。 */
    private record LipSyncDrivingAudio(String url, Integer durationMs) {
    }

    /**
     * 对口型计费时长（秒）：成片长度由驱动音频决定（源视频只提供画面），故以驱动音频时长为准、
     * 音频时长未知时才退回源视频时长，并向上取整到官方计价粒度
     * （{@value #LIP_SYNC_BILLING_GRANULARITY_SECONDS} 秒）的整数倍。
     * 两侧都取不到返回 null，由统一计费侧按默认时长兜底。
     *
     * @param videoRecord     源视频生成记录
     * @param audioDurationMs 驱动音频时长（毫秒，可空）
     * @return 计价秒数，无可用时长时为 null
     */
    private Integer resolveLipSyncDurationSeconds(AidGenRecord videoRecord, Integer audioDurationMs) {
        Integer seconds = null;
        if (Objects.nonNull(audioDurationMs) && audioDurationMs > 0) {
            seconds = (int) Math.ceil(audioDurationMs / MS_PER_SECOND);
        } else if (Objects.nonNull(videoRecord.getVideoDuration()) && videoRecord.getVideoDuration() > 0) {
            seconds = videoRecord.getVideoDuration().intValue();
        }
        if (Objects.isNull(seconds) || seconds <= 0) {
            return seconds;
        }
        int granularity = LIP_SYNC_BILLING_GRANULARITY_SECONDS;
        return ((seconds + granularity - 1) / granularity) * granularity;
    }

    /**
     * 兜底音色老入参成对校验：voiceModelId 与 timbreCode 只传其一视为前端误传（兜底意图不完整），
     * 仅在未传 voiceLibraryId（新入参）时校验——新入参存在时老入参本就被忽略。
     *
     * @param voiceLibraryId 兜底音色库ID（新入参，可空）
     * @param voiceModelId   兜底模型ID（老入参，可空）
     * @param timbreCode     兜底音色编码（老入参，可空）
     */
    private void assertFallbackVoicePaired(Long voiceLibraryId, Long voiceModelId, String timbreCode) {
        if (Objects.nonNull(voiceLibraryId)) {
            return;
        }
        boolean hasModelId = Objects.nonNull(voiceModelId);
        boolean hasTimbre = StrUtil.isNotBlank(timbreCode);
        if (hasModelId != hasTimbre) {
            log.info("对口型兜底音色老入参不成对, voiceModelId={}, timbreCode={}", voiceModelId, timbreCode);
            throw new ServiceException("音色参数不全");
        }
    }

    /**
     * 加载「有台词」的目标分镜（清洗后非空）。
     * 传入 storyboardIds 时先做归属强校验：任一 ID 不存在/不属于该项目剧集/非本人，整批拒绝
     * （防前端乱传导致静默漏配，任务生成前拦截）。
     * 查询字段精简：仅 select 对口型必需列（id/dialogue_text/sort_order/final_video_id/project_id/episode_id）。
     */
    private List<AidStoryboard> loadDialogueStoryboards(Long projectId, Long episodeId,
                                                        List<Long> storyboardIds, Long userId) {
        List<Long> ids = null;
        if (CollectionUtil.isNotEmpty(storyboardIds)) {
            ids = storyboardIds.stream()
                    .filter(Objects::nonNull).distinct().collect(Collectors.toList());
            // 传了列表但全是空元素：视为误传拒绝，不能退化成"全量分镜"处理
            if (CollectionUtil.isEmpty(ids)) {
                log.info("批量对口型 storyboardIds 全为空元素, projectId={}, episodeId={}", projectId, episodeId);
                throw new ServiceException("参数错误");
            }
        }
        List<AidStoryboard> storyboards = aidStoryboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getDialogueText, AidStoryboard::getSortOrder,
                                AidStoryboard::getFinalVideoId, AidStoryboard::getProjectId, AidStoryboard::getEpisodeId)
                        .eq(AidStoryboard::getProjectId, projectId)
                        .eq(AidStoryboard::getEpisodeId, episodeId)
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                        .in(CollectionUtil.isNotEmpty(ids), AidStoryboard::getId, ids)
                        .orderByAsc(AidStoryboard::getSortOrder).orderByAsc(AidStoryboard::getId));
        // 显式归属校验（台词过滤前对比）：传入的分镜必须全部命中该项目+剧集+本人，杜绝乱传静默漏配
        if (CollectionUtil.isNotEmpty(ids) && storyboards.size() != ids.size()) {
            log.info("批量对口型存在无效分镜ID, projectId={}, episodeId={}, expect={}, actual={}",
                    projectId, episodeId, ids.size(), storyboards.size());
            throw new ServiceException("分镜不存在");
        }
        if (CollectionUtil.isEmpty(storyboards)) {
            return new ArrayList<>();
        }
        // 有台词过滤：清洗后为空（纯标记/空白）视为无台词，自动跳过不处理
        return storyboards.stream()
                .filter(s -> StrUtil.isNotBlank(DialogueTextSanitizer.sanitize(s.getDialogueText())))
                .collect(Collectors.toList());
    }

    /**
     * 已对口型分镜集合：分镜下存在对口型产物（audio_record.sync_video_url 非空）即视为已完成。
     * 查询字段精简：仅 select storyboard_id。
     */
    private Set<Long> loadLipSyncedStoryboardIds(List<AidStoryboard> targets, Long userId) {
        Set<Long> storyboardIds = targets.stream().map(AidStoryboard::getId).collect(Collectors.toSet());
        List<AidAudioRecord> lipSynced = aidAudioRecordService.list(
                Wrappers.<AidAudioRecord>lambdaQuery()
                        .select(AidAudioRecord::getStoryboardId)
                        .in(AidAudioRecord::getStoryboardId, storyboardIds)
                        .eq(AidAudioRecord::getUserId, userId)
                        .eq(AidAudioRecord::getDelFlag, DEL_FLAG_NORMAL)
                        .isNotNull(AidAudioRecord::getSyncVideoUrl)
                        .ne(AidAudioRecord::getSyncVideoUrl, ""));
        return lipSynced.stream()
                .map(AidAudioRecord::getStoryboardId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 前置校验分镜视频（对口型源视频与计费时基，<strong>恒取原视频轨</strong>）：
     * 存在、归属本人、文件已生成、时长已回填，任一缺失整批拒绝、不产生任务记录。
     * final_video_id 恒指配音前原视频；历史数据若误指配音视频（compose），自动回落该分镜
     * 最新原视频记录，绝不把配音视频当源视频二次对口型。
     * 查询字段精简：仅 select id/user_id/file_url/video_duration/gen_type/del_flag。
     *
     * @return 分镜ID → 原视频记录（对口型源视频）
     */
    private Map<Long, AidGenRecord> loadAndValidateFinalVideos(List<AidStoryboard> targets, Long userId) {
        List<Long> missingVideo = targets.stream()
                .filter(s -> Objects.isNull(s.getFinalVideoId()))
                .map(AidStoryboard::getId)
                .collect(Collectors.toList());
        if (CollectionUtil.isNotEmpty(missingVideo)) {
            log.info("批量对口型存在未出片分镜, missing={}", missingVideo);
            throw new ServiceException("请先生成视频");
        }
        Set<Long> videoRecordIds = targets.stream()
                .map(AidStoryboard::getFinalVideoId)
                .collect(Collectors.toSet());
        List<AidGenRecord> records = aidGenRecordService.list(
                Wrappers.<AidGenRecord>lambdaQuery()
                        .select(AidGenRecord::getId, AidGenRecord::getUserId,
                                AidGenRecord::getFileUrl, AidGenRecord::getVideoDuration,
                                AidGenRecord::getGenType, AidGenRecord::getDelFlag)
                        .in(AidGenRecord::getId, videoRecordIds));
        Map<Long, AidGenRecord> recordById = records.stream()
                .collect(Collectors.toMap(AidGenRecord::getId, r -> r, (a, b) -> a));

        // 历史脏数据兜底：final_video_id 误指配音视频的分镜，按"最新原视频记录"回落
        List<Long> composePointedIds = new ArrayList<>();
        for (AidStoryboard storyboard : targets) {
            AidGenRecord record = recordById.get(storyboard.getFinalVideoId());
            if (Objects.nonNull(record) && GenTypeEnum.COMPOSE.getValue().equals(record.getGenType())) {
                composePointedIds.add(storyboard.getId());
            }
        }
        Map<Long, AidGenRecord> fallbackOriginals = loadLatestOriginalVideos(composePointedIds, userId);

        Map<Long, AidGenRecord> result = new HashMap<>();
        for (AidStoryboard storyboard : targets) {
            AidGenRecord record = recordById.get(storyboard.getFinalVideoId());
            if (Objects.nonNull(record) && GenTypeEnum.COMPOSE.getValue().equals(record.getGenType())) {
                record = fallbackOriginals.get(storyboard.getId());
                if (Objects.isNull(record)) {
                    log.info("批量对口型分镜无原视频可作源视频, storyboardId={}", storyboard.getId());
                    throw new ServiceException("请先生成视频");
                }
            }
            if (Objects.isNull(record) || !Objects.equals(DEL_FLAG_NORMAL, record.getDelFlag())
                    || !Objects.equals(userId, record.getUserId()) || StrUtil.isBlank(record.getFileUrl())) {
                log.info("批量对口型分镜视频不可用, storyboardId={}, videoRecordId={}",
                        storyboard.getId(), storyboard.getFinalVideoId());
                throw new ServiceException("素材异常");
            }
            if (Objects.isNull(record.getVideoDuration()) || record.getVideoDuration() <= 0) {
                log.info("批量对口型分镜视频时长缺失, storyboardId={}, videoRecordId={}",
                        storyboard.getId(), record.getId());
                throw new ServiceException("视频时长缺失");
            }
            result.put(storyboard.getId(), record);
        }
        return result;
    }

    /**
     * 批量取分镜「最新原视频记录」（i2v/multi/edge/upload_video，有文件），
     * 供 final_video_id 误指配音视频的历史数据回落使用。
     * 查询字段精简：仅 select id/storyboard_id/user_id/file_url/video_duration/gen_type/del_flag。
     */
    private Map<Long, AidGenRecord> loadLatestOriginalVideos(List<Long> storyboardIds, Long userId) {
        Map<Long, AidGenRecord> result = new HashMap<>();
        if (CollectionUtil.isEmpty(storyboardIds)) {
            return result;
        }
        List<AidGenRecord> records = aidGenRecordService.list(
                Wrappers.<AidGenRecord>lambdaQuery()
                        .select(AidGenRecord::getId, AidGenRecord::getStoryboardId, AidGenRecord::getUserId,
                                AidGenRecord::getFileUrl, AidGenRecord::getVideoDuration,
                                AidGenRecord::getGenType, AidGenRecord::getDelFlag)
                        .in(AidGenRecord::getStoryboardId, storyboardIds)
                        .eq(AidGenRecord::getUserId, userId)
                        .in(AidGenRecord::getGenType, GenTypeEnum.originalVideoValues())
                        .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL)
                        .isNotNull(AidGenRecord::getFileUrl)
                        .orderByAsc(AidGenRecord::getId));
        // id 升序遍历、后写覆盖 → 每分镜保留最新一条
        for (AidGenRecord record : records) {
            if (StrUtil.isNotBlank(record.getFileUrl())) {
                result.put(record.getStoryboardId(), record);
            }
        }
        return result;
    }

    /**
     * 批量预解析每分镜音色：台词首个角色段 → 角色资产（主名归一化匹配）→ 启用音色绑定（剧集精确优先）。
     * 解析不出的分镜 map 无该 key（提交时回落请求兜底音色）。口径与批量配音完全一致。
     */
    private Map<Long, Long> resolveVoiceBindings(List<AidStoryboard> targets, Long projectId,
                                                 Long episodeId, Long userId) {
        Map<Long, Long> result = new HashMap<>();
        // 加载项目级角色目录（查询字段精简：仅 id/name）
        List<AidRolePropScene> assets = rpsService.list(
                Wrappers.<AidRolePropScene>lambdaQuery()
                        .select(AidRolePropScene::getId, AidRolePropScene::getName)
                        .eq(AidRolePropScene::getProjectId, projectId)
                        .eq(AidRolePropScene::getUserId, userId)
                        .eq(AidRolePropScene::getAssetType, ASSET_TYPE_CHARACTER)
                        .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL));
        if (CollectionUtil.isEmpty(assets)) {
            return result;
        }
        Map<String, Long> assetIdByKey = new HashMap<>();
        for (AidRolePropScene asset : assets) {
            if (StrUtil.isNotBlank(asset.getName())) {
                // 归一化口径与配音链路一致（连字符统一为下划线），同名取先入库者
                assetIdByKey.putIfAbsent(
                        StoryboardImageReferenceResolver.normalizeAssetRefName(asset.getName()), asset.getId());
            }
        }
        // 加载启用绑定（剧集精确优先于全局；查询字段精简）
        Set<Long> assetIds = new LinkedHashSet<>(assetIdByKey.values());
        List<AidRoleVoiceBinding> bindings = roleVoiceBindingService.list(
                Wrappers.<AidRoleVoiceBinding>lambdaQuery()
                        .select(AidRoleVoiceBinding::getAssetId, AidRoleVoiceBinding::getEpisodeId,
                                AidRoleVoiceBinding::getVoiceLibraryId)
                        .in(AidRoleVoiceBinding::getAssetId, assetIds)
                        .eq(AidRoleVoiceBinding::getUserId, userId)
                        .and(wrapper -> wrapper.eq(AidRoleVoiceBinding::getEpisodeId, episodeId)
                                .or().eq(AidRoleVoiceBinding::getEpisodeId, 0L)
                                .or().isNull(AidRoleVoiceBinding::getEpisodeId))
                        .eq(AidRoleVoiceBinding::getStatus, STATUS_NORMAL)
                        .eq(AidRoleVoiceBinding::getDelFlag, DEL_FLAG_NORMAL));
        Map<Long, Long> voiceByAssetId = new HashMap<>();
        Map<Long, Boolean> exactByAssetId = new HashMap<>();
        for (AidRoleVoiceBinding binding : bindings) {
            if (Objects.isNull(binding.getAssetId()) || Objects.isNull(binding.getVoiceLibraryId())) {
                continue;
            }
            boolean exact = Objects.equals(binding.getEpisodeId(), episodeId);
            // 剧集精确绑定优先覆盖全局绑定
            if (!voiceByAssetId.containsKey(binding.getAssetId())
                    || (exact && !Boolean.TRUE.equals(exactByAssetId.get(binding.getAssetId())))) {
                voiceByAssetId.put(binding.getAssetId(), binding.getVoiceLibraryId());
                exactByAssetId.put(binding.getAssetId(), exact);
            }
        }
        // 逐分镜：首个角色段 → 资产 → 绑定音色（整段单音色，与单分镜配音口径一致）
        for (AidStoryboard storyboard : targets) {
            List<DialogueSegment> segments = audioReferenceResolver.parse(storyboard.getDialogueText());
            for (DialogueSegment segment : segments) {
                if (segment.isNarration()) {
                    continue;
                }
                Long assetId = lookupAssetId(assetIdByKey, segment);
                if (Objects.nonNull(assetId) && voiceByAssetId.containsKey(assetId)) {
                    result.put(storyboard.getId(), voiceByAssetId.get(assetId));
                }
                break;
            }
        }
        return result;
    }

    /** 段角色 → 资产ID：先按角色主名精确，再按完整引用名回退（口径与配音链路一致）。 */
    private Long lookupAssetId(Map<String, Long> assetIdByKey, DialogueSegment segment) {
        Long hit = null;
        if (StrUtil.isNotBlank(segment.getRoleName())) {
            hit = assetIdByKey.get(StoryboardImageReferenceResolver.normalizeAssetRefName(segment.getRoleName()));
        }
        if (Objects.isNull(hit) && StrUtil.isNotBlank(segment.getRoleRef())) {
            hit = assetIdByKey.get(StoryboardImageReferenceResolver.normalizeAssetRefName(segment.getRoleRef()));
        }
        return hit;
    }

    /**
     * 前置校验本批用到的所有音色可用性：启用未删、未到下架时间、所属模型启用；
     * 老入参兜底（voiceModelId）同样校验模型启用。校验不过整批拒绝、不产生任务记录。
     * 查询字段精简：音色仅 select id/status/del_flag/offline_time/model_id，模型仅 select id/status/model_code。
     */
    private void validateVoicesUsable(Map<Long, Long> voiceByStoryboardId, Long fallbackVoiceLibraryId,
                                      Long fallbackVoiceModelId) {
        Set<Long> voiceIds = new LinkedHashSet<>(voiceByStoryboardId.values());
        if (Objects.nonNull(fallbackVoiceLibraryId)) {
            voiceIds.add(fallbackVoiceLibraryId);
        }
        voiceIds.remove(null);
        Set<Long> modelIds = new LinkedHashSet<>();
        if (CollectionUtil.isNotEmpty(voiceIds)) {
            List<AidAiVoiceLibrary> voices = aidAiVoiceLibraryService.list(
                    Wrappers.<AidAiVoiceLibrary>lambdaQuery()
                            .select(AidAiVoiceLibrary::getId, AidAiVoiceLibrary::getStatus,
                                    AidAiVoiceLibrary::getDelFlag, AidAiVoiceLibrary::getOfflineTime,
                                    AidAiVoiceLibrary::getModelId)
                            .in(AidAiVoiceLibrary::getId, voiceIds));
            Map<Long, AidAiVoiceLibrary> voiceById = voices.stream()
                    .collect(Collectors.toMap(AidAiVoiceLibrary::getId, v -> v, (a, b) -> a));
            long now = System.currentTimeMillis();
            for (Long voiceId : voiceIds) {
                AidAiVoiceLibrary voice = voiceById.get(voiceId);
                if (Objects.isNull(voice) || !Objects.equals(DEL_FLAG_NORMAL, voice.getDelFlag())
                        || !Objects.equals(STATUS_NORMAL, voice.getStatus())) {
                    log.info("批量对口型前置校验音色不可用, voiceLibraryId={}", voiceId);
                    throw new ServiceException("音色不可用");
                }
                if (Objects.nonNull(voice.getOfflineTime()) && voice.getOfflineTime().getTime() <= now) {
                    log.info("批量对口型前置校验音色已下架, voiceLibraryId={}, offlineTime={}",
                            voiceId, voice.getOfflineTime());
                    throw new ServiceException("音色已下架");
                }
                if (Objects.nonNull(voice.getModelId())) {
                    modelIds.add(voice.getModelId());
                }
            }
        }
        // 老入参兜底模型一并校验启用
        if (Objects.nonNull(fallbackVoiceModelId)) {
            modelIds.add(fallbackVoiceModelId);
        }
        if (CollectionUtil.isEmpty(modelIds)) {
            return;
        }
        List<AidAiModel> models = aidAiModelService.list(
                Wrappers.<AidAiModel>lambdaQuery()
                        .select(AidAiModel::getId, AidAiModel::getStatus, AidAiModel::getModelCode)
                        .in(AidAiModel::getId, modelIds));
        Map<Long, AidAiModel> modelById = models.stream()
                .filter(m -> Objects.equals(STATUS_NORMAL, m.getStatus()))
                .collect(Collectors.toMap(AidAiModel::getId, m -> m, (a, b) -> a));
        for (Long modelId : modelIds) {
            if (Objects.isNull(modelById.get(modelId))) {
                log.info("批量对口型前置校验模型已停用, modelId={}", modelId);
                throw new ServiceException("模型已停用");
            }
        }
    }

    /**
     * MiniMax 音色单条文本上限前置校验：逐分镜定位所用 TTS 模型，MiniMax 模型的分镜
     * 按清洗后台词长度校验（与实际下发 TTS 的文本同口径），超限整批拒绝。
     * 查询字段精简：音色仅 select id/model_id，模型仅 select id/model_code/capability_json。
     */
    private void validateMinimaxTextLimit(List<AidStoryboard> targets, Map<Long, Long> voiceByStoryboardId,
                                          Long fallbackVoiceLibraryId, Long fallbackVoiceModelId) {
        // 音色 → 模型ID 映射（绑定音色 + 兜底音色）
        Set<Long> voiceIds = new LinkedHashSet<>(voiceByStoryboardId.values());
        if (Objects.nonNull(fallbackVoiceLibraryId)) {
            voiceIds.add(fallbackVoiceLibraryId);
        }
        voiceIds.remove(null);
        Map<Long, Long> modelIdByVoiceId = new HashMap<>();
        if (CollectionUtil.isNotEmpty(voiceIds)) {
            List<AidAiVoiceLibrary> voices = aidAiVoiceLibraryService.list(
                    Wrappers.<AidAiVoiceLibrary>lambdaQuery()
                            .select(AidAiVoiceLibrary::getId, AidAiVoiceLibrary::getModelId)
                            .in(AidAiVoiceLibrary::getId, voiceIds));
            for (AidAiVoiceLibrary voice : voices) {
                if (Objects.nonNull(voice.getModelId())) {
                    modelIdByVoiceId.put(voice.getId(), voice.getModelId());
                }
            }
        }
        Set<Long> modelIds = new LinkedHashSet<>(modelIdByVoiceId.values());
        if (Objects.nonNull(fallbackVoiceModelId)) {
            modelIds.add(fallbackVoiceModelId);
        }
        if (CollectionUtil.isEmpty(modelIds)) {
            return;
        }
        // 三级判定各模型是否 MiniMax（providerCode → capability_json.provider → supportsModel 兜底）
        Set<Long> minimaxModelIds = resolveMinimaxModelIds(modelIds);
        if (CollectionUtil.isEmpty(minimaxModelIds)) {
            return;
        }
        for (AidStoryboard storyboard : targets) {
            Long voiceId = voiceByStoryboardId.get(storyboard.getId());
            if (Objects.isNull(voiceId)) {
                voiceId = fallbackVoiceLibraryId;
            }
            Long modelId = Objects.nonNull(voiceId) ? modelIdByVoiceId.get(voiceId) : fallbackVoiceModelId;
            if (Objects.isNull(modelId) || !minimaxModelIds.contains(modelId)) {
                continue;
            }
            String sanitized = DialogueTextSanitizer.sanitize(storyboard.getDialogueText());
            if (StrUtil.length(sanitized) > MinimaxTtsConstants.BATCH_TEXT_MAX_LENGTH) {
                log.info("批量对口型 MiniMax 单条文本超限: storyboardId={}, textLen={}, max={}",
                        storyboard.getId(), StrUtil.length(sanitized), MinimaxTtsConstants.BATCH_TEXT_MAX_LENGTH);
                throw new ServiceException("文本过长");
            }
        }
    }

    /**
     * 批量判定 MiniMax 模型集合。
     * 查询字段精简：仅 select id/model_code/capability_json。
     */
    private Set<Long> resolveMinimaxModelIds(Set<Long> modelIds) {
        Set<Long> result = new LinkedHashSet<>();
        List<AidAiModel> models = aidAiModelService.list(
                Wrappers.<AidAiModel>lambdaQuery()
                        .select(AidAiModel::getId, AidAiModel::getModelCode, AidAiModel::getCapabilityJson)
                        .in(AidAiModel::getId, modelIds));
        for (AidAiModel model : models) {
            String providerCode = null;
            try {
                AiModelConfigVo config = aiModelConfigService.selectByModelId(model.getId());
                if (Objects.nonNull(config)) {
                    providerCode = config.getProviderCode();
                }
            } catch (Exception e) {
                log.warn("批量对口型获取 providerCode 失败: modelId={}, err={}", model.getId(), e.getMessage());
            }
            String capabilityProvider = MinimaxProviderDetector.parseCapabilityProvider(model.getCapabilityJson());
            if (minimaxProviderDetector.isMinimax(providerCode, capabilityProvider, model.getModelCode())) {
                result.add(model.getId());
            }
        }
        return result;
    }

    /**
     * 解析分镜台词中的发言角色主名（按出现顺序去重）。
     * 旁白段与无角色标记的纯文本段不计入；解析异常返回空列表（展示增强，不阻断流程）。
     */
    private List<String> resolveSpeakerRoles(String dialogueText) {
        List<String> roles = new ArrayList<>();
        if (StrUtil.isBlank(dialogueText)) {
            return roles;
        }
        try {
            List<DialogueSegment> segments = audioReferenceResolver.parse(dialogueText);
            Set<String> seen = new LinkedHashSet<>();
            for (DialogueSegment segment : segments) {
                if (segment.isNarration() || StrUtil.isBlank(segment.getRoleName())) {
                    continue;
                }
                seen.add(segment.getRoleName().trim());
            }
            roles.addAll(seen);
        } catch (Exception ex) {
            log.warn("发言角色解析失败(忽略), err={}", ex.getMessage());
        }
        return roles;
    }

    /**
     * Vidu 单次对口型只能选择一张目标人脸，混合角色或“旁白+角色”不能共用一个音色提交。
     */
    private void validateSingleSpeaker(AidStoryboard storyboard) {
        List<DialogueSegment> segments = audioReferenceResolver.parse(storyboard.getDialogueText());
        Set<String> speakers = new LinkedHashSet<>();
        for (DialogueSegment segment : segments) {
            String speaker = segment.isNarration()
                    ? "__narration__"
                    : StoryboardImageReferenceResolver.normalizeAssetRefName(
                            StrUtil.blankToDefault(segment.getRoleName(), segment.getRoleRef()));
            speakers.add(speaker);
        }
        if (speakers.size() > 1) {
            log.info("对口型包含多个发言角色, storyboardId={}, speakers={}",
                    storyboard.getId(), speakers);
            throw new ServiceException("仅支持单角色");
        }
    }

    // ==================== 父任务管理 ====================

    /** 查同项目+剧集下活跃（PENDING/PROCESSING）的批量对口型父任务；无则 null。 */
    private AidExtractTask findActiveTask(Long projectId, Long episodeId, Long userId) {
        return extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getStatus,
                                AidExtractTask::getTotalCount)
                        .eq(AidExtractTask::getProjectId, projectId)
                        .eq(AidExtractTask::getEpisodeId, episodeId)
                        .eq(AidExtractTask::getUserId, userId)
                        .eq(AidExtractTask::getTaskType, TASK_TYPE_LIP_SYNC_BATCH)
                        .in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING)
                        .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByDesc(AidExtractTask::getId)
                        .last("LIMIT 1"), false);
    }

    /** 父任务置 FAILED（仅非终态可置，避免覆盖已写入的终态）。 */
    private void failTask(Long taskId, String errorMessage) {
        extractTaskService.update(Wrappers.<AidExtractTask>lambdaUpdate()
                .eq(AidExtractTask::getId, taskId)
                .in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING)
                .set(AidExtractTask::getStatus, TASK_STATUS_FAILED)
                .set(AidExtractTask::getErrorMessage, errorMessage)
                .set(AidExtractTask::getUpdateTime, DateUtils.getNowDate())
                .set(AidExtractTask::getUpdateBy, "system"));
    }

    /** 构建输入快照JSON（排查依据，含逐分镜音色与源视频记录ID；单个与批量共用）。 */
    private String buildInputSnapshot(Long projectId, Long episodeId, List<AidStoryboard> targets,
                                      LipSyncTtsParams tts, boolean overwrite,
                                      Map<Long, Long> voiceByStoryboardId,
                                      Map<Long, AidGenRecord> sourceVideoByStoryboardId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("projectId", projectId);
        snapshot.put("episodeId", episodeId);
        snapshot.put("storyboardIds", targets.stream().map(AidStoryboard::getId).collect(Collectors.toList()));
        snapshot.put("fallbackVoiceLibraryId", tts.voiceLibraryId());
        snapshot.put("overwrite", overwrite);
        snapshot.put("resolvedVoices", voiceByStoryboardId);
        Map<Long, Long> sourceVideoIds = new LinkedHashMap<>();
        for (AidStoryboard s : targets) {
            AidGenRecord record = sourceVideoByStoryboardId.get(s.getId());
            sourceVideoIds.put(s.getId(), Objects.isNull(record) ? null : record.getId());
        }
        snapshot.put("sourceVideoRecordIds", sourceVideoIds);
        try {
            return OBJECT_MAPPER.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.error("对口型 inputSnapshot 序列化失败", e);
            throw new ServiceException("提交失败，请重试");
        }
    }

    /** ItemContext → 明细 Map 列表（resultData / SSE 载荷） */
    private List<Map<String, Object>> toItemMaps(List<ItemContext> items) {
        List<Map<String, Object>> result = new ArrayList<>(items.size());
        for (ItemContext ctx : items) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("storyboardId", ctx.storyboardId);
            item.put("speakerRoles", Objects.isNull(ctx.speakerRoles) ? new ArrayList<>() : ctx.speakerRoles);
            item.put("voiceLibraryId", ctx.voiceLibraryId);
            item.put("audioRecordId", ctx.audioRecordId);
            item.put("audioMediaTaskId", ctx.audioMediaTaskId);
            item.put("audioUrl", ctx.audioUrl);
            item.put("durationMs", ctx.audioDurationMs);
            item.put("sourceVideoRecordId", ctx.sourceVideoRecordId);
            item.put("lipSyncMediaTaskId", ctx.lipSyncMediaTaskId);
            item.put("lipSyncVideoRecordId", ctx.lipSyncVideoRecordId);
            item.put("lipSyncVideoUrl", ctx.lipSyncVideoUrl);
            item.put("workflowStage", ctx.workflowStage);
            item.put("status", Objects.isNull(ctx.status) ? TASK_STATUS_PROCESSING : ctx.status);
            item.put("errorMessage", ctx.errorMessage);
            result.add(item);
        }
        return result;
    }

    /** 回写进度/终态 resultData（含每条明细）；终态时同步更新任务状态与错误信息。 */
    private void writeResultData(Long taskId, String status, List<ItemContext> items, String errorMessage) {
        String json;
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", toItemMaps(items));
            json = OBJECT_MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("批量对口型 resultData 序列化失败, taskId={}", taskId, e);
            return;
        }
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        // 任何进度/终态回写都只允许命中非终态，禁止迟到事件把已失败/取消任务复活。
        update.in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING);
        update.set(AidExtractTask::getResultData, json);
        if (!TASK_STATUS_PROCESSING.equals(status)) {
            update.set(AidExtractTask::getStatus, status);
            update.set(AidExtractTask::getErrorMessage, errorMessage);
        }
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        update.set(AidExtractTask::getUpdateBy, "system");
        extractTaskService.update(update);
    }
}
