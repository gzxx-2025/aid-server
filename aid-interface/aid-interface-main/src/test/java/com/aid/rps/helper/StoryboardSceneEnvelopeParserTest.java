package com.aid.rps.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aid.aid.domain.AidRolePropScene;
import com.aid.common.exception.ServiceException;

class StoryboardSceneEnvelopeParserTest
{
    private final StoryboardSceneEnvelopeParser parser = new StoryboardSceneEnvelopeParser();

    @Test
    void keepsMultipleShotsInsideOneBoundSceneEnvelope()
    {
        AidRolePropScene scene = scene(101L, "天衡剑宫广场_早晨");
        String output = """
                {"scenes":[{"sceneName":"天衡剑宫广场_早晨","shots":[
                  {"scriptContent":"甲拔剑。","visualDescription":"甲拔剑"},
                  {"scriptContent":"乙后退。","visualDescription":"乙后退"}
                ]}]}
                """;

        List<StoryboardSceneEnvelopeParser.SceneEnvelope> result =
                parser.parse(output, List.of(scene), false);

        assertEquals(1, result.size());
        assertSame(scene, result.get(0).scene());
        assertEquals(2, result.get(0).shots().size());
        assertEquals("甲拔剑。\n乙后退。", result.get(0).plotContent());
    }

    @Test
    void parsesProfessionalShotsInsideScenesWrapper()
    {
        AidRolePropScene scene = scene(102L, "雨巷_夜晚");
        String output = """
                {"scenes":[{"sceneName":"雨巷_夜晚","shots":[
                  {"content":["镜头组：001","剧本内容：她撑伞走入雨巷。","画面说明：雨线清晰"]},
                  {"content":["镜头组：002","剧本内容：他从巷口追来。","画面说明：脚步急促"]}
                ]}]}
                """;

        List<StoryboardSceneEnvelopeParser.SceneEnvelope> result =
                parser.parse(output, List.of(scene), true);

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).shots().size());
        assertEquals("她撑伞走入雨巷。\n他从巷口追来。", result.get(0).plotContent());
    }

    @Test
    void rejectsUnknownSceneNameInsteadOfFuzzyBinding()
    {
        AidRolePropScene scene = scene(103L, "宅院正厅_上午");
        String output = """
                {"scenes":[{"sceneName":"宅院偏厅_上午","shots":[
                  {"scriptContent":"众人落座。"}
                ]}]}
                """;

        ServiceException error = assertThrows(ServiceException.class,
                () -> parser.parse(output, List.of(scene), false));
        assertEquals("场景名称未匹配", error.getMessage());
    }

    @Test
    void rejectsUnknownSceneEnvelopeFields()
    {
        AidRolePropScene scene = scene(116L, "庭院_上午");
        String output = """
                {"scenes":[{"sceneName":"庭院_上午","sceneCode":"001","shots":[
                  {"scriptContent":"甲入场。"}
                ]}]}
                """;

        assertThrows(ServiceException.class,
                () -> parser.parse(output, List.of(scene), false));
    }

    @Test
    void rejectsUnknownProfessionalShotFields()
    {
        AidRolePropScene scene = scene(117L, "庭院_上午");
        String output = """
                {"scenes":[{"sceneName":"庭院_上午","shots":[
                  {"content":["剧本内容：甲入场。"],"sceneCode":"001"}
                ]}]}
                """;

        assertThrows(ServiceException.class,
                () -> parser.parse(output, List.of(scene), true));
    }

    @Test
    void acceptsOrderedCompleteCoverageAcrossMultipleScenes()
    {
        AidRolePropScene first = scene(104L, "庭院_上午");
        AidRolePropScene second = scene(105L, "书房_上午");
        String output = """
                {"scenes":[
                  {"sceneName":"庭院_上午","shots":[{"scriptContent":"甲走过庭院。"}]},
                  {"sceneName":"书房_上午","shots":[{"scriptContent":"乙在书房翻书。"}]}
                ]}
                """;

        List<StoryboardSceneEnvelopeParser.SceneEnvelope> result = parser.parse(
                output, List.of(first, second), false, "甲走过庭院。\r\n乙在书房翻书。");

        assertEquals(2, result.size());
    }

    @Test
    void rejectsMissingRepeatedAndReorderedScriptCoverage()
    {
        AidRolePropScene scene = scene(106L, "庭院_上午");
        String missing = """
                {"scenes":[{"sceneName":"庭院_上午","shots":[{"scriptContent":"甲入场。"}]}]}
                """;
        String repeated = """
                {"scenes":[{"sceneName":"庭院_上午","shots":[
                  {"scriptContent":"甲入场。"},{"scriptContent":"甲入场。乙退场。"}
                ]}]}
                """;
        String reordered = """
                {"scenes":[{"sceneName":"庭院_上午","shots":[
                  {"scriptContent":"乙退场。"},{"scriptContent":"甲入场。"}
                ]}]}
                """;

        assertThrows(ServiceException.class,
                () -> parser.parse(missing, List.of(scene), false, "甲入场。乙退场。"));
        assertThrows(ServiceException.class,
                () -> parser.parse(repeated, List.of(scene), false, "甲入场。乙退场。"));
        assertThrows(ServiceException.class,
                () -> parser.parse(reordered, List.of(scene), false, "甲入场。乙退场。"));
    }

    @Test
    void allowsEmptyScenesOnlyForSelectiveChunkWithoutMatch()
    {
        AidRolePropScene scene = scene(107L, "庭院_上午");

        List<StoryboardSceneEnvelopeParser.SceneEnvelope> result = parser.parse(
                "{\"scenes\":[]}", List.of(scene), false, "此片段发生在书房。", true);

        assertEquals(0, result.size());
        assertThrows(ServiceException.class,
                () -> parser.parse("{\"scenes\":[]}", List.of(scene), false,
                        "此片段发生在书房。", false));
    }

    @Test
    void mergesAdjacentDuplicateSceneWrappersWithoutCreatingFakeSceneBoundary()
    {
        AidRolePropScene scene = scene(108L, "庭院_上午");
        String output = """
                {"scenes":[
                  {"sceneName":"庭院_上午","shots":[{"scriptContent":"甲入场。"}]},
                  {"sceneName":"庭院_上午","shots":[{"scriptContent":"乙退场。"}]}
                ]}
                """;

        List<StoryboardSceneEnvelopeParser.SceneEnvelope> result = parser.parse(
                output, List.of(scene), false, "甲入场。乙退场。");

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).shots().size());
    }

    @Test
    void acceptsOrderedNonOverlappingSpansForSelectiveGenerationAndRejectsReordering()
    {
        AidRolePropScene scene = scene(109L, "庭院_上午");
        String ordered = """
                {"scenes":[{"sceneName":"庭院_上午","shots":[
                  {"scriptContent":"甲入场。"},{"scriptContent":"丙离开。"}
                ]}]}
                """;
        String reordered = """
                {"scenes":[{"sceneName":"庭院_上午","shots":[
                  {"scriptContent":"丙离开。"},{"scriptContent":"甲入场。"}
                ]}]}
                """;
        String source = "甲入场。乙在书房翻书。丙离开。";

        assertEquals(1, parser.parse(ordered, List.of(scene), false, source, true).size());
        assertThrows(ServiceException.class,
                () -> parser.parse(reordered, List.of(scene), false, source, true));
    }

    @Test
    void selectiveCoverageRequiresEachShotToBeAContinuousOriginalSpan()
    {
        AidRolePropScene scene = scene(112L, "庭院_上午");
        String source = "甲在庭院。乙去书房。丙回到庭院。";
        String validWithGap = """
                {"scenes":[{"sceneName":"庭院_上午","shots":[
                  {"scriptContent":"甲在庭院。"},{"scriptContent":"丙回到庭院。"}
                ]}]}
                """;
        String inventedByCharacterSubsequence = """
                {"scenes":[{"sceneName":"庭院_上午","shots":[
                  {"scriptContent":"甲书房"}
                ]}]}
                """;

        assertEquals(1, parser.parse(validWithGap, List.of(scene), false, source, true).size());
        assertThrows(ServiceException.class,
                () -> parser.parse(inventedByCharacterSubsequence,
                        List.of(scene), false, source, true));
    }

    @Test
    void selectiveGenerationKeepsRepeatedSceneWrappersAcrossSkippedTextSeparate()
    {
        AidRolePropScene scene = scene(115L, "庭院_上午");
        String output = """
                {"scenes":[
                  {"sceneName":"庭院_上午","shots":[{"scriptContent":"甲入场。"}]},
                  {"sceneName":"庭院_上午","shots":[{"scriptContent":"丙回到庭院。"}]}
                ]}
                """;

        List<StoryboardSceneEnvelopeParser.SceneEnvelope> result = parser.parse(
                output, List.of(scene), false, "甲入场。乙在书房。丙回到庭院。", true);

        assertEquals(2, result.size());
    }

    @Test
    void treatsPromptRequiredQuoteNormalizationAsCoverageEquivalent()
    {
        AidRolePropScene scene = scene(113L, "庭院_上午");
        String output = """
                {"scenes":[{"sceneName":"庭院_上午","shots":[
                  {"scriptContent":"甲说：「你好。」"},
                  {"scriptContent":"乙答：「早。」"}
                ]}]}
                """;

        assertEquals(1, parser.parse(output, List.of(scene), false,
                "甲说：“你好。”乙答：『早。』").size());
        assertEquals(1, parser.parse(
                "{\"scenes\":[{\"sceneName\":\"庭院_上午\",\"shots\":[{\"scriptContent\":\"乙答：「早。」\"}]}]}",
                List.of(scene), false, "甲说：‘你好。’乙答：\"早。\"", true).size());
    }

    @Test
    void acceptsEachNonProfessionalPromptFieldSetWithoutChangingShotSchema()
    {
        AidRolePropScene scene = scene(114L, "庭院_上午");
        String standard = """
                {"scenes":[{"sceneName":"庭院_上午","shots":[{"shotNumber":"001","scriptContent":"甲入场。","dialogue":"","visualDescription":"","actionState":"","narrativeFunction":"","timeOfDay":"","eraCoordinate":"","dateCoordinate":"","weather":"","referenceAssets":"","shotSize":"","cameraAngle":"","focalLength":"","cameraMovement":"","composition":"","atmosphere":"","colorTone":"","lighting":"","exposureBlur":"","soundEffect":""}]}]}
                """;
        String simple = """
                {"scenes":[{"sceneName":"庭院_上午","shots":[{"shotNumber":"001","scriptContent":"甲入场。","visualDescription":"","dialogue":"","referenceAssets":"","shotSize":"","cameraAngle":"","focalLength":"","cameraMovement":""}]}]}
                """;
        String commentary = """
                {"scenes":[{"sceneName":"庭院_上午","shots":[{"shotNumber":"001","scriptContent":"甲入场。","visualDescription":"","dialogue":"旁白","actionState":"","narrativeFunction":"","timeOfDay":"","eraCoordinate":"","dateCoordinate":"","weather":"","referenceAssets":"","shotSize":"","cameraAngle":"","focalLength":"","cameraMovement":"","composition":"","atmosphere":"","soundEffect":""}]}]}
                """;
        String commentarySimple = """
                {"scenes":[{"sceneName":"庭院_上午","shots":[{"shotNumber":"001","scriptContent":"甲入场。","visualDescription":"","dialogue":"旁白","referenceAssets":"","shotSize":"","cameraAngle":"","focalLength":"","cameraMovement":""}]}]}
                """;

        assertEquals(1, parser.parse(standard, List.of(scene), false, "甲入场。").size());
        assertEquals(1, parser.parse(simple, List.of(scene), false, "甲入场。").size());
        assertEquals(1, parser.parse(commentary, List.of(scene), false, "甲入场。").size());
        assertEquals(1, parser.parse(commentarySimple, List.of(scene), false, "甲入场。").size());
    }

    @Test
    void rejectsSceneAssetsWithConflictingNormalizedNames()
    {
        AidRolePropScene first = scene(110L, "天衡 剑宫广场_早晨");
        AidRolePropScene second = scene(111L, "天衡剑宫广场_早晨");
        String output = """
                {"scenes":[{"sceneName":"天衡剑宫广场_早晨","shots":[
                  {"scriptContent":"众人集结。"}
                ]}]}
                """;

        assertThrows(ServiceException.class,
                () -> parser.parse(output, List.of(first, second), false));
    }

    private AidRolePropScene scene(Long id, String name)
    {
        AidRolePropScene scene = new AidRolePropScene();
        scene.setId(id);
        scene.setName(name);
        return scene;
    }
}
