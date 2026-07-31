package com.aid.rps.helper;

import com.aid.rps.helper.SceneExtractionNormalizer.NormalizedScene;
import com.aid.rps.model.ExistingAssetLib;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneExtractionNormalizerTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldUseStructuredTimeAndRebuildUnknownName()
    {
        ObjectNode item = scene("凌霄殿外_未知", "凌霄殿外", "上午", "天兵拦住孙悟空");

        NormalizedScene result = SceneExtractionNormalizer.normalize(item);

        assertEquals("凌霄殿外_上午", result.canonicalName());
        assertEquals("凌霄殿外", result.specificLocation());
        assertEquals("上午", result.timeOfDay());
        assertFalse(result.defaulted());
        assertEquals("凌霄殿外_上午", item.path("name").asText());
        assertEquals("上午", item.path("timeOfDay").asText());
    }

    @Test
    void shouldReplaceNoTimeWithUnifiedDefault()
    {
        ObjectNode item = scene("凌霄殿外_无", "凌霄殿外", "无", "天兵守在殿外");

        NormalizedScene result = SceneExtractionNormalizer.normalize(item);

        assertEquals("凌霄殿外_上午", result.canonicalName());
        assertEquals(SceneExtractionNormalizer.DEFAULT_TIME_OF_DAY, result.timeOfDay());
        assertTrue(result.defaulted());
    }

    @Test
    void shouldInferStandardTimeFromPlotWhenFieldsAreInvalid()
    {
        ObjectNode item = scene("南天门外_未知", "南天门外", "未知", "夜晚，月光照在南天门外");

        NormalizedScene result = SceneExtractionNormalizer.normalize(item);

        assertEquals("南天门外_夜晚", result.canonicalName());
        assertEquals("夜晚", result.timeOfDay());
        assertFalse(result.defaulted());
    }

    @Test
    void shouldUseValidNameSuffixWhenStructuredTimeIsInvalid()
    {
        ObjectNode item = scene("南天门外_黄昏", "南天门外", "未说明", "众人立于门外");

        NormalizedScene result = SceneExtractionNormalizer.normalize(item);

        assertEquals("南天门外_黄昏", result.canonicalName());
        assertEquals("黄昏", result.timeOfDay());
    }

    @Test
    void shouldRecoverLocationFromNameAndRemoveDiagnosticMarker()
    {
        ObjectNode item = scene("凌霄殿前（推断）_夜晚", null, "夜晚", "诸神退避");

        NormalizedScene result = SceneExtractionNormalizer.normalize(item);

        assertEquals("凌霄殿前_夜晚", result.canonicalName());
        assertEquals("凌霄殿前", result.specificLocation());
    }

    @Test
    void shouldRemoveInvalidTimeSuffixFromSpecificLocation()
    {
        ObjectNode item = scene("凌霄殿外_无", "凌霄殿外_无", "无", "天兵守在殿外");

        NormalizedScene result = SceneExtractionNormalizer.normalize(item);

        assertEquals("凌霄殿外_上午", result.canonicalName());
        assertEquals("凌霄殿外", result.specificLocation());
    }

    @Test
    void shouldRejectPlaceholderLocation()
    {
        ObjectNode item = scene("未知_上午", "未知", "上午", "众人站立");

        assertThrows(IllegalArgumentException.class, () -> SceneExtractionNormalizer.normalize(item));
    }

    @Test
    void shouldDeduplicateScenesByLocationAndTime()
    {
        ExistingAssetLib lib = new ExistingAssetLib();
        lib.addScene("凌霄殿外_上午", 1L);
        lib.addScene("凌霄殿外_夜晚", 2L);

        assertEquals(1L, lib.findSceneIdByName("凌霄殿外_上午"));
        assertEquals(2L, lib.findSceneIdByName("凌霄殿外_夜晚"));
        assertEquals("凌霄殿外_上午, 凌霄殿外_夜晚", lib.getSceneNamesJoined());
    }

    private ObjectNode scene(String name, String specificLocation, String timeOfDay, String plotContent)
    {
        ObjectNode item = OBJECT_MAPPER.createObjectNode();
        if (name != null)
        {
            item.put("name", name);
        }
        if (specificLocation != null)
        {
            item.put("specificLocation", specificLocation);
        }
        if (timeOfDay != null)
        {
            item.put("timeOfDay", timeOfDay);
        }
        if (plotContent != null)
        {
            item.put("plotContent", plotContent);
        }
        return item;
    }
}
