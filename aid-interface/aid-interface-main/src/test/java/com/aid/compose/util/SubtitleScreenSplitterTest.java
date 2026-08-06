package com.aid.compose.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SubtitleScreenSplitterTest {

    @Test
    void shouldPreserveOrderAndBalanceSevenToTwelveCharactersPerScreen() {
        String subtitle = "第一句台词很长需要分屏，第二句也要继续显示。";

        List<String> screens = SubtitleScreenSplitter.split(subtitle, 10);

        assertEquals(subtitle, String.join("", screens));
        assertTrue(screens.size() > 1);
        assertTrue(screens.stream().allMatch(screen -> {
            int characters = SubtitleScreenSplitter.charCount(screen);
            return characters >= 7 && characters <= 12;
        }));
    }

    @Test
    void shouldKeepDifferentLinesAsOrderedScreens() {
        List<String> screens = SubtitleScreenSplitter.split("人物甲：第一句\n人物乙：第二句", 20);

        assertEquals(List.of("人物甲：第一句", "人物乙：第二句"), screens);
    }

    @Test
    void shouldKeepElevenCharacterLineOnOneScreen() {
        String subtitle = "这句台词刚好十一字正文";

        List<String> screens = SubtitleScreenSplitter.split(subtitle, 10);

        assertEquals(List.of(subtitle), screens);
    }

    @Test
    void shouldKeepShortDialogueWithoutPadding() {
        List<String> screens = SubtitleScreenSplitter.split("快走", 10);

        assertEquals(List.of("快走"), screens);
    }
}
