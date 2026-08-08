package com.aid.project.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aid.aid.util.HiddenStylePromptJsonUtils;

class HiddenStylePromptJsonUtilsTest
{
    @Test
    void normalizesFixedEnglishKeys()
    {
        String normalized = HiddenStylePromptJsonUtils.normalize("{\"character\":\"3D style\"}");

        assertEquals("3D style", HiddenStylePromptJsonUtils.resolve(
                normalized, HiddenStylePromptJsonUtils.KEY_CHARACTER, "fallback"));
        assertTrue(normalized.contains("\"scene\":\"\""));
        assertTrue(normalized.contains("\"prop\":\"\""));
    }

    @Test
    void invalidOrEmptyCharacterFallsBackToPublicPrompt()
    {
        assertEquals("公开风格", HiddenStylePromptJsonUtils.resolve(
                "not-json", HiddenStylePromptJsonUtils.KEY_CHARACTER, "公开风格"));
        assertEquals("公开风格", HiddenStylePromptJsonUtils.resolve(
                "{\"character\":\"\"}", HiddenStylePromptJsonUtils.KEY_CHARACTER, "公开风格"));
    }

    @Test
    void rejectsUnknownOrNonStringFields()
    {
        assertThrows(IllegalArgumentException.class,
                () -> HiddenStylePromptJsonUtils.normalize("{\"character\":1}"));
        assertThrows(IllegalArgumentException.class,
                () -> HiddenStylePromptJsonUtils.normalize("{\"character\":\"x\",\"other\":\"y\"}"));
    }
}
