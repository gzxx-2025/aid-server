package com.aid.compose.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.common.exception.ServiceException;
import com.aid.compose.ComposeConstants;
import com.aid.compose.dto.ComposeGroupDto;
import com.aid.compose.dto.EpisodeExportResult;
import com.aid.compose.dto.StoryboardComposeRequest;
import com.aid.media.enums.MediaTaskStatus;

class VideoComposeServiceImplValidationTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        assistant.setCurrentNamespace(VideoComposeServiceImplValidationTest.class.getName());
        TableInfoHelper.initTableInfo(assistant, AidMediaTask.class);
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
