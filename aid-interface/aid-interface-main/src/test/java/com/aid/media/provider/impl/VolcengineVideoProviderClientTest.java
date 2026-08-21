package com.aid.media.provider.impl;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceAudioInput;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.ProviderSubmitResult;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VolcengineVideoProviderClientTest {

    private final VolcengineVideoProviderClient client = new VolcengineVideoProviderClient();

    @Test
    void endpointConfigurationControlsCompleteProxyPaths() {
        AiModelConfigVo config = config("text");
        config.setBaseUrl("https://proxy.test");
        config.setApiSuffix("/tenant/ark/v9/create-video");
        config.setTaskQuerySuffix("/tenant/ark/v9/video/%s/status");

        assertEquals("https://proxy.test/tenant/ark/v9/create-video",
                VolcengineVideoProviderClient.buildSubmitUrl(config));
        assertEquals("https://proxy.test/tenant/ark/v9/video/a%2Fb/status",
                VolcengineVideoProviderClient.buildQueryUrl(config, "a/b"));
    }

    @Test
    void rawHttpTransportPreservesSdkJsonContractForSubmitAndQuery() throws Exception {
        AtomicReference<String> submitBody = new AtomicReference<>();
        AtomicReference<String> submitAuth = new AtomicReference<>();
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handleLocalContract(
                exchange, submitBody, submitAuth, handlerFailure));
        server.start();
        try {
            AiModelConfigVo config = config("text");
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            config.setApiSuffix("/proxy/ark/v9/video/tasks");
            config.setTaskQuerySuffix("/proxy/ark/v9/video/tasks/%s");
            config.setBillingRuleJson(tokenVideoBillingRule());

            ProviderSubmitResult submitted = client.submit(config, request());
            ProviderTaskResult queried = client.query(config, submitted.getProviderTaskId());

            if (handlerFailure.get() != null) {
                throw new AssertionError(handlerFailure.get());
            }
            assertEquals("task-local", submitted.getProviderTaskId());
            assertEquals("SUCCEEDED", queried.getStatus());
            assertEquals("Bearer test-key", submitAuth.get());
            JsonNode body = new ObjectMapper().readTree(submitBody.get());
            assertEquals("doubao-seedance-2-5-260628", body.path("model").asText());
            assertEquals("text", body.path("content").get(0).path("type").asText());
            assertEquals("test prompt", body.path("content").get(0).path("text").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void buildsAllSixSeedance25SceneContracts() {
        MediaVideoGenerateRequest text = request();
        assertEquals(List.of("text"), client.buildContents(text, config("text")).stream()
                .map(CreateContentGenerationTaskRequest.Content::getType).toList());

        MediaVideoGenerateRequest first = request();
        first.setImageUrl("https://cdn.test/first.png");
        List<CreateContentGenerationTaskRequest.Content> firstContents = client.buildContents(first, config("first_frame"));
        assertEquals("first_frame", firstContents.get(1).getRole());
        client.buildCreateRequest("doubao-seedance-2-5-260628", firstContents, first, config("first_frame"));

        MediaVideoGenerateRequest firstLast = request();
        firstLast.setImageUrl("https://cdn.test/first.png");
        firstLast.setOptions(Map.of("resolution", "720P", "lastFrameImageUrl", "https://cdn.test/last.png"));
        List<CreateContentGenerationTaskRequest.Content> firstLastContents =
                client.buildContents(firstLast, config("first_last_frame"));
        assertEquals(List.of("first_frame", "last_frame"), firstLastContents.stream().skip(1)
                .map(CreateContentGenerationTaskRequest.Content::getRole).toList());

        for (String scene : List.of("edit", "extend")) {
            AiModelConfigVo sceneConfig = config(scene);
            MediaVideoGenerateRequest sceneRequest = request();
            sceneRequest.setPrompt("向后延长 @video1");
            sceneRequest.setOptions(Map.of("resolution", "720P", "referenceVideoUrl", "https://cdn.test/base.mov"));
            List<CreateContentGenerationTaskRequest.Content> sceneContents = client.buildContents(sceneRequest, sceneConfig);
            CreateContentGenerationTaskRequest built = client.buildCreateRequest(
                    sceneConfig.getRealModelCode(), sceneContents, sceneRequest, sceneConfig);
            assertEquals(scene, built.getOmniReferenceTaskType());
            assertEquals("reference_video", sceneContents.get(1).getRole());
            assertEquals("向后延长 @video1", sceneContents.get(0).getText());
            assertEquals("mov", built.getOutputFormat());
        }
    }

    @Test
    void buildsReferenceVideoAudioAndOmniContract() {
        AiModelConfigVo config = config("reference");
        MediaVideoGenerateRequest request = request();
        request.setImageUrl("https://cdn.test/i.png");
        request.setOptions(Map.of("referenceVideos", List.of("https://cdn.test/v.mov"),
                "output_format", "mov", "resolution", "720P"));
        ReferenceAudioInput audio = new ReferenceAudioInput();
        audio.setSampleUrl("https://cdn.test/a.mp3");
        request.setReferenceAudios(List.of(audio));

        List<CreateContentGenerationTaskRequest.Content> contents = client.buildContents(request, config);
        assertEquals(List.of("text", "image_url", "video_url", "audio_url"),
                contents.stream().map(CreateContentGenerationTaskRequest.Content::getType).toList());
        assertEquals("reference_video", contents.get(2).getRole());

        CreateContentGenerationTaskRequest built = client.buildCreateRequest(
                config.getRealModelCode(), contents, request, config);
        assertEquals("auto", built.getOmniReferenceTaskType());
        assertEquals("mov", built.getOutputFormat());
        assertEquals(-1L, built.getDuration());
    }

    @Test
    void referenceSceneSupportsPureAudio() {
        AiModelConfigVo config = config("reference");
        MediaVideoGenerateRequest request = request();
        request.setOptions(Map.of("resolution", "480P"));
        ReferenceAudioInput audio = new ReferenceAudioInput();
        audio.setSampleUrl("https://cdn.test/a.wav");
        request.setReferenceAudios(List.of(audio));
        assertEquals(List.of("text", "audio_url"), client.buildContents(request, config).stream()
                .map(CreateContentGenerationTaskRequest.Content::getType).toList());
    }

    @Test
    void legacySeedance20ActuallySendsBilledReferenceVideo() {
        AiModelConfigVo config = config(null);
        config.setCapabilityJson("{\"maxReferenceImages\":9,\"maxReferenceVideos\":3}");
        MediaVideoGenerateRequest request = request();
        request.setAspectRatio("16:9");
        request.setDurationSeconds(5);
        request.setOptions(Map.of("referenceVideoUrl", "https://cdn.test/legacy.mp4"));
        List<CreateContentGenerationTaskRequest.Content> contents = client.buildContents(request, config);
        assertEquals("video_url", contents.get(1).getType());
        assertEquals("reference_video", contents.get(1).getRole());
    }

    @Test
    void rejectsDirectApiAttemptToBypassLockedEditParameters() {
        AiModelConfigVo config = config("edit");
        MediaVideoGenerateRequest request = request();
        request.setAspectRatio("16:9");
        request.setDurationSeconds(5);
        request.setOptions(Map.of("referenceVideoUrl", "https://cdn.test/edit.mov", "resolution", "720P"));
        assertThrows(ServiceException.class, () -> client.buildCreateRequest(
                config.getRealModelCode(), client.buildContents(request, config), request, config));
    }

    @Test
    void seedancePromptPreservesOfficialIndexesAndConvertsInternalPlaceholders() {
        AiModelConfigVo config = config("edit");
        MediaVideoGenerateRequest request = request();
        request.setImageUrl("https://cdn.test/ref.png");
        request.setPrompt("向后延长 @video1，参考 @图片1[角色] 和 @音频1[音频-旁白]，@中景");
        request.setOptions(Map.of("resolution", "720P", "referenceVideoUrl", "https://cdn.test/edit.mov"));
        ReferenceAudioInput audio = new ReferenceAudioInput();
        audio.setSampleUrl("https://cdn.test/ref.mp3");
        request.setReferenceAudios(List.of(audio));

        List<CreateContentGenerationTaskRequest.Content> contents = client.buildContents(request, config);

        assertEquals("向后延长 @video1，参考 @image1 和 @audio1，中景", contents.get(0).getText());
    }

    @Test
    void firstFrameRejectsLastFrameInsteadOfSilentlyIgnoringIt() {
        AiModelConfigVo config = config("first_frame");
        MediaVideoGenerateRequest request = request();
        request.setImageUrl("https://cdn.test/first.png");
        request.setOptions(Map.of("resolution", "720P", "lastFrameImageUrl", "https://cdn.test/last.png"));

        assertThrows(ServiceException.class, () -> client.buildContents(request, config));
    }

    @Test
    void textSceneRejectsRawVideoAndAudioEvenWhenCapabilityLimitIsZero() {
        AiModelConfigVo config = config("text");
        config.setCapabilityJson("{\"videoScenario\":\"text\",\"maxReferenceImages\":0,"
                + "\"maxReferenceVideos\":0,\"maxReferenceAudios\":0,\"defaultOutputFormat\":\"mp4\"}");
        MediaVideoGenerateRequest request = request();
        request.setOptions(Map.of("resolution", "720P", "referenceVideoUrl", "https://cdn.test/raw.mp4"));
        ReferenceAudioInput audio = new ReferenceAudioInput();
        audio.setSampleUrl("https://cdn.test/raw.mp3");
        request.setReferenceAudios(List.of(audio));

        assertThrows(ServiceException.class,
                () -> VolcengineVideoProviderClient.validateFullRequest(config, request));
        assertThrows(ServiceException.class, () -> client.buildContents(request, config));
    }

    @Test
    void queryCarriesProviderUsageIntoNormalizedResult() throws Exception {
        AiModelConfigVo config = config("text");
        config.setBillingRuleJson(tokenVideoBillingRule());
        ProviderTaskResult result = client.parseQueryResponse(config,
                "{\"status\":\"succeeded\",\"duration\":7,"
                        + "\"content\":{\"video_url\":\"https://cdn.test/out.mp4\"},"
                        + "\"usage\":{\"completion_tokens\":194400,\"total_tokens\":194400}}",
                "task-1");
        assertEquals(7, result.getVideoDurationSeconds());
        assertEquals(194400, result.getCompletionTokens());
        assertEquals(194400, result.getTotalTokens());
        assertEquals(Boolean.TRUE, result.getTerminalConfirmed());
    }

    @Test
    void succeededTokenVideoWithoutUsageKeepsPollingInsteadOfSettlingPrehold() throws Exception {
        AiModelConfigVo config = config("text");
        config.setBillingRuleJson(tokenVideoBillingRule());

        ProviderTaskResult result = client.parseQueryResponse(config,
                "{\"status\":\"succeeded\","
                        + "\"content\":{\"video_url\":\"https://cdn.test/out.mp4\"}}",
                "task-no-usage");

        assertEquals("PROCESSING", result.getStatus());
        assertEquals(Boolean.FALSE, result.getQuerySuccessful());
        assertEquals(Boolean.FALSE, result.getTerminalConfirmed());
        assertEquals(null, result.getCompletionTokens());
    }

    private AiModelConfigVo config(String scene) {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setModelCode("doubao-seedance-2.5-" + (scene == null ? "legacy" : scene));
        config.setRealModelCode("doubao-seedance-2-5-260628");
        config.setBaseUrl("https://ark.cn-beijing.volces.com");
        config.setApiSuffix("/api/v3/contents/generations/tasks");
        config.setTaskQuerySuffix("/api/v3/contents/generations/tasks/%s");
        config.setApiKey("test-key");
        config.setDefaultSizeCode("720P");
        config.setDefaultAspectRatio("adaptive");
        config.setDefaultDurationSeconds(-1);
        config.setCapabilityJson(scene == null ? "{}" : "{\"videoScenario\":\"" + scene
                + "\",\"maxReferenceImages\":30,\"maxReferenceVideos\":10,"
                + "\"supportsReferenceAudio\":true,\"maxReferenceAudios\":10,"
                + "\"referenceAudioFormats\":[\"wav\",\"mp3\"],\"defaultOutputFormat\":\""
                + (("edit".equals(scene) || "extend".equals(scene)) ? "mov" : "mp4") + "\"}");
        return config;
    }

    private MediaVideoGenerateRequest request() {
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setPrompt("test prompt");
        request.setAspectRatio("adaptive");
        request.setDurationSeconds(-1);
        request.setOptions(Map.of("resolution", "720P"));
        return request;
    }

    private String tokenVideoBillingRule() {
        return "{\"meterType\":\"TOKEN\",\"chargeType\":\"VIDEO\"}";
    }

    private void handleLocalContract(HttpExchange exchange,
                                     AtomicReference<String> submitBody,
                                     AtomicReference<String> submitAuth,
                                     AtomicReference<Throwable> handlerFailure) {
        try {
            String response;
            if ("POST".equals(exchange.getRequestMethod())) {
                assertEquals("/proxy/ark/v9/video/tasks", exchange.getRequestURI().getPath());
                submitBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                submitAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                response = "{\"id\":\"task-local\"}";
            } else {
                assertEquals("GET", exchange.getRequestMethod());
                assertEquals("/proxy/ark/v9/video/tasks/task-local", exchange.getRequestURI().getPath());
                response = "{\"status\":\"succeeded\",\"duration\":5,"
                        + "\"content\":{\"video_url\":\"https://cdn.test/local.mp4\"},"
                        + "\"usage\":{\"completion_tokens\":1,\"total_tokens\":1}}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (Throwable throwable) {
            handlerFailure.set(throwable);
        } finally {
            exchange.close();
        }
    }
}
