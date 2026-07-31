package com.aid.rps.resolver;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色级参考音频治理用例。
 *
 * @author 视觉AID
 */
class StoryboardRoleAudioReferenceResolverTest
{
    private StoryboardRoleAudioReferenceResolver resolver;

    @BeforeEach
    void setUp()
    {
        resolver = new StoryboardRoleAudioReferenceResolver();
        ReflectionTestUtils.setField(resolver, "dialogueResolver", new StoryboardAudioReferenceResolver());
    }

    @Test
    void shouldSelectCanonicalAudioReferencesBySpeakerOrder()
    {
        String dialogue = "[法海_初始形象]：「白素贞，人妖殊途。」｜[白素贞_半妖态]：「我不会退。」";

        List<String> result = resolver.resolveSpeakerReferenceNames(dialogue,
                List.of("音频-白素贞", "音频-许仙", "音频-法海"));

        assertEquals(List.of("音频-法海", "音频-白素贞"), result);
    }

    @Test
    void shouldReplaceLegacyFormAudioSectionWithRoleLevelReferences()
    {
        String dialogue = "[法海_初始形象]：「住手。」｜[白素贞_半妖态]：「不。」";
        String referenceInfo = "角色：[法海_初始形象]、[白素贞_半妖态]；"
                + "音频：[音频-白素贞_初始形象]、[音频-白素贞_半妖态]";

        String result = resolver.ensureReferenceInfo(referenceInfo, dialogue,
                List.of("音频-法海", "音频-白素贞"));

        assertTrue(result.contains("音频：[音频-法海]、[音频-白素贞]"));
        assertFalse(result.contains("音频-白素贞_初始形象"));
        assertFalse(result.contains("音频-白素贞_半妖态"));
    }

    @Test
    void shouldRepairGeneratedPromptAudioPlaceholdersDeterministically()
    {
        String dialogue = "[法海_初始形象]：「住手。」｜[白素贞_半妖态]：「不。」";
        String prompt = "法海参考@图片3[法海_初始形象]。"
                + "白素贞参考@图片4[白素贞_半妖态]，声音参考@音频1[音频-白素贞_初始形象]。";

        String result = resolver.normalizeGeneratedVideoPrompt(prompt, dialogue,
                List.of("音频-法海", "音频-白素贞"));

        assertTrue(result.contains("@图片3[法海_初始形象]"));
        assertTrue(result.contains("@图片4[白素贞_半妖态]"));
        assertTrue(result.contains("法海声音参考@音频1[音频-法海]"));
        assertTrue(result.contains("声音参考@音频2[音频-白素贞]"));
        assertFalse(result.contains("音频-白素贞_初始形象"));
    }

    @Test
    void shouldKeepValidGeneratedPromptWithoutMovingAudioReferences()
    {
        String dialogue = "[法海_初始形象]：「住手。」｜[白素贞_半妖态]：「不。」";
        String prompt = "场景：雷峰塔。\n"
                + "角色：法海声音参考@音频1[音频-法海]，白素贞声音参考@音频2[音频-白素贞]。\n"
                + "[镜头1]，法海与白素贞对峙。\n"
                + "全局风格：国风动画，无BGM，无字幕。";

        String result = resolver.normalizeGeneratedVideoPrompt(prompt, dialogue,
                List.of("音频-法海", "音频-白素贞"));

        assertEquals(prompt, result);
        assertTrue(result.endsWith("全局风格：国风动画，无BGM，无字幕。"));
    }

    @Test
    void shouldKeepValidStoredPromptThroughVideoPreparation()
    {
        String dialogue = "[法海_初始形象]：「住手。」｜[白素贞_半妖态]：「不。」";
        String prompt = "角色：法海声音参考@音频1[音频-法海]，"
                + "白素贞声音参考@音频2[音频-白素贞]。\n"
                + "全局风格：国风动画，无BGM，无字幕。";

        String normalized = resolver.normalizeStoredVideoPrompt(prompt, dialogue);
        String aligned = resolver.alignPromptToResolvedRoleReferences(
                normalized, dialogue, List.of("音频-法海", "音频-白素贞"));

        assertEquals(prompt, normalized);
        assertEquals(prompt, aligned);
    }

    @Test
    void shouldRepairIncorrectIndexesAtOriginalPositions()
    {
        String dialogue = "[法海_初始形象]：「住手。」｜[白素贞_半妖态]：「不。」";
        String prompt = "场景：雷峰塔。\n"
                + "角色：法海声音参考@音频2[音频-法海]，白素贞声音参考@音频1[音频-白素贞]。\n"
                + "全局风格：国风动画，无BGM，无字幕。";

        String result = resolver.normalizeGeneratedVideoPrompt(prompt, dialogue,
                List.of("音频-法海", "音频-白素贞"));

        assertTrue(result.contains("法海声音参考@音频1[音频-法海]"));
        assertTrue(result.contains("白素贞声音参考@音频2[音频-白素贞]"));
        assertFalse(result.contains("音频角色映射"));
        assertTrue(result.endsWith("全局风格：国风动画，无BGM，无字幕。"));
    }

    @Test
    void shouldKeepAcceptedReferenceInPlaceAndOnlyDegradeRejectedReference()
    {
        String dialogue = "[法海_初始形象]：「住手。」｜[白素贞_半妖态]：「不。」";
        String prompt = "角色：法海声音参考@音频1[音频-法海]，"
                + "白素贞声音参考@音频2[音频-白素贞]。\n"
                + "全局风格：国风动画，无BGM，无字幕。";

        String result = resolver.alignPromptToResolvedRoleReferences(
                prompt, dialogue, List.of("音频-白素贞"));

        assertFalse(result.contains("@音频1[音频-法海]"));
        assertTrue(result.contains("法海声音参考法海"));
        assertTrue(result.contains("白素贞声音参考@音频1[音频-白素贞]"));
        assertFalse(result.contains("音频角色映射"));
        assertTrue(result.endsWith("全局风格：国风动画，无BGM，无字幕。"));
    }

    @Test
    void shouldNotInventAudioForRoleWithoutBindingCandidate()
    {
        String dialogue = "[法海_初始形象]：「住手。」｜[白素贞_初始形象]：「不。」";

        List<String> result = resolver.resolveSpeakerReferenceNames(dialogue, List.of("音频-白素贞"));

        assertEquals(List.of("音频-白素贞"), result);
    }

    @Test
    void shouldHealStoredPromptBeforeBindingValidation()
    {
        String dialogue = "[法海_初始形象]：「住手。」｜[白素贞_初始形象]：「不。」";
        String storedPrompt = "法海与白素贞对峙，白素贞声音参考@音频1[音频-白素贞_初始形象]。";

        String result = resolver.normalizeStoredVideoPrompt(storedPrompt, dialogue);

        assertTrue(result.contains("法海声音参考@音频1[音频-法海]"));
        assertTrue(result.contains("白素贞声音参考@音频2[音频-白素贞]"));
        assertFalse(result.contains("音频-白素贞_初始形象"));
    }

    @Test
    void shouldDegradeRejectedRolePlaceholderToAvoidExplicitAudioIndexCollision()
    {
        String dialogue = "[法海_初始形象]：「住手。」";
        String prompt = "法海声音参考@音频1[音频-法海]。";

        String result = resolver.alignPromptToResolvedRoleReferences(prompt, dialogue, List.of());

        assertFalse(result.contains("@音频1"));
        assertTrue(result.contains("法海声音参考法海"));
    }

    @Test
    void shouldKeepRepeatedNormalizationIdempotent()
    {
        String dialogue = "[法海_初始形象]：「住手。」｜[白素贞_初始形象]：「不。」";
        List<String> available = List.of("音频-法海", "音频-白素贞");
        String once = resolver.normalizeGeneratedVideoPrompt("二人对峙。", dialogue, available);

        String twice = resolver.normalizeGeneratedVideoPrompt(once, dialogue, available);

        assertEquals(once, twice);
    }
}
