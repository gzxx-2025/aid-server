package com.aid.project.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.aid.aid.util.HiddenStylePromptJsonUtils;

class HiddenStylePromptJsonUtilsAdditionalTest
{
    @Test
    void fromCharacterPromptBuildsCompleteJson()
    {
        String json = HiddenStylePromptJsonUtils.fromCharacterPrompt("hidden character style");

        assertEquals("hidden character style", HiddenStylePromptJsonUtils.resolve(
                json, HiddenStylePromptJsonUtils.KEY_CHARACTER, "fallback"));
        assertEquals("", HiddenStylePromptJsonUtils.resolve(
                json, HiddenStylePromptJsonUtils.KEY_SCENE, ""));
        assertEquals("", HiddenStylePromptJsonUtils.resolve(
                json, HiddenStylePromptJsonUtils.KEY_PROP, ""));
    }

    @Test
    void blankInputAndNullCharacterAreHandledConsistently()
    {
        assertNull(HiddenStylePromptJsonUtils.normalize("  "));

        String json = HiddenStylePromptJsonUtils.fromCharacterPrompt(null);
        assertEquals("public fallback", HiddenStylePromptJsonUtils.resolve(
                json, HiddenStylePromptJsonUtils.KEY_CHARACTER, "public fallback"));
    }

    @Test
    void normalizeRejectsMalformedAndNonObjectJson()
    {
        assertThrows(IllegalArgumentException.class,
                () -> HiddenStylePromptJsonUtils.normalize("not-json"));
        assertThrows(IllegalArgumentException.class,
                () -> HiddenStylePromptJsonUtils.normalize("[\"character\"]"));
    }

    @Test
    void synchronizingCharacterKeepsSceneAndPropTemplates()
    {
        String json = HiddenStylePromptJsonUtils.withCharacterPrompt(
                "{\"character\":\"old\",\"scene\":\"scene template\",\"prop\":\"prop template\"}",
                "new character");

        assertEquals("new character", HiddenStylePromptJsonUtils.resolve(
                json, HiddenStylePromptJsonUtils.KEY_CHARACTER, ""));
        assertEquals("scene template", HiddenStylePromptJsonUtils.resolve(
                json, HiddenStylePromptJsonUtils.KEY_SCENE, ""));
        assertEquals("prop template", HiddenStylePromptJsonUtils.resolve(
                json, HiddenStylePromptJsonUtils.KEY_PROP, ""));
    }
}
