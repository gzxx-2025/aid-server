package com.aid.compose.util;

import com.aid.compose.domain.TimedSubtitleCue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubtitleSpeakerMatcherTest {

    @Test
    void shouldMatchRecognizedSegmentsToScriptSpeakersAndClampDuration() {
        TimedSubtitleCue first = cue(0.2, 1.8, "你终于来了。", null);
        TimedSubtitleCue second = cue(1.9, 4.8, "我已经等很久了！", "speaker_1");

        List<TimedSubtitleCue> result = SubtitleSpeakerMatcher.match(
                List.of(first, second), "甲：你终于来了\n乙：我已经等很久了", 4.0);

        assertEquals(2, result.size());
        assertEquals("甲", result.get(0).getSpeaker());
        assertEquals("你终于来了", result.get(0).getText());
        assertEquals("乙", result.get(1).getSpeaker());
        assertEquals(4.0, result.get(1).getEndSeconds());
    }

    @Test
    void shouldFallbackToNarratorWithoutScript() {
        List<TimedSubtitleCue> result = SubtitleSpeakerMatcher.match(
                List.of(cue(0, 1, "风起了，", "speaker_0")), null, 2);

        assertEquals("旁白", result.get(0).getSpeaker());
        assertEquals("风起了", result.get(0).getText());
    }

    private TimedSubtitleCue cue(double start, double end, String text, String speaker) {
        TimedSubtitleCue cue = new TimedSubtitleCue();
        cue.setStartSeconds(start);
        cue.setEndSeconds(end);
        cue.setText(text);
        cue.setSpeaker(speaker);
        return cue;
    }
}
