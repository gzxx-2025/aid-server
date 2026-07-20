package com.aid.compose.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidAiVoiceLibrary;
import com.aid.aid.domain.AidAudioRecord;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.mapper.AidAudioRecordMapper;
import com.aid.aid.mapper.AidEpisodeEditorMapper;
import com.aid.aid.mapper.AidGenRecordMapper;
import com.aid.aid.service.IAidAiVoiceLibraryService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.SecurityUtils;
import com.aid.compose.ComposeConstants;
import com.aid.compose.dto.timeline.EpisodeTimelineGetRequest;
import com.aid.compose.dto.timeline.EpisodeTimelineResult;
import com.aid.compose.dto.timeline.EpisodeTimelineSaveRequest;
import com.aid.compose.dto.timeline.TimelineBgm;
import com.aid.compose.dto.timeline.TimelineData;
import com.aid.compose.dto.timeline.TimelineSegment;
import com.aid.compose.dto.timeline.TimelineSubtitleItem;
import com.aid.compose.dto.timeline.TimelineVideoItem;
import com.aid.compose.dto.timeline.TimelineVoiceItem;
import com.aid.compose.service.EpisodeTimelineService;
import com.aid.compose.util.MaterialUrlGuard;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.project.service.IProjectContentGuardService;
import com.aid.voice.util.DialogueSubtitleFormatter;
import com.aid.voice.util.DialogueTextSanitizer;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 剧集剪辑时间轴服务实现：timeline_json 的自动初始化 / 校验 / 覆盖保存。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EpisodeTimelineServiceImpl implements EpisodeTimelineService {

    /** 时间轴结构版本 */
    private static final int TIMELINE_VERSION = 1;

    /** 默认分辨率档 */
    private static final String DEFAULT_RESOLUTION = "FHD";

    /** 合法分辨率档 */
    private static final Set<String> VALID_RESOLUTIONS = Set.of("SD", "HD", "FHD", "2K", "4K");

    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 生成成功状态（aid_gen_record.status） */
    private static final int GEN_STATUS_SUCCESS = 1;

    /** 分镜视频的生成类型（aid_gen_record.gen_type 视频类取值；compose=配音合成视频、upload_video=用户上传，均可被设为最终视频） */
    private static final List<String> VIDEO_GEN_TYPES = List.of("i2v", "multi", "edge", "upload_video", "compose");

    /** 已选中标记（aid_gen_record.is_selected） */
    private static final int SELECTED_YES = 1;

    /** 默认视频原声音量 */
    private static final int DEFAULT_VIDEO_VOLUME = 100;

    /** 默认配音音量 */
    private static final int DEFAULT_VOICE_VOLUME = 100;

    /** 默认背景音乐音量（压低避免盖配音，参考大众剪辑习惯） */
    private static final int DEFAULT_BGM_VOLUME = 30;

    /** 默认字幕字号（px） */
    private static final int DEFAULT_FONT_SIZE = 40;

    /** 默认字幕颜色 */
    private static final String DEFAULT_FONT_COLOR = "#FFFFFF";

    /** 默认字幕位置 */
    private static final String DEFAULT_SUBTITLE_POSITION = "bottom";

    /** 音量下限/上限 */
    private static final int VOLUME_MIN = 0;
    private static final int VOLUME_MAX = 100;

    /** 字号下限/上限（px） */
    private static final int FONT_SIZE_MIN = 12;
    private static final int FONT_SIZE_MAX = 120;

    /** 语速合法范围（与音色库口径一致） */
    private static final BigDecimal SPEED_MIN = new BigDecimal("0.5");
    private static final BigDecimal SPEED_MAX = new BigDecimal("2.0");

    /** 音调合法范围（与音色库口径一致） */
    private static final BigDecimal PITCH_MIN = new BigDecimal("-12");
    private static final BigDecimal PITCH_MAX = new BigDecimal("12");

    /** 单份工程最大段数 */
    private static final int MAX_SEGMENTS = 500;

    /** 单条 URL 最大长度（链接不应超过该值，超长多为误传内容） */
    private static final int MAX_URL_LENGTH = 2048;

    /** 字幕单段最大字数 */
    private static final int MAX_SUBTITLE_LENGTH = 500;

    /** 配音文本单段最大字数 */
    private static final int MAX_TTS_TEXT_LENGTH = 2000;

    /** 落库 JSON 最大字符数（保护 DB，超出视为异常工程） */
    private static final int MAX_JSON_LENGTH = 1_000_000;

    /** 剧集剪辑 Mapper（aid_episode_editor 直读，沿用 compose 模块统一做法） */
    private final AidEpisodeEditorMapper aidEpisodeEditorMapper;

    /** 抽卡记录 Mapper（分镜视频来源） */
    private final AidGenRecordMapper aidGenRecordMapper;

    /** 配音记录 Mapper（配音来源） */
    private final AidAudioRecordMapper aidAudioRecordMapper;

    /** 分镜服务（自动初始化取分镜顺序/台词/最终选中ID） */
    private final IAidStoryboardService aidStoryboardService;

    /** 音色库服务（配音音色参数快照） */
    private final IAidAiVoiceLibraryService aidAiVoiceLibraryService;

    /** 项目服务（自动建档时校验项目归属） */
    private final IAidComicProjectService aidComicProjectService;

    /** 媒体 URL 解析器：库内相对路径 ↔ 完整可播放 URL */
    private final MediaUrlResolver mediaUrlResolver;

    /** 项目内容修改守卫：项目公开期间禁止保存剪辑工程，须先关闭公开 */
    private final IProjectContentGuardService projectContentGuardService;

    @Override
    public EpisodeTimelineResult getTimeline(EpisodeTimelineGetRequest request) {
        validateLocateParams(Objects.isNull(request) ? null : request.getEpisodeEditorId(),
                Objects.isNull(request) ? null : request.getProjectId(),
                Objects.isNull(request) ? null : request.getEpisodeId());
        Long userId = SecurityUtils.getUserId();
        AidEpisodeEditor editor = resolveEditor(request.getEpisodeEditorId(),
                request.getProjectId(), request.getEpisodeId(), userId);

        TimelineData timeline = null;
        boolean rebuild = Boolean.TRUE.equals(request.getRebuild());
        String storedTimelineJson = editor.getTimelineJson();
        if (!rebuild && StrUtil.isNotBlank(editor.getTimelineJson())) {
            timeline = parseTimeline(editor.getTimelineJson());
        }
        if (Objects.isNull(timeline)) {
            // 首次进入 / 强制重建 / 旧数据解析失败：按分镜数据自动初始化并落库
            timeline = buildInitialTimeline(editor, userId);
            persistTimeline(editor, timeline, userId, false);
        } else {
            // 库内已有工程：出参前恒定化（老数据可能缺 voice/subtitle/bgm 结构），保证前端拿到的结构任何场景完全一致
            canonicalize(timeline);
            // 历史工程自愈 + 素材补齐：compose 段剥离残留配音轨；只补空视频轨/空配音轨，
            // 已有非空素材视为用户选择，绝不覆盖
            boolean backfilled = healAndBackfillTimeline(editor, userId, timeline);
            if (backfilled) {
                canonicalize(timeline);
                if (!persistBackfilledTimeline(editor, timeline, userId, storedTimelineJson)) {
                    // 与用户保存并发时条件更新会失败，重新读取最新工程再做内存补齐，避免返回或覆盖旧编辑。
                    TimelineData latest = reloadAndBackfillLatestTimeline(editor, userId);
                    if (Objects.nonNull(latest)) {
                        timeline = latest;
                    }
                }
            }
        }
        return toResult(editor, timeline);
    }

    @Override
    public EpisodeTimelineResult saveTimeline(EpisodeTimelineSaveRequest request) {
        if (Objects.isNull(request) || Objects.isNull(request.getTimeline())
                || Objects.isNull(request.getTimeline().getSegments())) {
            log.error("时间轴保存入参非法: timeline/segments 为空");
            throw new ServiceException("参数有误");
        }
        validateLocateParams(request.getEpisodeEditorId(), request.getProjectId(), request.getEpisodeId());
        Long userId = SecurityUtils.getUserId();
        AidEpisodeEditor editor = resolveEditor(request.getEpisodeEditorId(),
                request.getProjectId(), request.getEpisodeId(), userId);

        // 公开锁：项目公开期间禁止修改剪辑工程，须先关闭公开
        projectContentGuardService.assertProjectEditable(editor.getProjectId());

        TimelineData timeline = request.getTimeline();
        // 校验 + 补默认值 + URL 统一转相对路径 + 重算总时长
        normalizeForSave(timeline);
        // compose 段（声音已合进画面）剥离配音轨后再落库：杜绝双重人声工程入库
        if (stripComposeSegmentVoices(editor.getId(), timeline, loadComposeVideoRecordIds(timeline, userId))) {
            canonicalize(timeline);
        }
        // 覆盖保存；已是导出终态（成功/失败）时回置 0=工程已修改待重新导出
        persistTimeline(editor, timeline, userId, true);
        log.info("时间轴保存成功, episodeEditorId={}, segments={}", editor.getId(), timeline.getSegments().size());
        return toResult(editor, timeline);
    }

    // ==================== 定位 / 建档 ====================

    /**
     * 定位参数校验：episodeEditorId 与 projectId+episodeId 二选一。
     *
     * @param editorId  剪辑记录ID
     * @param projectId 项目ID
     * @param episodeId 剧集ID
     */
    private void validateLocateParams(Long editorId, Long projectId, Long episodeId) {
        if (Objects.isNull(editorId) && (Objects.isNull(projectId) || Objects.isNull(episodeId))) {
            log.error("时间轴定位入参非法: episodeEditorId 与 projectId+episodeId 均为空");
            throw new ServiceException("参数有误");
        }
    }

    /**
     * 定位剪辑记录：传 ID 校验归属；未传按「用户+项目+剧集」查最新记录，无则自动建档。
     * 与导出接口同口径，保证前端两条链路拿到同一条记录。
     *
     * @param editorId  剪辑记录ID（可空）
     * @param projectId 项目ID（editorId 为空时必填）
     * @param episodeId 剧集ID（editorId 为空时必填）
     * @param userId    当前用户ID
     * @return 剪辑记录（含 timelineJson 等完整展示字段）
     */
    private AidEpisodeEditor resolveEditor(Long editorId, Long projectId, Long episodeId, Long userId) {
        if (Objects.nonNull(editorId)) {
            AidEpisodeEditor editor = aidEpisodeEditorMapper.selectById(editorId);
            if (Objects.isNull(editor) || !Objects.equals(DEL_FLAG_NORMAL, editor.getDelFlag())) {
                log.error("时间轴剪辑记录缺失, episodeEditorId={}", editorId);
                throw new ServiceException("记录不存在");
            }
            if (!Objects.equals(editor.getUserId(), userId)) {
                log.error("时间轴剪辑记录越权, episodeEditorId={}, ownerId={}, userId={}",
                        editorId, editor.getUserId(), userId);
                throw new ServiceException("无权操作");
            }
            return editor;
        }
        // 项目归属校验：防止给他人项目建档
        AidComicProject project = aidComicProjectService.getById(projectId);
        if (Objects.isNull(project) || !Objects.equals(project.getUserId(), userId)) {
            log.error("时间轴项目缺失或越权, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("项目不存在");
        }
        AidEpisodeEditor existed = selectLatestEditor(projectId, episodeId, userId);
        if (Objects.nonNull(existed)) {
            return existed;
        }
        // 首次进入剪辑器自动建档
        AidEpisodeEditor created = new AidEpisodeEditor();
        created.setUserId(userId);
        created.setProjectId(projectId);
        created.setEpisodeId(episodeId);
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
            AidEpisodeEditor concurrent = selectLatestEditor(projectId, episodeId, userId);
            if (Objects.nonNull(concurrent)) {
                log.info("时间轴并发建档冲突复用已有记录, episodeEditorId={}, projectId={}, episodeId={}, userId={}",
                        concurrent.getId(), projectId, episodeId, userId);
                return concurrent;
            }
            log.error("时间轴建档唯一键冲突且复查无记录, projectId={}, episodeId={}, userId={}",
                    projectId, episodeId, userId, e);
            throw new ServiceException("剪辑记录创建失败，请重试");
        }
        log.info("时间轴自动创建剪辑记录, episodeEditorId={}, projectId={}, episodeId={}, userId={}",
                created.getId(), projectId, episodeId, userId);
        return created;
    }

    /**
     * 按「用户+项目+剧集」查最新一条剪辑记录（时间轴展示所需字段，新增出参字段时此处必须同步补充）。
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
                                AidEpisodeEditor::getTimelineJson, AidEpisodeEditor::getExportStatus,
                                AidEpisodeEditor::getExportProgress, AidEpisodeEditor::getFinalVideoUrl,
                                AidEpisodeEditor::getErrorMsg, AidEpisodeEditor::getDelFlag)
                        .eq(AidEpisodeEditor::getUserId, userId)
                        .eq(AidEpisodeEditor::getProjectId, projectId)
                        .eq(AidEpisodeEditor::getEpisodeId, episodeId)
                        .eq(AidEpisodeEditor::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByDesc(AidEpisodeEditor::getId)
                        .last("LIMIT 1"));
    }

    // ==================== 自动初始化 ====================

    /**
     * 按分镜数据构建初始时间轴：
     * 视频=最终选中的分镜视频（无则最新成功，仍无则留空）；配音=最终选中配音（含音色参数快照）；
     * 字幕=台词格式化「人物：说的话」；音量/字号给默认值；背景音乐默认为空。
     *
     * @param editor 剪辑记录
     * @param userId 当前用户ID
     * @return 初始时间轴
     */
    private TimelineData buildInitialTimeline(AidEpisodeEditor editor, Long userId) {
        // 查询字段精简：初始化只需顺序/台词/最终选中ID（新增消费字段时此处必须同步补充）
        List<AidStoryboard> storyboards = aidStoryboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getSortOrder, AidStoryboard::getDialogueText,
                                AidStoryboard::getFinalVideoId, AidStoryboard::getFinalAudioId)
                        .eq(AidStoryboard::getProjectId, editor.getProjectId())
                        .eq(AidStoryboard::getEpisodeId, editor.getEpisodeId())
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidStoryboard::getSortOrder)
                        .orderByAsc(AidStoryboard::getId));

        TimelineData timeline = new TimelineData();
        timeline.setVersion(TIMELINE_VERSION);
        timeline.setResolution(DEFAULT_RESOLUTION);
        timeline.setSegments(new ArrayList<>());
        timeline.setBgm(defaultBgm());
        if (CollectionUtil.isEmpty(storyboards)) {
            // 该剧集还没有分镜：返回空 segments 的完整结构（字段齐全，前端展示空时间轴）
            canonicalize(timeline);
            return timeline;
        }

        Set<Long> storyboardIds = new LinkedHashSet<>();
        for (AidStoryboard sb : storyboards) {
            storyboardIds.add(sb.getId());
        }
        Map<Long, AidGenRecord> videoBySb = loadVideoRecords(storyboards, storyboardIds, userId);
        Map<Long, AidAudioRecord> audioBySb = loadAudioRecords(storyboards, storyboardIds, userId);
        Map<Long, AidAiVoiceLibrary> voiceLibById = loadVoiceLibraries(audioBySb);

        double total = 0.0;
        int order = 1;
        for (AidStoryboard sb : storyboards) {
            TimelineSegment segment = new TimelineSegment();
            segment.setStoryboardId(sb.getId());
            segment.setSortOrder(order++);
            AidGenRecord videoRecord = videoBySb.get(sb.getId());
            TimelineVideoItem video = buildVideoItem(videoRecord);
            segment.setVideo(video);
            // 段视频为配音合成视频（compose，声音已合进画面）时不再自动挂配音轨，避免成片双重配音
            segment.setVoice(isComposeVideo(videoRecord)
                    ? null : buildVoiceItem(audioBySb.get(sb.getId()), voiceLibById));
            segment.setSubtitle(buildSubtitleItem(sb.getDialogueText()));
            timeline.getSegments().add(segment);
            total += Objects.isNull(video.getDurationSeconds()) ? 0.0 : video.getDurationSeconds();
        }
        timeline.setTotalDurationSeconds(total);
        // 恒定化：无配音/无字幕的段补空结构，保证任何场景出参与落库结构完全一致
        canonicalize(timeline);
        return timeline;
    }

    /**
     * 已有工程的自愈与素材补齐统一入口：
     * ① 补空视频轨；② compose 段（声音已合进画面）剥离残留配音轨；③ 补空配音轨。
     * compose 记录集合在视频补齐之后加载，保证新补入的配音合成视频段同样纳入剥离/跳过范围。
     *
     * @param editor   剪辑记录
     * @param userId   当前用户ID
     * @param timeline 已有时间轴
     * @return true=时间轴发生变化（需要写回）
     */
    private boolean healAndBackfillTimeline(AidEpisodeEditor editor, Long userId, TimelineData timeline) {
        boolean changed = backfillMissingVideos(editor, userId, timeline);
        Set<Long> composeVideoRecordIds = loadComposeVideoRecordIds(timeline, userId);
        // 非短路或（|）：剥离与补配音都必须执行
        changed = stripComposeSegmentVoices(editor.getId(), timeline, composeVideoRecordIds) | changed;
        changed = backfillMissingVoices(editor, userId, timeline, composeVideoRecordIds) | changed;
        return changed;
    }

    /**
     * 段视频为配音合成视频（compose，声音已合进画面）时剥离该段残留配音轨：
     * 历史工程可能同时挂着源配音，预览与成片都会叠两层人声。
     *
     * @param episodeEditorId        剪辑记录ID（日志用）
     * @param timeline               时间轴
     * @param composeVideoRecordIds  compose 类型的视频记录ID集合
     * @return true=至少剥离一段配音
     */
    private boolean stripComposeSegmentVoices(Long episodeEditorId, TimelineData timeline,
                                              Set<Long> composeVideoRecordIds) {
        if (Objects.isNull(timeline) || CollectionUtil.isEmpty(timeline.getSegments())
                || CollectionUtil.isEmpty(composeVideoRecordIds)) {
            return false;
        }
        int stripped = 0;
        for (TimelineSegment segment : timeline.getSegments()) {
            if (isComposeVideoSegment(segment, composeVideoRecordIds)
                    && Objects.nonNull(segment.getVoice()) && StrUtil.isNotBlank(segment.getVoice().getUrl())) {
                segment.setVoice(null);
                stripped++;
            }
        }
        if (stripped > 0) {
            log.info("时间轴配音合成视频段剥离残留配音轨, episodeEditorId={}, stripped={}", episodeEditorId, stripped);
        }
        return stripped > 0;
    }

    /**
     * 为已有时间轴补齐空视频轨。
     * 选择优先级复用初始化规则：已选配音合成视频 → 分镜主视频 → 已选普通视频 → 最新成功视频。
     * 已有非空 URL 视为用户在剪辑器中的明确选择，不参与自动替换。
     *
     * @param editor   剪辑记录
     * @param userId   当前用户ID
     * @param timeline 已有时间轴
     * @return true=至少补齐一个视频轨
     */
    private boolean backfillMissingVideos(AidEpisodeEditor editor, Long userId, TimelineData timeline) {
        if (Objects.isNull(timeline) || CollectionUtil.isEmpty(timeline.getSegments())) {
            return false;
        }
        Set<Long> missingStoryboardIds = new LinkedHashSet<>();
        for (TimelineSegment segment : timeline.getSegments()) {
            if (Objects.nonNull(segment) && Objects.nonNull(segment.getStoryboardId())
                    && (Objects.isNull(segment.getVideo()) || StrUtil.isBlank(segment.getVideo().getUrl()))) {
                missingStoryboardIds.add(segment.getStoryboardId());
            }
        }
        if (missingStoryboardIds.isEmpty()) {
            return false;
        }

        // 查询字段精简：补视频只需要分镜主键与最终主视频ID。
        List<AidStoryboard> storyboards = aidStoryboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getFinalVideoId)
                        .eq(AidStoryboard::getProjectId, editor.getProjectId())
                        .eq(AidStoryboard::getEpisodeId, editor.getEpisodeId())
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                        .in(AidStoryboard::getId, missingStoryboardIds));
        if (CollectionUtil.isEmpty(storyboards)) {
            return false;
        }

        Set<Long> validStoryboardIds = new LinkedHashSet<>();
        for (AidStoryboard storyboard : storyboards) {
            validStoryboardIds.add(storyboard.getId());
        }
        Map<Long, AidGenRecord> preferredVideos = loadVideoRecords(storyboards, validStoryboardIds, userId);
        if (preferredVideos.isEmpty()) {
            return false;
        }

        int filled = 0;
        for (TimelineSegment segment : timeline.getSegments()) {
            if (Objects.isNull(segment) || Objects.isNull(segment.getStoryboardId())
                    || (Objects.nonNull(segment.getVideo()) && StrUtil.isNotBlank(segment.getVideo().getUrl()))) {
                continue;
            }
            AidGenRecord preferred = preferredVideos.get(segment.getStoryboardId());
            if (Objects.nonNull(preferred)) {
                segment.setVideo(buildVideoItem(preferred));
                // 补入配音合成视频（声音已合进画面）时同步清空该段配音轨，避免成片双重配音
                if (isComposeVideo(preferred)) {
                    segment.setVoice(null);
                }
                filled++;
            }
        }
        if (filled > 0) {
            log.info("时间轴空视频自动补齐, episodeEditorId={}, filled={}", editor.getId(), filled);
        }
        return filled > 0;
    }

    /**
     * 批量加载各分镜的分镜视频（取用优先级）：
     * ① 被选中的配音视频（genType=compose 且 is_selected=1，配音/对口型产物，成品预览与成片合成优先用它）；
     * ② 分镜视频指针（final_video_id，配音前原视频）；③ is_selected=1 的最新记录；④ 最新成功记录；均无则留空。
     *
     * @param storyboards   分镜列表
     * @param storyboardIds 分镜ID集合
     * @param userId        当前用户ID
     * @return 分镜ID → 选用的抽卡记录
     */
    private Map<Long, AidGenRecord> loadVideoRecords(List<AidStoryboard> storyboards,
                                                     Set<Long> storyboardIds, Long userId) {
        // 查询字段精简：时间轴只需地址/时长/选中标记/类型（新增消费字段时此处必须同步补充）
        List<AidGenRecord> records = aidGenRecordMapper.selectList(
                new LambdaQueryWrapper<AidGenRecord>()
                        .select(AidGenRecord::getId, AidGenRecord::getStoryboardId, AidGenRecord::getFileUrl,
                                AidGenRecord::getVideoDuration, AidGenRecord::getIsSelected,
                                AidGenRecord::getGenType)
                        .eq(AidGenRecord::getUserId, userId)
                        .in(AidGenRecord::getStoryboardId, storyboardIds)
                        .in(AidGenRecord::getGenType, VIDEO_GEN_TYPES)
                        .eq(AidGenRecord::getStatus, GEN_STATUS_SUCCESS)
                        .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL)
                        .isNotNull(AidGenRecord::getFileUrl)
                        .orderByAsc(AidGenRecord::getId));
        Map<Long, AidGenRecord> byId = new HashMap<>();
        // 后写覆盖（id 升序遍历）：latest=最新成功；latestSelected=最新已选中；latestSelectedCompose=最新使用中配音视频
        Map<Long, AidGenRecord> latestBySb = new HashMap<>();
        Map<Long, AidGenRecord> latestSelectedBySb = new HashMap<>();
        Map<Long, AidGenRecord> latestSelectedComposeBySb = new HashMap<>();
        for (AidGenRecord record : records) {
            byId.put(record.getId(), record);
            latestBySb.put(record.getStoryboardId(), record);
            if (Objects.equals(record.getIsSelected(), SELECTED_YES)) {
                latestSelectedBySb.put(record.getStoryboardId(), record);
                if ("compose".equals(record.getGenType())) {
                    latestSelectedComposeBySb.put(record.getStoryboardId(), record);
                }
            }
        }
        Map<Long, AidGenRecord> result = new HashMap<>();
        for (AidStoryboard sb : storyboards) {
            // 配音轨使用中优先：已完成批量配音的分镜，成品预览直接用带配音的视频
            AidGenRecord chosen = latestSelectedComposeBySb.get(sb.getId());
            if (Objects.isNull(chosen) && Objects.nonNull(sb.getFinalVideoId())) {
                chosen = byId.get(sb.getFinalVideoId());
            }
            if (Objects.isNull(chosen)) {
                chosen = latestSelectedBySb.get(sb.getId());
            }
            if (Objects.isNull(chosen)) {
                chosen = latestBySb.get(sb.getId());
            }
            if (Objects.nonNull(chosen)) {
                result.put(sb.getId(), chosen);
            }
        }
        return result;
    }

    /**
     * 为已有时间轴补齐空配音轨（工程初始化早于配音生成的场景）。
     * 选择优先级复用初始化规则：最终选中配音（final_audio_id）→ 最新成功配音。
     * 已有非空 URL 的配音视为用户在剪辑器中的明确选择，不参与自动替换；
     * 段视频为配音合成视频（compose，声音已合进画面）的段不补配音，避免成片双重配音。
     *
     * @param editor                 剪辑记录
     * @param userId                 当前用户ID
     * @param timeline               已有时间轴
     * @param composeVideoRecordIds  compose 类型的视频记录ID集合（视频补齐后加载）
     * @return true=至少补齐一个配音轨
     */
    private boolean backfillMissingVoices(AidEpisodeEditor editor, Long userId, TimelineData timeline,
                                          Set<Long> composeVideoRecordIds) {
        if (Objects.isNull(timeline) || CollectionUtil.isEmpty(timeline.getSegments())) {
            return false;
        }
        Set<Long> missingStoryboardIds = new LinkedHashSet<>();
        for (TimelineSegment segment : timeline.getSegments()) {
            if (Objects.nonNull(segment) && Objects.nonNull(segment.getStoryboardId())
                    && !isComposeVideoSegment(segment, composeVideoRecordIds)
                    && (Objects.isNull(segment.getVoice()) || StrUtil.isBlank(segment.getVoice().getUrl()))) {
                missingStoryboardIds.add(segment.getStoryboardId());
            }
        }
        if (missingStoryboardIds.isEmpty()) {
            return false;
        }

        // 查询字段精简：补配音只需要分镜主键与最终配音ID。
        List<AidStoryboard> storyboards = aidStoryboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getFinalAudioId)
                        .eq(AidStoryboard::getProjectId, editor.getProjectId())
                        .eq(AidStoryboard::getEpisodeId, editor.getEpisodeId())
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                        .in(AidStoryboard::getId, missingStoryboardIds));
        if (CollectionUtil.isEmpty(storyboards)) {
            return false;
        }
        Set<Long> validStoryboardIds = new LinkedHashSet<>();
        for (AidStoryboard storyboard : storyboards) {
            validStoryboardIds.add(storyboard.getId());
        }
        Map<Long, AidAudioRecord> audioBySb = loadAudioRecords(storyboards, validStoryboardIds, userId);
        if (audioBySb.isEmpty()) {
            return false;
        }
        Map<Long, AidAiVoiceLibrary> voiceLibById = loadVoiceLibraries(audioBySb);

        int filled = 0;
        for (TimelineSegment segment : timeline.getSegments()) {
            if (Objects.isNull(segment) || Objects.isNull(segment.getStoryboardId())
                    || isComposeVideoSegment(segment, composeVideoRecordIds)
                    || (Objects.nonNull(segment.getVoice()) && StrUtil.isNotBlank(segment.getVoice().getUrl()))) {
                continue;
            }
            AidAudioRecord record = audioBySb.get(segment.getStoryboardId());
            if (Objects.nonNull(record)) {
                segment.setVoice(buildVoiceItem(record, voiceLibById));
                filled++;
            }
        }
        if (filled > 0) {
            log.info("时间轴空配音自动补齐, episodeEditorId={}, filled={}", editor.getId(), filled);
        }
        return filled > 0;
    }

    /**
     * 判断记录是否为配音合成视频（compose，声音已合进画面）。
     *
     * @param record 抽卡记录（可空）
     * @return true=配音合成视频
     */
    private boolean isComposeVideo(AidGenRecord record) {
        return Objects.nonNull(record)
                && Objects.equals(ComposeConstants.GEN_TYPE_COMPOSE, record.getGenType());
    }

    /**
     * 判断时间轴段的视频是否为配音合成视频（按段视频 genRecordId 匹配预取的 compose 记录集合）。
     *
     * @param segment                时间轴段
     * @param composeVideoRecordIds  compose 类型的视频记录ID集合
     * @return true=该段视频为配音合成视频
     */
    private boolean isComposeVideoSegment(TimelineSegment segment, Set<Long> composeVideoRecordIds) {
        return Objects.nonNull(segment) && Objects.nonNull(segment.getVideo())
                && Objects.nonNull(segment.getVideo().getGenRecordId())
                && composeVideoRecordIds.contains(segment.getVideo().getGenRecordId());
    }

    /**
     * 预取时间轴各段视频记录中 compose 类型的记录ID集合（一次批量查询，供补配音跳过判断）。
     * 查询字段精简：仅需记录主键（新增使用字段时此处必须同步补充）。
     *
     * @param timeline 时间轴
     * @param userId   当前用户ID
     * @return compose 类型的视频记录ID集合
     */
    private Set<Long> loadComposeVideoRecordIds(TimelineData timeline, Long userId) {
        Set<Long> genRecordIds = new LinkedHashSet<>();
        for (TimelineSegment segment : timeline.getSegments()) {
            if (Objects.nonNull(segment) && Objects.nonNull(segment.getVideo())
                    && Objects.nonNull(segment.getVideo().getGenRecordId())) {
                genRecordIds.add(segment.getVideo().getGenRecordId());
            }
        }
        if (genRecordIds.isEmpty()) {
            return Set.of();
        }
        List<AidGenRecord> records = aidGenRecordMapper.selectList(
                new LambdaQueryWrapper<AidGenRecord>()
                        .select(AidGenRecord::getId)
                        .in(AidGenRecord::getId, genRecordIds)
                        .eq(AidGenRecord::getUserId, userId)
                        .eq(AidGenRecord::getGenType, ComposeConstants.GEN_TYPE_COMPOSE));
        Set<Long> result = new LinkedHashSet<>();
        for (AidGenRecord record : records) {
            result.add(record.getId());
        }
        return result;
    }

    /**
     * 批量加载各分镜的配音记录：优先最终选中（final_audio_id），其次最新成功；均无则该段无配音。
     *
     * @param storyboards   分镜列表
     * @param storyboardIds 分镜ID集合
     * @param userId        当前用户ID
     * @return 分镜ID → 选用的配音记录
     */
    private Map<Long, AidAudioRecord> loadAudioRecords(List<AidStoryboard> storyboards,
                                                       Set<Long> storyboardIds, Long userId) {
        // 查询字段精简：时间轴只需地址/时长/文本/音色参数（新增消费字段时此处必须同步补充）
        List<AidAudioRecord> records = aidAudioRecordMapper.selectList(
                new LambdaQueryWrapper<AidAudioRecord>()
                        .select(AidAudioRecord::getId, AidAudioRecord::getStoryboardId, AidAudioRecord::getAudioUrl,
                                AidAudioRecord::getDurationMs, AidAudioRecord::getTtsText,
                                AidAudioRecord::getVoiceLibraryId, AidAudioRecord::getVoiceModelId,
                                AidAudioRecord::getTimbreCode)
                        .eq(AidAudioRecord::getUserId, userId)
                        .in(AidAudioRecord::getStoryboardId, storyboardIds)
                        .eq(AidAudioRecord::getStatus, MediaTaskStatus.SUCCEEDED.name())
                        .eq(AidAudioRecord::getDelFlag, DEL_FLAG_NORMAL)
                        .isNotNull(AidAudioRecord::getAudioUrl)
                        .orderByAsc(AidAudioRecord::getId));
        Map<Long, AidAudioRecord> byId = new HashMap<>();
        Map<Long, AidAudioRecord> latestBySb = new HashMap<>();
        for (AidAudioRecord record : records) {
            // 「无台词」占位配音（历史脏数据，内容为朗读"无"字）不进时间轴：该段视为无配音
            if (DialogueTextSanitizer.isNoDialoguePlaceholder(record.getTtsText())) {
                continue;
            }
            byId.put(record.getId(), record);
            latestBySb.put(record.getStoryboardId(), record);
        }
        Map<Long, AidAudioRecord> result = new HashMap<>();
        for (AidStoryboard sb : storyboards) {
            AidAudioRecord chosen = Objects.nonNull(sb.getFinalAudioId()) ? byId.get(sb.getFinalAudioId()) : null;
            if (Objects.isNull(chosen)) {
                chosen = latestBySb.get(sb.getId());
            }
            if (Objects.nonNull(chosen)) {
                result.put(sb.getId(), chosen);
            }
        }
        return result;
    }

    /**
     * 批量加载配音用到的音色库记录（音色名称 / 默认语速 / 默认音调快照）。
     *
     * @param audioBySb 分镜ID → 配音记录
     * @return 音色库ID → 音色库记录
     */
    private Map<Long, AidAiVoiceLibrary> loadVoiceLibraries(Map<Long, AidAudioRecord> audioBySb) {
        Set<Long> voiceLibraryIds = new LinkedHashSet<>();
        for (AidAudioRecord record : audioBySb.values()) {
            if (Objects.nonNull(record.getVoiceLibraryId())) {
                voiceLibraryIds.add(record.getVoiceLibraryId());
            }
        }
        Map<Long, AidAiVoiceLibrary> result = new HashMap<>();
        if (voiceLibraryIds.isEmpty()) {
            return result;
        }
        // 查询字段精简：快照只需名称/默认语速/默认音调（新增消费字段时此处必须同步补充）
        List<AidAiVoiceLibrary> voices = aidAiVoiceLibraryService.list(
                Wrappers.<AidAiVoiceLibrary>lambdaQuery()
                        .select(AidAiVoiceLibrary::getId, AidAiVoiceLibrary::getVoiceName,
                                AidAiVoiceLibrary::getDefaultSpeed, AidAiVoiceLibrary::getDefaultPitch)
                        .in(AidAiVoiceLibrary::getId, voiceLibraryIds));
        for (AidAiVoiceLibrary voice : voices) {
            result.put(voice.getId(), voice);
        }
        return result;
    }

    /**
     * 构建视频轨元素（record 为 null 时留空占位，url=null 表示该分镜暂无视频）。
     *
     * @param record 选用的抽卡记录（可空）
     * @return 视频轨元素
     */
    private TimelineVideoItem buildVideoItem(AidGenRecord record) {
        TimelineVideoItem item = new TimelineVideoItem();
        item.setVolume(DEFAULT_VIDEO_VOLUME);
        item.setMuted(false);
        if (Objects.isNull(record)) {
            item.setDurationSeconds(0.0);
            return item;
        }
        item.setGenRecordId(record.getId());
        item.setUrl(record.getFileUrl());
        item.setDurationSeconds(Objects.isNull(record.getVideoDuration())
                ? 0.0 : record.getVideoDuration().doubleValue());
        return item;
    }

    /**
     * 构建配音轨元素（record 为 null 时返回 null，表示该段无配音）。
     *
     * @param record       选用的配音记录（可空）
     * @param voiceLibById 音色库快照
     * @return 配音轨元素或 null
     */
    private TimelineVoiceItem buildVoiceItem(AidAudioRecord record, Map<Long, AidAiVoiceLibrary> voiceLibById) {
        if (Objects.isNull(record)) {
            return null;
        }
        TimelineVoiceItem item = new TimelineVoiceItem();
        item.setAudioRecordId(record.getId());
        item.setUrl(record.getAudioUrl());
        item.setDurationSeconds(Objects.isNull(record.getDurationMs())
                ? 0.0 : record.getDurationMs() / 1000.0);
        item.setVolume(DEFAULT_VOICE_VOLUME);
        item.setMuted(false);
        item.setTtsText(record.getTtsText());
        item.setVoiceLibraryId(record.getVoiceLibraryId());
        item.setVoiceModelId(record.getVoiceModelId());
        item.setTimbreCode(record.getTimbreCode());
        AidAiVoiceLibrary voice = Objects.isNull(record.getVoiceLibraryId())
                ? null : voiceLibById.get(record.getVoiceLibraryId());
        if (Objects.nonNull(voice)) {
            item.setVoiceName(voice.getVoiceName());
            item.setSpeed(voice.getDefaultSpeed());
            item.setPitch(voice.getDefaultPitch());
        }
        return item;
    }

    /**
     * 构建字幕轨元素：台词统一格式化为「人物：说的话」，无台词返回 null。
     *
     * @param dialogueText 分镜台词原文
     * @return 字幕轨元素或 null
     */
    private TimelineSubtitleItem buildSubtitleItem(String dialogueText) {
        String text = DialogueSubtitleFormatter.format(dialogueText);
        if (StrUtil.isBlank(text)) {
            return null;
        }
        TimelineSubtitleItem item = new TimelineSubtitleItem();
        item.setText(text);
        item.setFontSize(DEFAULT_FONT_SIZE);
        item.setFontColor(DEFAULT_FONT_COLOR);
        item.setPosition(DEFAULT_SUBTITLE_POSITION);
        item.setShow(true);
        return item;
    }

    /**
     * 默认背景音乐轨：无音乐 + 默认音量。
     *
     * @return 背景音乐轨
     */
    private TimelineBgm defaultBgm() {
        TimelineBgm bgm = new TimelineBgm();
        bgm.setVolume(DEFAULT_BGM_VOLUME);
        bgm.setLoop(true);
        bgm.setFade(true);
        return bgm;
    }

    // ==================== 保存校验 / 归一化 ====================

    /**
     * 保存前校验与归一化：URL 必须为链接并转相对路径、音量/字号/语速/音调收敛合法范围、
     * 文本超长拒绝、补默认值、重算总时长。
     *
     * @param timeline 时间轴工程（就地修改）
     */
    private void normalizeForSave(TimelineData timeline) {
        if (timeline.getSegments().size() > MAX_SEGMENTS) {
            log.error("时间轴保存拒绝: 段数超限, segments={}", timeline.getSegments().size());
            throw new ServiceException("分镜段数超限");
        }
        timeline.setVersion(TIMELINE_VERSION);
        String resolution = StrUtil.isBlank(timeline.getResolution())
                ? DEFAULT_RESOLUTION : timeline.getResolution().trim().toUpperCase();
        timeline.setResolution(VALID_RESOLUTIONS.contains(resolution) ? resolution : DEFAULT_RESOLUTION);

        double total = 0.0;
        for (int i = 0; i < timeline.getSegments().size(); i++) {
            TimelineSegment segment = timeline.getSegments().get(i);
            if (Objects.isNull(segment)) {
                log.error("时间轴保存拒绝: 第{}段为空", i);
                throw new ServiceException("参数有误");
            }
            // 视频轨：允许 url 留空（该分镜暂无视频），但结构必须存在
            if (Objects.isNull(segment.getVideo())) {
                segment.setVideo(new TimelineVideoItem());
            }
            TimelineVideoItem video = segment.getVideo();
            video.setUrl(normalizeUrl(video.getUrl(), "视频", i));
            video.setDurationSeconds(nonNegative(video.getDurationSeconds()));
            video.setVolume(clampVolume(video.getVolume(), DEFAULT_VIDEO_VOLUME));
            video.setMuted(Boolean.TRUE.equals(video.getMuted()));
            total += video.getDurationSeconds();

            // 配音轨：整体可空；有 url 才视为有配音
            TimelineVoiceItem voice = segment.getVoice();
            if (Objects.nonNull(voice)) {
                voice.setUrl(normalizeUrl(voice.getUrl(), "配音", i));
                voice.setDurationSeconds(nonNegative(voice.getDurationSeconds()));
                voice.setVolume(clampVolume(voice.getVolume(), DEFAULT_VOICE_VOLUME));
                voice.setMuted(Boolean.TRUE.equals(voice.getMuted()));
                voice.setSpeed(clampDecimal(voice.getSpeed(), SPEED_MIN, SPEED_MAX));
                voice.setPitch(clampDecimal(voice.getPitch(), PITCH_MIN, PITCH_MAX));
                if (StrUtil.isNotBlank(voice.getTtsText()) && voice.getTtsText().length() > MAX_TTS_TEXT_LENGTH) {
                    log.error("时间轴保存拒绝: 第{}段配音文本超长, len={}", i, voice.getTtsText().length());
                    throw new ServiceException("配音文本过长");
                }
            }

            // 字幕轨：整体可空；有文本才视为有字幕
            TimelineSubtitleItem subtitle = segment.getSubtitle();
            if (Objects.nonNull(subtitle)) {
                if (StrUtil.isNotBlank(subtitle.getText()) && subtitle.getText().length() > MAX_SUBTITLE_LENGTH) {
                    log.error("时间轴保存拒绝: 第{}段字幕超长, len={}", i, subtitle.getText().length());
                    throw new ServiceException("字幕过长");
                }
                subtitle.setFontSize(clampInt(subtitle.getFontSize(), FONT_SIZE_MIN, FONT_SIZE_MAX, DEFAULT_FONT_SIZE));
                if (StrUtil.isBlank(subtitle.getFontColor())) {
                    subtitle.setFontColor(DEFAULT_FONT_COLOR);
                }
                if (StrUtil.isBlank(subtitle.getPosition())) {
                    subtitle.setPosition(DEFAULT_SUBTITLE_POSITION);
                }
                if (Objects.isNull(subtitle.getShow())) {
                    subtitle.setShow(true);
                }
            }
        }
        timeline.setTotalDurationSeconds(total);

        // 背景音乐轨：结构缺失补默认；有 url 才视为有音乐
        if (Objects.isNull(timeline.getBgm())) {
            timeline.setBgm(defaultBgm());
        }
        TimelineBgm bgm = timeline.getBgm();
        bgm.setUrl(normalizeUrl(bgm.getUrl(), "背景音乐", -1));
        bgm.setVolume(clampVolume(bgm.getVolume(), DEFAULT_BGM_VOLUME));
        if (Objects.isNull(bgm.getLoop())) {
            bgm.setLoop(true);
        }
        if (Objects.isNull(bgm.getFade())) {
            bgm.setFade(true);
        }
        // 恒定化收口：前端未传的 voice/subtitle 结构补空对象，保证落库与出参结构任何场景完全一致
        canonicalize(timeline);
    }

    /**
     * 出参/落库结构恒定化：无论首次初始化、老工程数据、某段无配音/无字幕/无背景音乐，
     * 补齐所有层级结构与默认值，保证返回给 C 端的字段名与层级在任何场景下完全一致——
     * video/voice/subtitle/bgm 对象永不为 null（空态用 url/text=null 表达），
     * 音量/字号等配置字段永不缺失；总时长与段序号统一重算。幂等，可重复调用。
     *
     * @param timeline 时间轴工程（就地修改）
     */
    private void canonicalize(TimelineData timeline) {
        if (Objects.isNull(timeline.getVersion())) {
            timeline.setVersion(TIMELINE_VERSION);
        }
        if (StrUtil.isBlank(timeline.getResolution())) {
            timeline.setResolution(DEFAULT_RESOLUTION);
        }
        if (Objects.isNull(timeline.getSegments())) {
            timeline.setSegments(new ArrayList<>());
        }
        double total = 0.0;
        int order = 1;
        for (TimelineSegment segment : timeline.getSegments()) {
            // 段序号按数组下标重算，保证连续
            segment.setSortOrder(order++);
            // 视频轨：恒有对象，无视频时 url=null、时长 0、音量默认
            if (Objects.isNull(segment.getVideo())) {
                segment.setVideo(new TimelineVideoItem());
            }
            TimelineVideoItem video = segment.getVideo();
            video.setDurationSeconds(nonNegative(video.getDurationSeconds()));
            video.setVolume(clampVolume(video.getVolume(), DEFAULT_VIDEO_VOLUME));
            video.setMuted(Boolean.TRUE.equals(video.getMuted()));
            total += video.getDurationSeconds();
            // 配音轨：恒有对象，无配音时 url 与音色字段=null、时长 0、音量默认
            if (Objects.isNull(segment.getVoice())) {
                segment.setVoice(new TimelineVoiceItem());
            }
            TimelineVoiceItem voice = segment.getVoice();
            voice.setDurationSeconds(nonNegative(voice.getDurationSeconds()));
            voice.setVolume(clampVolume(voice.getVolume(), DEFAULT_VOICE_VOLUME));
            voice.setMuted(Boolean.TRUE.equals(voice.getMuted()));
            // 字幕轨：恒有对象，无字幕时 text=null、样式字段仍给默认值
            if (Objects.isNull(segment.getSubtitle())) {
                segment.setSubtitle(new TimelineSubtitleItem());
            }
            TimelineSubtitleItem subtitle = segment.getSubtitle();
            subtitle.setFontSize(clampInt(subtitle.getFontSize(), FONT_SIZE_MIN, FONT_SIZE_MAX, DEFAULT_FONT_SIZE));
            if (StrUtil.isBlank(subtitle.getFontColor())) {
                subtitle.setFontColor(DEFAULT_FONT_COLOR);
            }
            if (StrUtil.isBlank(subtitle.getPosition())) {
                subtitle.setPosition(DEFAULT_SUBTITLE_POSITION);
            }
            if (Objects.isNull(subtitle.getShow())) {
                subtitle.setShow(true);
            }
        }
        timeline.setTotalDurationSeconds(total);
        // 背景音乐轨：恒有对象，无音乐时 url/name=null、音量默认
        if (Objects.isNull(timeline.getBgm())) {
            timeline.setBgm(defaultBgm());
        }
        TimelineBgm bgm = timeline.getBgm();
        bgm.setVolume(clampVolume(bgm.getVolume(), DEFAULT_BGM_VOLUME));
        if (Objects.isNull(bgm.getLoop())) {
            bgm.setLoop(true);
        }
        if (Objects.isNull(bgm.getFade())) {
            bgm.setFade(true);
        }
    }

    /**
     * URL 归一化：必须是可访问链接（拒绝 Base64/data URI/超长内容/blob 本地地址/嵌套协议头），
     * 本站地址统一转相对路径落库。
     *
     * @param url   原始 URL（可空）
     * @param label 轨道名（日志用）
     * @param index 段下标（日志用，-1=全片级）
     * @return 归一化后的 URL（相对路径或外链原样）；空返回 null
     */
    private String normalizeUrl(String url, String label, int index) {
        if (StrUtil.isBlank(url)) {
            return null;
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase();
        // 数据库存储规范：禁止文件内容落库，资源必须先传 OSS 再存链接
        if (lower.startsWith("data:") || lower.contains(";base64,") || trimmed.length() > MAX_URL_LENGTH) {
            log.error("时间轴保存拒绝: {}轨第{}项非链接资源(疑似Base64/超长), len={}", label, index, trimmed.length());
            throw new ServiceException("素材需为链接");
        }
        // 浏览器本地临时地址（blob:）/嵌套协议头（如 https://cdn/blob:http://…）：
        // 文件从未上传对象存储，落库后导出必失败，保存阶段即拒绝并提示先上传素材
        if (MaterialUrlGuard.isForbidden(trimmed)) {
            log.error("时间轴保存拒绝: {}轨第{}项为本地临时地址或非法结构, url={}", label, index, trimmed);
            throw new ServiceException("素材需先上传");
        }
        return mediaUrlResolver.toRelativePath(trimmed);
    }

    // ==================== 落库 / 出参 ====================

    /**
     * 条件写回自动补齐后的时间轴。仅当数据库仍是本次读取的旧 JSON 时更新，防止覆盖并发保存。
     * 自动补齐不改变用户剪辑配置，也不重置成片导出状态。
     *
     * @param editor              剪辑记录
     * @param timeline            补齐后的时间轴
     * @param userId              当前用户ID
     * @param expectedTimelineJson 本次读取到的旧时间轴JSON
     * @return true=写回成功，false=期间已有其他请求更新工程
     */
    private boolean persistBackfilledTimeline(AidEpisodeEditor editor, TimelineData timeline, Long userId,
                                              String expectedTimelineJson) {
        String json = serializeTimeline(editor.getId(), timeline);
        LambdaUpdateWrapper<AidEpisodeEditor> update = new LambdaUpdateWrapper<>();
        update.eq(AidEpisodeEditor::getId, editor.getId());
        update.eq(AidEpisodeEditor::getTimelineJson, expectedTimelineJson);
        update.set(AidEpisodeEditor::getTimelineJson, json);
        update.set(AidEpisodeEditor::getUpdateTime, new Date());
        update.set(AidEpisodeEditor::getUpdateBy, String.valueOf(userId));
        int affected = aidEpisodeEditorMapper.update(null, update);
        if (affected <= 0) {
            log.info("时间轴自动补齐写回跳过，并发期间工程已更新: episodeEditorId={}", editor.getId());
            return false;
        }
        editor.setTimelineJson(json);
        return true;
    }

    /**
     * 自动补齐条件写回冲突后重新读取最新工程，并仅在内存中补齐空视频/空配音，确保本次响应不返回旧编辑。
     */
    private TimelineData reloadAndBackfillLatestTimeline(AidEpisodeEditor editor, Long userId) {
        AidEpisodeEditor latest = aidEpisodeEditorMapper.selectOne(
                Wrappers.<AidEpisodeEditor>lambdaQuery()
                        .select(AidEpisodeEditor::getId, AidEpisodeEditor::getTimelineJson)
                        .eq(AidEpisodeEditor::getId, editor.getId())
                        .eq(AidEpisodeEditor::getUserId, userId)
                        .eq(AidEpisodeEditor::getDelFlag, DEL_FLAG_NORMAL)
                        .last("LIMIT 1"));
        if (Objects.isNull(latest) || StrUtil.isBlank(latest.getTimelineJson())) {
            return null;
        }
        TimelineData timeline = parseTimeline(latest.getTimelineJson());
        if (Objects.isNull(timeline)) {
            return null;
        }
        canonicalize(timeline);
        // 与读主链路同口径：compose 段剥离残留配音轨 + 补空视频/空配音
        if (healAndBackfillTimeline(editor, userId, timeline)) {
            canonicalize(timeline);
        }
        return timeline;
    }

    /**
     * 覆盖保存 timeline_json。
     *
     * @param editor            剪辑记录
     * @param timeline          时间轴工程（URL 已为相对路径）
     * @param userId            当前用户ID
     * @param resetExportStatus true=保存工程时，导出终态（成功/失败）回置 0（工程已修改待重新导出）
     */
    private void persistTimeline(AidEpisodeEditor editor, TimelineData timeline, Long userId,
                                 boolean resetExportStatus) {
        String json = serializeTimeline(editor.getId(), timeline);
        LambdaUpdateWrapper<AidEpisodeEditor> update = new LambdaUpdateWrapper<>();
        update.eq(AidEpisodeEditor::getId, editor.getId());
        update.set(AidEpisodeEditor::getTimelineJson, json);
        boolean resetStatus = resetExportStatus
                && (Objects.equals(editor.getExportStatus(), ComposeConstants.EXPORT_STATUS_SUCCESS)
                || Objects.equals(editor.getExportStatus(), ComposeConstants.EXPORT_STATUS_FAILED));
        if (resetStatus) {
            // 工程已修改：成片过期，状态回置 0 待重新导出（保留历史成片地址供回看）
            update.set(AidEpisodeEditor::getExportStatus, 0);
            editor.setExportStatus(0);
        }
        update.set(AidEpisodeEditor::getUpdateTime, new Date());
        update.set(AidEpisodeEditor::getUpdateBy, String.valueOf(userId));
        aidEpisodeEditorMapper.update(null, update);
        editor.setTimelineJson(json);
    }

    /** 时间轴序列化与长度保护统一收口。 */
    private String serializeTimeline(Long editorId, TimelineData timeline) {
        String json = JSON.toJSONString(timeline);
        if (json.length() > MAX_JSON_LENGTH) {
            log.error("时间轴保存拒绝: 工程JSON超长, episodeEditorId={}, len={}", editorId, json.length());
            throw new ServiceException("工程数据过大");
        }
        return json;
    }

    /**
     * 解析库内 timeline_json（解析失败返回 null，交由调用方重建，不阻断用户）。
     *
     * @param json 库内 JSON
     * @return 时间轴工程或 null
     */
    private TimelineData parseTimeline(String json) {
        try {
            TimelineData timeline = JSON.parseObject(json, TimelineData.class);
            // 结构完整性兜底：老数据缺 segments 视为需要重建
            if (Objects.isNull(timeline) || Objects.isNull(timeline.getSegments())) {
                return null;
            }
            return timeline;
        } catch (Exception ex) {
            log.warn("时间轴JSON解析失败,将按分镜数据重建, err={}", ex.getMessage());
            return null;
        }
    }

    /**
     * 组装出参：剪辑记录元信息 + 时间轴（video/voice/bgm 的 url 就地转完整域名）。
     *
     * @param editor   剪辑记录
     * @param timeline 时间轴工程（相对路径）
     * @return 出参
     */
    private EpisodeTimelineResult toResult(AidEpisodeEditor editor, TimelineData timeline) {
        // 出参 URL 转完整地址（timeline 已完成落库，此处就地修改不影响库内数据）
        if (CollectionUtil.isNotEmpty(timeline.getSegments())) {
            for (TimelineSegment segment : timeline.getSegments()) {
                if (Objects.nonNull(segment.getVideo()) && StrUtil.isNotBlank(segment.getVideo().getUrl())) {
                    segment.getVideo().setUrl(mediaUrlResolver.toFullUrl(segment.getVideo().getUrl()));
                }
                if (Objects.nonNull(segment.getVoice()) && StrUtil.isNotBlank(segment.getVoice().getUrl())) {
                    segment.getVoice().setUrl(mediaUrlResolver.toFullUrl(segment.getVoice().getUrl()));
                }
            }
        }
        if (Objects.nonNull(timeline.getBgm()) && StrUtil.isNotBlank(timeline.getBgm().getUrl())) {
            timeline.getBgm().setUrl(mediaUrlResolver.toFullUrl(timeline.getBgm().getUrl()));
        }

        EpisodeTimelineResult result = new EpisodeTimelineResult();
        result.setEpisodeEditorId(editor.getId());
        result.setProjectId(editor.getProjectId());
        result.setEpisodeId(editor.getEpisodeId());
        result.setExportStatus(editor.getExportStatus());
        result.setExportProgress(editor.getExportProgress());
        result.setFinalVideoUrl(editor.getFinalVideoUrl());
        result.setErrorMsg(editor.getErrorMsg());
        result.setTimeline(timeline);
        return result;
    }

    // ==================== 小工具 ====================

    /**
     * 音量收敛到 [0,100]，空取默认值。
     *
     * @param volume       入参音量
     * @param defaultValue 默认值
     * @return 合法音量
     */
    private Integer clampVolume(Integer volume, int defaultValue) {
        return clampInt(volume, VOLUME_MIN, VOLUME_MAX, defaultValue);
    }

    /**
     * 整数收敛到 [min,max]，空取默认值。
     *
     * @param value        入参值
     * @param min          下限
     * @param max          上限
     * @param defaultValue 默认值
     * @return 合法值
     */
    private Integer clampInt(Integer value, int min, int max, int defaultValue) {
        if (Objects.isNull(value)) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 小数收敛到 [min,max]，空保持空（预留参数不强制）。
     *
     * @param value 入参值
     * @param min   下限
     * @param max   上限
     * @return 合法值或 null
     */
    private BigDecimal clampDecimal(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (Objects.isNull(value)) {
            return null;
        }
        return value.max(min).min(max);
    }

    /**
     * 时长空/负数归零。
     *
     * @param seconds 入参秒数
     * @return 非负秒数
     */
    private double nonNegative(Double seconds) {
        return Objects.isNull(seconds) || seconds < 0 ? 0.0 : seconds;
    }
}
