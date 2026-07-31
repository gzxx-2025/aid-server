package com.aid.voice.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DialogueSubtitleFormatterTest {

    @Test
    void shouldAlwaysAddSpeakerAndRemovePunctuation() {
        assertEquals("旁白：风停了天亮了", DialogueSubtitleFormatter.format("风停了，天亮了。"));
        assertEquals("悟空：俺也去也", DialogueSubtitleFormatter.format("悟空：俺也去也！"));
    }

    @Test
    void shouldKeepStructuredSpeakersForEveryLine() {
        String raw = "[甲_初始形象]：「你来了？」|[乙_初始形象]：我来了。";

        assertEquals("甲：你来了\n乙：我来了", DialogueSubtitleFormatter.format(raw));
    }

    @Test
    void shouldDropNoDialoguePlaceholder() {
        assertNull(DialogueSubtitleFormatter.format("（无台词）"));
    }
}
