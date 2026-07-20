package com.aid.storyboard.service.impl;

import java.util.ArrayList;
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
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.MinimaxTtsConstants;
import com.aid.media.provider.MinimaxProviderDetector;
import com.aid.service.IAiModelConfigService;
import com.aid.compose.ComposeConstants;
import com.aid.compose.domain.ComposeCommand;
import com.aid.compose.domain.ComposeGroup;
import com.aid.compose.domain.ComposeSubmitResult;
import com.aid.compose.service.CoreComposeService;
import com.aid.enums.CreationStepEnum;
import com.aid.enums.GenTypeEnum;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.resolver.StoryboardAudioReferenceResolver;
import com.aid.rps.resolver.StoryboardAudioReferenceResolver.DialogueSegment;
import com.aid.rps.resolver.StoryboardImageReferenceResolver;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.sse.AssetExtractSseManager;
import com.aid.step.service.ICreationStepService;
import com.aid.storyboard.dto.GenerateAudioRequest;
import com.aid.storyboard.dto.SetFinalSelectionRequest;
import com.aid.storyboard.dto.StoryboardAudioBatchRequest;
import com.aid.storyboard.service.IStoryboardAudioBatchService;
import com.aid.storyboard.service.IStoryboardWorkbenchService;
import com.aid.storyboard.vo.AudioTaskVO;
import com.aid.voice.util.DialogueTextSanitizer;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜批量配音服务实现：逐分镜「配音 → 合成配音视频 → 自动设为使用中」，
 * 全程复用统一任务与扣费流程，产物只新增记录、绝不覆盖原视频。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class StoryboardAudioBatchServiceImpl implements IStoryboardAudioBatchService {

    /** 任务类型（aid_extract_task.task_type） */
    private static final String TASK_TYPE_AUDIO_BATCH = "storyboard_audio_generate";

    /** SSE 进度阶段标识 */
    private static final String SSE_STAGE = "storyboard_audio_generate";

    /** 任务状态（与其它批量任务字符串口径一致） */
    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";

    /** 单批分镜上限（与分镜最终产物批量上限同口径） */
    private static final int MAX_BATCH_SIZE = 50;

    /** 活跃父任务视为「在跑」的最大静默时长（毫秒）：超时视为中断残留，自动置 FAILED 放行新批次 */
    private static final long ACTIVE_TASK_STALE_MS = 30L * 60L * 1000L;

    /** 轮询间隔（毫秒） */
    private static final long POLL_INTERVAL_MS = 5000L;

    /** 配音 OSS 兜底等待上限（次）：5s × 12 = 60 秒（同步 TTS 极少数 OSS 未就绪场景） */
    private static final int AUDIO_WAIT_MAX_TIMES = 12;

    /** 合成阶段轮询上限（次）：5s × 240 = 20 分钟，覆盖整批 MPS 并行合成 */
    private static final int COMPOSE_POLL_MAX_TIMES = 240;

    /** 合成成功后等待成片记录落库的重试上限（次，间隔 2 秒；监听器同 JVM 事件极短） */
    private static final int GEN_RECORD_WAIT_MAX_TIMES = 6;

    /** 成片记录等待间隔（毫秒） */
    private static final long GEN_RECORD_WAIT_INTERVAL_MS = 2000L;

    /** 进度：配音阶段区间 [5,60)，合成阶段区间 [60,100) */
    private static final int PROGRESS_DUB_BASE = 5;
    private static final int PROGRESS_DUB_SPAN = 55;
    private static final int PROGRESS_COMPOSE_BASE = 60;
    private static final int PROGRESS_COMPOSE_SPAN = 40;

    /** setFinalSelection 的视频产物类型 */
    private static final String RECORD_TYPE_VIDEO = "video";

    /** 毫秒 → 秒换算 */
    private static final double MS_PER_SECOND = 1000.0;

    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 状态：启用 */
    private static final String STATUS_NORMAL = "0";

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

    /** 核心合成方法（单分镜「视频+配音」单组合成，与接口1/接口2 同一收口） */
    @Resource
    private CoreComposeService coreComposeService;

    /** 媒体 URL 解析器：DB 相对路径 → 完整可访问 URL（喂 MPS 需完整 URL） */
    @Resource
    private MediaUrlResolver mediaUrlResolver;

    /** 任务执行租约登记/心跳（僵尸回收按租约判活，PROCESSING 期间必须持有租约） */
    @Resource
    private IAssetExtractService assetExtractService;

    /** 通用线程池：承载批量配音的异步执行（配音串行 + 合成并行等待） */
    @Resource
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    /** 单分镜执行上下文（配音→合成→切换 三步的中间态） */
    private static final class ItemContext {
        /** 分镜ID */
        Long storyboardId;
        /** 原最终视频记录ID（切换前的"使用中"视频） */
        Long originalVideoRecordId;
        /** 原最终视频记录（时长/URL 供合成用） */
        AidGenRecord originalVideoRecord;
        /** 配音记录ID */
        Long audioRecordId;
        /** 合成媒体任务ID（aid_media_task.id） */
        Long composeMediaTaskId;
        /** 配音视频生成记录ID（aid_gen_record.id，genType=compose） */
        Long dubbedVideoRecordId;
        /** 配音视频 URL（相对路径） */
        String dubbedVideoUrl;
        /** 发言角色主名（台词解析，按出现顺序去重；空=无角色标记/纯旁白） */
        List<String> speakerRoles;
        /** 该分镜实际使用的音色库ID（角色绑定优先，其次请求兜底；老入参兜底路径为 null） */
        Long voiceLibraryId;
        /** 终态：SUCCEEDED / FAILED；执行中为 null */
        String status;
        /** 失败原因短文案 */
        String errorMessage;

        boolean finished() {
            return Objects.nonNull(status);
        }
    }

    @Override
    public AssetExtractTaskVO batchGenerateAudio(StoryboardAudioBatchRequest request, Long userId) {
        if (Objects.isNull(request) || Objects.isNull(request.getProjectId())
                || Objects.isNull(request.getEpisodeId()) || Objects.isNull(userId)) {
            log.info("批量配音入参缺失, userId={}", userId);
            throw new ServiceException("参数错误");
        }
        Long projectId = request.getProjectId();
        Long episodeId = request.getEpisodeId();

        // 前置快校验（零 I/O）：分辨率档合法性 + 兜底音色老入参必须成对，前端乱传直接拒绝
        if (!ComposeConstants.isValidResolution(request.getResolution())) {
            log.info("批量配音分辨率档非法, resolution={}", request.getResolution());
            throw new ServiceException("分辨率无效");
        }
        assertFallbackVoicePaired(request.getVoiceLibraryId(), request.getVoiceModelId(), request.getTimbreCode());

        // 步骤校验：配音需步骤6已解锁
        creationStepService.checkStepUnlocked(projectId, episodeId, userId, CreationStepEnum.AUDIO.getValue());

        // 防重：同项目+剧集已有活跃批量配音任务 → 幂等返回该任务（前端重连 SSE）；
        //       静默超 30 分钟的活跃任务视为中断残留，置 FAILED 后放行新批次
        AidExtractTask active = findActiveTask(projectId, episodeId, userId);
        if (Objects.nonNull(active)) {
            if (isStale(active)) {
                markStaleFailed(active);
            } else {
                log.info("批量配音重连活跃任务: taskId={}, projectId={}, episodeId={}",
                        active.getId(), projectId, episodeId);
                return AssetExtractTaskVO.builder()
                        .taskId(active.getId())
                        .status(active.getStatus())
                        .totalCount(active.getTotalCount())
                        .build();
            }
        }

        // 加载目标分镜：仅「有台词」的分镜（清洗后非空）
        List<AidStoryboard> targets = loadDialogueStoryboards(request, userId);
        if (CollectionUtil.isEmpty(targets)) {
            log.info("批量配音无可配分镜, projectId={}, episodeId={}", projectId, episodeId);
            throw new ServiceException("无可配音分镜");
        }

        // overwrite=false：配音轨已有「使用中」配音视频（is_selected=1 的 compose 记录）的分镜视为已配音，跳过；
        // overwrite=true：重配——新增一条配音视频记录后在配音轨内切换使用中，原记录一律保留不覆盖
        if (!Boolean.TRUE.equals(request.getOverwrite())) {
            Set<Long> dubbedStoryboardIds = loadDubbedStoryboardIds(targets, userId);
            targets = targets.stream()
                    .filter(s -> !dubbedStoryboardIds.contains(s.getId()))
                    .collect(Collectors.toList());
            if (CollectionUtil.isEmpty(targets)) {
                log.info("批量配音全部分镜已配音(overwrite=false), projectId={}, episodeId={}", projectId, episodeId);
                throw new ServiceException("已全部配音");
            }
        }
        if (targets.size() > MAX_BATCH_SIZE) {
            log.info("批量配音超上限, size={}, max={}", targets.size(), MAX_BATCH_SIZE);
            throw new ServiceException("批量过多");
        }

        // 前置校验分镜视频（配音素材源，恒取原视频轨）：存在、归属本人、文件已生成、时长已回填
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
            log.info("批量配音存在未绑定音色的分镜, projectId={}, episodeId={}, unresolved={}",
                    projectId, episodeId, unresolved);
            throw new ServiceException("请先绑定音色");
        }

        // 前置校验本批音色可用性（建任务之前）：绑定音色 + 兜底音色须启用未删未下架、所属模型启用；
        // 返回代表性模型编码（本批逐分镜模型可能不同，取首个启用模型代表，父任务 model_code 非空约束用）
        String representativeModelCode = validateVoicesUsable(voiceByStoryboardId, request.getVoiceLibraryId());
        if (StrUtil.isBlank(representativeModelCode)) {
            // 老入参兜底（voiceModelId+timbreCode）：按模型ID反查编码并校验启用
            representativeModelCode = resolveFallbackModelCode(request.getVoiceModelId());
        }
        if (StrUtil.isBlank(representativeModelCode)) {
            log.error("批量配音无法解析模型编码, projectId={}, episodeId={}", projectId, episodeId);
            throw new ServiceException("模型异常");
        }

        // MiniMax 音色单条文本上限前置校验（500 字符）：超限整批拒绝，不产生任务记录
        validateMinimaxTextLimit(targets, voiceByStoryboardId, request);

        // 创建父任务（SSE / 微信推送锚点)；配音与合成的计费随各自统一任务逐条冻结/结算，父级不另设扣费
        AidExtractTask task = new AidExtractTask();
        task.setProjectId(projectId);
        task.setEpisodeId(episodeId);
        task.setUserId(userId);
        task.setTaskType(TASK_TYPE_AUDIO_BATCH);
        task.setStatus(TASK_STATUS_PENDING);
        // model_code 为表非空列（NOT NULL）：填代表性 TTS 模型编码，缺省会触发数据库非空约束报"数据非法"
        task.setModelCode(representativeModelCode);
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
                    voiceByStoryboardId, finalVideoByStoryboardId, userId));
        } catch (Exception rejectEx) {
            // 线程池拒绝：父任务置失败，避免永久 PENDING
            log.error("批量配音派发被拒绝, taskId={}", taskId, rejectEx);
            failTask(taskId, "提交失败，请重试");
            throw new ServiceException("提交失败，请重试");
        }

        log.info("批量配音受理: taskId={}, projectId={}, episodeId={}, shots={}",
                taskId, projectId, episodeId, targets.size());
        return AssetExtractTaskVO.builder()
                .taskId(taskId)
                .status(TASK_STATUS_PENDING)
                .totalCount(targets.size())
                .build();
    }

    /**
     * 异步执行：阶段一逐分镜串行「配音 + 提交合成」（同用户计费锁天然串行）；
     * 阶段二统一等待全部合成终态（MPS 上游并行），成片就绪后逐条切换"使用中"。
     */
    private void executeBatch(Long taskId, StoryboardAudioBatchRequest request, List<AidStoryboard> targets,
                              Map<Long, Long> voiceByStoryboardId,
                              Map<Long, AidGenRecord> finalVideoByStoryboardId, Long userId) {
        // PENDING → PROCESSING（CAS，防重复执行）
        boolean started = extractTaskService.update(Wrappers.<AidExtractTask>lambdaUpdate()
                .eq(AidExtractTask::getId, taskId)
                .eq(AidExtractTask::getStatus, TASK_STATUS_PENDING)
                .set(AidExtractTask::getStatus, TASK_STATUS_PROCESSING)
                .set(AidExtractTask::getUpdateTime, DateUtils.getNowDate()));
        if (!started) {
            log.info("批量配音任务状态已变化，跳过执行: taskId={}", taskId);
            return;
        }
        // 登记执行租约 + 心跳常驻续租：「租约失活僵尸回收」按租约判活，
        // 配音/合成等待期间必须持有租约，否则会被误判进程已死置 FAILED
        assetExtractService.markTaskProcessing(taskId);
        try {
            int total = targets.size();
            List<ItemContext> items = new ArrayList<>(total);

            // ===== 阶段一：逐分镜串行配音 + 提交单组合成 =====
            for (int i = 0; i < total; i++) {
                AidStoryboard storyboard = targets.get(i);
                ItemContext ctx = new ItemContext();
                ctx.storyboardId = storyboard.getId();
                AidGenRecord originalVideo = finalVideoByStoryboardId.get(storyboard.getId());
                ctx.originalVideoRecord = originalVideo;
                ctx.originalVideoRecordId = Objects.isNull(originalVideo) ? null : originalVideo.getId();
                // 发言角色 + 实际音色：供进度明细/结果展示（角色绑定优先，其次请求兜底音色）
                ctx.speakerRoles = resolveSpeakerRoles(storyboard.getDialogueText());
                Long boundVoice = voiceByStoryboardId.get(storyboard.getId());
                ctx.voiceLibraryId = Objects.nonNull(boundVoice) ? boundVoice : request.getVoiceLibraryId();
                items.add(ctx);
                try {
                    // 1) 配音（同步 TTS；极少数 OSS 未就绪兜底短等待）
                    AidAudioRecord audioRecord = dubOne(request, storyboard,
                            voiceByStoryboardId.get(storyboard.getId()), userId);
                    ctx.audioRecordId = audioRecord.getId();
                    // 2) 提交该分镜「视频+配音」单组合成（成片经 ComposeResultListener 回写 gen_record）
                    ctx.composeMediaTaskId = submitDubCompose(taskId, storyboard, originalVideo,
                            audioRecord, request, userId);
                } catch (Exception e) {
                    // 单条失败不阻断整批
                    String reason = (e instanceof ServiceException) ? e.getMessage() : "配音失败";
                    ctx.status = TASK_STATUS_FAILED;
                    ctx.errorMessage = reason;
                    log.error("批量配音单条失败: taskId={}, storyboardId={}, reason={}",
                            taskId, storyboard.getId(), reason, e);
                }
                writeResultData(taskId, TASK_STATUS_PROCESSING, items, null);
                int progress = PROGRESS_DUB_BASE + (i + 1) * PROGRESS_DUB_SPAN / total;
                sseManager.sendProgress(taskId, SSE_STAGE, progress,
                        String.format("配音中 %d/%d", i + 1, total));
            }

            // ===== 阶段二：统一等待合成终态（MPS 并行），成片就绪后切换"使用中" =====
            awaitComposeAndSwitch(taskId, items, userId, total);

            // ===== 终态汇总 =====
            long succeeded = items.stream().filter(it -> TASK_STATUS_SUCCEEDED.equals(it.status)).count();
            long failed = total - succeeded;
            String finalStatus;
            String errorMessage = null;
            if (failed == 0) {
                finalStatus = TASK_STATUS_SUCCEEDED;
            } else if (succeeded > 0) {
                finalStatus = TASK_STATUS_PARTIAL_FAILED;
                errorMessage = String.format("%d 条配音失败", failed);
            } else {
                finalStatus = TASK_STATUS_FAILED;
                errorMessage = "配音失败";
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
            // 终态微信模板推送：与其它批量任务同一规则（用户开关/偏好在服务内部判断）
            wechatNotifyService.notifyTaskTerminal(taskId);
            log.info("批量配音完成: taskId={}, total={}, succeeded={}, failed={}, finalStatus={}",
                    taskId, total, succeeded, failed, finalStatus);
        } catch (Exception ex) {
            // 执行器级异常（DB 抖动等）：父任务置失败并通知；已提交的配音/合成由各自事件链收尾
            log.error("批量配音执行异常: taskId={}", taskId, ex);
            failTask(taskId, "配音失败");
            try { sseManager.sendError(taskId, "配音失败"); } catch (Exception ignore) { }
            try { wechatNotifyService.notifyTaskTerminal(taskId); } catch (Exception ignore) { }
        } finally {
            // 任何退出路径都停掉心跳续租，避免租约悬挂影响后续僵尸回收
            assetExtractService.deactivateTaskProcessingHeartbeat(taskId);
        }
    }

    /**
     * 单分镜配音：复用 generateAudio 全链路；返回已就绪（audioUrl 非空）的配音记录。
     * 极少数同步成功但 OSS 未就绪的兜底场景短轮询等待，超时按失败处理。
     */
    private AidAudioRecord dubOne(StoryboardAudioBatchRequest request, AidStoryboard storyboard,
                                  Long boundVoiceLibraryId, Long userId) {
        GenerateAudioRequest single = new GenerateAudioRequest();
        single.setStoryboardId(storyboard.getId());
        // 台词原文直传：generateAudio 入口统一做台词标记清洗（剥 [角色_形象]：/@音频N/竖线），与单条口径一致
        single.setTtsText(storyboard.getDialogueText());
        if (Objects.nonNull(boundVoiceLibraryId)) {
            single.setVoiceLibraryId(boundVoiceLibraryId);
        } else if (Objects.nonNull(request.getVoiceLibraryId())) {
            single.setVoiceLibraryId(request.getVoiceLibraryId());
        } else {
            // 兼容老入参兜底（前置校验已保证 voiceModelId+timbreCode 成对存在）
            single.setVoiceModelId(request.getVoiceModelId());
            single.setTimbreCode(request.getTimbreCode());
        }
        single.setEmotion(request.getEmotion());
        single.setEmotionScale(request.getEmotionScale());
        single.setSpeechRate(request.getSpeechRate());
        single.setLoudnessRate(request.getLoudnessRate());
        single.setPitch(request.getPitch());
        AudioTaskVO vo = storyboardWorkbenchService.generateAudio(single, userId);
        if (Objects.isNull(vo) || Objects.isNull(vo.getId())) {
            log.error("批量配音 TTS 无返回, storyboardId={}", storyboard.getId());
            throw new ServiceException("配音失败");
        }
        // 常规路径：同步成功直接返回；兜底路径：短轮询等 OSS 回填
        for (int i = 0; i <= AUDIO_WAIT_MAX_TIMES; i++) {
            AidAudioRecord record = aidAudioRecordService.getById(vo.getId());
            if (Objects.isNull(record) || MediaTaskStatus.FAILED.name().equals(record.getStatus())) {
                log.error("批量配音 TTS 失败, storyboardId={}, audioRecordId={}", storyboard.getId(), vo.getId());
                throw new ServiceException("配音失败");
            }
            if (MediaTaskStatus.SUCCEEDED.name().equals(record.getStatus())
                    && StrUtil.isNotBlank(record.getAudioUrl())) {
                return record;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceException("配音失败");
            }
        }
        log.error("批量配音等待音频就绪超时, storyboardId={}, audioRecordId={}", storyboard.getId(), vo.getId());
        throw new ServiceException("配音超时");
    }

    /**
     * 提交单分镜「视频 + 配音」单组合成。
     * 配音时长按视频时长截齐（TrackTime 截断），成片时长 = 原视频时长，杜绝"视频播完只剩声音"的黑尾；
     * 成片由 ComposeResultListener 落 aid_gen_record（genType=compose，storyboardId=callbackRecordId）。
     *
     * @return 合成媒体任务ID（aid_media_task.id）
     */
    private Long submitDubCompose(Long taskId, AidStoryboard storyboard, AidGenRecord originalVideo,
                                  AidAudioRecord audioRecord, StoryboardAudioBatchRequest request, Long userId) {
        double videoSeconds = originalVideo.getVideoDuration().doubleValue();
        Double audioSeconds = Objects.isNull(audioRecord.getDurationMs())
                ? null : audioRecord.getDurationMs() / MS_PER_SECOND;
        ComposeGroup group = new ComposeGroup();
        List<String> videoUrls = new ArrayList<>();
        videoUrls.add(mediaUrlResolver.toFullUrl(originalVideo.getFileUrl()));
        group.setVideoUrls(videoUrls);
        List<Double> videoDurations = new ArrayList<>();
        videoDurations.add(videoSeconds);
        group.setVideoDurations(videoDurations);
        List<String> audioUrls = new ArrayList<>();
        audioUrls.add(mediaUrlResolver.toFullUrl(audioRecord.getAudioUrl()));
        group.setAudioUrls(audioUrls);
        List<Double> audioDurations = new ArrayList<>();
        // 配音时长截齐视频：配音更长按视频截断（预检 durationWarning 已引导精简台词），更短由合成核心补空白占位
        audioDurations.add(Objects.isNull(audioSeconds) ? null : Math.min(audioSeconds, videoSeconds));
        group.setAudioDurations(audioDurations);

        ComposeCommand command = new ComposeCommand();
        List<ComposeGroup> groups = new ArrayList<>();
        groups.add(group);
        command.setGroups(groups);
        command.setUserId(userId);
        command.setProjectId(storyboard.getProjectId());
        command.setEpisodeId(storyboard.getEpisodeId());
        command.setResolution(request.getResolution());
        // 成片落 aid_gen_record，归属本分镜（ComposeResultListener 按 callbackRecordId 写 storyboardId）
        command.setCallbackCategory(ComposeConstants.CALLBACK_GEN_RECORD);
        command.setCallbackRecordId(storyboard.getId());
        // 批次标识仅供排查（aid_media_task.compose_batch_id）；audio_record 未写该批次号，不会误触发接口1事件链
        command.setComposeBatchId("adub_" + taskId + "_" + storyboard.getId());
        ComposeSubmitResult submit = coreComposeService.compose(command);
        return submit.getMediaTaskId();
    }

    /**
     * 阶段二：轮询全部合成媒体任务至终态；成功的等成片记录落库后切换"使用中"（原视频自动置未使用）。
     */
    private void awaitComposeAndSwitch(Long taskId, List<ItemContext> items, Long userId, int total) {
        for (int round = 0; round < COMPOSE_POLL_MAX_TIMES; round++) {
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
                log.warn("批量配音合成等待被中断, taskId={}", taskId);
                return;
            }
            // 批查在途合成任务状态（查询字段精简：仅终态判定必需列）
            Set<Long> mediaTaskIds = pending.stream()
                    .map(it -> it.composeMediaTaskId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Map<Long, AidMediaTask> taskById = new HashMap<>();
            if (CollectionUtil.isNotEmpty(mediaTaskIds)) {
                List<AidMediaTask> tasks = aidMediaTaskMapper.selectList(
                        new LambdaQueryWrapper<AidMediaTask>()
                                .select(AidMediaTask::getId, AidMediaTask::getStatus,
                                        AidMediaTask::getOssUrl, AidMediaTask::getProviderTaskId)
                                .in(AidMediaTask::getId, mediaTaskIds));
                for (AidMediaTask t : tasks) {
                    taskById.put(t.getId(), t);
                }
            }
            for (ItemContext ctx : pending) {
                AidMediaTask mediaTask = Objects.isNull(ctx.composeMediaTaskId)
                        ? null : taskById.get(ctx.composeMediaTaskId);
                if (Objects.isNull(mediaTask)) {
                    continue;
                }
                if (MediaTaskStatus.FAILED.name().equals(mediaTask.getStatus())) {
                    ctx.status = TASK_STATUS_FAILED;
                    ctx.errorMessage = "合成失败";
                    log.error("批量配音单条合成失败: taskId={}, storyboardId={}, mediaTaskId={}",
                            taskId, ctx.storyboardId, ctx.composeMediaTaskId);
                } else if (MediaTaskStatus.SUCCEEDED.name().equals(mediaTask.getStatus())
                        && StrUtil.isNotBlank(mediaTask.getOssUrl())) {
                    finalizeDubbedItem(taskId, ctx, mediaTask, userId);
                }
            }
            writeResultData(taskId, TASK_STATUS_PROCESSING, items, null);
            long done = items.stream().filter(ItemContext::finished).count();
            int progress = PROGRESS_COMPOSE_BASE + (int) (done * PROGRESS_COMPOSE_SPAN / total);
            sseManager.sendProgress(taskId, SSE_STAGE, progress,
                    String.format("配音视频合成中 %d/%d", done, total));
        }
        // 轮询超时：剩余在途条目按超时失败（MPS 任务本体仍会跑完并落成片记录，用户可手动设为使用中）
        for (ItemContext ctx : items) {
            if (!ctx.finished()) {
                ctx.status = TASK_STATUS_FAILED;
                ctx.errorMessage = "合成超时";
                log.error("批量配音合成等待超时: taskId={}, storyboardId={}, mediaTaskId={}",
                        taskId, ctx.storyboardId, ctx.composeMediaTaskId);
            }
        }
    }

    /**
     * 合成成功收尾：等成片记录（ComposeResultListener 事件落库）就绪 → setFinalSelection 设为被选中。
     * 切换采用视频大类单选互斥：新配音视频 is_selected=1，同分镜原视频与旧配音视频的选中同时被取消
     * （一个分镜只保留一个被选中的视频）；final_video_id 指针不受影响，原视频记录本身保留不动（不覆盖、不删除）。
     */
    private void finalizeDubbedItem(Long taskId, ItemContext ctx, AidMediaTask mediaTask, Long userId) {
        // 成片记录匹配：优先按 providerTaskId 精确匹配；缺失时按成片地址匹配，
        // 防止兜底条件失效取到该分镜历史配音视频（旧记录被误设为本次成片）
        boolean hasProviderTaskId = StrUtil.isNotBlank(mediaTask.getProviderTaskId());
        AidGenRecord dubbedRecord = null;
        for (int i = 0; i < GEN_RECORD_WAIT_MAX_TIMES; i++) {
            dubbedRecord = aidGenRecordService.getOne(Wrappers.<AidGenRecord>lambdaQuery()
                    .select(AidGenRecord::getId, AidGenRecord::getFileUrl)
                    .eq(AidGenRecord::getStoryboardId, ctx.storyboardId)
                    .eq(AidGenRecord::getGenType, ComposeConstants.GEN_TYPE_COMPOSE)
                    .eq(hasProviderTaskId, AidGenRecord::getTaskId, mediaTask.getProviderTaskId())
                    .eq(!hasProviderTaskId, AidGenRecord::getFileUrl, mediaTask.getOssUrl())
                    .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL)
                    .orderByDesc(AidGenRecord::getId)
                    .last("LIMIT 1"), false);
            if (Objects.nonNull(dubbedRecord)) {
                break;
            }
            try {
                Thread.sleep(GEN_RECORD_WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (Objects.isNull(dubbedRecord)) {
            // 成片已出但记录未落（监听器异常等极端场景）：按失败记录，成片可从媒体任务复查
            ctx.status = TASK_STATUS_FAILED;
            ctx.errorMessage = "成片入库超时";
            log.error("批量配音成片记录未就绪: taskId={}, storyboardId={}, mediaTaskId={}",
                    taskId, ctx.storyboardId, ctx.composeMediaTaskId);
            return;
        }
        ctx.dubbedVideoRecordId = dubbedRecord.getId();
        ctx.dubbedVideoUrl = dubbedRecord.getFileUrl();
        try {
            // 视频大类单选切换：新配音视频设为被选中（原视频与旧配音视频的选中自动取消）；
            // finalVideoId 指针不受影响——详情"分镜视频"恒为配音前原视频
            SetFinalSelectionRequest select = new SetFinalSelectionRequest();
            select.setStoryboardId(ctx.storyboardId);
            select.setRecordId(dubbedRecord.getId());
            select.setRecordType(RECORD_TYPE_VIDEO);
            storyboardWorkbenchService.setFinalSelection(select, userId);
            ctx.status = TASK_STATUS_SUCCEEDED;
            log.info("批量配音单条完成: taskId={}, storyboardId={}, dubbedRecordId={}, originalRecordId={}",
                    taskId, ctx.storyboardId, ctx.dubbedVideoRecordId, ctx.originalVideoRecordId);
        } catch (Exception e) {
            // 成片已入库但切换失败：保留记录供用户手动设为使用中
            ctx.status = TASK_STATUS_FAILED;
            ctx.errorMessage = "切换主视频失败";
            log.error("批量配音切换使用中失败: taskId={}, storyboardId={}, dubbedRecordId={}",
                    taskId, ctx.storyboardId, ctx.dubbedVideoRecordId, e);
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
            item.put("originalVideoRecordId", ctx.originalVideoRecordId);
            item.put("dubbedVideoRecordId", ctx.dubbedVideoRecordId);
            item.put("dubbedVideoUrl", ctx.dubbedVideoUrl);
            item.put("status", Objects.isNull(ctx.status) ? TASK_STATUS_PROCESSING : ctx.status);
            item.put("errorMessage", ctx.errorMessage);
            result.add(item);
        }
        return result;
    }

    /**
     * 解析分镜台词中的发言角色主名（按出现顺序去重）。
     * 旁白段与无角色标记的纯文本段不计入；解析异常返回空列表（展示增强，不阻断配音）。
     *
     * @param dialogueText 台词原文
     * @return 发言角色主名列表
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
            log.info("兜底音色老入参不成对, voiceModelId={}, timbreCode={}", voiceModelId, timbreCode);
            throw new ServiceException("音色参数不全");
        }
    }

    /**
     * 加载「有台词」的目标分镜（清洗后非空）。
     * 传入 storyboardIds 时先做归属强校验：任一 ID 不存在/不属于该项目剧集/非本人，整批拒绝
     * （防前端乱传导致静默漏配，任务生成前拦截）。
     * 查询字段精简：仅 select 批量配音必需列（id/dialogue_text/sort_order/final_video_id/project_id/episode_id），
     * 后续扩展取数请同步增列。
     */
    private List<AidStoryboard> loadDialogueStoryboards(StoryboardAudioBatchRequest request, Long userId) {
        List<Long> ids = null;
        if (CollectionUtil.isNotEmpty(request.getStoryboardIds())) {
            ids = request.getStoryboardIds().stream()
                    .filter(Objects::nonNull).distinct().collect(Collectors.toList());
            // 传了列表但全是空元素：视为误传拒绝，不能退化成"全量分镜"处理
            if (CollectionUtil.isEmpty(ids)) {
                log.info("批量配音 storyboardIds 全为空元素, projectId={}, episodeId={}",
                        request.getProjectId(), request.getEpisodeId());
                throw new ServiceException("参数错误");
            }
        }
        List<AidStoryboard> storyboards = aidStoryboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getDialogueText, AidStoryboard::getSortOrder,
                                AidStoryboard::getFinalVideoId, AidStoryboard::getProjectId, AidStoryboard::getEpisodeId)
                        .eq(AidStoryboard::getProjectId, request.getProjectId())
                        .eq(AidStoryboard::getEpisodeId, request.getEpisodeId())
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                        .in(CollectionUtil.isNotEmpty(ids), AidStoryboard::getId, ids)
                        .orderByAsc(AidStoryboard::getSortOrder).orderByAsc(AidStoryboard::getId));
        // 显式归属校验（台词过滤前对比）：传入的分镜必须全部命中该项目+剧集+本人，杜绝乱传静默漏配
        if (CollectionUtil.isNotEmpty(ids) && storyboards.size() != ids.size()) {
            log.info("批量配音存在无效分镜ID, projectId={}, episodeId={}, expect={}, actual={}",
                    request.getProjectId(), request.getEpisodeId(), ids.size(), storyboards.size());
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
     * 已配音分镜集合：配音轨存在「使用中」记录（genType=compose 且 is_selected=1）即视为已配音。
     * 查询字段精简：仅 select storyboard_id，后续扩展取数请同步增列。
     */
    private Set<Long> loadDubbedStoryboardIds(List<AidStoryboard> targets, Long userId) {
        Set<Long> storyboardIds = targets.stream().map(AidStoryboard::getId).collect(Collectors.toSet());
        List<AidGenRecord> dubbed = aidGenRecordService.list(
                Wrappers.<AidGenRecord>lambdaQuery()
                        .select(AidGenRecord::getStoryboardId)
                        .in(AidGenRecord::getStoryboardId, storyboardIds)
                        .eq(AidGenRecord::getUserId, userId)
                        .eq(AidGenRecord::getGenType, ComposeConstants.GEN_TYPE_COMPOSE)
                        .eq(AidGenRecord::getIsSelected, 1)
                        .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL));
        return dubbed.stream()
                .map(AidGenRecord::getStoryboardId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 前置校验分镜视频（配音贴回视频的素材与时基，<strong>恒取原视频轨</strong>）：
     * 存在、归属本人、文件已生成、时长已回填，任一缺失整批拒绝、不产生任务记录。
     * final_video_id 恒指配音前原视频（配音视频被选中也不改该指针）；历史数据若误指配音视频（compose），
     * 自动回落该分镜最新原视频记录，绝不把配音视频当素材二次配音。
     * 查询字段精简：仅 select id/user_id/file_url/video_duration/gen_type/del_flag，后续扩展取数请同步增列。
     *
     * @return 分镜ID → 原视频记录（配音素材源）
     */
    private Map<Long, AidGenRecord> loadAndValidateFinalVideos(List<AidStoryboard> targets, Long userId) {
        List<Long> missingVideo = targets.stream()
                .filter(s -> Objects.isNull(s.getFinalVideoId()))
                .map(AidStoryboard::getId)
                .collect(Collectors.toList());
        if (CollectionUtil.isNotEmpty(missingVideo)) {
            log.info("批量配音存在未出片分镜, missing={}", missingVideo);
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
            if (Objects.nonNull(record) && ComposeConstants.GEN_TYPE_COMPOSE.equals(record.getGenType())) {
                composePointedIds.add(storyboard.getId());
            }
        }
        Map<Long, AidGenRecord> fallbackOriginals = loadLatestOriginalVideos(composePointedIds, userId);

        Map<Long, AidGenRecord> result = new HashMap<>();
        for (AidStoryboard storyboard : targets) {
            AidGenRecord record = recordById.get(storyboard.getFinalVideoId());
            if (Objects.nonNull(record) && ComposeConstants.GEN_TYPE_COMPOSE.equals(record.getGenType())) {
                record = fallbackOriginals.get(storyboard.getId());
                if (Objects.isNull(record)) {
                    log.info("批量配音分镜无原视频可作素材, storyboardId={}", storyboard.getId());
                    throw new ServiceException("请先生成视频");
                }
            }
            if (Objects.isNull(record) || !Objects.equals(DEL_FLAG_NORMAL, record.getDelFlag())
                    || !Objects.equals(userId, record.getUserId()) || StrUtil.isBlank(record.getFileUrl())) {
                log.info("批量配音分镜视频不可用, storyboardId={}, videoRecordId={}",
                        storyboard.getId(), storyboard.getFinalVideoId());
                throw new ServiceException("素材异常");
            }
            if (Objects.isNull(record.getVideoDuration()) || record.getVideoDuration() <= 0) {
                log.info("批量配音分镜视频时长缺失, storyboardId={}, videoRecordId={}",
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
                        .in(AidGenRecord::getGenType, GenTypeEnum.I2V.getValue(), GenTypeEnum.MULTI.getValue(),
                                GenTypeEnum.EDGE.getValue(), GenTypeEnum.UPLOAD_VIDEO.getValue())
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
     * 解析不出的分镜 map 无该 key（提交时回落请求兜底音色）。
     */
    private Map<Long, Long> resolveVoiceBindings(List<AidStoryboard> targets, Long projectId,
                                                 Long episodeId, Long userId) {
        Map<Long, Long> result = new HashMap<>();
        // 加载项目级角色目录（查询字段精简：仅 id/name）：
        // 剧集角色主资产项目内唯一（episodeId=0），按项目查才能命中全局角色与历史按集角色
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
                // 归一化口径与配音预检一致（连字符统一为下划线），同名取先入库者
                assetIdByKey.putIfAbsent(
                        StoryboardImageReferenceResolver.normalizeAssetRefName(asset.getName()), asset.getId());
            }
        }
        // 加载启用绑定（剧集精确优先于全局，口径与配音预检一致；查询字段精简）
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
        // 逐分镜：首个角色段 → 资产 → 绑定音色（当前配音链路为整段单音色，与单分镜配音口径一致）
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

    /** 段角色 → 资产ID：先按角色主名精确，再按完整引用名回退（口径与配音预检一致）。 */
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
     * 前置校验本批用到的所有音色可用性：启用未删、未到下架时间、所属模型启用。
     * 与配音链路内部校验同口径，但提前到建任务之前，校验不过整批拒绝、不产生任务记录。
     * 查询字段精简：音色仅 select id/status/del_flag/offline_time/model_id，模型仅 select id/status/model_code；
     * 后续扩展取数请同步增列。
     *
     * @param voiceByStoryboardId    逐分镜解析出的绑定音色
     * @param fallbackVoiceLibraryId 请求兜底音色库ID（可空）
     * @return 代表性 TTS 模型编码（本批首个启用模型；无音色库音色时返回 null）
     */
    private String validateVoicesUsable(Map<Long, Long> voiceByStoryboardId, Long fallbackVoiceLibraryId) {
        Set<Long> voiceIds = new LinkedHashSet<>(voiceByStoryboardId.values());
        if (Objects.nonNull(fallbackVoiceLibraryId)) {
            voiceIds.add(fallbackVoiceLibraryId);
        }
        voiceIds.remove(null);
        if (CollectionUtil.isEmpty(voiceIds)) {
            return null;
        }
        List<AidAiVoiceLibrary> voices = aidAiVoiceLibraryService.list(
                Wrappers.<AidAiVoiceLibrary>lambdaQuery()
                        .select(AidAiVoiceLibrary::getId, AidAiVoiceLibrary::getStatus,
                                AidAiVoiceLibrary::getDelFlag, AidAiVoiceLibrary::getOfflineTime,
                                AidAiVoiceLibrary::getModelId)
                        .in(AidAiVoiceLibrary::getId, voiceIds));
        Map<Long, AidAiVoiceLibrary> voiceById = voices.stream()
                .collect(Collectors.toMap(AidAiVoiceLibrary::getId, v -> v, (a, b) -> a));
        Set<Long> modelIds = new LinkedHashSet<>();
        long now = System.currentTimeMillis();
        for (Long voiceId : voiceIds) {
            AidAiVoiceLibrary voice = voiceById.get(voiceId);
            if (Objects.isNull(voice) || !Objects.equals(DEL_FLAG_NORMAL, voice.getDelFlag())
                    || !Objects.equals(STATUS_NORMAL, voice.getStatus())) {
                log.info("批量配音前置校验音色不可用, voiceLibraryId={}", voiceId);
                throw new ServiceException("音色不可用");
            }
            if (Objects.nonNull(voice.getOfflineTime()) && voice.getOfflineTime().getTime() <= now) {
                log.info("批量配音前置校验音色已下架, voiceLibraryId={}, offlineTime={}",
                        voiceId, voice.getOfflineTime());
                throw new ServiceException("音色已下架");
            }
            if (Objects.nonNull(voice.getModelId())) {
                modelIds.add(voice.getModelId());
            }
        }
        // 所属模型启用校验 + 取首个启用模型编码作为父任务代表性 model_code
        String representativeModelCode = null;
        if (CollectionUtil.isNotEmpty(modelIds)) {
            List<AidAiModel> models = aidAiModelService.list(
                    Wrappers.<AidAiModel>lambdaQuery()
                            .select(AidAiModel::getId, AidAiModel::getStatus, AidAiModel::getModelCode)
                            .in(AidAiModel::getId, modelIds));
            Map<Long, AidAiModel> modelById = models.stream()
                    .filter(m -> Objects.equals(STATUS_NORMAL, m.getStatus()))
                    .collect(Collectors.toMap(AidAiModel::getId, m -> m, (a, b) -> a));
            for (Long modelId : modelIds) {
                AidAiModel model = modelById.get(modelId);
                if (Objects.isNull(model)) {
                    log.info("批量配音前置校验模型已停用, modelId={}", modelId);
                    throw new ServiceException("模型已停用");
                }
                if (StrUtil.isBlank(representativeModelCode)) {
                    representativeModelCode = model.getModelCode();
                }
            }
        }
        return representativeModelCode;
    }

    /**
     * MiniMax 音色单条文本上限前置校验：逐分镜定位所用模型，MiniMax 模型的分镜
     * 按清洗后台词长度校验（与实际下发 TTS 的文本同口径），超过 500 字符整批拒绝。
     * 查询字段精简：音色仅 select id/model_id，模型仅 select id/model_code/capability_json。
     *
     * @param targets             目标分镜列表
     * @param voiceByStoryboardId 逐分镜解析出的绑定音色
     * @param request             批量配音请求（兜底音色/模型来源）
     */
    private void validateMinimaxTextLimit(List<AidStoryboard> targets, Map<Long, Long> voiceByStoryboardId,
                                          StoryboardAudioBatchRequest request) {
        // 音色 → 模型ID 映射（绑定音色 + 兜底音色）
        Set<Long> voiceIds = new LinkedHashSet<>(voiceByStoryboardId.values());
        if (Objects.nonNull(request.getVoiceLibraryId())) {
            voiceIds.add(request.getVoiceLibraryId());
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
        // 收集本批全部模型ID（音色归属模型 + 老入参兜底模型）
        Set<Long> modelIds = new LinkedHashSet<>(modelIdByVoiceId.values());
        if (Objects.nonNull(request.getVoiceModelId())) {
            modelIds.add(request.getVoiceModelId());
        }
        if (CollectionUtil.isEmpty(modelIds)) {
            return;
        }
        // 三级判定各模型是否 MiniMax（providerCode → capability_json.provider → supportsModel 兜底）
        Set<Long> minimaxModelIds = resolveMinimaxModelIds(modelIds);
        if (CollectionUtil.isEmpty(minimaxModelIds)) {
            return;
        }
        // 逐分镜校验：仅 MiniMax 模型分镜按清洗后台词长度拦截
        for (AidStoryboard storyboard : targets) {
            Long modelId = resolveStoryboardModelId(storyboard.getId(), voiceByStoryboardId,
                    modelIdByVoiceId, request);
            if (Objects.isNull(modelId) || !minimaxModelIds.contains(modelId)) {
                continue;
            }
            String sanitized = DialogueTextSanitizer.sanitize(storyboard.getDialogueText());
            if (StrUtil.length(sanitized) > MinimaxTtsConstants.BATCH_TEXT_MAX_LENGTH) {
                log.info("批量配音 MiniMax 单条文本超限: storyboardId={}, textLen={}, max={}",
                        storyboard.getId(), StrUtil.length(sanitized), MinimaxTtsConstants.BATCH_TEXT_MAX_LENGTH);
                throw new ServiceException("文本过长");
            }
        }
    }

    /**
     * 批量判定 MiniMax 模型集合。
     * 查询字段精简：仅 select id/model_code/capability_json。
     *
     * @param modelIds 模型ID集合
     * @return 归属 MiniMax 的模型ID集合
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
                log.warn("批量配音获取 providerCode 失败: modelId={}, err={}", model.getId(), e.getMessage());
            }
            String capabilityProvider = MinimaxProviderDetector.parseCapabilityProvider(model.getCapabilityJson());
            if (minimaxProviderDetector.isMinimax(providerCode, capabilityProvider, model.getModelCode())) {
                result.add(model.getId());
            }
        }
        return result;
    }

    /**
     * 定位分镜实际使用的配音模型ID：绑定音色 → 请求兜底音色 → 老入参兜底模型。
     *
     * @param storyboardId        分镜ID
     * @param voiceByStoryboardId 逐分镜解析出的绑定音色
     * @param modelIdByVoiceId    音色 → 模型ID 映射
     * @param request             批量配音请求
     * @return 模型ID；无法定位返回 null
     */
    private Long resolveStoryboardModelId(Long storyboardId, Map<Long, Long> voiceByStoryboardId,
                                          Map<Long, Long> modelIdByVoiceId, StoryboardAudioBatchRequest request) {
        Long voiceId = voiceByStoryboardId.get(storyboardId);
        if (Objects.isNull(voiceId)) {
            voiceId = request.getVoiceLibraryId();
        }
        if (Objects.nonNull(voiceId)) {
            return modelIdByVoiceId.get(voiceId);
        }
        // 老入参兜底：voiceModelId 直接就是模型ID
        return request.getVoiceModelId();
    }

    /**
     * 老入参兜底模型编码解析：按 voiceModelId 反查模型编码并校验启用。
     * 查询字段精简：仅 select id/status/model_code。
     *
     * @param voiceModelId 兜底配音模型ID（可空）
     * @return 模型编码；无法解析返回 null
     */
    private String resolveFallbackModelCode(Long voiceModelId) {
        if (Objects.isNull(voiceModelId)) {
            return null;
        }
        AidAiModel model = aidAiModelService.getOne(Wrappers.<AidAiModel>lambdaQuery()
                .select(AidAiModel::getId, AidAiModel::getStatus, AidAiModel::getModelCode)
                .eq(AidAiModel::getId, voiceModelId)
                .last("LIMIT 1"), false);
        if (Objects.isNull(model) || !Objects.equals(STATUS_NORMAL, model.getStatus())) {
            log.info("批量配音兜底模型不可用, voiceModelId={}", voiceModelId);
            throw new ServiceException("模型已停用");
        }
        return model.getModelCode();
    }

    /** 查同项目+剧集下活跃（PENDING/PROCESSING）的批量配音父任务；无则 null。 */
    private AidExtractTask findActiveTask(Long projectId, Long episodeId, Long userId) {
        return extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getStatus,
                                AidExtractTask::getTotalCount, AidExtractTask::getUpdateTime)
                        .eq(AidExtractTask::getProjectId, projectId)
                        .eq(AidExtractTask::getEpisodeId, episodeId)
                        .eq(AidExtractTask::getUserId, userId)
                        .eq(AidExtractTask::getTaskType, TASK_TYPE_AUDIO_BATCH)
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

    /** 中断残留任务置 FAILED（放行新批次；在途配音/合成由各自事件链收尾，不受影响）。 */
    private void markStaleFailed(AidExtractTask task) {
        log.warn("批量配音清理中断残留任务: taskId={}, status={}", task.getId(), task.getStatus());
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

    /** 构建输入快照JSON（排查依据，含逐分镜音色与原视频记录ID）。 */
    private String buildInputSnapshot(StoryboardAudioBatchRequest request, List<AidStoryboard> targets,
                                      Map<Long, Long> voiceByStoryboardId,
                                      Map<Long, AidGenRecord> finalVideoByStoryboardId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("projectId", request.getProjectId());
        snapshot.put("episodeId", request.getEpisodeId());
        snapshot.put("storyboardIds", targets.stream().map(AidStoryboard::getId).collect(Collectors.toList()));
        snapshot.put("fallbackVoiceLibraryId", request.getVoiceLibraryId());
        snapshot.put("overwrite", Boolean.TRUE.equals(request.getOverwrite()));
        snapshot.put("resolvedVoices", voiceByStoryboardId);
        Map<Long, Long> originalVideoIds = new LinkedHashMap<>();
        for (AidStoryboard s : targets) {
            AidGenRecord record = finalVideoByStoryboardId.get(s.getId());
            originalVideoIds.put(s.getId(), Objects.isNull(record) ? null : record.getId());
        }
        snapshot.put("originalVideoRecordIds", originalVideoIds);
        try {
            return OBJECT_MAPPER.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.error("批量配音 inputSnapshot 序列化失败", e);
            throw new ServiceException("提交失败，请重试");
        }
    }

    /** 回写进度/终态 resultData（含每条明细）；终态时同步更新任务状态与错误信息。 */
    private void writeResultData(Long taskId, String status, List<ItemContext> items, String errorMessage) {
        String json;
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", toItemMaps(items));
            json = OBJECT_MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("批量配音 resultData 序列化失败, taskId={}", taskId, e);
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
