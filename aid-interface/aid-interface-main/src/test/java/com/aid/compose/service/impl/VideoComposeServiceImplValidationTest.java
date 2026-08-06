package com.aid.compose.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.exception.ServiceException;
import com.aid.compose.ComposeConstants;
import com.aid.compose.domain.TimedSubtitleCue;
import com.aid.compose.dto.ComposeGroupDto;
import com.aid.compose.dto.EpisodeExportResult;
import com.aid.compose.dto.StoryboardComposeRequest;
import com.aid.compose.dto.timeline.TimelineData;
import com.aid.compose.dto.timeline.TimelineSegment;
import com.aid.compose.dto.timeline.TimelineSubtitleItem;
import com.aid.media.enums.MediaTaskStatus;

class VideoComposeServiceImplValidationTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        assistant.setCurrentNamespace(VideoComposeServiceImplValidationTest.class.getName());
        TableInfoHelper.initTableInfo(assistant, AidMediaTask.class);
        TableInfoHelper.initTableInfo(assistant, AidStoryboard.class);
    }

    @Test
    void acceptsRequestMatchingStoryboardScope() {
        VideoComposeServiceImpl service = mock(VideoComposeServiceImpl.class, CALLS_REAL_METHODS);
        StoryboardComposeRequest request = request(10L, 20L);

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "resolveVoiceoverScope",
                request, List.of(1L), List.of(record(10L, 20L))));
    }

    @Test
    void rejectsRequestProjectOverride() {
        VideoComposeServiceImpl service = mock(VideoComposeServiceImpl.class, CALLS_REAL_METHODS);
        StoryboardComposeRequest request = request(99L, 20L);

        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "resolveVoiceoverScope", request, List.of(1L), List.of(record(10L, 20L))));
    }

    @Test
    void rejectsMixedEpisodeStoryboards() {
        VideoComposeServiceImpl service = mock(VideoComposeServiceImpl.class, CALLS_REAL_METHODS);
        StoryboardComposeRequest request = request(null, null);

        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "resolveVoiceoverScope", request, List.of(1L, 2L),
                List.of(record(10L, 20L), record(10L, 21L))));
    }

    @Test
    void returnsExistingMediaTaskForIdempotentExport() {
        VideoComposeServiceImpl service = mock(VideoComposeServiceImpl.class, CALLS_REAL_METHODS);
        AidMediaTaskMapper mediaTaskMapper = mock(AidMediaTaskMapper.class);
        ReflectionTestUtils.setField(service, "aidMediaTaskMapper", mediaTaskMapper);
        AidMediaTask mediaTask = new AidMediaTask();
        mediaTask.setId(9001L);
        mediaTask.setStatus(MediaTaskStatus.WAIT_POLL.name());
        when(mediaTaskMapper.selectOne(any())).thenReturn(mediaTask);

        EpisodeExportResult result = ReflectionTestUtils.invokeMethod(service,
                "resolveInFlightExport", composingEditor("9001", new Date()));

        assertEquals(1001L, result.getEpisodeEditorId());
        assertEquals("9001", result.getExportTaskId());
        assertEquals(ComposeConstants.EXPORT_STATUS_COMPOSING, result.getExportStatus());
        assertFalse(result.getReused());
    }

    @Test
    void returnsAcceptingStateWithoutLeakingInternalRunToken() {
        VideoComposeServiceImpl service = mock(VideoComposeServiceImpl.class, CALLS_REAL_METHODS);

        EpisodeExportResult result = ReflectionTestUtils.invokeMethod(service,
                "resolveInFlightExport", composingEditor("ACCEPT_internal", new Date()));

        assertEquals(1001L, result.getEpisodeEditorId());
        assertNull(result.getExportTaskId());
        assertEquals(ComposeConstants.EXPORT_STATUS_COMPOSING, result.getExportStatus());
        assertFalse(result.getReused());
    }

    @Test
    void keepsTerminalTaskIdempotentUntilEditorStateIsReconciled() {
        VideoComposeServiceImpl service = mock(VideoComposeServiceImpl.class, CALLS_REAL_METHODS);
        AidMediaTaskMapper mediaTaskMapper = mock(AidMediaTaskMapper.class);
        ReflectionTestUtils.setField(service, "aidMediaTaskMapper", mediaTaskMapper);
        AidMediaTask mediaTask = new AidMediaTask();
        mediaTask.setId(9002L);
        mediaTask.setStatus(MediaTaskStatus.SUCCEEDED.name());
        when(mediaTaskMapper.selectOne(any())).thenReturn(mediaTask);

        EpisodeExportResult result = ReflectionTestUtils.invokeMethod(service,
                "resolveInFlightExport", composingEditor("9002", new Date()));

        assertEquals("9002", result.getExportTaskId());
        assertEquals(ComposeConstants.EXPORT_STATUS_COMPOSING, result.getExportStatus());
    }

    @Test
    void releasesStaleAcceptingStateForNewExport() {
        VideoComposeServiceImpl service = mock(VideoComposeServiceImpl.class, CALLS_REAL_METHODS);
        Date staleUpdateTime = new Date(System.currentTimeMillis() - 11L * 60L * 1000L);

        EpisodeExportResult result = ReflectionTestUtils.invokeMethod(service,
                "resolveInFlightExport", composingEditor("ACCEPT_stale", staleUpdateTime));

        assertNull(result);
    }

    @Test
    void releasesMissingMediaTaskForNewExport() {
        VideoComposeServiceImpl service = mock(VideoComposeServiceImpl.class, CALLS_REAL_METHODS);
        AidMediaTaskMapper mediaTaskMapper = mock(AidMediaTaskMapper.class);
        ReflectionTestUtils.setField(service, "aidMediaTaskMapper", mediaTaskMapper);
        when(mediaTaskMapper.selectOne(any())).thenReturn(null);

        EpisodeExportResult result = ReflectionTestUtils.invokeMethod(service,
                "resolveInFlightExport", composingEditor("9003", new Date()));

        assertNull(result);
    }

    @Test
    void rejectsExportGroupWithoutStoryboardId() {
        VideoComposeServiceImpl service = mock(VideoComposeServiceImpl.class, CALLS_REAL_METHODS);
        ComposeGroupDto group = exportGroup(null);

        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "validateExportGroups", List.of(group)));
    }

    @Test
    void rejectsLegacyExportTimelineShape() {
        VideoComposeServiceImpl service = mock(VideoComposeServiceImpl.class, CALLS_REAL_METHODS);
        ComposeGroupDto group = exportGroup(101L);

        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "validateRequestTimeline", "{\"videoClips\":[]}", List.of(group)));
    }

    @Test
    void acceptsCanonicalExportTimelineShape() {
        VideoComposeServiceImpl service = mock(VideoComposeServiceImpl.class, CALLS_REAL_METHODS);
        ComposeGroupDto group = exportGroup(101L);
        String timelineJson = "{\"version\":1,\"resolution\":\"FHD\",\"totalDurationSeconds\":10," +
                "\"segments\":[{\"storyboardId\":101,\"sortOrder\":1,\"video\":{}," +
                "\"voice\":{},\"subtitle\":{}}],\"bgm\":{},\"extraJson\":null}";

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                service, "validateRequestTimeline", timelineJson, List.of(group)));
    }

    @Test
    void rejectsCanonicalTimelineWithInvalidResolution() {
        VideoComposeServiceImpl service = mock(VideoComposeServiceImpl.class, CALLS_REAL_METHODS);
        ComposeGroupDto group = exportGroup(101L);
        String timelineJson = "{\"version\":1,\"resolution\":\"8K\",\"totalDurationSeconds\":10," +
                "\"segments\":[{\"storyboardId\":101,\"sortOrder\":1,\"video\":{}," +
                "\"voice\":{},\"subtitle\":{}}],\"bgm\":{},\"extraJson\":null}";

        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "validateRequestTimeline", timelineJson, List.of(group)));
    }

    @Test
    void backfillsWholeBatchFromStoryboardWhenClientBatchIsEmpty() {
        VideoComposeServiceImpl service = mock(VideoComposeServiceImpl.class, CALLS_REAL_METHODS);
        IAidStoryboardService storyboardService = mock(IAidStoryboardService.class);
        ReflectionTestUtils.setField(service, "aidStoryboardService", storyboardService);
        AidStoryboard firstStoryboard = storyboard(101L, "科普医师：第一句。\n张叔: 第二句！");
        AidStoryboard secondStoryboard = storyboard(102L, "旁白：第三句。");
        when(storyboardService.list(org.mockito.ArgumentMatchers.<Wrapper<AidStoryboard>>any()))
                .thenReturn(List.of(firstStoryboard, secondStoryboard));
        ComposeGroupDto firstGroup = exportGroup(101L);
        ComposeGroupDto secondGroup = exportGroup(102L);
        TimelineSegment firstSegment = timelineSegment(true);
        TimelineSegment secondSegment = timelineSegment(true);
        TimelineData timeline = timeline(firstSegment, secondSegment);

        Integer count = ReflectionTestUtils.invokeMethod(service,
                "backfillStoryboardSubtitlesWhenClientBatchEmpty",
                List.of(firstGroup, secondGroup), List.of(firstSegment, secondSegment), timeline,
                composingEditor("9004", new Date()), 2001L, false);

        assertEquals(2, count);
        assertEquals("科普医师：第一句\n张叔：第二句", firstGroup.getSubtitle());
        assertEquals("旁白：第三句", secondGroup.getSubtitle());
        assertEquals(firstGroup.getSubtitle(), firstSegment.getSubtitle().getText());
        assertEquals(secondGroup.getSubtitle(), secondSegment.getSubtitle().getText());
    }

    @Test
    void doesNotUseBackendWhenAnyClientGroupHasSubtitle() {
        VideoComposeServiceImpl service = mock(VideoComposeServiceImpl.class, CALLS_REAL_METHODS);
        IAidStoryboardService storyboardService = mock(IAidStoryboardService.class);
        ReflectionTestUtils.setField(service, "aidStoryboardService", storyboardService);
        ComposeGroupDto firstGroup = exportGroup(101L);
        firstGroup.setSubtitle("前端角色：保留内容");
        ComposeGroupDto secondGroup = exportGroup(102L);
        TimelineData timeline = timeline(timelineSegment(true), timelineSegment(true));

        Integer count = ReflectionTestUtils.invokeMethod(service,
                "backfillStoryboardSubtitlesWhenClientBatchEmpty",
                List.of(firstGroup, secondGroup), timeline.getSegments(), timeline,
                composingEditor("9005", new Date()), 2001L, false);

        assertEquals(0, count);
        assertEquals("前端角色：保留内容", firstGroup.getSubtitle());
        assertNull(secondGroup.getSubtitle());
        verifyNoInteractions(storyboardService);
    }

    @Test
    void doesNotUseBackendWhenAnyTimelineSegmentHasSubtitle() {
        VideoComposeServiceImpl service = mock(VideoComposeServiceImpl.class, CALLS_REAL_METHODS);
        IAidStoryboardService storyboardService = mock(IAidStoryboardService.class);
        ReflectionTestUtils.setField(service, "aidStoryboardService", storyboardService);
        ComposeGroupDto firstGroup = exportGroup(101L);
        ComposeGroupDto secondGroup = exportGroup(102L);
        TimelineSegment firstSegment = timelineSegment(true);
        firstSegment.getSubtitle().setText("时间线角色：已有内容");
        TimelineSegment secondSegment = timelineSegment(true);
        TimelineData timeline = timeline(firstSegment, secondSegment);

        Integer count = ReflectionTestUtils.invokeMethod(service,
                "backfillStoryboardSubtitlesWhenClientBatchEmpty",
                List.of(firstGroup, secondGroup), List.of(firstSegment, secondSegment), timeline,
                composingEditor("9006", new Date()), 2001L, false);

        assertEquals(0, count);
        assertNull(firstGroup.getSubtitle());
        assertNull(secondGroup.getSubtitle());
        verifyNoInteractions(storyboardService);
    }

    @Test
    void doesNotFallbackAfterFrontendSubtitleWasClearedByShowFalse() {
        VideoComposeServiceImpl service = mock(VideoComposeServiceImpl.class, CALLS_REAL_METHODS);
        IAidStoryboardService storyboardService = mock(IAidStoryboardService.class);
        ReflectionTestUtils.setField(service, "aidStoryboardService", storyboardService);
        ComposeGroupDto hiddenGroup = exportGroup(101L);
        hiddenGroup.setSubtitle("前端角色：已传但关闭");
        ComposeGroupDto visibleGroup = exportGroup(102L);
        TimelineSegment hiddenSegment = timelineSegment(false);
        TimelineSegment visibleSegment = timelineSegment(true);
        TimelineData timeline = timeline(hiddenSegment, visibleSegment);
        Boolean clientGroupBatchHasSubtitle = ReflectionTestUtils.invokeMethod(
                service, "hasAnyGroupSubtitle", List.of(hiddenGroup, visibleGroup));
        hiddenGroup.setSubtitle(null);

        Integer count = ReflectionTestUtils.invokeMethod(service,
                "backfillStoryboardSubtitlesWhenClientBatchEmpty",
                List.of(hiddenGroup, visibleGroup), List.of(hiddenSegment, visibleSegment), timeline,
                composingEditor("9007", new Date()), 2001L, clientGroupBatchHasSubtitle);

        assertEquals(0, count);
        assertNull(hiddenGroup.getSubtitle());
        assertNull(visibleGroup.getSubtitle());
        verifyNoInteractions(storyboardService);
    }

    @Test
    void rematchesExistingAsrCuesToTwoFrontendSpeakers() {
        VideoComposeServiceImpl service = mock(VideoComposeServiceImpl.class, CALLS_REAL_METHODS);
        ComposeGroupDto group = exportGroup(101L);
        group.setSubtitle("张叔担忧地说：\"有没有对症的治疗药物?\" [镜头2]\n"
                + "科普医师：目前需要进一步检查");
        group.setSubtitleCues(List.of(
                asrCue(0.2D, 1.8D, "有没有对症的治疗药物", "科普医师"),
                asrCue(1.9D, 3.8D, "目前需要进一步检查", "科普医师")));
        group.setSubtitleSourceMediaFingerprint("VIDEO:101");
        TimelineSegment segment = timelineSegment(true);

        Integer count = ReflectionTestUtils.invokeMethod(service,
                "rematchExistingAsrCueSpeakers", List.of(group), List.of(segment));

        assertEquals(1, count);
        assertEquals("张叔", group.getSubtitleCues().get(0).getSpeaker());
        assertEquals("科普医师", group.getSubtitleCues().get(1).getSpeaker());
        assertEquals(group.getSubtitleCues(), segment.getSubtitle().getCues());
        assertEquals("VIDEO:101", segment.getSubtitle().getSourceMediaFingerprint());
        assertEquals("张叔：有没有对症的治疗药物\n科普医师：目前需要进一步检查",
                segment.getSubtitle().getText());
    }

    private StoryboardComposeRequest request(Long projectId, Long episodeId) {
        StoryboardComposeRequest request = new StoryboardComposeRequest();
        request.setProjectId(projectId);
        request.setEpisodeId(episodeId);
        return request;
    }

    private ComposeGroupDto exportGroup(Long storyboardId) {
        ComposeGroupDto group = new ComposeGroupDto();
        group.setStoryboardId(storyboardId);
        group.setVideoUrls(List.of("/video/test.mp4"));
        group.setVideoDurations(List.of(10D));
        return group;
    }

    private AidGenRecord record(Long projectId, Long episodeId) {
        AidGenRecord record = new AidGenRecord();
        record.setId(100L);
        record.setProjectId(projectId);
        record.setEpisodeId(episodeId);
        return record;
    }

    private AidStoryboard storyboard(Long id, String dialogueText) {
        AidStoryboard storyboard = new AidStoryboard();
        storyboard.setId(id);
        storyboard.setDialogueText(dialogueText);
        return storyboard;
    }

    private TimedSubtitleCue asrCue(double start, double end, String text, String speaker) {
        TimedSubtitleCue cue = new TimedSubtitleCue();
        cue.setStartSeconds(start);
        cue.setEndSeconds(end);
        cue.setText(text);
        cue.setSpeaker(speaker);
        cue.setSource("ASR");
        return cue;
    }

    private TimelineSegment timelineSegment(boolean showSubtitle) {
        TimelineSegment segment = new TimelineSegment();
        TimelineSubtitleItem subtitle = new TimelineSubtitleItem();
        subtitle.setShow(showSubtitle);
        segment.setSubtitle(subtitle);
        return segment;
    }

    private TimelineData timeline(TimelineSegment... segments) {
        TimelineData timeline = new TimelineData();
        timeline.setSegments(List.of(segments));
        return timeline;
    }

    private AidEpisodeEditor composingEditor(String exportTaskId, Date updateTime) {
        AidEpisodeEditor editor = new AidEpisodeEditor();
        editor.setId(1001L);
        editor.setUserId(2001L);
        editor.setProjectId(3001L);
        editor.setEpisodeId(4001L);
        editor.setExportStatus(ComposeConstants.EXPORT_STATUS_COMPOSING);
        editor.setExportTaskId(exportTaskId);
        editor.setUpdateTime(updateTime);
        return editor;
    }
}
