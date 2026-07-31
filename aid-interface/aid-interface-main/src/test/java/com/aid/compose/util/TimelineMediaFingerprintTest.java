package com.aid.compose.util;

import com.aid.aid.domain.AidGenRecord;
import com.aid.compose.dto.ComposeGroupDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TimelineMediaFingerprintTest {

    @Test
    void shouldPreferStableRecordId() {
        assertEquals(TimelineMediaFingerprint.of(8L, "/a.mp4"),
                TimelineMediaFingerprint.of(8L, "https://cdn.test/other.mp4?token=1"));
        assertNotEquals(TimelineMediaFingerprint.of(8L, "/a.mp4"),
                TimelineMediaFingerprint.of(9L, "/a.mp4"));
    }

    @Test
    void shouldIgnoreSignedUrlQueryWithoutRecordId() {
        assertEquals(TimelineMediaFingerprint.of(null, "https://cdn.test/a.mp4?token=1"),
                TimelineMediaFingerprint.of(null, "https://cdn.test/a.mp4?token=2"));
    }

    @Test
    void shouldKeepGroupOrderAndIgnoreEachSignedQuery() {
        assertEquals(TimelineMediaFingerprint.ofGroup(List.of(
                        "https://cdn.test/a.mp4?token=1", "https://cdn.test/b.mp4?token=2")),
                TimelineMediaFingerprint.ofGroup(List.of(
                        "https://cdn.test/a.mp4?token=3", "https://cdn.test/b.mp4?token=4")));
        assertNotEquals(TimelineMediaFingerprint.ofGroup(List.of("/a.mp4", "/b.mp4")),
                TimelineMediaFingerprint.ofGroup(List.of("/b.mp4", "/a.mp4")));
    }

    @Test
    void shouldPreferFinalVoiceTrackAsRecognitionSource() {
        ComposeGroupDto group = new ComposeGroupDto();
        group.setVideoUrls(List.of("/video.mp4"));
        group.setVideoDurations(List.of(4D));
        group.setAudioUrls(List.of("/voice.mp3"));
        group.setAudioDurations(List.of(3D));
        AidGenRecord selectedVideo = new AidGenRecord();
        selectedVideo.setId(9L);
        selectedVideo.setFileUrl("/video.mp4");

        assertEquals(List.of("/voice.mp3"), SubtitleRecognitionMediaResolver.resolveUrls(group));
        assertEquals(TimelineMediaFingerprint.of(null, "/voice.mp3"),
                SubtitleRecognitionMediaResolver.fingerprint(group, selectedVideo));
    }
}
