package com.aid.media.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ViduResultUrlResolverTest {

    @Test
    void shouldResolveOnlyExplicitCreationUrl() {
        JsonNode root = ProviderResponseHelper.readTree(
            "{\"images\":[\"https://input.example.com/image.png\"],"
                + "\"callback_url\":\"https://aid.example.com/api/media/callback/vidu\","
                + "\"creations\":[{\"url\":\"https://output.example.com/video.mp4\"}]}");

        assertEquals("https://output.example.com/video.mp4", ViduResultUrlResolver.resolve(root));
    }

    @Test
    void shouldIgnoreInputAndCallbackUrlsWhenCreationIsMissing() {
        JsonNode root = ProviderResponseHelper.readTree(
            "{\"image_url\":\"https://input.example.com/image.png\","
                + "\"video_url\":\"https://input.example.com/video.mp4\","
                + "\"callback_url\":\"https://aid.example.com/api/media/callback/vidu\"}");

        assertNull(ViduResultUrlResolver.resolve(root));
    }

    @Test
    void shouldKeepSucceededTaskProcessingUntilCreationUrlIsReady() {
        assertEquals("PROCESSING", ViduResultUrlResolver.resolveReadyStatus("SUCCEEDED", null));
        assertEquals("PROCESSING", ViduResultUrlResolver.resolveReadyStatus("SUCCEEDED", " "));
    }

    @Test
    void shouldKeepSucceededStatusWhenCreationUrlIsReady() {
        assertEquals("SUCCEEDED", ViduResultUrlResolver.resolveReadyStatus(
            "SUCCEEDED", "https://output.example.com/result.mp4"));
    }
}
