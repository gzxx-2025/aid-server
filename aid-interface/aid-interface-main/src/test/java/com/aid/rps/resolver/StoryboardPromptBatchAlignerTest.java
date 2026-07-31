package com.aid.rps.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class StoryboardPromptBatchAlignerTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BUSINESS_NO_FIELD = "镜号";

    @Test
    void shouldAlignOutOfOrderElementsByStoryboardIdentity() throws Exception
    {
        List<StoryboardPromptBatchAligner.Target> targets = List.of(
                new StoryboardPromptBatchAligner.Target(101L, "001"),
                new StoryboardPromptBatchAligner.Target(205L, "002"));
        List<JsonNode> elements = List.of(
                json("{\"shotKey\":\"SB-205\",\"镜号\":\"2\",\"prompt\":\"second\"}"),
                json("{\"shotKey\":\"sb-101\",\"镜号\":\"1\",\"prompt\":\"first\"}"));

        StoryboardPromptBatchAligner.AlignmentResult result =
                StoryboardPromptBatchAligner.align(elements, targets, BUSINESS_NO_FIELD);

        assertTrue(result.valid());
        assertEquals("first", result.elements().get(0).path("prompt").asText());
        assertEquals("second", result.elements().get(1).path("prompt").asText());
    }

    @Test
    void shouldRejectBusinessNumberUsedAsPosition() throws Exception
    {
        List<JsonNode> elements = List.of(
                json("{\"shotIndex\":1,\"镜号\":\"1\",\"prompt\":\"first\"}"),
                json("{\"shotIndex\":2,\"镜号\":\"2\",\"prompt\":\"second\"}"));

        StoryboardPromptBatchAligner.AlignmentResult result = StoryboardPromptBatchAligner.align(
                elements, targets(), BUSINESS_NO_FIELD);

        assertFalse(result.valid());
        assertTrue(result.reason().startsWith("missing_shot_key"));
        assertTrue(result.elements().isEmpty());
    }

    @Test
    void shouldRejectZeroBasedIndexesWithoutIdentityKey() throws Exception
    {
        List<JsonNode> elements = List.of(
                json("{\"shotIndex\":0,\"镜号\":\"1\",\"prompt\":\"first\"}"),
                json("{\"shotIndex\":1,\"镜号\":\"2\",\"prompt\":\"second\"}"));

        StoryboardPromptBatchAligner.AlignmentResult result = StoryboardPromptBatchAligner.align(
                elements, targets(), BUSINESS_NO_FIELD);

        assertFalse(result.valid());
        assertTrue(result.reason().startsWith("missing_shot_key"));
    }

    @Test
    void shouldRejectPartiallyKeyedBatchWithoutReturningPartialResults() throws Exception
    {
        List<JsonNode> elements = List.of(
                json("{\"shotKey\":\"SB-101\",\"镜号\":\"1\",\"prompt\":\"first\"}"),
                json("{\"镜号\":\"2\",\"prompt\":\"second\"}"));

        StoryboardPromptBatchAligner.AlignmentResult result = StoryboardPromptBatchAligner.align(
                elements, targets(), BUSINESS_NO_FIELD);

        assertFalse(result.valid());
        assertTrue(result.reason().startsWith("missing_shot_key"));
        assertTrue(result.elements().isEmpty());
    }

    @Test
    void shouldRejectDuplicateIdentityKey() throws Exception
    {
        List<JsonNode> elements = List.of(
                json("{\"shotKey\":\"SB-101\",\"镜号\":\"1\",\"prompt\":\"first\"}"),
                json("{\"shotKey\":\"SB-101\",\"镜号\":\"1\",\"prompt\":\"duplicate\"}"));

        StoryboardPromptBatchAligner.AlignmentResult result = StoryboardPromptBatchAligner.align(
                elements, targets(), BUSINESS_NO_FIELD);

        assertFalse(result.valid());
        assertTrue(result.reason().startsWith("duplicate_shot_key"));
    }

    @Test
    void shouldRejectUnknownIdentityKey() throws Exception
    {
        List<JsonNode> elements = List.of(
                json("{\"shotKey\":\"SB-101\",\"镜号\":\"1\",\"prompt\":\"first\"}"),
                json("{\"shotKey\":\"SB-999\",\"镜号\":\"2\",\"prompt\":\"unknown\"}"));

        StoryboardPromptBatchAligner.AlignmentResult result = StoryboardPromptBatchAligner.align(
                elements, targets(), BUSINESS_NO_FIELD);

        assertFalse(result.valid());
        assertTrue(result.reason().startsWith("unknown_shot_key"));
    }

    @Test
    void shouldRejectCountMismatchBeforeAlignment() throws Exception
    {
        List<JsonNode> elements = List.of(
                json("{\"shotKey\":\"SB-101\",\"镜号\":\"1\",\"prompt\":\"first\"}"));

        StoryboardPromptBatchAligner.AlignmentResult result = StoryboardPromptBatchAligner.align(
                elements, targets(), BUSINESS_NO_FIELD);

        assertFalse(result.valid());
        assertTrue(result.reason().startsWith("count_mismatch"));
    }

    @Test
    void shouldRejectCrossShotBusinessNumber() throws Exception
    {
        List<JsonNode> elements = List.of(
                json("{\"shotKey\":\"SB-101\",\"镜号\":\"2\",\"prompt\":\"wrong\"}"),
                json("{\"shotKey\":\"SB-205\",\"镜号\":\"1\",\"prompt\":\"wrong\"}"));

        StoryboardPromptBatchAligner.AlignmentResult result = StoryboardPromptBatchAligner.align(
                elements, targets(), BUSINESS_NO_FIELD);

        assertFalse(result.valid());
        assertTrue(result.reason().startsWith("business_number_mismatch"));
        assertTrue(result.elements().isEmpty());
    }

    private static List<StoryboardPromptBatchAligner.Target> targets()
    {
        return List.of(
                new StoryboardPromptBatchAligner.Target(101L, "1"),
                new StoryboardPromptBatchAligner.Target(205L, "2"));
    }

    private static JsonNode json(String value) throws Exception
    {
        return OBJECT_MAPPER.readTree(value);
    }
}
