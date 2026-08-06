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

    @Test
    void shouldKeepUncleZhangForQuestionSplitIntoMultipleAsrCues() {
        TimedSubtitleCue first = cue(0.2, 1.2, "查出指标异常后", "speaker_0");
        TimedSubtitleCue second = cue(1.2, 2.8, "有没有对症的治疗药物", "speaker_0");
        TimedSubtitleCue third = cue(2.8, 3.8, "目前需要进一步检查", "speaker_0");
        String script = "张叔担忧地说：\"查出指标异常后，有没有对症的治疗药物?\" [镜头2]\n"
                + "科普医师：目前需要进一步检查";

        List<TimedSubtitleCue> result = SubtitleSpeakerMatcher.match(List.of(first, second, third), script, 4D);

        assertEquals(3, result.size());
        assertEquals("张叔", result.get(0).getSpeaker());
        assertEquals("张叔", result.get(1).getSpeaker());
        assertEquals("科普医师", result.get(2).getSpeaker());
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
