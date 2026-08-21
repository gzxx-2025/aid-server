package com.aid.media.provider.impl;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.MinimaxH3Constants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceAudioInput;
import com.aid.media.provider.MinimaxH3VideoRequestBuilder;
import com.aid.media.provider.ProviderTaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinimaxH3VideoProviderClientTest {

    @Test
    void baseUrlAllowsProxyOriginsButRejectsUnsafeGatewayShapes() {
        AiModelConfigVo config = config(MinimaxH3Constants.MODEL_T2V, 0);
        MinimaxH3VideoProviderClient.validateBaseUrl(config);
        for (String safe : List.of("https://api.minimaxi.com.evil.test", "http://proxy.test:8080",
            "https://proxy.test:8443")) {
            config.setBaseUrl(safe);
            assertDoesNotThrow(() -> MinimaxH3VideoProviderClient.validateBaseUrl(config));
        }
        for (String unsafe : List.of("ftp://api.minimaxi.com", "https://user:pass@api.minimaxi.com",
            "https://api.minimaxi.com/proxy", "https://api.minimaxi.com?x=1")) {
            config.setBaseUrl(unsafe);
            assertThrows(ServiceException.class, () -> MinimaxH3VideoProviderClient.validateBaseUrl(config));
        }
    }

    @Test
    void buildsFiveExplicitScenesAndReadsOptionDuration() {
        Map<String, Object> text = body(MinimaxH3Constants.MODEL_T2V, request(null, Map.of("duration", 7)), 0);
        assertEquals("16:9", text.get("ratio"));
        assertEquals(7, text.get("duration"));
        assertEquals(1, content(text).size());

        Map<String, Object> first = body(MinimaxH3Constants.MODEL_I2V_FIRST, request("first", Map.of()), 1);
        assertEquals("first_frame", content(first).get(1).get("role"));
        assertEquals("adaptive", first.get("ratio"));

        Map<String, Object> last = body(MinimaxH3Constants.MODEL_I2V_LAST, request("last", Map.of()), 1);
        assertEquals("last_frame", content(last).get(1).get("role"));

        Map<String, Object> firstLast = body(MinimaxH3Constants.MODEL_I2V_FIRST_LAST,
            request("first", Map.of("lastFrameImageUrl", "last")), 2);
        assertEquals("first_frame", content(firstLast).get(1).get("role"));
        assertEquals("last_frame", content(firstLast).get(2).get("role"));

        MediaVideoGenerateRequest reference = request(null, new LinkedHashMap<>(Map.of(
            "images", List.of("i1", "i2"), "referenceVideos", List.of("v1"))));
        ReferenceAudioInput audio = new ReferenceAudioInput();
        audio.setSampleUrl("a1");
        reference.setReferenceAudios(List.of(audio));
        Map<String, Object> ref = body(MinimaxH3Constants.MODEL_REFERENCE, reference, 9);
        assertEquals(List.of("text", "image_url", "image_url", "video_url", "audio_url"),
            content(ref).stream().map(item -> String.valueOf(item.get("type"))).toList());
    }

    @Test
    void submissionBodyUsesSanitizedPrompt() {
        AiModelConfigVo config = config(MinimaxH3Constants.MODEL_REFERENCE, 9);
        MediaVideoGenerateRequest request = request(null,
            Map.of("images", List.of("https://cdn.test/reference.png")));
        request.setPrompt("参考@图片1[角色]生成镜头\n---参考图映射---\n图1=https://cdn.test/reference.png");

        MinimaxH3VideoRequestBuilder.sanitizePrompt(config, request);
        Map<String, Object> body = MinimaxH3VideoRequestBuilder.buildSubmissionBody(config, request);

        assertEquals("参考图片1生成镜头", request.getPrompt());
        assertEquals("参考图片1生成镜头", content(body).get(0).get("text"));
    }

    @Test
    void rejectsFrameReferenceMixVideoOverflowAndAmbiguousLastFrame() {
        assertConcise(assertThrows(ServiceException.class, () -> body(MinimaxH3Constants.MODEL_I2V_FIRST,
            request("first", Map.of("referenceVideos", List.of("video"))), 1)));
        assertConcise(assertThrows(ServiceException.class, () -> body(MinimaxH3Constants.MODEL_REFERENCE,
            request(null, Map.of("referenceVideos", List.of("v1", "v2", "v3", "v4"))), 9)));
        assertConcise(assertThrows(ServiceException.class, () -> body(MinimaxH3Constants.MODEL_I2V_LAST,
            request("last-a", Map.of("lastFrameImageUrl", "last-b")), 1)));
        assertConcise(assertThrows(ServiceException.class, () -> body(MinimaxH3Constants.MODEL_I2V_FIRST_LAST,
            request("same", Map.of("lastFrameImageUrl", "same")), 2)));
    }

    @Test
    void allRequestBuilderValidationMessagesStayConcise() {
        MediaVideoGenerateRequest blankPrompt = request(null, Map.of());
        blankPrompt.setPrompt(" ");
        MediaVideoGenerateRequest longPrompt = request(null, Map.of());
        longPrompt.setPrompt("x".repeat(7001));

        List<Executable> invalidRequests = List.of(
            () -> MinimaxH3VideoRequestBuilder.buildSubmissionBody(null, null),
            () -> body(MinimaxH3Constants.MODEL_T2V, blankPrompt, 0),
            () -> body(MinimaxH3Constants.MODEL_T2V, longPrompt, 0),
            () -> body(MinimaxH3Constants.MODEL_T2V, request("image", Map.of()), 0),
            () -> body(MinimaxH3Constants.MODEL_I2V_FIRST, request(null, Map.of()), 1),
            () -> body(MinimaxH3Constants.MODEL_I2V_FIRST,
                request("first", Map.of("lastFrameImageUrl", "last")), 1),
            () -> body(MinimaxH3Constants.MODEL_I2V_LAST,
                request("last-a", Map.of("lastFrameImageUrl", "last-b")), 1),
            () -> body(MinimaxH3Constants.MODEL_I2V_LAST, request(null, Map.of()), 1),
            () -> body(MinimaxH3Constants.MODEL_I2V_FIRST_LAST, request("first", Map.of()), 2),
            () -> body(MinimaxH3Constants.MODEL_I2V_FIRST_LAST,
                request("same", Map.of("lastFrameImageUrl", "same")), 2),
            () -> body(MinimaxH3Constants.MODEL_REFERENCE,
                request(null, Map.of("lastFrameImageUrl", "last")), 9),
            () -> body(MinimaxH3Constants.MODEL_REFERENCE, request(null, Map.of()), 9),
            () -> body(MinimaxH3Constants.MODEL_I2V_FIRST,
                request("first", Map.of("referenceVideos", List.of("video"))), 1),
            () -> body(MinimaxH3Constants.MODEL_REFERENCE,
                request(null, Map.of("referenceVideos", List.of("v1", "v2", "v3", "v4"))), 9),
            () -> body(MinimaxH3Constants.MODEL_I2V_FIRST, request("first", Map.of()), 0),
            () -> body(MinimaxH3Constants.MODEL_T2V,
                request(null, Map.of("resolution", "4K")), 0),
            () -> body(MinimaxH3Constants.MODEL_T2V,
                request(null, Map.of("duration", "4.5")), 0),
            () -> body(MinimaxH3Constants.MODEL_T2V,
                request(null, Map.of("duration", 16)), 0),
            () -> {
                MediaVideoGenerateRequest invalidRatio = request(null, Map.of());
                invalidRatio.setAspectRatio("adaptive");
                body(MinimaxH3Constants.MODEL_T2V, invalidRatio, 0);
            },
            () -> body("minimax-h3-unknown", request(null, Map.of()), 0)
        );

        for (Executable invalidRequest : invalidRequests) {
            assertConcise(assertThrows(ServiceException.class, invalidRequest));
        }
    }

    @Test
    void parsesOfficialUsageForRefundOnlySettlement() {
        ProviderTaskResult result = MinimaxH3VideoProviderClient.parseQueryResponse(200,
            "{\"task\":{\"id\":\"t1\",\"model\":\"MiniMax-H3\","
                + "\"task_type\":\"generation\",\"modality\":\"video\",\"status\":\"succeeded\","
                + "\"content\":{\"url\":\"https://cdn.test/out.mp4\"},"
                + "\"usage\":{\"output_seconds\":6,\"input_seconds\":3,\"input_image_count\":7}}}",
            "t1", MinimaxH3Constants.MODEL_REFERENCE);
        assertEquals("SUCCEEDED", result.getStatus());
        assertEquals(6, result.getVideoDurationSeconds());
        assertEquals(3, result.getInputVideoSeconds());
        assertEquals(7, result.getInputImageCount());
        assertEquals(Boolean.TRUE, result.getTerminalConfirmed());

        ProviderTaskResult ignored = MinimaxH3VideoProviderClient.parseQueryResponse(200,
            "{\"task\":{\"id\":\"t2\",\"model\":\"MiniMax-H3\","
                + "\"task_type\":\"regeneration\",\"modality\":\"video\",\"status\":\"succeeded\","
                + "\"content\":{\"url\":\"https://cdn.test/regenerated.mp4\"}}}",
            "t2", MinimaxH3Constants.MODEL_REFERENCE);
        assertEquals(Boolean.FALSE, ignored.getQuerySuccessful());
        assertEquals(Boolean.FALSE, ignored.getTerminalConfirmed());

        ProviderTaskResult incompleteUsage = MinimaxH3VideoProviderClient.parseQueryResponse(200,
            "{\"task\":{\"id\":\"t3\",\"model\":\"MiniMax-H3\","
                + "\"task_type\":\"generation\",\"modality\":\"video\",\"status\":\"succeeded\","
                + "\"duration\":6,"
                + "\"content\":{\"url\":\"https://cdn.test/out.mp4\"},"
                + "\"usage\":{}}}", "t3", MinimaxH3Constants.MODEL_T2V);
        assertEquals("SUCCEEDED", incompleteUsage.getStatus());
        assertEquals(6, incompleteUsage.getVideoDurationSeconds());
        assertEquals(0, incompleteUsage.getInputVideoSeconds());
        assertEquals(0, incompleteUsage.getInputImageCount());
        assertEquals(Boolean.TRUE, incompleteUsage.getTerminalConfirmed());

        ProviderTaskResult noOfficialDuration = MinimaxH3VideoProviderClient.parseQueryResponse(200,
            "{\"task\":{\"id\":\"t4\",\"model\":\"MiniMax-H3\","
                + "\"task_type\":\"generation\",\"modality\":\"video\",\"status\":\"succeeded\","
                + "\"content\":{\"url\":\"https://cdn.test/out.mp4\"},\"usage\":{}}}",
            "t4", MinimaxH3Constants.MODEL_REFERENCE);
        assertEquals("PROCESSING", noOfficialDuration.getStatus());
        assertEquals(Boolean.FALSE, noOfficialDuration.getQuerySuccessful());
        assertEquals(Boolean.FALSE, noOfficialDuration.getTerminalConfirmed());

        ProviderTaskResult referenceInputUsageMissing = MinimaxH3VideoProviderClient.parseQueryResponse(200,
            "{\"task\":{\"id\":\"t5\",\"model\":\"MiniMax-H3\","
                + "\"task_type\":\"generation\",\"modality\":\"video\",\"status\":\"succeeded\","
                + "\"duration\":6,\"content\":{\"url\":\"https://cdn.test/out.mp4\"},"
                + "\"usage\":{\"output_seconds\":6}}}",
            "t5", MinimaxH3Constants.MODEL_REFERENCE);
        assertEquals("PROCESSING", referenceInputUsageMissing.getStatus());
        assertEquals("输入用量尚未就绪", referenceInputUsageMissing.getErrorMessage());
        assertTrue(referenceInputUsageMissing.getErrorMessage().codePointCount(
            0, referenceInputUsageMissing.getErrorMessage().length()) <= 12);
        assertEquals(Boolean.FALSE, referenceInputUsageMissing.getQuerySuccessful());
        assertEquals(Boolean.FALSE, referenceInputUsageMissing.getTerminalConfirmed());

        ProviderTaskResult firstFrameZeroOmission = MinimaxH3VideoProviderClient.parseQueryResponse(200,
            "{\"task\":{\"id\":\"t6\",\"model\":\"MiniMax-H3\","
                + "\"task_type\":\"generation\",\"modality\":\"video\",\"status\":\"succeeded\","
                + "\"content\":{\"url\":\"https://cdn.test/out.mp4\"},"
                + "\"usage\":{\"output_seconds\":6}}}",
            "t6", MinimaxH3Constants.MODEL_I2V_FIRST);
        assertEquals("SUCCEEDED", firstFrameZeroOmission.getStatus());
        assertEquals(0, firstFrameZeroOmission.getInputVideoSeconds());
        assertEquals(1, firstFrameZeroOmission.getInputImageCount());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> content(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("content");
    }

    private Map<String, Object> body(String code, MediaVideoGenerateRequest request, int maxImages) {
        AiModelConfigVo config = config(code, maxImages);
        MinimaxH3VideoRequestBuilder.sanitizePrompt(config, request);
        return MinimaxH3VideoRequestBuilder.buildSubmissionBody(config, request);
    }

    private MediaVideoGenerateRequest request(String imageUrl, Map<String, Object> options) {
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setPrompt("test prompt");
        request.setImageUrl(imageUrl);
        request.setOptions(options);
        return request;
    }

    private AiModelConfigVo config(String code, int maxImages) {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setModelCode(code);
        config.setBaseUrl("https://api.minimaxi.com");
        config.setApiSuffix("/v2/video_generation");
        config.setTaskQuerySuffix("/v2/query/video_generation/%s");
        config.setCapabilityJson("{\"maxReferenceImages\":" + maxImages
            + ",\"supportsReferenceAudio\":true,\"maxReferenceAudios\":3,"
            + "\"referenceAudioFormats\":[\"wav\",\"mp3\"]}");
        config.setSupportsCallback(false);
        return config;
    }

    private void assertConcise(ServiceException exception) {
        String message = exception.getMessage();
        assertTrue(message != null && message.codePointCount(0, message.length()) <= 12,
            () -> "client message is too long: " + message);
    }
}
