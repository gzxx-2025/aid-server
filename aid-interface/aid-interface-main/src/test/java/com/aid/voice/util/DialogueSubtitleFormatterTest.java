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
    void shouldParseJsonNewlinesAndBothSpeakerColons() {
        String raw = "科普医师：第一句。\n张叔: 第二句！";

        assertEquals("科普医师：第一句\n张叔：第二句", DialogueSubtitleFormatter.format(raw));
    }

    @Test
    void shouldParseLiteralEscapedNewlineWithoutLeakingPreviousSpeaker() {
        String raw = "科普医师：第一句。\\n张叔: 第二句！";

        assertEquals("科普医师：第一句\n张叔：第二句", DialogueSubtitleFormatter.format(raw));
    }

    @Test
    void shouldExtractSpeakerFromNaturalDialogueAndDropShotMarker() {
        String raw = "张叔担忧地说：\"查出指标异常后，有没有对症的治疗药物?\" [镜头2]";

        assertEquals("张叔：查出指标异常后有没有对症的治疗药物", DialogueSubtitleFormatter.format(raw));
    }

    @Test
    void shouldKeepEverySpeakerForMultipleNaturalDialoguesInOneStoryboard() {
        String raw = "张叔担忧地说：\"有没有对症的治疗药物?\" [镜头2]\n"
                + "科普医师专业地解释：\"目前需要进一步检查。\" [镜头3]";

        assertEquals("张叔：有没有对症的治疗药物\n科普医师：目前需要进一步检查",
                DialogueSubtitleFormatter.format(raw));
    }

    @Test
    void shouldDropNoDialoguePlaceholder() {
        assertNull(DialogueSubtitleFormatter.format("（无台词）"));
    }
}
