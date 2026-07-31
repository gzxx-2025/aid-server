package com.aid.rps.resolver;

import com.aid.rps.resolver.StoryboardAudioPlaceholders.PlaceholderResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryboardAudioPlaceholdersTest {

    private static final String DIALOGUE =
            "台词：【罗峰_初始形象，低沉独白】@音频1[音频-罗峰_初始形象]说：“开始吧。”，语速缓慢深沉。";

    @Test
    void shouldParseIndexAndNameFromDialogueLine() {
        PlaceholderResult result = StoryboardAudioPlaceholders.parse(DIALOGUE);

        assertFalse(result.isConflicted());
        assertEquals(1, result.getNames().size());
        assertEquals("音频-罗峰_初始形象", result.getNames().get(1));
        assertEquals(2, result.nextIndex());
    }

    @Test
    void shouldFlagConflictWhenSameIndexBoundToDifferentNames() {
        PlaceholderResult result = StoryboardAudioPlaceholders
                .parse("@音频1[音频-罗峰] 与 @音频1[音频-巴巴塔]");

        assertTrue(result.isConflicted());
        assertEquals("音频-罗峰", result.getNames().get(1));
    }

    @Test
    void shouldFlagConflictForEmptyPlaceholderName() {
        PlaceholderResult result = StoryboardAudioPlaceholders.parse("@音频1[]");

        assertTrue(result.isConflicted());
        assertTrue(result.getNames().isEmpty());
    }

    @Test
    void shouldKeepSameIndexWhenNameRepeats() {
        PlaceholderResult result = StoryboardAudioPlaceholders
                .parse("@音频1[音频-罗峰] 中间 @音频1[音频-罗峰]");

        assertFalse(result.isConflicted());
        assertEquals(1, result.getNames().size());
    }

    @Test
    void shouldExposeSameRegexToBothChains() {
        // 配音链路取编号、出片链路取引用名，必须来自同一份正则
        assertTrue(StoryboardAudioPlaceholders.contains(DIALOGUE));
        assertEquals(1, StoryboardAudioPlaceholders.firstIndex(DIALOGUE));
        assertFalse(StoryboardAudioPlaceholders.removeAll(DIALOGUE).contains("@音频"));
    }

    @Test
    void shouldReturnEmptyResultForBlankText() {
        PlaceholderResult result = StoryboardAudioPlaceholders.parse("  ");

        assertTrue(result.getNames().isEmpty());
        assertFalse(result.isConflicted());
        assertEquals(1, result.nextIndex());
        assertNull(StoryboardAudioPlaceholders.firstIndex(null));
        assertFalse(StoryboardAudioPlaceholders.contains(null));
    }
}
