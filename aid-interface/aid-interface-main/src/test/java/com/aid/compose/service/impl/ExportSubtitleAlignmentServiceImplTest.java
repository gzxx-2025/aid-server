package com.aid.compose.service.impl;

import com.alibaba.fastjson2.JSON;
import com.aid.aid.domain.AidGenRecord;
import com.aid.common.exception.ServiceException;
import com.aid.compose.domain.TimedSubtitleCue;
import com.aid.compose.dto.ComposeGroupDto;
import com.aid.compose.dto.timeline.TimelineSegment;
import com.aid.compose.dto.timeline.TimelineSubtitleItem;
import com.aid.compose.enums.SubtitleRecognitionStatus;
import com.aid.compose.util.TimelineMediaFingerprint;
import com.aid.media.dto.SpeechRecognitionResult;
import com.aid.media.provider.SpeechRecognitionClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExportSubtitleAlignmentServiceImplTest {

    @Test
    void shouldAlignDialogueAndWriteTimelineAtomically() {
        AtomicInteger calls = new AtomicInteger();
        ExportSubtitleAlignmentServiceImpl service = new ExportSubtitleAlignmentServiceImpl(
                List.of(successClient(calls)));
        ComposeGroupDto group = group("甲：你好，世界。");
        AidGenRecord selected = new AidGenRecord();
        selected.setId(99L);
        selected.setFileUrl("/video/current.mp4");
        TimelineSegment segment = new TimelineSegment();
        segment.setStoryboardId(3L);
        segment.setSubtitle(new TimelineSubtitleItem());

        service.align(List.of(group), List.of(segment), Map.of(3L, selected), null, null, null);

        assertEquals(1, calls.get());
        assertEquals("甲", group.getSubtitleCues().get(0).getSpeaker());
        assertEquals("你好世界", group.getSubtitleCues().get(0).getText());
        assertEquals(TimelineMediaFingerprint.of(99L, selected.getFileUrl()),
                group.getSubtitleSourceMediaFingerprint());
        assertEquals(group.getSubtitleCues(), segment.getSubtitle().getCues());
        assertEquals(SubtitleRecognitionStatus.COMPLETED,
                segment.getSubtitle().getRecognitionStatus());
        assertEquals("tencent_asr", segment.getSubtitle().getRecognitionProvider());
    }

    @Test
    void shouldSkipStoryboardWithoutDialogue() {
        AtomicInteger calls = new AtomicInteger();
        ExportSubtitleAlignmentServiceImpl service = new ExportSubtitleAlignmentServiceImpl(
                List.of(successClient(calls)));
        ComposeGroupDto group = group("无台词");

        assertEquals(0, service.countRequired(List.of(group), List.of(), Map.of()));
        service.align(List.of(group), List.of(), Map.of(), null, null, null);

        assertEquals(0, calls.get());
        assertNull(group.getSubtitle());
    }

    @Test
    void shouldFailWholeExportWhenStoryboardRecognitionFails() {
        SpeechRecognitionClient failedClient = new SpeechRecognitionClient() {
            @Override
            public String providerCode() {
                return "tencent_asr";
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public SpeechRecognitionResult recognize(String mediaUrl) {
                throw new ServiceException("字幕请求失败");
            }
        };
        ExportSubtitleAlignmentServiceImpl service = new ExportSubtitleAlignmentServiceImpl(List.of(failedClient));
        TimelineSegment segment = new TimelineSegment();
        segment.setStoryboardId(3L);
        segment.setSubtitle(new TimelineSubtitleItem());

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.align(List.of(group("甲：你好")), List.of(segment), Map.of(),
                        null, null, null));

        assertEquals("第1镜字幕请求失败", exception.getMessage());
        assertEquals(SubtitleRecognitionStatus.FAILED, segment.getSubtitle().getRecognitionStatus());
        assertEquals("字幕请求失败", segment.getSubtitle().getRecognitionError());
    }

    @Test
    void shouldFallbackToTextTimingWhenNoSpeechIsRecognized() {
        AtomicInteger calls = new AtomicInteger();
        SpeechRecognitionClient emptyClient = new SpeechRecognitionClient() {
            @Override
            public String providerCode() {
                return "tencent_asr";
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public SpeechRecognitionResult recognize(String mediaUrl) {
                calls.incrementAndGet();
                SpeechRecognitionResult result = new SpeechRecognitionResult();
                result.setDurationSeconds(4D);
                result.setCues(List.of());
                return result;
            }
        };
        ExportSubtitleAlignmentServiceImpl service = new ExportSubtitleAlignmentServiceImpl(List.of(emptyClient));
        ComposeGroupDto group = group("甲：没有识别到人声");
        TimelineSegment segment = segment(3L);

        service.align(List.of(group), List.of(segment), Map.of(), null, null, null);

        assertEquals(1, calls.get());
        assertEquals("甲：没有识别到人声", group.getSubtitle());
        assertNull(group.getSubtitleCues());
        assertEquals("甲：没有识别到人声", segment.getSubtitle().getText());
        assertNull(segment.getSubtitle().getCues());
        assertEquals(SubtitleRecognitionStatus.TEXT_FALLBACK,
                segment.getSubtitle().getRecognitionStatus());
        assertEquals("tencent_asr", segment.getSubtitle().getRecognitionProvider());
        assertEquals(group.getSubtitleSourceMediaFingerprint(),
                segment.getSubtitle().getSourceMediaFingerprint());

        assertEquals(0, service.countRequired(List.of(group), List.of(segment), Map.of()));
        service.align(List.of(group), List.of(segment), Map.of(), null, null, null);
        assertEquals(1, calls.get());
    }

    @Test
    void shouldResumeOnlyFailedStoryboardFromTimelineCheckpoint() {
        AtomicInteger firstRunCalls = new AtomicInteger();
        SpeechRecognitionClient firstRunClient = new SpeechRecognitionClient() {
            @Override
            public String providerCode() {
                return "tencent_asr";
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public SpeechRecognitionResult recognize(String mediaUrl) {
                firstRunCalls.incrementAndGet();
                if (mediaUrl.contains("second")) {
                    throw new ServiceException("字幕请求失败");
                }
                return successResult();
            }
        };
        ExportSubtitleAlignmentServiceImpl firstRunService =
                new ExportSubtitleAlignmentServiceImpl(List.of(firstRunClient));
        ComposeGroupDto first = group(3L, "https://example.com/first.mp4", "甲：第一句");
        ComposeGroupDto second = group(4L, "https://example.com/second.mp4", "乙：第二句");
        TimelineSegment firstSegment = segment(3L);
        TimelineSegment secondSegment = segment(4L);
        AidGenRecord firstVideo = selectedVideo(31L, "/first.mp4");
        AidGenRecord secondVideo = selectedVideo(41L, "/second.mp4");
        Map<Long, AidGenRecord> selected = Map.of(3L, firstVideo, 4L, secondVideo);
        AtomicInteger checkpoints = new AtomicInteger();

        assertThrows(ServiceException.class, () -> firstRunService.align(
                List.of(first, second), List.of(firstSegment, secondSegment), selected,
                null, checkpoints::incrementAndGet, null));

        assertEquals(2, firstRunCalls.get());
        assertEquals(4, checkpoints.get());
        assertEquals(SubtitleRecognitionStatus.COMPLETED,
                firstSegment.getSubtitle().getRecognitionStatus());
        assertEquals(SubtitleRecognitionStatus.FAILED,
                secondSegment.getSubtitle().getRecognitionStatus());
        TimelineSegment restoredFirst = JSON.parseObject(
                JSON.toJSONString(firstSegment), TimelineSegment.class);
        assertEquals(SubtitleRecognitionStatus.COMPLETED,
                restoredFirst.getSubtitle().getRecognitionStatus());
        assertEquals(firstSegment.getSubtitle().getSourceMediaFingerprint(),
                restoredFirst.getSubtitle().getSourceMediaFingerprint());

        AtomicInteger retryCalls = new AtomicInteger();
        ExportSubtitleAlignmentServiceImpl retryService = new ExportSubtitleAlignmentServiceImpl(
                List.of(successClient(retryCalls)));
        first.setSubtitle("新甲：第一句");
        assertEquals(1, retryService.countRequired(List.of(first, second),
                List.of(firstSegment, secondSegment), selected));
        assertEquals("新甲", first.getSubtitleCues().get(0).getSpeaker());

        retryService.align(List.of(first, second), List.of(firstSegment, secondSegment),
                selected, null, null, null);

        assertEquals(1, retryCalls.get());
        assertEquals(SubtitleRecognitionStatus.COMPLETED,
                secondSegment.getSubtitle().getRecognitionStatus());
    }

    @Test
    void shouldRematchWrongSpeakerWhenReusingSameAsrCheckpoint() {
        ExportSubtitleAlignmentServiceImpl service = new ExportSubtitleAlignmentServiceImpl(
                List.of(successClient(new AtomicInteger())));
        ComposeGroupDto group = group(3L, "https://example.com/video.mp4",
                "张叔：查出指标异常后有没有对症的治疗药物\n科普医师：目前需要进一步检查");
        AidGenRecord selected = selectedVideo(99L, "/video/current.mp4");
        String mediaFingerprint = TimelineMediaFingerprint.of(selected.getId(), selected.getFileUrl());
        TimedSubtitleCue staleCue = new TimedSubtitleCue();
        staleCue.setStartSeconds(0.2D);
        staleCue.setEndSeconds(1.8D);
        staleCue.setSpeaker("科普医师");
        staleCue.setText("查出指标异常后");
        staleCue.setSource("ASR");
        group.setSubtitleCues(List.of(staleCue));
        group.setSubtitleSourceMediaFingerprint(mediaFingerprint);
        TimelineSegment segment = segment(3L);
        segment.getSubtitle().setCues(List.of(staleCue));
        segment.getSubtitle().setSourceMediaFingerprint(mediaFingerprint);

        int required = service.countRequired(List.of(group), List.of(segment), Map.of(3L, selected));

        assertEquals(0, required);
        assertEquals("张叔", group.getSubtitleCues().get(0).getSpeaker());
    }

    private ComposeGroupDto group(String subtitle) {
        return group(3L, "https://example.com/video.mp4", subtitle);
    }

    private ComposeGroupDto group(Long storyboardId, String videoUrl, String subtitle) {
        ComposeGroupDto group = new ComposeGroupDto();
        group.setStoryboardId(storyboardId);
        group.setVideoUrls(List.of(videoUrl));
        group.setVideoDurations(List.of(4D));
        group.setSubtitle(subtitle);
        return group;
    }

    private TimelineSegment segment(Long storyboardId) {
        TimelineSegment segment = new TimelineSegment();
        segment.setStoryboardId(storyboardId);
        segment.setSubtitle(new TimelineSubtitleItem());
        return segment;
    }

    private AidGenRecord selectedVideo(Long id, String fileUrl) {
        AidGenRecord selected = new AidGenRecord();
        selected.setId(id);
        selected.setFileUrl(fileUrl);
        return selected;
    }

    private SpeechRecognitionClient successClient(AtomicInteger calls) {
        return new SpeechRecognitionClient() {
            @Override
            public String providerCode() {
                return "tencent_asr";
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public SpeechRecognitionResult recognize(String mediaUrl) {
                calls.incrementAndGet();
                return successResult();
            }
        };
    }

    private SpeechRecognitionResult successResult() {
        TimedSubtitleCue cue = new TimedSubtitleCue();
        cue.setStartSeconds(0.2D);
        cue.setEndSeconds(1.5D);
        cue.setText("你好，世界。");
        cue.setSpeaker("speaker_0");
        SpeechRecognitionResult result = new SpeechRecognitionResult();
        result.setCues(List.of(cue));
        return result;
    }
}
