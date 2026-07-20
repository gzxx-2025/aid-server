package com.aid.compose.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidAiVoiceLibrary;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.domain.AidAudioRecord;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidAudioRecordMapper;
import com.aid.aid.service.IAidAiVoiceLibraryService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.aid.mapper.AidEpisodeEditorMapper;
import com.aid.aid.mapper.AidGenRecordMapper;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.SecurityUtils;
import com.aid.compose.ComposeConstants;
import com.aid.compose.domain.ComposeCommand;
import com.aid.compose.domain.ComposeGroup;
import com.aid.compose.domain.ComposePendingContext;
import com.aid.compose.domain.ComposeSubmitResult;
import com.aid.compose.dto.ComposeAcceptResult;
import com.aid.compose.dto.ComposeGroupDto;
import com.aid.compose.dto.ComposeStatusRequest;
import com.aid.compose.dto.ComposeStatusResult;
import com.aid.compose.dto.EpisodeExportRequest;
import com.aid.compose.dto.EpisodeExportResult;
import com.aid.compose.dto.EpisodeExportStatusRequest;
import com.aid.compose.dto.EpisodeExportStatusResult;
import com.aid.compose.dto.StoryboardComposeRequest;
import com.aid.compose.dto.VoiceoverParam;
import com.aid.compose.service.ComposeBatchStore;
import com.aid.compose.service.CoreComposeService;
import com.aid.compose.service.VideoComposeService;
import com.aid.compose.util.MaterialUrlGuard;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.enums.GenTypeEnum;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.event.MediaTaskOssPersistedEvent;
import com.aid.media.service.IMediaGenerationService;
import com.aid.service.IAiModelConfigService;
import com.aid.voice.util.DialogueSubtitleFormatter;
import com.aid.voice.util.DialogueTextSanitizer;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 视频合成业务编排实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoComposeServiceImpl implements VideoComposeService {

    /** 受理状态：异步进行 */
    private static final String STATUS_ACCEPTED = "ACCEPTED";

    /** 音频来源：AI 文字配音 */
    private static final int AUDIO_SOURCE_AI = 1;

    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 音色库状态：启用 */
    private static final String VOICE_STATUS_NORMAL = "0";

    /** 合成进度：配音进行中 */
    private static final String COMPOSE_STATUS_VOICING = "VOICING";
    /** 合成进度：配音已齐、合成中 */
    private static final String COMPOSE_STATUS_COMPOSING = "COMPOSING";
    /** 合成进度：合成成功 */
    private static final String COMPOSE_STATUS_SUCCEEDED = "SUCCEEDED";
    /** 合成进度：失败（配音失败或合成失败） */
    private static final String COMPOSE_STATUS_FAILED = "FAILED";

    /** 配音/触发卡单判定阈值（毫秒）：静默超过该时长视为链路断裂，进度查询时自愈收口 */
    private static final long VOICEOVER_STUCK_MS = 10L * 60L * 1000L;

    /** 接口2 导出分组数上限（与剪辑器时间轴单工程段数上限同口径），防前端乱传锤合成层 */
    private static final int MAX_EXPORT_GROUPS = 500;

    /** 接口1 一键配音分镜数上限（与导出分组上限同口径），防单请求制造海量配音任务 */
    private static final int MAX_VOICEOVER_STORYBOARDS = 500;

    /** 接口2 单组素材（视频/配音）URL 数量上限，防单组乱传超长列表 */
    private static final int MAX_URLS_PER_GROUP = 20;

    /** 接口2 单组字幕最大字数（与剪辑器时间轴口径一致），防超长文本挤爆字幕渲染 */
    private static final int MAX_SUBTITLE_LENGTH = 500;

    /** 接口2 工程报文最大字符数（与剪辑器时间轴保存口径一致），防超大 JSON 落库 */
    private static final int MAX_TIMELINE_JSON_LENGTH = 1_000_000;

    /** 抽卡记录 Mapper（接口1 取分镜视频） */
    private final AidGenRecordMapper aidGenRecordMapper;

    /** 分镜服务（接口1 校验分镜归属并按分镜取被选中的视频） */
    private final IAidStoryboardService aidStoryboardService;

    /** 配音记录 Mapper */
    private final AidAudioRecordMapper aidAudioRecordMapper;

    /** 媒体任务 Mapper（aid_media_task 无独立 Service，沿用项目直读 Mapper 的统一做法） */
    private final AidMediaTaskMapper aidMediaTaskMapper;

    /** 剧集剪辑 Mapper（接口2） */
    private final AidEpisodeEditorMapper aidEpisodeEditorMapper;

    /** 项目服务（接口2 自动建档时校验项目归属） */
    private final IAidComicProjectService aidComicProjectService;

    /** 模型配置服务（按 voiceModelId 解析 TTS 模型码） */
    private final IAiModelConfigService aiModelConfigService;

    /** 音色库服务（逐段音色反查：voiceLibraryId → 模型 + 音色编码） */
    private final IAidAiVoiceLibraryService aidAiVoiceLibraryService;

    /** 统一媒体生成服务（异步配音） */
    private final IMediaGenerationService mediaGenerationService;

    /** 核心合成方法 */
    private final CoreComposeService coreComposeService;

    /** 事件发布器（导出卡单自愈：重发 OSS 持久化事件驱动成片回写） */
    private final ApplicationEventPublisher applicationEventPublisher;

    /** 合成批次 Redis 暂存/并发控制 */
    private final ComposeBatchStore composeBatchStore;

    /** 媒体 URL 解析器：DB 相对路径 → 完整可访问 URL（喂 MPS 需完整 URL） */
    private final MediaUrlResolver mediaUrlResolver;

    /** 通用线程池：承载事务提交后的异步配音派发（避免在事务内做阻塞HTTP占用DB连接） */
    private final ThreadPoolTaskExecutor threadPoolTaskExecutor;

    /** 配音派发任务：承载单条待发起的配音记录、文本与该段实际音色（支持逐段音色），事务提交后异步逐条发起 */
    private static final class VoiceoverDispatchJob {
        /** 已入库的配音记录（含主键、projectId、episodeId） */
        private final AidAudioRecord audioRecord;
        /** 该段配音文本 */
        private final String ttsText;
        /** 该段实际使用的 TTS 模型配置（逐段音色可能不同模型） */
        private final AiModelConfigVo modelConfig;
        /** 该段实际使用的厂商音色编码 */
        private final String voiceCode;

        VoiceoverDispatchJob(AidAudioRecord audioRecord, String ttsText,
                             AiModelConfigVo modelConfig, String voiceCode) {
            this.audioRecord = audioRecord;
            this.ttsText = ttsText;
            this.modelConfig = modelConfig;
            this.voiceCode = voiceCode;
        }
    }

    /** 单段音色解析结果（逐段音色用） */
    private static final class ResolvedSegmentVoice {
        /** TTS 模型配置 */
        final AiModelConfigVo modelConfig;
        /** 厂商音色编码 */
        final String voiceCode;
        /** 音色库ID（老入参兜底路径为 null） */
        final Long voiceLibraryId;
        /** 模型ID */
        final Long voiceModelId;

        ResolvedSegmentVoice(AiModelConfigVo modelConfig, String voiceCode, Long voiceLibraryId, Long voiceModelId) {
            this.modelConfig = modelConfig;
            this.voiceCode = voiceCode;
            this.voiceLibraryId = voiceLibraryId;
            this.voiceModelId = voiceModelId;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ComposeAcceptResult composeWithVoiceover(StoryboardComposeRequest request) {
        if (Objects.isNull(request) || CollectionUtil.isEmpty(request.getStoryboardIds())
                || Objects.isNull(request.getVoiceover())) {
            log.error("接口1 入参非法: storyboardIds/voiceover 为空");
            throw new ServiceException("参数有误");
        }
        // 前置快校验（零 I/O）：分镜数上限 + 分辨率档合法性，前端乱传直接拒绝
        if (request.getStoryboardIds().size() > MAX_VOICEOVER_STORYBOARDS) {
            log.info("接口1 分镜数超上限, size={}, max={}",
                    request.getStoryboardIds().size(), MAX_VOICEOVER_STORYBOARDS);
            throw new ServiceException("分镜过多");
        }
        if (!ComposeConstants.isValidResolution(request.getResolution())) {
            log.info("接口1 分辨率档非法, resolution={}", request.getResolution());
            throw new ServiceException("分辨率无效");
        }
        VoiceoverParam voiceover = request.getVoiceover();
        // 默认音色解析（可为 null）：voiceLibraryId 优先反查，其次 voiceModelId+timbreCode 老入参；
        // 允许默认音色缺失——只要每个有台词的段都带逐段音色（voiceLibraryIds），仍可受理；
        // 段级双空在下方循环内 fail-fast，避免"假受理后全失败"
        Map<Long, ResolvedSegmentVoice> voiceCache = new java.util.HashMap<>();
        ResolvedSegmentVoice defaultVoice = resolveDefaultVoice(voiceover, voiceCache);
        Long userId = SecurityUtils.getUserId();

        String composeBatchId = "cb_" + IdUtil.fastSimpleUUID();
        List<Long> storyboardIds = request.getStoryboardIds();
        List<String> ttsTexts = Objects.isNull(voiceover.getTtsTexts()) ? new ArrayList<>() : voiceover.getTtsTexts();

        List<Long> audioRecordIds = new ArrayList<>();
        List<ComposePendingContext.Item> items = new ArrayList<>();
        // 待派发的配音任务：事务提交后再异步发起，避免在事务内做阻塞HTTP
        List<VoiceoverDispatchJob> dispatchJobs = new ArrayList<>();

        // 前置校验（任务生成前 fail-fast）：分镜归属 + 每个分镜已有「分镜视频步骤被选中的视频」，
        // 任一缺失整批拒绝，不产生配音记录、不冻结积分
        List<AidGenRecord> genRecords = loadSelectedStoryboardVideos(storyboardIds, userId);

        for (int i = 0; i < storyboardIds.size(); i++) {
            AidGenRecord genRecord = genRecords.get(i);
            // 台词标记统一清洗（幂等）：剥掉 [角色_形象]：/@音频N[...]/竖线分隔符等结构标记，
            // 只朗读正文；清洗后为空视为该段无配音（与未填一致，合成时配音轨补 Empty）
            String ttsText = i < ttsTexts.size() ? DialogueTextSanitizer.sanitize(ttsTexts.get(i)) : null;

            // 纯配音合成：本接口不烧字幕、不加背景音乐（字幕/BGM 由成片合成导出阶段处理）
            ComposePendingContext.Item item = new ComposePendingContext.Item();
            item.setVideoUrl(mediaUrlResolver.toFullUrl(genRecord.getFileUrl()));
            item.setVideoDuration(Objects.isNull(genRecord.getVideoDuration())
                    ? null : genRecord.getVideoDuration().doubleValue());

            // 该段有配音文本才落配音记录；否则该组无配音（合成时配音轨补 Empty）
            if (StrUtil.isNotBlank(ttsText)) {
                // 逐段音色：该段带 voiceLibraryIds[i] 用指定音色（角色绑定），否则回落默认音色；
                // 双空 fail-fast 整批拒绝（事务回滚，不产生半批配音）
                ResolvedSegmentVoice segmentVoice = resolveSegmentVoice(voiceover, i, defaultVoice, voiceCache);
                if (Objects.isNull(segmentVoice)) {
                    log.error("接口1 段级音色缺失, batchId={}, index={}", composeBatchId, i);
                    throw new ServiceException("音色不可用");
                }
                AidAudioRecord audioRecord = buildAudioRecord(request, genRecord, segmentVoice, ttsText,
                        userId, composeBatchId);
                aidAudioRecordMapper.insert(audioRecord);
                // 仅收集派发任务，事务提交后异步发起（杜绝事务内阻塞HTTP + REQUIRES_NEW 双连接争抢）
                dispatchJobs.add(new VoiceoverDispatchJob(audioRecord, ttsText,
                        segmentVoice.modelConfig, segmentVoice.voiceCode));
                audioRecordIds.add(audioRecord.getId());
                item.setAudioRecordId(audioRecord.getId());
            }
            items.add(item);
        }

        // 整批无有效配音文本：合成无意义，且进度查询也无配音记录可依据，故直接拒绝（事务回滚，不残留空批次）
        if (audioRecordIds.isEmpty()) {
            log.error("接口1 无有效配音文本, batchId={}", composeBatchId);
            throw new ServiceException("请填写配音");
        }

        ComposePendingContext context = new ComposePendingContext();
        context.setComposeBatchId(composeBatchId);
        context.setUserId(userId);
        context.setProjectId(request.getProjectId());
        context.setEpisodeId(request.getEpisodeId());
        context.setResolution(request.getResolution());
        // 配音不可截断：段长以配音为准（取视频/配音较长者），视频不足由合成层拉伸填满，
        // 漏设会回落"视频5秒"定段长，配音超长时挤乱后续段（音画错位+片尾黑屏）
        context.setAlignStrategy(ComposeConstants.ALIGN_STRATEGY_AUDIO);
        context.setItems(items);
        composeBatchStore.saveContext(context);

        // 事务提交后再异步派发配音：避免在事务内做阻塞 HTTP，也避免 REQUIRES_NEW 与外层事务争抢连接造成连接池占用/死锁
        registerVoiceoverDispatch(dispatchJobs, userId);

        log.info("接口1 一键配音受理, batchId={}, storyboards={}, audios={}",
                composeBatchId, storyboardIds.size(), audioRecordIds.size());

        ComposeAcceptResult result = new ComposeAcceptResult();
        result.setComposeBatchId(composeBatchId);
        result.setAudioRecordIds(audioRecordIds);
        result.setStatus(STATUS_ACCEPTED);
        return result;
    }

    /**
     * 接口1 前置校验并按分镜取「分镜视频」（final_video_id 指针，即分镜视频步骤选中的原视频，
     * 与批量配音/分段打包同口径；配音视频抢占选中不影响该指针，重配音素材恒为配音前原视频）。
     * 分镜缺失/越权报「分镜不存在」；分镜未选定视频、指针失效或文件未生成报「请先选择视频」；
     * 校验全部通过才返回，任一失败整批拒绝（任务生成前，不产生配音记录）。
     * 查询字段精简：分镜仅 select id/user_id/final_video_id/del_flag，视频记录仅 select
     * id/storyboard_id/project_id/episode_id/gen_type/file_url/video_duration，后续扩展取数请同步增列。
     *
     * @param storyboardIds 分镜ID列表（顺序即合成顺序，允许同分镜重复出现）
     * @param userId        当前用户ID
     * @return 与 storyboardIds 顺序一一对应的分镜视频记录列表
     */
    private List<AidGenRecord> loadSelectedStoryboardVideos(List<Long> storyboardIds, Long userId) {
        for (Long storyboardId : storyboardIds) {
            if (Objects.isNull(storyboardId)) {
                log.error("接口1 分镜ID为空, userId={}", userId);
                throw new ServiceException("参数有误");
            }
        }
        // 分镜归属校验：全部存在、未删除、归属当前用户
        Set<Long> distinctIds = new LinkedHashSet<>(storyboardIds);
        List<AidStoryboard> storyboards = aidStoryboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getUserId,
                                AidStoryboard::getFinalVideoId, AidStoryboard::getDelFlag)
                        .in(AidStoryboard::getId, distinctIds)
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL));
        if (storyboards.size() != distinctIds.size()) {
            log.error("接口1 分镜缺失或越权, storyboardIds={}, userId={}", distinctIds, userId);
            throw new ServiceException("分镜不存在");
        }
        // 按 final_video_id 批量取分镜视频记录；genType 限原视频轨（历史脏数据误指配音视频时视为未选定）
        Set<Long> finalVideoIds = new LinkedHashSet<>();
        Map<Long, Long> finalVideoIdByStoryboardId = new java.util.HashMap<>();
        for (AidStoryboard storyboard : storyboards) {
            if (Objects.nonNull(storyboard.getFinalVideoId())) {
                finalVideoIds.add(storyboard.getFinalVideoId());
                finalVideoIdByStoryboardId.put(storyboard.getId(), storyboard.getFinalVideoId());
            }
        }
        Map<Long, AidGenRecord> recordById = new java.util.HashMap<>();
        if (CollectionUtil.isNotEmpty(finalVideoIds)) {
            List<AidGenRecord> records = aidGenRecordMapper.selectList(
                    Wrappers.<AidGenRecord>lambdaQuery()
                            .select(AidGenRecord::getId, AidGenRecord::getStoryboardId,
                                    AidGenRecord::getProjectId, AidGenRecord::getEpisodeId,
                                    AidGenRecord::getGenType, AidGenRecord::getFileUrl,
                                    AidGenRecord::getVideoDuration)
                            .in(AidGenRecord::getId, finalVideoIds)
                            .eq(AidGenRecord::getUserId, userId)
                            .in(AidGenRecord::getGenType, GenTypeEnum.originalVideoValues())
                            .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL));
            for (AidGenRecord record : records) {
                if (StrUtil.isNotBlank(record.getFileUrl())) {
                    recordById.put(record.getId(), record);
                }
            }
        }
        List<AidGenRecord> result = new ArrayList<>(storyboardIds.size());
        for (Long storyboardId : storyboardIds) {
            Long finalVideoId = finalVideoIdByStoryboardId.get(storyboardId);
            AidGenRecord record = Objects.isNull(finalVideoId) ? null : recordById.get(finalVideoId);
            if (Objects.isNull(record)) {
                log.error("接口1 分镜未选中视频, storyboardId={}, finalVideoId={}, userId={}",
                        storyboardId, finalVideoId, userId);
                throw new ServiceException("请先选择视频");
            }
            result.add(record);
        }
        return result;
    }

    @Override
    public EpisodeExportResult exportEpisode(EpisodeExportRequest request) {
        if (Objects.isNull(request) || CollectionUtil.isEmpty(request.getGroups())) {
            log.error("接口2 入参非法: request/groups 为空");
            throw new ServiceException("参数有误");
        }
        // 前置快校验（零 I/O）：分组数 / 分辨率档 / 工程报文大小，前端乱传直接拒绝
        if (request.getGroups().size() > MAX_EXPORT_GROUPS) {
            log.info("接口2 分组数超上限, groups={}, max={}", request.getGroups().size(), MAX_EXPORT_GROUPS);
            throw new ServiceException("分组过多");
        }
        if (!ComposeConstants.isValidResolution(request.getResolution())) {
            log.info("接口2 分辨率档非法, resolution={}", request.getResolution());
            throw new ServiceException("分辨率无效");
        }
        if (StrUtil.isNotBlank(request.getTimelineJson())
                && request.getTimelineJson().length() > MAX_TIMELINE_JSON_LENGTH) {
            log.info("接口2 工程报文超长, length={}, max={}",
                    request.getTimelineJson().length(), MAX_TIMELINE_JSON_LENGTH);
            throw new ServiceException("工程报文过大");
        }
        // 分组数据逐项校验：视频必填、时长必填且与 URL 对齐（时长决定对齐与扣费，缺失会导致 0 冻结+字幕错位）；
        // 同时做素材地址结构快校验（blob:/data:/嵌套协议头直接拒绝），零 I/O、不触库
        validateExportGroups(request.getGroups());
        rejectForbiddenUrl(request.getGlobalBgmUrl(), ComposeConstants.MATERIAL_LABEL_BGM, -1);
        Long userId = SecurityUtils.getUserId();
        // 定位剪辑记录：传 episodeEditorId 校验归属；未传按 项目+剧集+用户 查找或自动创建
        AidEpisodeEditor editor = resolveEditor(request, userId);
        // 冗余配音归一化：段视频为配音视频（compose，声音已合进画面）时自动忽略该组配音 MP3，
        // 防止成片同一段叠两层人声；发生在指纹计算之前，等价请求可命中成片复用
        normalizeComposeGroupAudio(request.getGroups(), userId, editor.getProjectId(), editor.getEpisodeId());
        // 受理锁：同一剪辑记录的导出受理串行化，堵住并发双击在「防重查库→置合成中」窗口期的重复冻结
        if (!composeBatchStore.tryExportLock(editor.getId())) {
            log.info("接口2 导出受理锁竞争失败, episodeEditorId={}, userId={}", editor.getId(), userId);
            throw new ServiceException("合成中请稍候");
        }
        try {
            // 锁内重读剪辑记录：锁外快照可能已被上一个并发受理改为「合成中」，防重与复用判定必须基于最新状态
            AidEpisodeEditor freshEditor = reloadEditorInLock(editor.getId());
            return doExportEpisode(request, freshEditor, userId);
        } finally {
            composeBatchStore.unlockExport(editor.getId());
        }
    }

    /**
     * 受理锁内重读剪辑记录（防重/复用判定使用最新状态）。
     * 查询字段精简：受理流程所需字段（新增使用字段时此处必须同步补充），
     * 刻意不读大字段 timeline_json（受理只覆盖写入、不读取）。
     *
     * @param episodeEditorId 剪辑记录ID
     * @return 最新剪辑记录
     */
    private AidEpisodeEditor reloadEditorInLock(Long episodeEditorId) {
        AidEpisodeEditor fresh = aidEpisodeEditorMapper.selectOne(
                Wrappers.<AidEpisodeEditor>lambdaQuery()
                        .select(AidEpisodeEditor::getId, AidEpisodeEditor::getUserId,
                                AidEpisodeEditor::getProjectId, AidEpisodeEditor::getEpisodeId,
                                AidEpisodeEditor::getExportStatus, AidEpisodeEditor::getExportTaskId,
                                AidEpisodeEditor::getFinalVideoUrl, AidEpisodeEditor::getPendingVideoUrl,
                                AidEpisodeEditor::getExportFingerprint, AidEpisodeEditor::getUpdateTime,
                                AidEpisodeEditor::getDelFlag)
                        .eq(AidEpisodeEditor::getId, episodeEditorId)
                        .eq(AidEpisodeEditor::getDelFlag, DEL_FLAG_NORMAL)
                        .last("LIMIT 1"));
        if (Objects.isNull(fresh)) {
            log.error("接口2 锁内重读剪辑记录缺失, episodeEditorId={}", episodeEditorId);
            throw new ServiceException("记录不存在");
        }
        return fresh;
    }

    /**
     * 导出受理主流程（受理锁内执行）。
     * 刻意不包整体事务：素材可达性探测与预冻结均为阻塞外部调用，包事务会长时间占用连接；
     * 各 DB 写入单条原子，受理失败由补偿回写落失败终态。
     *
     * @param request 导出入参
     * @param editor  剪辑记录
     * @param userId  当前用户ID
     * @return 导出受理结果
     */
    private EpisodeExportResult doExportEpisode(EpisodeExportRequest request, AidEpisodeEditor editor, Long userId) {
        // 防重复提交：上一次导出仍在合成中时直接拒绝，避免重复冻结扣费
        rejectIfComposing(editor);

        // 素材指纹：分组素材（视频/配音URL与时长、字幕、BGM、分辨率）归一化后哈希。
        // 上次导出成功且指纹一致 → 素材未变（没重新出片/重新配音/改字幕），直接复用成片，不再合成不再扣费。
        // 最新成片可能在待审槽（已过审内容重新导出的产物），复用优先返回待审新片
        String fingerprint = computeExportFingerprint(request);
        String latestVideoUrl = StrUtil.isNotBlank(editor.getPendingVideoUrl())
                ? editor.getPendingVideoUrl() : editor.getFinalVideoUrl();
        if (!Boolean.TRUE.equals(request.getForceRecompose())
                && Objects.equals(editor.getExportStatus(), ComposeConstants.EXPORT_STATUS_SUCCESS)
                && StrUtil.isNotBlank(latestVideoUrl)
                && Objects.equals(fingerprint, editor.getExportFingerprint())) {
            // 复用不触碰导出状态与审核状态，仅保存最新工程报文（不影响公开展示）
            if (StrUtil.isNotBlank(request.getTimelineJson())) {
                LambdaUpdateWrapper<AidEpisodeEditor> timelineUpdate = new LambdaUpdateWrapper<>();
                timelineUpdate.eq(AidEpisodeEditor::getId, editor.getId());
                timelineUpdate.set(AidEpisodeEditor::getTimelineJson, request.getTimelineJson());
                timelineUpdate.set(AidEpisodeEditor::getUpdateTime, new Date());
                timelineUpdate.set(AidEpisodeEditor::getUpdateBy, String.valueOf(userId));
                aidEpisodeEditorMapper.update(null, timelineUpdate);
            }
            log.info("接口2 素材未变复用已有成片, episodeEditorId={}, fingerprint={}", editor.getId(), fingerprint);
            EpisodeExportResult reusedResult = new EpisodeExportResult();
            reusedResult.setEpisodeEditorId(editor.getId());
            reusedResult.setExportTaskId(editor.getExportTaskId());
            reusedResult.setExportStatus(ComposeConstants.EXPORT_STATUS_SUCCESS);
            reusedResult.setReused(true);
            reusedResult.setFinalVideoUrl(latestVideoUrl);
            return reusedResult;
        }
        // 说明：已过审/审核中内容重新导出不再回落审核状态、不下架旧片——
        // 新成片由回写监听器落待审槽（pending_video_url），旧片继续公开展示，重新过审后新片转正

        LambdaUpdateWrapper<AidEpisodeEditor> editorUpdate = new LambdaUpdateWrapper<>();
        editorUpdate.eq(AidEpisodeEditor::getId, editor.getId());
        editorUpdate.set(AidEpisodeEditor::getExportStatus, ComposeConstants.EXPORT_STATUS_COMPOSING);
        editorUpdate.set(AidEpisodeEditor::getExportProgress, 0);
        editorUpdate.set(AidEpisodeEditor::getErrorMsg, null);
        if (StrUtil.isNotBlank(request.getTimelineJson())) {
            editorUpdate.set(AidEpisodeEditor::getTimelineJson, request.getTimelineJson());
        }
        editorUpdate.set(AidEpisodeEditor::getUpdateTime, new Date());
        editorUpdate.set(AidEpisodeEditor::getUpdateBy, String.valueOf(userId));
        aidEpisodeEditorMapper.update(null, editorUpdate);

        ComposeCommand command = buildExportCommand(request, editor, userId);
        ComposeSubmitResult submitResult;
        try {
            submitResult = coreComposeService.compose(command);
        } catch (Exception composeEx) {
            // 受理失败补偿：把刚置的「合成中」落为失败终态（条件更新，不覆盖并发终态），
            // 同步把失败原因回写 errorMsg，进度轮询与本次响应口径一致
            writeAcceptFailed(editor.getId(), resolveShortError(composeEx));
            throw composeEx;
        }

        String exportTaskId = String.valueOf(submitResult.getMediaTaskId());
        LambdaUpdateWrapper<AidEpisodeEditor> taskIdUpdate = new LambdaUpdateWrapper<>();
        taskIdUpdate.eq(AidEpisodeEditor::getId, editor.getId());
        taskIdUpdate.set(AidEpisodeEditor::getExportTaskId, exportTaskId);
        // 素材指纹在受理成功后才落库：仅描述「当前任务对应的素材」，受理失败不残留新指纹
        taskIdUpdate.set(AidEpisodeEditor::getExportFingerprint, fingerprint);
        taskIdUpdate.set(AidEpisodeEditor::getUpdateTime, new Date());
        taskIdUpdate.set(AidEpisodeEditor::getUpdateBy, String.valueOf(userId));
        aidEpisodeEditorMapper.update(null, taskIdUpdate);

        log.info("接口2 导出受理, episodeEditorId={}, mediaTaskId={}", editor.getId(), submitResult.getMediaTaskId());

        EpisodeExportResult result = new EpisodeExportResult();
        result.setEpisodeEditorId(editor.getId());
        result.setExportTaskId(exportTaskId);
        result.setExportStatus(ComposeConstants.EXPORT_STATUS_COMPOSING);
        result.setReused(false);
        return result;
    }

    /**
     * 受理失败补偿回写：仅当记录仍处「合成中」时落失败终态 + 失败原因。
     * 补偿失败只记录日志，不吞掉原始受理异常。
     *
     * @param episodeEditorId 剪辑记录ID
     * @param errorMsg        失败原因短文案
     */
    private void writeAcceptFailed(Long episodeEditorId, String errorMsg) {
        try {
            LambdaUpdateWrapper<AidEpisodeEditor> failUpdate = new LambdaUpdateWrapper<>();
            failUpdate.eq(AidEpisodeEditor::getId, episodeEditorId);
            failUpdate.eq(AidEpisodeEditor::getExportStatus, ComposeConstants.EXPORT_STATUS_COMPOSING);
            failUpdate.set(AidEpisodeEditor::getExportStatus, ComposeConstants.EXPORT_STATUS_FAILED);
            failUpdate.set(AidEpisodeEditor::getErrorMsg, errorMsg);
            failUpdate.set(AidEpisodeEditor::getUpdateTime, new Date());
            aidEpisodeEditorMapper.update(null, failUpdate);
        } catch (Exception writeEx) {
            log.error("接口2 受理失败补偿回写异常, episodeEditorId={}", episodeEditorId, writeEx);
        }
    }

    /**
     * 提取受理异常的用户短文案：业务异常直接用其消息，其余统一「合成失败」。
     *
     * @param ex 受理异常
     * @return 失败原因短文案
     */
    private String resolveShortError(Exception ex) {
        String message = ex.getMessage();
        boolean bizError = ex instanceof ServiceException
                || (ex.getClass() == RuntimeException.class && StrUtil.isNotBlank(message));
        return bizError && StrUtil.isNotBlank(message) ? message : "合成失败";
    }

    /**
     * 素材地址结构快校验：blob:/data:/嵌套协议头等前端误传地址直接拒绝。
     *
     * @param url        素材地址（可空，空不校验）
     * @param label      素材标签（错误文案前缀）
     * @param groupIndex 分组下标（日志用，-1=全片级）
     */
    private void rejectForbiddenUrl(String url, String label, int groupIndex) {
        if (MaterialUrlGuard.isForbidden(url)) {
            log.error("合成素材地址结构非法, groupIndex={}, label={}, url={}", groupIndex, label, url);
            throw new ServiceException(label + "异常");
        }
    }

    /**
     * 计算导出素材指纹：分辨率 + 整片BGM + 各组（视频URL/时长、配音URL/时长、字幕、分组BGM）
     * 归一化拼接后 SHA-256。URL 统一转相对路径（同一文件的全 URL 与相对路径视为相同素材），
     * 时长统一保留 3 位小数去尾零。分镜重新出片/重新配音会生成新 OSS 文件（URL 必变），
     * 字幕/BGM/分辨率改动直接改变指纹——任何素材变化都会触发重新合成。
     *
     * @param request 导出入参
     * @return SHA-256 十六进制指纹（64字符）
     */
    private String computeExportFingerprint(EpisodeExportRequest request) {
        StringBuilder raw = new StringBuilder("v1");
        raw.append("|R:").append(StrUtil.isBlank(request.getResolution())
                ? "FHD" : request.getResolution().trim().toUpperCase());
        raw.append("|GB:").append(normalizeFingerprintUrl(request.getGlobalBgmUrl()));
        for (ComposeGroupDto group : request.getGroups()) {
            raw.append("|G");
            appendUrlList(raw, group.getVideoUrls());
            appendDurationList(raw, group.getVideoDurations());
            appendUrlList(raw, group.getAudioUrls());
            appendDurationList(raw, group.getAudioDurations());
            raw.append("|S:").append(StrUtil.isBlank(group.getSubtitle()) ? "" : group.getSubtitle().trim());
            raw.append("|B:").append(normalizeFingerprintUrl(group.getBgmUrl()));
        }
        return DigestUtil.sha256Hex(raw.toString());
    }

    /**
     * 指纹用 URL 归一化：转相对路径（全 URL 与相对路径指向同一文件时指纹一致），空返回空串。
     *
     * @param url 原始 URL
     * @return 归一化结果
     */
    private String normalizeFingerprintUrl(String url) {
        if (StrUtil.isBlank(url)) {
            return "";
        }
        String relative = mediaUrlResolver.toRelativePath(url.trim());
        return StrUtil.isBlank(relative) ? url.trim() : relative;
    }

    /**
     * 指纹拼接 URL 列表（保序）。
     *
     * @param raw  拼接器
     * @param urls URL 列表
     */
    private void appendUrlList(StringBuilder raw, List<String> urls) {
        raw.append("|U:");
        if (CollectionUtil.isEmpty(urls)) {
            return;
        }
        for (String url : urls) {
            raw.append(normalizeFingerprintUrl(url)).append(',');
        }
    }

    /**
     * 指纹拼接时长列表（保序，3 位小数去尾零，null 记为 -）。
     *
     * @param raw       拼接器
     * @param durations 时长列表
     */
    private void appendDurationList(StringBuilder raw, List<Double> durations) {
        raw.append("|D:");
        if (CollectionUtil.isEmpty(durations)) {
            return;
        }
        for (Double duration : durations) {
            if (Objects.isNull(duration)) {
                raw.append('-');
            } else {
                raw.append(BigDecimal.valueOf(duration)
                        .setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
            }
            raw.append(',');
        }
    }

    @Override
    public EpisodeExportStatusResult queryExportStatus(EpisodeExportStatusRequest request) {
        // episodeEditorId 与 projectId+episodeId 二选一
        if (Objects.isNull(request) || (Objects.isNull(request.getEpisodeEditorId())
                && (Objects.isNull(request.getProjectId()) || Objects.isNull(request.getEpisodeId())))) {
            log.error("接口2 进度查询入参非法: episodeEditorId 与 projectId+episodeId 均为空");
            throw new ServiceException("参数有误");
        }
        Long userId = SecurityUtils.getUserId();
        // 查询字段精简：仅返回进度展示所需字段（新增出参字段时此处必须同步补充）
        LambdaQueryWrapper<AidEpisodeEditor> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(AidEpisodeEditor::getId, AidEpisodeEditor::getProjectId, AidEpisodeEditor::getEpisodeId,
                AidEpisodeEditor::getExportStatus, AidEpisodeEditor::getExportProgress,
                AidEpisodeEditor::getFinalVideoUrl, AidEpisodeEditor::getPendingVideoUrl,
                AidEpisodeEditor::getCoverUrl,
                AidEpisodeEditor::getErrorMsg, AidEpisodeEditor::getExportTaskId);
        // 仅可查本人未删除的记录（防越权）
        wrapper.eq(AidEpisodeEditor::getUserId, userId);
        wrapper.eq(AidEpisodeEditor::getDelFlag, DEL_FLAG_NORMAL);
        if (Objects.nonNull(request.getEpisodeEditorId())) {
            wrapper.eq(AidEpisodeEditor::getId, request.getEpisodeEditorId());
        } else {
            wrapper.eq(AidEpisodeEditor::getProjectId, request.getProjectId());
            wrapper.eq(AidEpisodeEditor::getEpisodeId, request.getEpisodeId());
        }
        wrapper.orderByDesc(AidEpisodeEditor::getId);
        wrapper.last("LIMIT 1");
        AidEpisodeEditor editor = aidEpisodeEditorMapper.selectOne(wrapper);
        if (Objects.isNull(editor)) {
            log.info("接口2 进度查询未命中记录, editorId={}, projectId={}, episodeId={}, userId={}",
                    request.getEpisodeEditorId(), request.getProjectId(), request.getEpisodeId(), userId);
            throw new ServiceException("记录不存在");
        }
        // 自愈：合成任务已终态但剪辑记录仍停留在合成中（历史回写事件丢失）时，按任务终态补偿回写后重读
        editor = selfHealStuckExport(editor, wrapper);

        EpisodeExportStatusResult result = new EpisodeExportStatusResult();
        result.setEpisodeEditorId(editor.getId());
        result.setProjectId(editor.getProjectId());
        result.setEpisodeId(editor.getEpisodeId());
        result.setExportStatus(editor.getExportStatus());
        result.setExportProgress(editor.getExportProgress());
        result.setFinalVideoUrl(editor.getFinalVideoUrl());
        result.setCoverUrl(editor.getCoverUrl());
        result.setErrorMsg(editor.getErrorMsg());
        result.setExportTaskId(editor.getExportTaskId());
        // 待审新片：非空=成片已变更需重新提审（旧片继续公开展示，过审后新片自动转正）
        result.setPendingVideoUrl(editor.getPendingVideoUrl());
        result.setNeedReaudit(StrUtil.isNotBlank(editor.getPendingVideoUrl()));
        return result;
    }

    /**
     * 导出卡单自愈：剪辑记录仍显示「合成中」，但对应 COMPOSE 任务其实已终态
     * （回写事件丢失，如进程重启或事件未发布，回写监听器没有触发）。
     * 成功 → 重发 OSS 持久化事件驱动 ComposeResultListener 按双槽规则回写后重读；
     * 失败 → 直接补写失败终态。任务仍在跑或自愈异常时原样返回，不影响查询主链路。
     *
     * @param editor  当前剪辑记录
     * @param wrapper 原查询条件（自愈后重读用）
     * @return 自愈后的最新剪辑记录（无需自愈时原样返回）
     */
    private AidEpisodeEditor selfHealStuckExport(AidEpisodeEditor editor,
            LambdaQueryWrapper<AidEpisodeEditor> wrapper) {
        if (!Objects.equals(editor.getExportStatus(), ComposeConstants.EXPORT_STATUS_COMPOSING)
                || StrUtil.isBlank(editor.getExportTaskId())
                || !NumberUtil.isLong(editor.getExportTaskId())) {
            return editor;
        }
        try {
            // 查询字段精简：自愈判定仅需终态与结果字段（新增使用字段时此处必须同步补充）
            AidMediaTask task = aidMediaTaskMapper.selectOne(new LambdaQueryWrapper<AidMediaTask>()
                    .select(AidMediaTask::getId, AidMediaTask::getStatus, AidMediaTask::getOssUrl,
                            AidMediaTask::getErrorMessage, AidMediaTask::getUserId)
                    .eq(AidMediaTask::getId, Long.parseLong(editor.getExportTaskId()))
                    .last("LIMIT 1"));
            if (Objects.isNull(task)) {
                return editor;
            }
            if (MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())
                    && StrUtil.isNotBlank(task.getOssUrl())) {
                // 重发事件让 ComposeResultListener 按审核双槽规则回写（监听器幂等）
                applicationEventPublisher.publishEvent(
                        new MediaTaskOssPersistedEvent(this, task.getId(), task.getUserId()));
                log.info("接口2 导出卡单自愈成功, episodeEditorId={}, taskId={}", editor.getId(), task.getId());
            } else if (MediaTaskStatus.FAILED.name().equals(task.getStatus())) {
                LambdaUpdateWrapper<AidEpisodeEditor> failUpdate = new LambdaUpdateWrapper<>();
                failUpdate.eq(AidEpisodeEditor::getId, editor.getId());
                failUpdate.eq(AidEpisodeEditor::getExportStatus, ComposeConstants.EXPORT_STATUS_COMPOSING);
                failUpdate.set(AidEpisodeEditor::getExportStatus, ComposeConstants.EXPORT_STATUS_FAILED);
                failUpdate.set(AidEpisodeEditor::getErrorMsg,
                        StrUtil.blankToDefault(task.getErrorMessage(), "合成失败"));
                failUpdate.set(AidEpisodeEditor::getUpdateTime, new Date());
                aidEpisodeEditorMapper.update(null, failUpdate);
                log.info("接口2 导出卡单自愈为失败终态, episodeEditorId={}, taskId={}", editor.getId(), task.getId());
            } else {
                // 任务仍在跑：无需自愈
                return editor;
            }
            AidEpisodeEditor refreshed = aidEpisodeEditorMapper.selectOne(wrapper);
            return Objects.nonNull(refreshed) ? refreshed : editor;
        } catch (Exception ex) {
            // 自愈属于兜底增强，异常不阻断进度查询
            log.error("接口2 导出卡单自愈异常, episodeEditorId={}", editor.getId(), ex);
            return editor;
        }
    }

    /**
     * 接口2 分组数据校验（fail-fast，零 I/O）：
     * 每组视频 URL 非空且结构合法、数量不超上限；视频时长必填且与 URL 等长、每项 > 0；
     * 有配音则配音时长同样必填对齐；字幕不超长；
     * 视频/配音/分组 BGM 地址均拒绝 blob:/data:/嵌套协议头等前端误传结构。
     * 时长决定段对齐（字幕区间、BGM 铺设、配音补位）与预冻结扣费，缺失或错位直接拒绝。
     *
     * @param groups 合成分组列表
     */
    private void validateExportGroups(List<ComposeGroupDto> groups) {
        for (int i = 0; i < groups.size(); i++) {
            ComposeGroupDto group = groups.get(i);
            if (Objects.isNull(group) || CollectionUtil.isEmpty(group.getVideoUrls())) {
                log.error("接口2 第{}组视频URL为空", i);
                throw new ServiceException("参数有误");
            }
            if (group.getVideoUrls().size() > MAX_URLS_PER_GROUP) {
                log.error("接口2 第{}组视频URL数超上限, size={}, max={}",
                        i, group.getVideoUrls().size(), MAX_URLS_PER_GROUP);
                throw new ServiceException("素材过多");
            }
            for (String videoUrl : group.getVideoUrls()) {
                rejectForbiddenUrl(videoUrl, ComposeConstants.MATERIAL_LABEL_VIDEO, i);
            }
            if (CollectionUtil.isEmpty(group.getVideoDurations())
                    || group.getVideoDurations().size() != group.getVideoUrls().size()) {
                log.error("接口2 第{}组视频时长缺失或与URL数量不一致, urls={}, durations={}",
                        i, group.getVideoUrls().size(),
                        CollectionUtil.isEmpty(group.getVideoDurations()) ? 0 : group.getVideoDurations().size());
                throw new ServiceException("视频时长有误");
            }
            for (Double duration : group.getVideoDurations()) {
                if (Objects.isNull(duration) || duration <= 0) {
                    log.error("接口2 第{}组存在非法视频时长, duration={}", i, duration);
                    throw new ServiceException("视频时长有误");
                }
            }
            // 字幕超长拦截：超过渲染上限的长文本会挤爆字幕排版（换行渲染按行数放大）
            if (StrUtil.isNotBlank(group.getSubtitle()) && group.getSubtitle().length() > MAX_SUBTITLE_LENGTH) {
                log.error("接口2 第{}组字幕超长, length={}, max={}", i, group.getSubtitle().length(), MAX_SUBTITLE_LENGTH);
                throw new ServiceException("字幕过长");
            }
            rejectForbiddenUrl(group.getBgmUrl(), ComposeConstants.MATERIAL_LABEL_BGM, i);
            if (CollectionUtil.isEmpty(group.getAudioUrls())) {
                continue;
            }
            if (group.getAudioUrls().size() > MAX_URLS_PER_GROUP) {
                log.error("接口2 第{}组配音URL数超上限, size={}, max={}",
                        i, group.getAudioUrls().size(), MAX_URLS_PER_GROUP);
                throw new ServiceException("素材过多");
            }
            for (String audioUrl : group.getAudioUrls()) {
                rejectForbiddenUrl(audioUrl, ComposeConstants.MATERIAL_LABEL_AUDIO, i);
            }
            if (CollectionUtil.isEmpty(group.getAudioDurations())
                    || group.getAudioDurations().size() != group.getAudioUrls().size()) {
                log.error("接口2 第{}组配音时长缺失或与URL数量不一致, urls={}, durations={}",
                        i, group.getAudioUrls().size(),
                        CollectionUtil.isEmpty(group.getAudioDurations()) ? 0 : group.getAudioDurations().size());
                throw new ServiceException("配音时长有误");
            }
            for (Double duration : group.getAudioDurations()) {
                if (Objects.isNull(duration) || duration <= 0) {
                    log.error("接口2 第{}组存在非法配音时长, duration={}", i, duration);
                    throw new ServiceException("配音时长有误");
                }
            }
        }
    }

    /**
     * 冗余配音归一化（接口2 受理前置）：段视频为本人「配音视频」（genType=compose，配音/对口型产物，
     * 声音已合进画面）时，该组传入的配音 MP3 属于冗余素材（多为历史剪辑工程残留），
     * 服务端自动忽略该组配音后继续受理——既避免成片同一段叠两层人声，也保证工程可随时重新导出。
     * 比对不过滤删除标志：配音视频记录被删除不改变文件内已烧入人声的事实。
     * 实现：一次索引查询（命中 idx_proj_ep_user_type）取本剧集全部配音视频相对路径，内存归一化比对。
     *
     * @param groups    合成分组列表（就地修改：冗余配音组的 audioUrls/audioDurations 被置空）
     * @param userId    当前用户ID
     * @param projectId 项目ID
     * @param episodeId 剧集ID（电影为 0）
     */
    private void normalizeComposeGroupAudio(List<ComposeGroupDto> groups, Long userId,
                                            Long projectId, Long episodeId) {
        if (Objects.isNull(projectId) || Objects.isNull(episodeId) || !hasAnyGroupAudio(groups)) {
            return;
        }
        // 查询字段精简：冗余配音比对仅需成片地址（新增使用字段时此处必须同步补充）
        List<AidGenRecord> composeRecords = aidGenRecordMapper.selectList(
                Wrappers.<AidGenRecord>lambdaQuery()
                        .select(AidGenRecord::getId, AidGenRecord::getFileUrl)
                        .eq(AidGenRecord::getProjectId, projectId)
                        .eq(AidGenRecord::getEpisodeId, episodeId)
                        .eq(AidGenRecord::getUserId, userId)
                        .eq(AidGenRecord::getGenType, GenTypeEnum.COMPOSE.getValue())
                        .isNotNull(AidGenRecord::getFileUrl));
        if (CollectionUtil.isEmpty(composeRecords)) {
            return;
        }
        // 归一化口径与成片复用指纹一致（normalizeFingerprintUrl），全 URL 与相对路径指向同一文件时可对上
        Set<String> composePaths = new LinkedHashSet<>();
        for (AidGenRecord record : composeRecords) {
            String normalized = normalizeFingerprintUrl(record.getFileUrl());
            if (StrUtil.isNotBlank(normalized)) {
                composePaths.add(normalized);
            }
        }
        if (CollectionUtil.isEmpty(composePaths)) {
            return;
        }
        for (int i = 0; i < groups.size(); i++) {
            ComposeGroupDto group = groups.get(i);
            if (Objects.isNull(group) || CollectionUtil.isEmpty(group.getAudioUrls())
                    || CollectionUtil.isEmpty(group.getVideoUrls())) {
                continue;
            }
            for (String videoUrl : group.getVideoUrls()) {
                String candidate = normalizeFingerprintUrl(videoUrl);
                if (StrUtil.isNotBlank(candidate) && composePaths.contains(candidate)) {
                    log.warn("接口2 配音视频段冗余配音已自动忽略, userId={}, projectId={}, episodeId={}, "
                            + "groupIndex={}, videoUrl={}", userId, projectId, episodeId, i, videoUrl);
                    group.setAudioUrls(null);
                    group.setAudioDurations(null);
                    break;
                }
            }
        }
    }

    /**
     * 判断分组列表中是否存在带配音的组（无配音时冗余配音归一化可零查询直接返回）。
     *
     * @param groups 合成分组列表
     * @return true=至少一组带配音
     */
    private boolean hasAnyGroupAudio(List<ComposeGroupDto> groups) {
        for (ComposeGroupDto group : groups) {
            if (Objects.nonNull(group) && CollectionUtil.isNotEmpty(group.getAudioUrls())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 定位剪辑记录：
     * 传 episodeEditorId → 按 ID 查并校验归属当前用户（防越权）；
     * 未传 → 必须传 projectId+episodeId，校验项目归属后按「用户+项目+剧集」查最新记录，无则自动创建。
     *
     * @param request 接口2 入参
     * @param userId  当前用户ID
     * @return 剪辑记录（必不为 null）
     */
    private AidEpisodeEditor resolveEditor(EpisodeExportRequest request, Long userId) {
        if (Objects.nonNull(request.getEpisodeEditorId())) {
            AidEpisodeEditor editor = aidEpisodeEditorMapper.selectById(request.getEpisodeEditorId());
            if (Objects.isNull(editor) || !Objects.equals(DEL_FLAG_NORMAL, editor.getDelFlag())) {
                log.error("接口2 剧集剪辑记录缺失, episodeEditorId={}", request.getEpisodeEditorId());
                throw new ServiceException("记录不存在");
            }
            // 归属校验：只能导出自己的剪辑记录
            if (!Objects.equals(editor.getUserId(), userId)) {
                log.error("接口2 剪辑记录越权, episodeEditorId={}, ownerId={}, userId={}",
                        editor.getId(), editor.getUserId(), userId);
                throw new ServiceException("无权操作");
            }
            return editor;
        }
        if (Objects.isNull(request.getProjectId()) || Objects.isNull(request.getEpisodeId())) {
            log.error("接口2 未传 episodeEditorId 且 projectId/episodeId 不全, projectId={}, episodeId={}",
                    request.getProjectId(), request.getEpisodeId());
            throw new ServiceException("参数有误");
        }
        // 项目归属校验：防止给他人项目建档
        AidComicProject project = aidComicProjectService.getById(request.getProjectId());
        if (Objects.isNull(project) || !Objects.equals(project.getUserId(), userId)) {
            log.error("接口2 项目缺失或越权, projectId={}, userId={}", request.getProjectId(), userId);
            throw new ServiceException("项目不存在");
        }
        AidEpisodeEditor existed = selectLatestEditor(request.getProjectId(), request.getEpisodeId(), userId);
        if (Objects.nonNull(existed)) {
            return existed;
        }
        // 首次导出自动建档
        AidEpisodeEditor created = new AidEpisodeEditor();
        created.setUserId(userId);
        created.setProjectId(request.getProjectId());
        created.setEpisodeId(request.getEpisodeId());
        created.setExportStatus(0);
        created.setExportProgress(0);
        created.setDelFlag(DEL_FLAG_NORMAL);
        Date now = new Date();
        created.setCreateTime(now);
        created.setUpdateTime(now);
        created.setCreateBy(String.valueOf(userId));
        try {
            aidEpisodeEditorMapper.insert(created);
        } catch (DuplicateKeyException e) {
            // 撞 uk_project_episode：并发请求已先建档，复用先插入的那条
            AidEpisodeEditor concurrent = selectLatestEditor(request.getProjectId(), request.getEpisodeId(), userId);
            if (Objects.nonNull(concurrent)) {
                log.info("接口2 并发建档冲突复用已有记录, episodeEditorId={}, projectId={}, episodeId={}, userId={}",
                        concurrent.getId(), request.getProjectId(), request.getEpisodeId(), userId);
                return concurrent;
            }
            log.error("接口2 建档唯一键冲突且复查无记录, projectId={}, episodeId={}, userId={}",
                    request.getProjectId(), request.getEpisodeId(), userId, e);
            throw new ServiceException("剪辑记录创建失败，请重试");
        }
        log.info("接口2 自动创建剪辑记录, episodeEditorId={}, projectId={}, episodeId={}, userId={}",
                created.getId(), request.getProjectId(), request.getEpisodeId(), userId);
        return created;
    }

    /**
     * 按「用户+项目+剧集」查最新一条剪辑记录。
     * 查询字段精简：防重、更新与成片复用判定所需字段（新增使用字段时此处必须同步补充）；
     * finalVideoUrl/pendingVideoUrl/exportFingerprint 缺失会导致按项目+剧集导出时指纹比对恒不等、复用永远不命中。
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID
     * @param userId    当前用户ID
     * @return 最新剪辑记录，无则 null
     */
    private AidEpisodeEditor selectLatestEditor(Long projectId, Long episodeId, Long userId) {
        return aidEpisodeEditorMapper.selectOne(
                Wrappers.<AidEpisodeEditor>lambdaQuery()
                        .select(AidEpisodeEditor::getId, AidEpisodeEditor::getUserId,
                                AidEpisodeEditor::getProjectId, AidEpisodeEditor::getEpisodeId,
                                AidEpisodeEditor::getExportStatus, AidEpisodeEditor::getExportTaskId,
                                AidEpisodeEditor::getFinalVideoUrl, AidEpisodeEditor::getPendingVideoUrl,
                                AidEpisodeEditor::getExportFingerprint, AidEpisodeEditor::getUpdateTime,
                                AidEpisodeEditor::getDelFlag)
                        .eq(AidEpisodeEditor::getUserId, userId)
                        .eq(AidEpisodeEditor::getProjectId, projectId)
                        .eq(AidEpisodeEditor::getEpisodeId, episodeId)
                        .eq(AidEpisodeEditor::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByDesc(AidEpisodeEditor::getId)
                        .last("LIMIT 1"));
    }

    /** 「合成中但无任务ID」的受理在途保护窗（毫秒）：窗内视为上一请求仍在受理（探测/冻结中），拒绝并发提交 */
    private static final long EXPORT_ACCEPTING_GRACE_MS = 10L * 60L * 1000L;

    /**
     * 防重复提交：记录已处于「合成中」且对应合成任务仍在跑时拒绝再次导出（避免重复冻结扣费）；
     * 任务已终态或不存在（状态残留）则放行，允许重新导出。
     *
     * @param editor 剪辑记录
     */
    private void rejectIfComposing(AidEpisodeEditor editor) {
        if (!Objects.equals(editor.getExportStatus(), ComposeConstants.EXPORT_STATUS_COMPOSING)) {
            return;
        }
        String taskIdStr = editor.getExportTaskId();
        if (StrUtil.isBlank(taskIdStr) || !NumberUtil.isLong(taskIdStr)) {
            // 「合成中但无任务ID」= 上一请求仍在受理窗口（素材探测/预冻结中，耗时可能超过受理锁 TTL）。
            // 保护窗内拒绝（防受理锁过期后并发双任务双冻结）；超窗视为受理崩溃残留，放行重新导出
            Date updateTime = editor.getUpdateTime();
            if (Objects.nonNull(updateTime)
                    && System.currentTimeMillis() - updateTime.getTime() < EXPORT_ACCEPTING_GRACE_MS) {
                log.info("接口2 上次导出仍在受理窗口内,拒绝并发提交, episodeEditorId={}, updateTime={}",
                        editor.getId(), updateTime);
                throw new ServiceException("合成中请稍候");
            }
            return;
        }
        // 查询字段精简：防重判断只需任务状态
        AidMediaTask task = aidMediaTaskMapper.selectOne(
                Wrappers.<AidMediaTask>lambdaQuery()
                        .select(AidMediaTask::getId, AidMediaTask::getStatus)
                        .eq(AidMediaTask::getId, Long.parseLong(taskIdStr))
                        .last("LIMIT 1"));
        if (Objects.isNull(task)) {
            return;
        }
        boolean finished = MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())
                || MediaTaskStatus.FAILED.name().equals(task.getStatus());
        if (!finished) {
            log.info("接口2 上次导出仍在合成中,拒绝重复提交, episodeEditorId={}, taskId={}, status={}",
                    editor.getId(), task.getId(), task.getStatus());
            throw new ServiceException("合成中请稍候");
        }
    }

    /**
     * 解析 TTS 模型配置。
     *
     * @param voiceModelId TTS 模型ID
     * @return 模型配置
     */
    private AiModelConfigVo resolveVoiceModel(Long voiceModelId) {
        if (Objects.isNull(voiceModelId)) {
            log.error("接口1 配音模型ID为空");
            throw new ServiceException("参数有误");
        }
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelId(voiceModelId);
        if (Objects.isNull(modelConfig) || StrUtil.isBlank(modelConfig.getModelCode())) {
            log.error("接口1 配音模型不存在, voiceModelId={}", voiceModelId);
            throw new ServiceException("模型异常");
        }
        return modelConfig;
    }

    /**
     * 接口1 合成进度查询（纯轮询）。见接口定义说明。
     *
     * @param request 入参（composeBatchId 必填）
     * @return 合成进度结果
     */
    @Override
    public ComposeStatusResult queryComposeStatus(ComposeStatusRequest request) {
        if (Objects.isNull(request) || StrUtil.isBlank(request.getComposeBatchId())) {
            log.error("合成进度查询入参非法: composeBatchId 为空");
            throw new ServiceException("参数有误");
        }
        String batchId = request.getComposeBatchId().trim();
        Long userId = SecurityUtils.getUserId();

        List<AidAudioRecord> audioRecords = listComposeBatchAudioRecords(batchId, userId);
        if (CollectionUtil.isEmpty(audioRecords)) {
            log.info("合成进度查询: 批次不存在或不属于当前用户, batchId={}, userId={}", batchId, userId);
            throw new ServiceException("批次不存在");
        }
        // 配音卡单自愈：配音记录停留 PROCESSING 但对应媒体任务已终态/已丢失时按任务态补偿回写，
        // 自愈发生后重读记录，避免前端因事件丢失永远收到 VOICING
        if (selfHealStuckVoiceover(audioRecords, userId)) {
            audioRecords = listComposeBatchAudioRecords(batchId, userId);
        }

        int total = audioRecords.size();
        int succeeded = 0;
        int failed = 0;
        String firstAudioError = null;
        for (AidAudioRecord ar : audioRecords) {
            if (MediaTaskStatus.SUCCEEDED.name().equals(ar.getStatus())) {
                succeeded++;
            } else if (MediaTaskStatus.FAILED.name().equals(ar.getStatus())) {
                failed++;
                if (StrUtil.isBlank(firstAudioError) && StrUtil.isNotBlank(ar.getErrorMessage())) {
                    firstAudioError = ar.getErrorMessage();
                }
            }
        }

        ComposeStatusResult result = new ComposeStatusResult();
        result.setComposeBatchId(batchId);
        result.setAudioTotal(total);
        result.setAudioSucceeded(succeeded);
        result.setAudioFailed(failed);

        AidMediaTask composeTask = aidMediaTaskMapper.selectOne(
                Wrappers.<AidMediaTask>lambdaQuery()
                        .select(AidMediaTask::getId, AidMediaTask::getStatus,
                                AidMediaTask::getOssUrl, AidMediaTask::getOutputDurationSeconds)
                        .eq(AidMediaTask::getComposeBatchId, batchId)
                        .eq(AidMediaTask::getMediaType, ComposeConstants.MEDIA_TYPE_COMPOSE)
                        .eq(AidMediaTask::getUserId, userId)
                        .orderByDesc(AidMediaTask::getId)
                        .last("LIMIT 1"));

        if (Objects.nonNull(composeTask)) {
            if (MediaTaskStatus.SUCCEEDED.name().equals(composeTask.getStatus())
                    && StrUtil.isNotBlank(composeTask.getOssUrl())) {
                // 合成成功且成片地址就绪：回传成片地址（相对路径，出参 @MediaUrl 自动拼域名）与时长
                result.setStatus(COMPOSE_STATUS_SUCCEEDED);
                result.setVideoUrl(composeTask.getOssUrl());
                result.setVideoDuration(composeTask.getOutputDurationSeconds());
            } else if (MediaTaskStatus.FAILED.name().equals(composeTask.getStatus())) {
                result.setStatus(COMPOSE_STATUS_FAILED);
                result.setErrorMessage("合成失败");
            } else {
                // 合成中，或已置 SUCCEEDED 但成片 OSS 尚未回填的极短窗口，均按合成中处理
                result.setStatus(COMPOSE_STATUS_COMPOSING);
            }
        } else if (failed > 0 || composeBatchStore.isFailed(batchId)) {
            // 配音失败，或配音已齐但合成触发阶段异常被标记失败（此时不会再产生合成任务）
            result.setStatus(COMPOSE_STATUS_FAILED);
            result.setErrorMessage(StrUtil.isNotBlank(firstAudioError) ? firstAudioError : "合成失败");
        } else if (succeeded == total) {
            // 配音已齐、合成任务尚未落库：正常为事件链触发中的极短窗口；
            // 若触发事件丢失（服务重启等）则重发判齐事件自愈，仍无法触发时按失败收口
            selfHealComposeNotTriggered(batchId, audioRecords, userId);
            if (composeBatchStore.isFailed(batchId)) {
                result.setStatus(COMPOSE_STATUS_FAILED);
                result.setErrorMessage("合成失败");
            } else {
                result.setStatus(COMPOSE_STATUS_COMPOSING);
            }
        } else {
            // 仍有配音在跑
            result.setStatus(COMPOSE_STATUS_VOICING);
        }
        return result;
    }

    /**
     * 查询合成批次的配音记录。
     * 查询字段精简：进度汇总 + 卡单自愈所需字段（新增使用字段时此处必须同步补充）。
     *
     * @param batchId 合成批次号
     * @param userId  当前用户ID
     * @return 配音记录列表
     */
    private List<AidAudioRecord> listComposeBatchAudioRecords(String batchId, Long userId) {
        return aidAudioRecordMapper.selectList(
                Wrappers.<AidAudioRecord>lambdaQuery()
                        .select(AidAudioRecord::getId, AidAudioRecord::getStatus, AidAudioRecord::getErrorMessage,
                                AidAudioRecord::getTtsMediaTaskId, AidAudioRecord::getCreateTime,
                                AidAudioRecord::getUpdateTime)
                        .eq(AidAudioRecord::getComposeBatchId, batchId)
                        .eq(AidAudioRecord::getUserId, userId)
                        .eq(AidAudioRecord::getDelFlag, DEL_FLAG_NORMAL));
    }

    /**
     * 配音卡单自愈：配音记录停留 PROCESSING，但对应统一媒体任务其实已终态
     * （事件在发布前进程重启等场景导致回填丢失），或派发任务丢失从未建成媒体任务。
     * 任务成功 → 重发 OSS 持久化事件驱动 AudioRecordEventListener 回填 + ComposeAudioReadyListener 判齐（监听器幂等）；
     * 任务失败 → 直接补写配音失败终态；
     * 任务不存在 → 同批串行派发存在正常排队等待，以「派发活动锚点」（批内已建任务的最近更新时间，
     * 无任务时为记录创建时间）静默超 {@value #VOICEOVER_STUCK_MS} 毫秒判定派发丢失，补写失败终态。
     * 自愈属兜底增强，异常不阻断进度查询。
     *
     * @param audioRecords 本批配音记录
     * @param userId       当前用户ID
     * @return true=发生了自愈动作（调用方应重读记录）
     */
    private boolean selfHealStuckVoiceover(List<AidAudioRecord> audioRecords, Long userId) {
        List<AidAudioRecord> processing = new ArrayList<>();
        for (AidAudioRecord record : audioRecords) {
            if (MediaTaskStatus.PROCESSING.name().equals(record.getStatus())) {
                processing.add(record);
            }
        }
        if (CollectionUtil.isEmpty(processing)) {
            return false;
        }
        boolean healed = false;
        try {
            // 按整批记录反查媒体任务：既供逐条状态判定，也供计算派发线程最近活动锚点
            Map<Long, AidMediaTask> taskByAudioRecordId = loadAudioMediaTasks(audioRecords);
            // 派发活动锚点：批内已建任务的最大更新时间（同批串行派发，最近一次建任务/状态推进即线程活动证据）
            long lastDispatchActivity = 0L;
            for (AidMediaTask task : taskByAudioRecordId.values()) {
                Date activity = Objects.nonNull(task.getUpdateTime()) ? task.getUpdateTime() : task.getCreateTime();
                if (Objects.nonNull(activity)) {
                    lastDispatchActivity = Math.max(lastDispatchActivity, activity.getTime());
                }
            }
            long now = System.currentTimeMillis();
            for (AidAudioRecord record : processing) {
                AidMediaTask mediaTask = taskByAudioRecordId.get(record.getId());
                if (Objects.nonNull(mediaTask)) {
                    if (MediaTaskStatus.SUCCEEDED.name().equals(mediaTask.getStatus())
                            && StrUtil.isNotBlank(mediaTask.getOssUrl())) {
                        // 重发事件让 AudioRecordEventListener 回填、ComposeAudioReadyListener 判齐（均幂等）
                        applicationEventPublisher.publishEvent(
                                new MediaTaskOssPersistedEvent(this, mediaTask.getId(), mediaTask.getUserId()));
                        healed = true;
                        log.info("接口1 配音卡单自愈(任务已成功重发事件), audioRecordId={}, mediaTaskId={}",
                                record.getId(), mediaTask.getId());
                    } else if (MediaTaskStatus.FAILED.name().equals(mediaTask.getStatus())) {
                        healed = writeVoiceoverFailed(record.getId()) || healed;
                        log.info("接口1 配音卡单自愈为失败终态, audioRecordId={}, mediaTaskId={}",
                                record.getId(), mediaTask.getId());
                    }
                    // 任务仍在跑：无需自愈
                    continue;
                }
                // 无媒体任务：锚点取 max(记录创建时间, 派发活动锚点)——同批串行派发时后段正常排队不误杀，
                // 锚点静默超阈值仍未建任务才判定派发丢失（进程重启/线程池丢弃），按失败收口
                Date createTime = record.getCreateTime();
                long anchor = Math.max(Objects.isNull(createTime) ? 0L : createTime.getTime(), lastDispatchActivity);
                if (anchor > 0L && now - anchor > VOICEOVER_STUCK_MS) {
                    healed = writeVoiceoverFailed(record.getId()) || healed;
                    log.error("接口1 配音派发丢失自愈为失败终态, audioRecordId={}, createTime={}, userId={}",
                            record.getId(), createTime, userId);
                }
            }
        } catch (Exception ex) {
            // 自愈属于兜底增强，异常不阻断进度查询
            log.error("接口1 配音卡单自愈异常, userId={}", userId, ex);
        }
        return healed;
    }

    /**
     * 批量反查配音记录对应的统一媒体任务（同记录多任务时保留最新一条）。
     * 查询字段精简：自愈判定仅需终态、结果与活动时间字段（新增使用字段时此处必须同步补充）。
     *
     * @param records 待反查的配音记录
     * @return 配音记录ID → 最新媒体任务
     */
    private Map<Long, AidMediaTask> loadAudioMediaTasks(List<AidAudioRecord> records) {
        List<Long> recordIds = new ArrayList<>(records.size());
        for (AidAudioRecord record : records) {
            recordIds.add(record.getId());
        }
        List<AidMediaTask> tasks = aidMediaTaskMapper.selectList(
                Wrappers.<AidMediaTask>lambdaQuery()
                        .select(AidMediaTask::getId, AidMediaTask::getStatus, AidMediaTask::getOssUrl,
                                AidMediaTask::getBizTaskId, AidMediaTask::getUserId,
                                AidMediaTask::getCreateTime, AidMediaTask::getUpdateTime)
                        .eq(AidMediaTask::getBizTaskType, ComposeConstants.BIZ_TASK_TYPE_AUDIO_RECORD)
                        .in(AidMediaTask::getBizTaskId, recordIds)
                        .orderByAsc(AidMediaTask::getId));
        Map<Long, AidMediaTask> taskByAudioRecordId = new java.util.HashMap<>();
        for (AidMediaTask task : tasks) {
            // id 升序遍历、后写覆盖 → 每条配音记录保留最新任务
            taskByAudioRecordId.put(task.getBizTaskId(), task);
        }
        return taskByAudioRecordId;
    }

    /**
     * 配音失败终态补偿回写（条件更新：仅 PROCESSING 可置，避免覆盖并发回填的终态）。
     *
     * @param audioRecordId 配音记录ID
     * @return true=本次写入生效
     */
    private boolean writeVoiceoverFailed(Long audioRecordId) {
        LambdaUpdateWrapper<AidAudioRecord> update = new LambdaUpdateWrapper<>();
        update.eq(AidAudioRecord::getId, audioRecordId);
        update.eq(AidAudioRecord::getStatus, MediaTaskStatus.PROCESSING.name());
        update.set(AidAudioRecord::getStatus, MediaTaskStatus.FAILED.name());
        update.set(AidAudioRecord::getErrorMessage, "配音失败");
        update.set(AidAudioRecord::getUpdateTime, new Date());
        return aidAudioRecordMapper.update(null, update) > 0;
    }

    /**
     * 合成未触发自愈：本批配音已全部成功但合成任务迟迟未落库。
     * 未标记已触发 → 重发最后一条成功配音的 OSS 持久化事件，驱动 ComposeAudioReadyListener 重新判齐触发（幂等）；
     * 已标记触发但任务缺失且静默超 {@value #VOICEOVER_STUCK_MS} 毫秒 → 触发链路断裂（进程崩溃），标记批次失败收口。
     * 自愈属兜底增强，异常不阻断进度查询。
     *
     * @param batchId      合成批次号
     * @param audioRecords 本批配音记录（已全部成功）
     * @param userId       当前用户ID
     */
    private void selfHealComposeNotTriggered(String batchId, List<AidAudioRecord> audioRecords, Long userId) {
        try {
            long lastUpdated = 0L;
            AidAudioRecord lastRecord = null;
            for (AidAudioRecord record : audioRecords) {
                Date anchor = Objects.nonNull(record.getUpdateTime()) ? record.getUpdateTime() : record.getCreateTime();
                if (Objects.nonNull(anchor) && anchor.getTime() >= lastUpdated) {
                    lastUpdated = anchor.getTime();
                    lastRecord = record;
                }
            }
            if (Objects.isNull(lastRecord) || System.currentTimeMillis() - lastUpdated <= VOICEOVER_STUCK_MS) {
                // 正常触发窗口内：等待事件链完成，不做自愈
                return;
            }
            if (!composeBatchStore.isTriggered(batchId)) {
                if (Objects.nonNull(lastRecord.getTtsMediaTaskId())) {
                    // 触发事件丢失：重发最后一条配音的 OSS 持久化事件驱动重新判齐（监听器幂等）
                    applicationEventPublisher.publishEvent(new MediaTaskOssPersistedEvent(
                            this, lastRecord.getTtsMediaTaskId(), userId));
                    log.info("接口1 合成未触发自愈(重发判齐事件), batchId={}, mediaTaskId={}",
                            batchId, lastRecord.getTtsMediaTaskId());
                } else {
                    // 无任务关联无法重发判齐事件（异常历史数据）：标记失败收口，避免永久 COMPOSING
                    composeBatchStore.markFailed(batchId);
                    log.error("接口1 合成未触发且无任务关联,自愈为失败, batchId={}", batchId);
                }
                return;
            }
            // 已标记触发但静默超时仍无合成任务：触发后进程崩溃等极端场景，标记失败收口避免永久 COMPOSING
            composeBatchStore.markFailed(batchId);
            log.error("接口1 合成触发链路断裂自愈为失败, batchId={}", batchId);
        } catch (Exception ex) {
            log.error("接口1 合成未触发自愈异常, batchId={}", batchId, ex);
        }
    }

    /**
     * 解析默认音色：voiceLibraryId 优先反查音色库，其次 voiceModelId+timbreCode 老入参；双空返回 null。
     *
     * @param voiceover  配音参数
     * @param voiceCache 音色库解析缓存（同批同音色只查一次）
     * @return 默认音色；未配置返回 null（此时要求每个有台词段都带逐段音色）
     */
    private ResolvedSegmentVoice resolveDefaultVoice(VoiceoverParam voiceover,
                                                     Map<Long, ResolvedSegmentVoice> voiceCache) {
        if (Objects.nonNull(voiceover.getVoiceLibraryId())) {
            return resolveVoiceLibrary(voiceover.getVoiceLibraryId(), voiceCache);
        }
        if (Objects.nonNull(voiceover.getVoiceModelId()) && StrUtil.isNotBlank(voiceover.getTimbreCode())) {
            AiModelConfigVo modelConfig = resolveVoiceModel(voiceover.getVoiceModelId());
            return new ResolvedSegmentVoice(modelConfig, voiceover.getTimbreCode(),
                    null, voiceover.getVoiceModelId());
        }
        return null;
    }

    /**
     * 解析某段实际音色：voiceLibraryIds[index] 非空用指定音色，否则回落默认音色；双空返回 null。
     *
     * @param voiceover    配音参数
     * @param index        段下标
     * @param defaultVoice 默认音色（可为 null）
     * @param voiceCache   音色库解析缓存
     * @return 该段音色；解析不出返回 null
     */
    private ResolvedSegmentVoice resolveSegmentVoice(VoiceoverParam voiceover, int index,
                                                     ResolvedSegmentVoice defaultVoice,
                                                     Map<Long, ResolvedSegmentVoice> voiceCache) {
        List<Long> voiceLibraryIds = voiceover.getVoiceLibraryIds();
        if (Objects.nonNull(voiceLibraryIds) && index < voiceLibraryIds.size()
                && Objects.nonNull(voiceLibraryIds.get(index))) {
            return resolveVoiceLibrary(voiceLibraryIds.get(index), voiceCache);
        }
        return defaultVoice;
    }

    /**
     * 反查音色库 → 段音色（带缓存）：校验音色启用未删未下架、所属模型可用。
     *
     * @param voiceLibraryId 音色库ID
     * @param voiceCache     解析缓存
     * @return 段音色
     */
    private ResolvedSegmentVoice resolveVoiceLibrary(Long voiceLibraryId,
                                                     Map<Long, ResolvedSegmentVoice> voiceCache) {
        ResolvedSegmentVoice cached = voiceCache.get(voiceLibraryId);
        if (Objects.nonNull(cached)) {
            return cached;
        }
        AidAiVoiceLibrary voice = aidAiVoiceLibraryService.getById(voiceLibraryId);
        if (Objects.isNull(voice) || !Objects.equals(DEL_FLAG_NORMAL, voice.getDelFlag())
                || !Objects.equals(VOICE_STATUS_NORMAL, voice.getStatus())) {
            log.error("接口1 音色库记录不可用, voiceLibraryId={}", voiceLibraryId);
            throw new ServiceException("音色不可用");
        }
        if (Objects.nonNull(voice.getOfflineTime())
                && voice.getOfflineTime().getTime() <= System.currentTimeMillis()) {
            log.error("接口1 音色已下架, voiceLibraryId={}, offlineTime={}", voiceLibraryId, voice.getOfflineTime());
            throw new ServiceException("音色已下架");
        }
        AiModelConfigVo modelConfig = resolveVoiceModel(voice.getModelId());
        ResolvedSegmentVoice resolved = new ResolvedSegmentVoice(
                modelConfig, voice.getVoiceCode(), voice.getId(), voice.getModelId());
        voiceCache.put(voiceLibraryId, resolved);
        return resolved;
    }

    /**
     * 构建配音业务记录（写 compose_batch_id，只存相对路径）。
     *
     * @param request        接口1 入参
     * @param genRecord      分镜视频记录
     * @param segmentVoice   该段实际音色
     * @param ttsText        配音文本
     * @param userId         用户ID
     * @param composeBatchId 合成批次号
     * @return 配音记录
     */
    private AidAudioRecord buildAudioRecord(StoryboardComposeRequest request, AidGenRecord genRecord,
                                            ResolvedSegmentVoice segmentVoice, String ttsText,
                                            Long userId, String composeBatchId) {
        AidAudioRecord record = new AidAudioRecord();
        record.setUserId(userId);
        record.setProjectId(Objects.nonNull(request.getProjectId()) ? request.getProjectId() : genRecord.getProjectId());
        record.setEpisodeId(Objects.nonNull(request.getEpisodeId()) ? request.getEpisodeId() : genRecord.getEpisodeId());
        record.setStoryboardId(genRecord.getStoryboardId());
        record.setAudioSource(AUDIO_SOURCE_AI);
        record.setTtsText(ttsText);
        record.setVoiceModelId(segmentVoice.voiceModelId);
        record.setVoiceLibraryId(segmentVoice.voiceLibraryId);
        record.setTimbreCode(segmentVoice.voiceCode);
        record.setEnableLipSync(0);
        record.setStatus(MediaTaskStatus.PROCESSING.name());
        record.setComposeBatchId(composeBatchId);
        record.setDelFlag(DEL_FLAG_NORMAL);
        record.setCreateTime(new Date());
        return record;
    }

    /**
     * 发起单条配音（异步派发，自包含）。
     *
     * @param audioRecord 已入库的配音记录
     * @param modelConfig 该段 TTS 模型配置
     * @param ttsText     配音文本
     * @param voiceCode   该段厂商音色编码
     * @param userId      用户ID
     */
    private void dispatchVoiceover(AidAudioRecord audioRecord, AiModelConfigVo modelConfig, String ttsText,
                                   String voiceCode, Long userId) {
        MediaAudioGenerateRequest mediaReq = new MediaAudioGenerateRequest();
        mediaReq.setUserId(userId);
        mediaReq.setProjectId(audioRecord.getProjectId());
        mediaReq.setEpisodeId(audioRecord.getEpisodeId());
        mediaReq.setModelName(modelConfig.getModelCode());
        mediaReq.setTtsText(ttsText);
        mediaReq.setVoiceCode(voiceCode);
        mediaReq.setBizTaskId(audioRecord.getId());
        mediaReq.setBizTaskType(ComposeConstants.BIZ_TASK_TYPE_AUDIO_RECORD);
        try {
            mediaGenerationService.generateAudio(mediaReq);
        } catch (Exception ex) {
            // 异步派发失败：仅标记本条 FAILED（不抛异常，避免误伤同批其它段）；
            // 冻结积分由 generateAudio 内部失败路径自行退回，无跨段泄漏
            log.error("接口1 配音提交失败, audioRecordId={}", audioRecord.getId(), ex);
            LambdaUpdateWrapper<AidAudioRecord> update = new LambdaUpdateWrapper<>();
            update.eq(AidAudioRecord::getId, audioRecord.getId());
            update.set(AidAudioRecord::getStatus, MediaTaskStatus.FAILED.name());
            update.set(AidAudioRecord::getErrorMessage, "配音失败");
            update.set(AidAudioRecord::getUpdateTime, new Date());
            aidAudioRecordMapper.update(null, update);
        }
    }

    /**
     * 事务提交后异步派发本批配音（每条携带各自的段级音色）。
     * 同批串行派发（单后台线程逐条执行）是刻意设计：每条配音的预冻结要抢同一用户的账户锁
     * （{@code account:lock:userId}，同用户强制串行、等待上限 10 秒），并发派发只会让 N 条同时
     * 空耗锁等待——排在后面的段等满上限直接"系统繁忙"失败，且 N 个并发 REQUIRES_NEW 事务还会
     * 争抢连接池放大持锁时长；串行后锁基本零竞争，单条冻结毫秒级完成，同时天然保护上游 TTS 提交频率。
     *
     * @param jobs   待派发配音任务
     * @param userId 用户ID
     */
    private void registerVoiceoverDispatch(List<VoiceoverDispatchJob> jobs, Long userId) {
        if (CollectionUtil.isEmpty(jobs)) {
            return;
        }
        // 提交后统一派发的动作：整批提交一个后台任务、任务内逐条串行发起；
        // 单条失败由 dispatchVoiceover 内部落 FAILED（不抛出），不影响后续段
        Runnable dispatchSequentially = () -> {
            for (VoiceoverDispatchJob job : jobs) {
                dispatchVoiceover(job.audioRecord, job.modelConfig, job.ttsText, job.voiceCode, userId);
            }
        };
        Runnable dispatchAll = () -> {
            try {
                threadPoolTaskExecutor.execute(dispatchSequentially);
            } catch (Exception rejectEx) {
                // 线程池拒绝（队列满/应用关闭）：降级当前线程同步执行，绝不吞掉本批配音
                log.warn("接口1 配音派发被线程池拒绝，降级同步执行, jobs={}", jobs.size(), rejectEx);
                dispatchSequentially.run();
            }
        };
        // 事务进行中：注册 afterCommit，提交成功后才派发；回滚则不触发
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatchAll.run();
                }
            });
        } else {
            // 兜底：无事务上下文（理论上本方法始终在 @Transactional 内调用）
            dispatchAll.run();
        }
    }

    /**
     * 组装接口2 的 ComposeCommand（前端分组 URL → 规范化前的全 URL）。
     *
     * @param request 接口2 入参
     * @param editor  剧集剪辑记录
     * @param userId  用户ID
     * @return 合成指令
     */
    private ComposeCommand buildExportCommand(EpisodeExportRequest request, AidEpisodeEditor editor, Long userId) {
        ComposeCommand command = new ComposeCommand();
        command.setUserId(userId);
        command.setProjectId(Objects.nonNull(request.getProjectId()) ? request.getProjectId() : editor.getProjectId());
        command.setEpisodeId(Objects.nonNull(request.getEpisodeId()) ? request.getEpisodeId() : editor.getEpisodeId());
        command.setResolution(request.getResolution());
        // 与接口1 同口径按配音对齐：段长取视频/配音较长者，配音超长时末条视频自动变速拉伸填满，
        // 否则配音长于画面的段会挤乱后续段（音画错位+片尾黑屏）；无配音段不受影响（段长=视频）
        command.setAlignStrategy(ComposeConstants.ALIGN_STRATEGY_AUDIO);
        command.setGlobalBgmUrl(StrUtil.isBlank(request.getGlobalBgmUrl())
                ? null : mediaUrlResolver.toFullUrl(request.getGlobalBgmUrl()));
        command.setCallbackCategory(ComposeConstants.CALLBACK_EPISODE_EDITOR);
        command.setCallbackRecordId(editor.getId());

        List<ComposeGroup> groups = new ArrayList<>();
        for (ComposeGroupDto dto : request.getGroups()) {
            ComposeGroup group = new ComposeGroup();
            group.setVideoUrls(toFullUrls(dto.getVideoUrls()));
            group.setVideoDurations(dto.getVideoDurations());
            group.setAudioUrls(toFullUrls(dto.getAudioUrls()));
            group.setAudioDurations(dto.getAudioDurations());
            // 字幕统一格式化（幂等）：兜底剥掉 [角色_形象] 等结构标记，输出「人物：说的话」
            group.setSubtitle(DialogueSubtitleFormatter.format(dto.getSubtitle()));
            group.setBgmUrl(StrUtil.isBlank(dto.getBgmUrl()) ? null : mediaUrlResolver.toFullUrl(dto.getBgmUrl()));
            groups.add(group);
        }
        command.setGroups(groups);
        return command;
    }

    /**
     * 批量 DB 相对路径 → 完整 URL。
     *
     * @param urls 原 URL 列表
     * @return 完整 URL 列表
     */
    private List<String> toFullUrls(List<String> urls) {
        if (CollectionUtil.isEmpty(urls)) {
            return urls;
        }
        List<String> result = new ArrayList<>(urls.size());
        for (String url : urls) {
            result.add(mediaUrlResolver.toFullUrl(url));
        }
        return result;
    }
}
