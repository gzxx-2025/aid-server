package com.aid.compose.util;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aid.compose.dto.ComposeGroupDto;
import com.aid.compose.dto.timeline.TimelineSegment;
import com.aid.compose.dto.timeline.TimelineVideoItem;

class TimelineSubtitleMatcherTest {

    @Test
    void shouldMatchPartialAndReorderedExportByStoryboardId() {
        TimelineSegment first = segment(101L, "/video/101.mp4");
        TimelineSegment third = segment(103L, "/video/103.mp4");

        List<TimelineSegment> matched = TimelineSubtitleMatcher.match(
                List.of(group(103L, "/video/103.mp4"), group(101L, "/video/101.mp4")),
                List.of(first, segment(102L, "/video/102.mp4"), third));

        assertSame(third, matched.get(0));
        assertSame(first, matched.get(1));
    }

    @Test
    void shouldNotGuessMatchFromVideoUrlWhenStoryboardIdIsMissing() {
        TimelineSegment third = segment(103L, "/video/103.mp4");

        List<TimelineSegment> matched = TimelineSubtitleMatcher.match(
                List.of(group(null, "https://cdn.test/video/103.mp4")),
                List.of(segment(101L, "/video/101.mp4"), segment(102L, "/video/102.mp4"), third));

        assertNull(matched.get(0));
    }

    @Test
    void shouldKeepOtherMatchesWhenOneGroupCannotBeResolved() {
        TimelineSegment first = segment(101L, "/video/101.mp4");

        List<TimelineSegment> matched = TimelineSubtitleMatcher.match(
                List.of(group(999L, "/video/missing.mp4"), group(101L, "/video/101.mp4")),
                List.of(first, segment(102L, "/video/102.mp4"), segment(103L, "/video/103.mp4")));

        assertNull(matched.get(0));
        assertSame(first, matched.get(1));
    }

    @Test
    void shouldNotGuessMatchFromEqualArrayIndex() {
        TimelineSegment first = segment(101L, null);
        TimelineSegment second = segment(102L, null);

        List<TimelineSegment> matched = TimelineSubtitleMatcher.match(
                List.of(group(null, "/video/a.mp4"), group(null, "/video/b.mp4")),
                List.of(first, second));

        assertNull(matched.get(0));
        assertNull(matched.get(1));
    }

    @Test
    void shouldIgnoreVideoUrlWhenStoryboardIdDoesNotMatch() {
        TimelineSegment first = segment(101L, "/video/shared.mp4");

        List<TimelineSegment> matched = TimelineSubtitleMatcher.match(
                List.of(group(102L, "/video/shared.mp4")),
                List.of(first));

        assertNull(matched.get(0));
    }

    @Test
    void shouldRejectAmbiguousDuplicateStoryboardId() {
        List<TimelineSegment> matched = TimelineSubtitleMatcher.match(
                List.of(group(101L, "/video/101.mp4")),
                List.of(segment(101L, "/video/first.mp4"), segment(101L, "/video/second.mp4")));

        assertNull(matched.get(0));
    }

    private static ComposeGroupDto group(Long storyboardId, String videoUrl) {
        ComposeGroupDto group = new ComposeGroupDto();
        group.setStoryboardId(storyboardId);
        group.setVideoUrls(List.of(videoUrl));
        return group;
    }

    private static TimelineSegment segment(Long storyboardId, String videoUrl) {
        TimelineVideoItem video = new TimelineVideoItem();
        video.setUrl(videoUrl);
        TimelineSegment segment = new TimelineSegment();
        segment.setStoryboardId(storyboardId);
        segment.setVideo(video);
        return segment;
    }
}
