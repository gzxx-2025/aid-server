package com.aid.compose.service.impl;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aid.common.exception.ServiceException;
import com.aid.compose.dto.timeline.TimelineData;
import com.aid.compose.dto.timeline.TimelineSegment;

class EpisodeTimelineServiceImplValidationTest {

    @Test
    void rejectsTimelineSegmentWithoutStoryboardId() {
        EpisodeTimelineServiceImpl service = mock(EpisodeTimelineServiceImpl.class, CALLS_REAL_METHODS);
        TimelineData timeline = timeline(segment(null));

        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "normalizeForSave", timeline));
    }

    @Test
    void rejectsDuplicateStoryboardIds() {
        EpisodeTimelineServiceImpl service = mock(EpisodeTimelineServiceImpl.class, CALLS_REAL_METHODS);
        TimelineData timeline = timeline(segment(101L), segment(101L));

        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "normalizeForSave", timeline));
    }

    @Test
    void rejectsStoredLegacyTimelineShape() {
        EpisodeTimelineServiceImpl service = mock(EpisodeTimelineServiceImpl.class, CALLS_REAL_METHODS);

        TimelineData parsed = ReflectionTestUtils.invokeMethod(
                service, "parseTimeline", "{\"videoClips\":[],\"subtitleItems\":[]}");

        assertNull(parsed);
    }

    private TimelineData timeline(TimelineSegment... segments) {
        TimelineData timeline = new TimelineData();
        timeline.setSegments(List.of(segments));
        return timeline;
    }

    private TimelineSegment segment(Long storyboardId) {
        TimelineSegment segment = new TimelineSegment();
        segment.setStoryboardId(storyboardId);
        return segment;
    }
}
