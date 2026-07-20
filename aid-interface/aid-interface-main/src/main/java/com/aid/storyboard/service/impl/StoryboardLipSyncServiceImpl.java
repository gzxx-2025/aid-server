package com.aid.storyboard.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
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
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiVoiceLibraryService;
import com.aid.aid.service.IAidAudioRecordService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidGenRecordService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidRoleVoiceBindingService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.enums.CreationStepEnum;
import com.aid.enums.GenTypeEnum;
import com.aid.media.constants.MinimaxTtsConstants;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.provider.MinimaxProviderDetector;
import com.aid.media.service.IMediaGenerationService;
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
 * 全程复用统一任务与扣费流程；批量模式产物自动设为 compose 类主视频，单个模式由用户手选。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class StoryboardLipSyncServiceImpl implements IStoryboardLipSyncService {

    /** 批量父任务类型（aid_extract_task.task_type） */
    private static final String TASK_TYPE_LIP_SYNC_BATCH = "storyboard_lip_sync_generate";

    /** SSE 进度阶段标识 */
    private static final String SSE_STAGE = "storyboard_lip_sync_generate";

    /** 任务状态（与其它批量任务字符串口径一致） */
    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";

    /** 单批分镜上限（与批量配音同口径） */
    private static final int MAX_BATCH_SIZE = 50;

    /** 活跃父任务视为「在跑」的最大静默时长（毫秒）：超时视为中断残留，自动置 FAILED 放行新批次 */
    private static final long ACTIVE_TASK_STALE_MS = 30L * 60L * 1000L;

    /** 轮询间隔（毫秒） */
    private static final long POLL_INTERVAL_MS = 5000L;

    /** 单个受理内配音 OSS 兜底等待上限（次）：2s × 10 = 20 秒（同步 TTS 极少数 OSS 未就绪场景） */
    private static final int SINGLE_AUDIO_WAIT_MAX_TIMES = 10;

    /** 单个受理内配音兜底等待间隔（毫秒） */
    private static final long SINGLE_AUDIO_WAIT_INTERVAL_MS = 2000L;

    /** 批量配音 OSS 兜底等待上限（次）：5s × 12 = 60 秒（异步线程内，与批量配音同口径） */
    private static final int BATCH_AUDIO_WAIT_MAX_TIMES = 12;

    /** 对口型阶段轮询上限（次）：5s × 240 = 20 分钟，覆盖整批上游并行合成 */
    private static final int LIP_SYNC_POLL_MAX_TIMES = 240;

    /** 对口型成功后等待配音视频记录落库的重试上限（次，间隔 2 秒；监听器同 JVM 事件极短） */
    private static final int GEN_RECORD_WAIT_MAX_TIMES = 6;

    /** 配音视频记录等待间隔（毫秒） */
    private static final long GEN_RECORD_WAIT_INTERVAL_MS = 2000L;

    /** 进度：配音+提交阶段区间 [5,60)，对口型等待阶段区间 [60,100) */
    private static final int PROGRESS_DUB_BASE = 5;
    private static final int PROGRESS_DUB_SPAN = 55;
    private static final int PROGRESS_LIP_SYNC_BASE = 60;
    private static final int PROGRESS_LIP_SYNC_SPAN = 40;

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

    /** 台词朗读时长预估：约 4.5 字/秒（与既有配音预估口径一致） */
    private static final double ESTIMATED_CHARS_PER_SECOND = 4.5;

    /** 时长错配拦截：预计朗读时长超过源视频时长的倍数阈值 */
    private static final double LIP_SYNC_AUDIO_OVER_RATIO = 1.5;

    /** 时长错配拦截：预计朗读时长超出源视频的绝对秒数阈值（与倍数阈值同时满足才拦截） */
    private static final int LIP_SYNC_AUDIO_OVER_SECONDS = 2;

    /** 对口型官方计价粒度（秒）：计费时长向上取整到该粒度 */
    private static final int LIP_SYNC_BILLING_GRANULARITY_SECONDS = 5;

    /** 毫秒 → 秒换算 */
    private static final double MS_PER_SECOND = 1000.0;

    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 状态：启用 */
    private static final String STATUS_NORMAL = "0";

    /** 单分镜对口型受理锁前缀：覆盖「防重校验→TTS→提交对口型」全过程，堵并发双击竞态 */
    private static final String LIP_SYNC_LOCK_PREFIX = "storyboard:lip_sync:lock:";

    /** 单分镜对口型受理锁 TTL（秒）：覆盖同步 TTS + OSS 兜底等待 + 提交的最长耗时 */
    private static final long LIP_SYNC_LOCK_TTL_SECONDS = 120L;

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
    private AidMediaTaskMapper aidMediaTaskMapper;

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

    /** 媒体 URL 解析器：DB 相对路径 → 完整可访问 URL（下游 Provider 需完整 URL） */
    @Resource
    private MediaUrlResolver mediaUrlResolver;

    /** 任务执行租约登记/心跳（僵尸回收按租约判活，PROCESSING 期间必须持有租约） */
    @Resource
    private IAssetExtractService assetExtractService;

    /** 通用线程池：承载批量对口型的异步执行 */
    @Resource
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    /** Redis 缓存：单分镜对口型受理锁（SETNX + token CAS 释放） */
    @Resource
    private RedisCache redisCache;

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
        /** 对口型媒体任务ID（aid_media_task.id） */
        Long lipSyncMediaTaskId;
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
    public AudioTaskVO lipSync(LipSyncRequest request, Long userId) {
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

        // 分镜级受理锁（SETNX+token）：覆盖「防重校验→TTS→提交」全过程，堵并发双击双任务双扣费
        String lockKey = LIP_SYNC_LOCK_PREFIX + storyboard.getId();
        String lockToken = IdUtil.fastSimpleUUID();
        if (!tryAcquireLipSyncLock(lockKey, lockToken)) {
            log.info("对口型受理锁竞争失败, storyboardId={}", storyboard.getId());
            throw new ServiceException("对口型进行中");
        }
        try {
            // 防重：该分镜最新对口型任务仍在进行中时拒绝重复提交
            assertNoRunningLipSync(storyboard.getId(), userId);

            // 音色解析：台词角色绑定音色优先 → 请求兜底音色 → 双空拒绝
            Long boundVoiceLibraryId = resolveVoiceBindings(Collections.singletonList(storyboard),
                    storyboard.getProjectId(), storyboard.getEpisodeId(), userId).get(storyboard.getId());
            boolean hasFallback = Objects.nonNull(request.getVoiceLibraryId())
                    || (Objects.nonNull(request.getVoiceModelId()) && StrUtil.isNotBlank(request.getTimbreCode()));
            if (Objects.isNull(boundVoiceLibraryId) && !hasFallback) {
                log.info("对口型分镜未绑定音色且无兜底音色, storyboardId={}", storyboard.getId());
                throw new ServiceException("请先绑定音色");
            }

            // 时长错配前置拦截（零成本，任务生成前）：按台词字数预估朗读时长 vs 源视频时长
            validateEstimatedDuration(sanitized, videoRecord);

            // 对口型模型前置解析：未配置时在 TTS 之前拒绝，避免白扣配音费用
            AidAiModel lipSyncModel = resolveLipSyncModel();

            // 台词现场 TTS 配音（统一任务 + 统一计费；同步链路，OSS 未就绪兜底短等待）
            AidAudioRecord audioRecord = dubForLipSync(storyboard, boundVoiceLibraryId,
                    request.getVoiceLibraryId(), request.getVoiceModelId(), request.getTimbreCode(),
                    request.getEmotion(), request.getEmotionScale(), request.getSpeechRate(),
                    request.getLoudnessRate(), request.getPitch(), userId,
                    SINGLE_AUDIO_WAIT_MAX_TIMES, SINGLE_AUDIO_WAIT_INTERVAL_MS);

            // 提交对口型（统一任务 + 统一计费），回写配音记录关联
            submitLipSyncTask(storyboard, videoRecord, audioRecord, lipSyncModel, userId);

            // 受理返回：前端按 id 轮询 GET /api/user/storyboard/audio/{taskId} 获取对口型进度
            AidAudioRecord fresh = aidAudioRecordService.getById(audioRecord.getId());
            return buildAcceptedVO(Objects.nonNull(fresh) ? fresh : audioRecord);
        } finally {
            releaseLipSyncLock(lockKey, lockToken);
        }
    }

    /**
     * 获取单分镜对口型受理锁（SETNX，带 TTL 防死锁）。
     *
     * @param key   锁键
     * @param token 持锁标识（释放时 CAS 校验，防误删他人锁）
     * @return true=获取成功
     */
    private boolean tryAcquireLipSyncLock(String key, String token) {
        Boolean ok = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(key, token, LIP_SYNC_LOCK_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 释放单分镜对口型受理锁：token 一致才删除（Lua CAS），锁已过期被他人持有时不误删。
     *
     * @param key   锁键
     * @param token 持锁标识
     */
    private void releaseLipSyncLock(String key, String token) {
        try {
            String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
            redisCache.redisTemplate.execute(
                    new DefaultRedisScript<>(script, Long.class),
                    Collections.singletonList(key), token);
        } catch (Exception e) {
            log.warn("对口型受理锁释放失败, key={}, msg={}", key, e.getMessage());
        }
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

        // 防重：同项目+剧集已有活跃批量对口型任务 → 幂等返回该任务（前端重连 SSE）；
        //       静默超 30 分钟的活跃任务视为中断残留，置 FAILED 后放行新批次
        AidExtractTask active = findActiveTask(projectId, episodeId, userId);
        if (Objects.nonNull(active)) {
            if (isStale(active)) {
                markStaleFailed(active);
            } else {
                log.info("批量对口型重连活跃任务: taskId={}, projectId={}, episodeId={}",
                        active.getId(), projectId, episodeId);
                return AssetExtractTaskVO.builder()
                        .taskId(active.getId())
                        .status(active.getStatus())
                        .totalCount(active.getTotalCount())
                        .build();
            }
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
        validateVoicesUsable(voiceByStoryboardId, request.getVoiceLibraryId(), request.getVoiceModelId());

        // MiniMax 音色单条文本上限前置校验（500 字符）：超限整批拒绝，不产生任务记录
        validateMinimaxTextLimit(targets, voiceByStoryboardId, request.getVoiceLibraryId(),
                request.getVoiceModelId());

        // 对口型模型前置解析：未配置整批拒绝；父任务 model_code 记对口型模型编码
        AidAiModel lipSyncModel = resolveLipSyncModel();

        // 创建父任务（SSE / 微信推送锚点）；配音与对口型的计费随各自统一任务逐条冻结/结算，父级不另设扣费
        AidExtractTask task = new AidExtractTask();
        task.setProjectId(projectId);
        task.setEpisodeId(episodeId);
        task.setUserId(userId);
        task.setTaskType(TASK_TYPE_LIP_SYNC_BATCH);
        task.setStatus(TASK_STATUS_PENDING);
        task.setModelCode(lipSyncModel.getModelCode());
        task.setTotalCount(targets.size());
        task.setInputSnapshot(buildInputSnapshot(request, targets, voiceByStoryboardId, finalVideoByStoryboardId));
        task.setDelFlag(DEL_FLAG_NORMAL);
        task.setCreateTime(DateUtils.getNowDate());
        task.setCreateBy(String.valueOf(userId));
        task.setUpdateTime(DateUtils.getNowDate());
        task.setUpdateBy(String.valueOf(userId));
        extractTaskService.save(task);
        Long taskId = task.getId();

        List<AidStoryboard> finalTargets = targets;
        try {
            threadPoolTaskExecutor.execute(() -> executeBatch(taskId, request, finalTargets,
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
     * 异步执行：阶段一逐分镜串行「TTS 配音 + 提交对口型」（同用户计费锁天然串行）；
     * 阶段二统一等待全部对口型终态（上游并行），产物就绪后逐条设为 compose 类主视频。
     */
    private void executeBatch(Long taskId, StoryboardLipSyncBatchRequest request, List<AidStoryboard> targets,
                              Map<Long, Long> voiceByStoryboardId,
                              Map<Long, AidGenRecord> finalVideoByStoryboardId,
                              AidAiModel lipSyncModel, Long userId) {
        // PENDING → PROCESSING（CAS，防重复执行）
        boolean started = extractTaskService.update(Wrappers.<AidExtractTask>lambdaUpdate()
                .eq(AidExtractTask::getId, taskId)
                .eq(AidExtractTask::getStatus, TASK_STATUS_PENDING)
                .set(AidExtractTask::getStatus, TASK_STATUS_PROCESSING)
                .set(AidExtractTask::getUpdateTime, DateUtils.getNowDate()));
        if (!started) {
            log.info("批量对口型任务状态已变化，跳过执行: taskId={}", taskId);
            return;
        }
        // 登记执行租约 + 心跳常驻续租：僵尸回收按租约判活，等待期间必须持有租约
        assetExtractService.markTaskProcessing(taskId);
        try {
            int total = targets.size();
            List<ItemContext> items = new ArrayList<>(total);

            // ===== 阶段一：逐分镜串行 TTS 配音 + 提交对口型 =====
            for (int i = 0; i < total; i++) {
                AidStoryboard storyboard = targets.get(i);
                ItemContext ctx = new ItemContext();
                ctx.storyboardId = storyboard.getId();
                AidGenRecord sourceVideo = finalVideoByStoryboardId.get(storyboard.getId());
                ctx.sourceVideoRecordId = Objects.isNull(sourceVideo) ? null : sourceVideo.getId();
                ctx.speakerRoles = resolveSpeakerRoles(storyboard.getDialogueText());
                Long boundVoice = voiceByStoryboardId.get(storyboard.getId());
                ctx.voiceLibraryId = Objects.nonNull(boundVoice) ? boundVoice : request.getVoiceLibraryId();
                items.add(ctx);
                try {
                    // 0) 分镜级防重：该分镜已有进行中的对口型任务则单条失败，不阻断整批
                    assertNoRunningLipSync(storyboard.getId(), userId);
                    // 0.5) 时长错配预估拦截（零成本）：超长台词单条失败
                    validateEstimatedDuration(DialogueTextSanitizer.sanitize(storyboard.getDialogueText()),
                            sourceVideo);
                    // 1) 台词现场 TTS 配音（统一任务 + 统一计费）
                    AidAudioRecord audioRecord = dubForLipSync(storyboard, boundVoice,
                            request.getVoiceLibraryId(), request.getVoiceModelId(), request.getTimbreCode(),
                            request.getEmotion(), request.getEmotionScale(), request.getSpeechRate(),
                            request.getLoudnessRate(), request.getPitch(), userId,
                            BATCH_AUDIO_WAIT_MAX_TIMES, POLL_INTERVAL_MS);
                    ctx.audioRecordId = audioRecord.getId();
                    // 2) 提交对口型任务（统一任务 + 统一计费）
                    ctx.lipSyncMediaTaskId = submitLipSyncTask(storyboard, sourceVideo, audioRecord,
                            lipSyncModel, userId);
                } catch (Exception e) {
                    // 单条失败不阻断整批
                    String reason = (e instanceof ServiceException) ? e.getMessage() : "对口型失败";
                    ctx.status = TASK_STATUS_FAILED;
                    ctx.errorMessage = reason;
                    log.error("批量对口型单条失败: taskId={}, storyboardId={}, reason={}",
                            taskId, storyboard.getId(), reason, e);
                }
                writeResultData(taskId, TASK_STATUS_PROCESSING, items, null);
                int progress = PROGRESS_DUB_BASE + (i + 1) * PROGRESS_DUB_SPAN / total;
                sseManager.sendProgress(taskId, SSE_STAGE, progress,
                        String.format("配音中 %d/%d", i + 1, total));
            }

            // ===== 阶段二：统一等待对口型终态（上游并行），产物就绪后设为 compose 类主视频 =====
            awaitLipSyncAndSwitch(taskId, items, userId, total);

            // ===== 终态汇总 =====
            long succeeded = items.stream().filter(it -> TASK_STATUS_SUCCEEDED.equals(it.status)).count();
            long failed = total - succeeded;
            String finalStatus;
            String errorMessage = null;
            if (failed == 0) {
                finalStatus = TASK_STATUS_SUCCEEDED;
            } else if (succeeded > 0) {
                finalStatus = TASK_STATUS_PARTIAL_FAILED;
                errorMessage = String.format("%d 条对口型失败", failed);
            } else {
                finalStatus = TASK_STATUS_FAILED;
                errorMessage = "对口型失败";
            }
            writeResultData(taskId, finalStatus, items, errorMessage);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("totalCount", total);
            payload.put("successCount", (int) succeeded);
            payload.put("failCount", (int) failed);
            payload.put("items", toItemMaps(items));
            if (TASK_STATUS_SUCCEEDED.equals(finalStatus)) {
                sseManager.sendComplete(taskId, payload);
            } else if (TASK_STATUS_PARTIAL_FAILED.equals(finalStatus)) {
                sseManager.sendPartialFailed(taskId, payload, errorMessage);
            } else {
                sseManager.sendError(taskId, errorMessage);
            }
            // 终态微信模板推送：与其它批量任务同一规则
            wechatNotifyService.notifyTaskTerminal(taskId);
            log.info("批量对口型完成: taskId={}, total={}, succeeded={}, failed={}, finalStatus={}",
                    taskId, total, succeeded, failed, finalStatus);
        } catch (Exception ex) {
            // 执行器级异常（DB 抖动等）：父任务置失败并通知；已提交的配音/对口型由各自事件链收尾
            log.error("批量对口型执行异常: taskId={}", taskId, ex);
            failTask(taskId, "对口型失败");
            try { sseManager.sendError(taskId, "对口型失败"); } catch (Exception ignore) { }
            try { wechatNotifyService.notifyTaskTerminal(taskId); } catch (Exception ignore) { }
        } finally {
            // 任何退出路径都停掉心跳续租，避免租约悬挂影响后续僵尸回收
            assetExtractService.deactivateTaskProcessingHeartbeat(taskId);
        }
    }

    /**
     * 台词现场 TTS 配音：复用单分镜配音链路（统一任务 + 统一计费）；返回已就绪（audioUrl 非空）的配音记录。
     * 台词原文直传，generateAudio 入口统一做台词标记清洗（剥 [角色_形象]：/@音频N/竖线等，仅保留可朗读正文）。
     *
     * @param storyboard            分镜（含台词）
     * @param boundVoiceLibraryId   角色绑定音色（可空）
     * @param fallbackVoiceLibraryId 请求兜底音色库ID（可空）
     * @param fallbackVoiceModelId  请求兜底模型ID（老入参，可空）
     * @param fallbackTimbreCode    请求兜底音色编码（老入参，可空）
     * @param waitMaxTimes          OSS 兜底等待上限（次）
     * @param waitIntervalMs        OSS 兜底等待间隔（毫秒）
     * @return 已就绪的配音记录
     */
    private AidAudioRecord dubForLipSync(AidStoryboard storyboard, Long boundVoiceLibraryId,
                                         Long fallbackVoiceLibraryId, Long fallbackVoiceModelId,
                                         String fallbackTimbreCode, String emotion, Integer emotionScale,
                                         Integer speechRate, Integer loudnessRate, Integer pitch, Long userId,
                                         int waitMaxTimes, long waitIntervalMs) {
        GenerateAudioRequest single = new GenerateAudioRequest();
        single.setStoryboardId(storyboard.getId());
        single.setTtsText(storyboard.getDialogueText());
        if (Objects.nonNull(boundVoiceLibraryId)) {
            single.setVoiceLibraryId(boundVoiceLibraryId);
        } else if (Objects.nonNull(fallbackVoiceLibraryId)) {
            single.setVoiceLibraryId(fallbackVoiceLibraryId);
        } else {
            // 兼容老入参兜底（前置校验已保证 voiceModelId+timbreCode 成对存在）
            single.setVoiceModelId(fallbackVoiceModelId);
            single.setTimbreCode(fallbackTimbreCode);
        }
        single.setEmotion(emotion);
        single.setEmotionScale(emotionScale);
        single.setSpeechRate(speechRate);
        single.setLoudnessRate(loudnessRate);
        single.setPitch(pitch);
        AudioTaskVO vo = storyboardWorkbenchService.generateAudio(single, userId);
        if (Objects.isNull(vo) || Objects.isNull(vo.getId())) {
            log.error("对口型 TTS 无返回, storyboardId={}", storyboard.getId());
            throw new ServiceException("配音失败");
        }
        // 常规路径：同步成功直接返回；兜底路径：短轮询等 OSS 回填
        for (int i = 0; i <= waitMaxTimes; i++) {
            AidAudioRecord record = aidAudioRecordService.getById(vo.getId());
            if (Objects.isNull(record) || MediaTaskStatus.FAILED.name().equals(record.getStatus())) {
                log.error("对口型 TTS 失败, storyboardId={}, audioRecordId={}", storyboard.getId(), vo.getId());
                throw new ServiceException("配音失败");
            }
            if (MediaTaskStatus.SUCCEEDED.name().equals(record.getStatus())
                    && StrUtil.isNotBlank(record.getAudioUrl())) {
                return record;
            }
            try {
                Thread.sleep(waitIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceException("配音失败");
            }
        }
        log.error("对口型等待音频就绪超时, storyboardId={}, audioRecordId={}", storyboard.getId(), vo.getId());
        throw new ServiceException("配音超时");
    }

    /**
     * 提交对口型任务（统一媒体任务 + 统一计费，SKU 按秒预冻结、失败自动退款），并回写配音记录关联。
     * 成功结果由既有 LipSyncEventListener 回填 sync_video_url 并落 compose 生成记录。
     *
     * @return 对口型媒体任务ID（aid_media_task.id）
     */
    private Long submitLipSyncTask(AidStoryboard storyboard, AidGenRecord videoRecord,
                                   AidAudioRecord audioRecord, AidAiModel lipSyncModel, Long userId) {
        // DB 存相对路径，下游 provider 需完整可访问 URL
        String videoUrl = mediaUrlResolver.toFullUrl(videoRecord.getFileUrl());
        String audioUrl = mediaUrlResolver.toFullUrl(audioRecord.getAudioUrl());

        MediaVideoGenerateRequest mediaReq = new MediaVideoGenerateRequest();
        mediaReq.setUserId(userId);
        mediaReq.setProjectId(storyboard.getProjectId());
        mediaReq.setEpisodeId(storyboard.getEpisodeId());
        mediaReq.setModelName(lipSyncModel.getModelCode());
        mediaReq.setPrompt(LIP_SYNC_TASK_PROMPT);
        // 计费时长（秒）：max(源视频时长, 配音时长) 向上取整到官方计价粒度，宁高勿低
        mediaReq.setDurationSeconds(resolveLipSyncDurationSeconds(videoRecord, audioRecord));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put(OPTIONS_KEY_VIDEO_URL, videoUrl);
        options.put(OPTIONS_KEY_AUDIO_URL, audioUrl);
        mediaReq.setOptions(options);
        // 业务任务关联：LipSyncEventListener 按 biz_task_type + biz_task_id 回填结果
        mediaReq.setBizTaskId(audioRecord.getId());
        mediaReq.setBizTaskType(BIZ_TASK_TYPE_LIP_SYNC);

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
        aidAudioRecordService.updateById(audioRecord);
        return mediaResp.getTaskId();
    }

    /**
     * 阶段二：轮询全部对口型媒体任务至终态；成功的等配音视频记录（LipSyncEventListener 落库）就绪后
     * 经 setFinalSelection 设为该分镜 compose 类主视频（compose 类内排他，分镜视频不受影响）。
     */
    private void awaitLipSyncAndSwitch(Long taskId, List<ItemContext> items, Long userId, int total) {
        for (int round = 0; round < LIP_SYNC_POLL_MAX_TIMES; round++) {
            List<ItemContext> pending = items.stream()
                    .filter(it -> !it.finished())
                    .collect(Collectors.toList());
            if (pending.isEmpty()) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("批量对口型等待被中断, taskId={}", taskId);
                return;
            }
            // 批查在途对口型任务状态（查询字段精简：仅终态判定必需列）
            Set<Long> mediaTaskIds = pending.stream()
                    .map(it -> it.lipSyncMediaTaskId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Map<Long, AidMediaTask> taskById = new HashMap<>();
            if (CollectionUtil.isNotEmpty(mediaTaskIds)) {
                List<AidMediaTask> tasks = aidMediaTaskMapper.selectList(
                        new LambdaQueryWrapper<AidMediaTask>()
                                .select(AidMediaTask::getId, AidMediaTask::getStatus, AidMediaTask::getOssUrl)
                                .in(AidMediaTask::getId, mediaTaskIds));
                for (AidMediaTask t : tasks) {
                    taskById.put(t.getId(), t);
                }
            }
            for (ItemContext ctx : pending) {
                AidMediaTask mediaTask = Objects.isNull(ctx.lipSyncMediaTaskId)
                        ? null : taskById.get(ctx.lipSyncMediaTaskId);
                if (Objects.isNull(mediaTask)) {
                    continue;
                }
                if (MediaTaskStatus.FAILED.name().equals(mediaTask.getStatus())) {
                    ctx.status = TASK_STATUS_FAILED;
                    ctx.errorMessage = "对口型失败";
                    log.error("批量对口型单条合成失败: taskId={}, storyboardId={}, mediaTaskId={}",
                            taskId, ctx.storyboardId, ctx.lipSyncMediaTaskId);
                } else if (MediaTaskStatus.SUCCEEDED.name().equals(mediaTask.getStatus())
                        && StrUtil.isNotBlank(mediaTask.getOssUrl())) {
                    finalizeLipSyncItem(taskId, ctx, mediaTask, userId);
                }
            }
            writeResultData(taskId, TASK_STATUS_PROCESSING, items, null);
            long done = items.stream().filter(ItemContext::finished).count();
            int progress = PROGRESS_LIP_SYNC_BASE + (int) (done * PROGRESS_LIP_SYNC_SPAN / total);
            sseManager.sendProgress(taskId, SSE_STAGE, progress,
                    String.format("对口型合成中 %d/%d", done, total));
        }
        // 轮询超时：剩余在途条目按超时失败（上游任务本体仍会跑完并落产物记录，用户可手动设为主视频）
        for (ItemContext ctx : items) {
            if (!ctx.finished()) {
                ctx.status = TASK_STATUS_FAILED;
                ctx.errorMessage = "对口型超时";
                log.error("批量对口型等待超时: taskId={}, storyboardId={}, mediaTaskId={}",
                        taskId, ctx.storyboardId, ctx.lipSyncMediaTaskId);
            }
        }
    }

    /**
     * 对口型成功收尾：等配音视频记录（LipSyncEventListener 按 fileUrl 幂等落库）就绪 →
     * setFinalSelection 设为 compose 类主视频（compose 类内排他，分镜视频与 final_video_id 不受影响）。
     */
    private void finalizeLipSyncItem(Long taskId, ItemContext ctx, AidMediaTask mediaTask, Long userId) {
        AidGenRecord lipSyncRecord = null;
        for (int i = 0; i < GEN_RECORD_WAIT_MAX_TIMES; i++) {
            // 查询字段精简：切换主视频仅需记录ID与地址
            lipSyncRecord = aidGenRecordService.getOne(Wrappers.<AidGenRecord>lambdaQuery()
                    .select(AidGenRecord::getId, AidGenRecord::getFileUrl)
                    .eq(AidGenRecord::getStoryboardId, ctx.storyboardId)
                    .eq(AidGenRecord::getGenType, GenTypeEnum.COMPOSE.getValue())
                    .eq(AidGenRecord::getFileUrl, mediaTask.getOssUrl())
                    .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL)
                    .orderByDesc(AidGenRecord::getId)
                    .last("LIMIT 1"), false);
            if (Objects.nonNull(lipSyncRecord)) {
                break;
            }
            try {
                Thread.sleep(GEN_RECORD_WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (Objects.isNull(lipSyncRecord)) {
            // 产物已出但记录未落（监听器异常等极端场景）：按失败记录，产物可从媒体任务复查
            ctx.status = TASK_STATUS_FAILED;
            ctx.errorMessage = "成片入库超时";
            log.error("批量对口型产物记录未就绪: taskId={}, storyboardId={}, mediaTaskId={}",
                    taskId, ctx.storyboardId, ctx.lipSyncMediaTaskId);
            return;
        }
        ctx.lipSyncVideoRecordId = lipSyncRecord.getId();
        ctx.lipSyncVideoUrl = lipSyncRecord.getFileUrl();
        try {
            // compose 类内排他切换：对口型视频设为该分镜配音视频主视频（旧配音视频自动置未选中）；
            // 分镜视频（video 类）与 finalVideoId 不受影响
            SetFinalSelectionRequest select = new SetFinalSelectionRequest();
            select.setStoryboardId(ctx.storyboardId);
            select.setRecordId(lipSyncRecord.getId());
            select.setRecordType(RECORD_TYPE_VIDEO);
            storyboardWorkbenchService.setFinalSelection(select, userId);
            ctx.status = TASK_STATUS_SUCCEEDED;
            log.info("批量对口型单条完成: taskId={}, storyboardId={}, lipSyncRecordId={}",
                    taskId, ctx.storyboardId, ctx.lipSyncVideoRecordId);
        } catch (Exception e) {
            // 产物已入库但切换失败：保留记录供用户手动设为主视频
            ctx.status = TASK_STATUS_FAILED;
            ctx.errorMessage = "切换主视频失败";
            log.error("批量对口型切换主视频失败: taskId={}, storyboardId={}, lipSyncRecordId={}",
                    taskId, ctx.storyboardId, ctx.lipSyncVideoRecordId, e);
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
     * 时长错配前置拦截（零成本，任务生成前）：按台词字数预估朗读时长（约 4.5 字/秒，向上取整），
     * 预估超过源视频时长 {@value #LIP_SYNC_AUDIO_OVER_RATIO} 倍且超出 {@value #LIP_SYNC_AUDIO_OVER_SECONDS}
     * 秒以上时拦截（上游输出跟随音频拉长，画面循环观感差且费用按输出秒数计）。
     * 源视频无时长（历史数据）不拦截；TTS 完成后不再二次拦截（计费按 max 已覆盖成本）。
     */
    private void validateEstimatedDuration(String sanitizedText, AidGenRecord videoRecord) {
        if (Objects.isNull(videoRecord) || Objects.isNull(videoRecord.getVideoDuration())
                || videoRecord.getVideoDuration() <= 0 || StrUtil.isBlank(sanitizedText)) {
            return;
        }
        int estimatedSeconds = (int) Math.ceil(sanitizedText.length() / ESTIMATED_CHARS_PER_SECOND);
        long videoSeconds = videoRecord.getVideoDuration();
        boolean overRatio = estimatedSeconds > videoSeconds * LIP_SYNC_AUDIO_OVER_RATIO;
        boolean overSeconds = estimatedSeconds - videoSeconds > LIP_SYNC_AUDIO_OVER_SECONDS;
        if (overRatio && overSeconds) {
            log.info("对口型时长错配预估拦截, videoSeconds={}, estimatedSeconds={}, chars={}",
                    videoSeconds, estimatedSeconds, sanitizedText.length());
            throw new ServiceException("台词过长");
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
     * 对口型计费时长（秒）：取 max(源视频时长, 配音音频时长)，宁高勿低，
     * 并向上取整到官方计价粒度（{@value #LIP_SYNC_BILLING_GRANULARITY_SECONDS} 秒）的整数倍。
     * 两侧都取不到返回 null，由统一计费侧按默认时长兜底。
     */
    private Integer resolveLipSyncDurationSeconds(AidGenRecord videoRecord, AidAudioRecord audioRecord) {
        Integer videoSeconds = null;
        if (Objects.nonNull(videoRecord.getVideoDuration()) && videoRecord.getVideoDuration() > 0) {
            videoSeconds = videoRecord.getVideoDuration().intValue();
        }
        Integer audioSeconds = null;
        if (Objects.nonNull(audioRecord.getDurationMs()) && audioRecord.getDurationMs() > 0) {
            audioSeconds = (int) Math.ceil(audioRecord.getDurationMs() / MS_PER_SECOND);
        }
        Integer seconds;
        if (Objects.nonNull(videoSeconds) && Objects.nonNull(audioSeconds)) {
            seconds = Math.max(videoSeconds, audioSeconds);
        } else {
            seconds = Objects.nonNull(videoSeconds) ? videoSeconds : audioSeconds;
        }
        if (Objects.isNull(seconds) || seconds <= 0) {
            return seconds;
        }
        int granularity = LIP_SYNC_BILLING_GRANULARITY_SECONDS;
        return ((seconds + granularity - 1) / granularity) * granularity;
    }

    /** 受理返回VO：受理成功即对口型进行中；幂等命中同步成功时按已回填结果展示成功 */
    private AudioTaskVO buildAcceptedVO(AidAudioRecord record) {
        String lipSyncStatus = StrUtil.isNotBlank(record.getSyncVideoUrl())
                ? MediaTaskStatus.SUCCEEDED.name() : MediaTaskStatus.PROCESSING.name();
        return AudioTaskVO.builder()
                .id(record.getId()).storyboardId(record.getStoryboardId())
                .audioSource(record.getAudioSource()).audioUrl(record.getAudioUrl())
                .durationMs(record.getDurationMs())
                .ttsText(record.getTtsText()).voiceModelId(record.getVoiceModelId())
                .timbreCode(record.getTimbreCode()).enableLipSync(record.getEnableLipSync())
                .status(record.getStatus())
                .errorMessage(record.getErrorMessage())
                .voiceLibraryId(record.getVoiceLibraryId())
                .syncVideoUrl(record.getSyncVideoUrl())
                .lipSyncStatus(lipSyncStatus)
                .createTime(record.getCreateTime())
                .build();
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

    // ==================== 父任务管理 ====================

    /** 查同项目+剧集下活跃（PENDING/PROCESSING）的批量对口型父任务；无则 null。 */
    private AidExtractTask findActiveTask(Long projectId, Long episodeId, Long userId) {
        return extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getStatus,
                                AidExtractTask::getTotalCount, AidExtractTask::getUpdateTime)
                        .eq(AidExtractTask::getProjectId, projectId)
                        .eq(AidExtractTask::getEpisodeId, episodeId)
                        .eq(AidExtractTask::getUserId, userId)
                        .eq(AidExtractTask::getTaskType, TASK_TYPE_LIP_SYNC_BATCH)
                        .in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING)
                        .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByDesc(AidExtractTask::getId)
                        .last("LIMIT 1"), false);
    }

    /** 活跃任务是否为中断残留：updateTime 静默超过 {@value #ACTIVE_TASK_STALE_MS} 毫秒。 */
    private boolean isStale(AidExtractTask task) {
        Date updateTime = task.getUpdateTime();
        return Objects.isNull(updateTime)
                || System.currentTimeMillis() - updateTime.getTime() > ACTIVE_TASK_STALE_MS;
    }

    /** 中断残留任务置 FAILED（放行新批次；在途配音/对口型由各自事件链收尾，不受影响）。 */
    private void markStaleFailed(AidExtractTask task) {
        log.warn("批量对口型清理中断残留任务: taskId={}, status={}", task.getId(), task.getStatus());
        extractTaskService.update(Wrappers.<AidExtractTask>lambdaUpdate()
                .eq(AidExtractTask::getId, task.getId())
                .in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING)
                .set(AidExtractTask::getStatus, TASK_STATUS_FAILED)
                .set(AidExtractTask::getErrorMessage, "任务中断")
                .set(AidExtractTask::getUpdateTime, DateUtils.getNowDate()));
    }

    /** 父任务置 FAILED（仅非终态可置，避免覆盖已写入的终态）。 */
    private void failTask(Long taskId, String errorMessage) {
        extractTaskService.update(Wrappers.<AidExtractTask>lambdaUpdate()
                .eq(AidExtractTask::getId, taskId)
                .in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING)
                .set(AidExtractTask::getStatus, TASK_STATUS_FAILED)
                .set(AidExtractTask::getErrorMessage, errorMessage)
                .set(AidExtractTask::getUpdateTime, DateUtils.getNowDate()));
    }

    /** 构建输入快照JSON（排查依据，含逐分镜音色与源视频记录ID）。 */
    private String buildInputSnapshot(StoryboardLipSyncBatchRequest request, List<AidStoryboard> targets,
                                      Map<Long, Long> voiceByStoryboardId,
                                      Map<Long, AidGenRecord> finalVideoByStoryboardId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("projectId", request.getProjectId());
        snapshot.put("episodeId", request.getEpisodeId());
        snapshot.put("storyboardIds", targets.stream().map(AidStoryboard::getId).collect(Collectors.toList()));
        snapshot.put("fallbackVoiceLibraryId", request.getVoiceLibraryId());
        snapshot.put("overwrite", Boolean.TRUE.equals(request.getOverwrite()));
        snapshot.put("resolvedVoices", voiceByStoryboardId);
        Map<Long, Long> sourceVideoIds = new LinkedHashMap<>();
        for (AidStoryboard s : targets) {
            AidGenRecord record = finalVideoByStoryboardId.get(s.getId());
            sourceVideoIds.put(s.getId(), Objects.isNull(record) ? null : record.getId());
        }
        snapshot.put("sourceVideoRecordIds", sourceVideoIds);
        try {
            return OBJECT_MAPPER.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.error("批量对口型 inputSnapshot 序列化失败", e);
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
            item.put("sourceVideoRecordId", ctx.sourceVideoRecordId);
            item.put("lipSyncVideoRecordId", ctx.lipSyncVideoRecordId);
            item.put("lipSyncVideoUrl", ctx.lipSyncVideoUrl);
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
        update.set(AidExtractTask::getResultData, json);
        if (!TASK_STATUS_PROCESSING.equals(status)) {
            update.set(AidExtractTask::getStatus, status);
            update.set(AidExtractTask::getErrorMessage, errorMessage);
        }
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        extractTaskService.update(update);
    }
}
