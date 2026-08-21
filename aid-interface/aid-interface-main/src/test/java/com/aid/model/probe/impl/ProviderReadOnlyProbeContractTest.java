package com.aid.model.probe.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.media.constants.JimengConstants;
import com.aid.media.constants.MinimaxH3Constants;
import com.aid.media.constants.MinimaxTtsConstants;
import com.aid.model.probe.ProbeResult;
import com.aid.model.probe.ProviderProbe;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class ProviderReadOnlyProbeContractTest {

    private static final String API_KEY = "test-key";
    private static final String API_SECRET = "test-secret";

    @Test
    void shouldUseOnlyReadOnlyOrMetadataContracts() throws Exception {
        for (ProbeCase probeCase : probeCases()) {
            CapturedRequest captured = new CapturedRequest();
            ProbeResult result = execute(probeCase, probeCase.successStatus(),
                    probeCase.successBody(), captured);

            assertTrue(result.isOk(), probeCase.name() + ": " + result.getMessage() + " / " + result.getDetail());
            assertEquals(probeCase.method(), captured.method(), probeCase.name());
            assertReadOnlyRoute(probeCase, captured);
            assertFalse(captured.path().contains("chat/completions"), probeCase.name());
            assertFalse(captured.path().contains("images/generations"), probeCase.name());
            assertFalse(captured.path().matches(".*/v1/videos/?$"), probeCase.name());
            assertAuth(probeCase, captured);
            if (probeCase.expectedBodyFragment() != null) {
                assertTrue(captured.body().contains(probeCase.expectedBodyFragment()),
                        probeCase.name() + " body=" + captured.body());
            }
            assertVerificationMessage(probeCase, result);
        }
    }

    @Test
    void shouldRejectAuthRouteRateLimitAndServerFailuresForEveryProbe() throws Exception {
        List<ResponseSpec> failures = List.of(
                new ResponseSpec(400, "{\"error\":{\"code\":\"invalid_parameter\"}}"),
                new ResponseSpec(401, "{\"error\":{\"code\":\"unauthorized\"}}"),
                new ResponseSpec(403, "{\"error\":{\"code\":\"forbidden\"}}"),
                new ResponseSpec(404, "{\"code\":\"not_found\",\"message\":\"Not Found\"}"),
                new ResponseSpec(404, "{\"error\":{\"code\":\"not_found\",\"message\":\"Not Found\"}}"),
                new ResponseSpec(429, "{\"error\":{\"code\":\"rate_limit\"}}"),
                new ResponseSpec(500, "internal error"),
                new ResponseSpec(503, "service unavailable")
        );
        for (ProbeCase probeCase : probeCases()) {
            for (ResponseSpec failure : failures) {
                ProbeResult result = execute(probeCase, failure.status(), failure.body(), new CapturedRequest());
                assertFalse(result.isOk(), probeCase.name() + " HTTP " + failure.status());
            }
        }
    }

    @Test
    void shouldDegradeMissingProxyReadOnlyRoutesWithoutClaimingAuthentication() throws Exception {
        List<RouteFallbackCase> cases = List.of(
                new RouteFallbackCase("openai-text", 404, "404 page not found"),
                new RouteFallbackCase("gemini-text", 404,
                        "<!DOCTYPE html><html lang=\"zh\"><title>Agentsflare Console</title></html>"),
                new RouteFallbackCase("dashscope-video", 404,
                        "Error request, response status: 404"),
                new RouteFallbackCase("volcengine", 404,
                        "{\"error\":{\"type\":\"http_error\",\"message\":\"Route not found\"}}"),
                new RouteFallbackCase("volcengine", 404,
                        "{\"error\":\"Route not found\"}")
        );

        for (RouteFallbackCase fallbackCase : cases) {
            ProbeResult result = execute(findCase(fallbackCase.probeName()), fallbackCase.status(),
                    fallbackCase.body(), new CapturedRequest());

            assertTrue(result.isOk(), fallbackCase.probeName() + ": " + result.getMessage());
            assertEquals("仅网关可达", result.getMessage(), fallbackCase.probeName());
            assertEquals("代理未开放只读探测接口，未验证密钥或模型",
                    result.getDetail(), fallbackCase.probeName());
        }
    }

    @Test
    void volcengineShouldTryTaskDetailBeforeGatewayOnlyFallback() throws Exception {
        List<String> requestedPaths = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            boolean listRoute = exchange.getRequestURI().getPath()
                    .endsWith("/proxy/ark/v9/tasks");
            String body = listRoute
                    ? "{\"error\":{\"type\":\"http_error\",\"message\":\"Route not found\"}}"
                    : "{\"error\":{\"code\":\"task_not_found\",\"message\":\"task not found\"}}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AidAiProvider provider = provider("volcengine",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            AidAiModel model = model("openai-compatible-text",
                    "doubao-seedance-2.0", "doubao-seedance-2-0-260128");

            ProbeResult result = new VolcengineProbe().probe(model, provider);

            assertTrue(result.isOk());
            assertEquals("鉴权查询正常", result.getMessage());
            assertEquals(2, requestedPaths.size());
            assertEquals("/proxy/ark/v9/tasks", requestedPaths.get(0));
            assertTrue(requestedPaths.get(1)
                    .startsWith("/proxy/ark/v9/tasks/cgt-"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void geminiPartialListFallbackShouldNotClaimModelMissing() throws Exception {
        List<String> requestedPaths = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            boolean exactModelRoute = exchange.getRequestURI().getPath().endsWith("/models/gemini-test");
            String body = exactModelRoute
                    ? "404 page not found"
                    : "{\"models\":[{\"name\":\"models/another-model\"}]}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",
                    exactModelRoute ? "text/plain" : "application/json");
            exchange.sendResponseHeaders(exactModelRoute ? 404 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AidAiProvider provider = provider("gemini",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            AidAiModel model = modelWithSuffix("gemini-text", "display-gemini", "gemini-test",
                    "/proxy/gemini/v9/models/{model}:generateContent");

            ProbeResult result = new GeminiProbe().probe(model, provider);

            assertTrue(result.isOk());
            assertEquals("仅供应商鉴权正常", result.getMessage());
            assertEquals("未验证当前模型权限", result.getDetail());
            assertEquals(List.of("/proxy/gemini/v9/models/gemini-test",
                    "/proxy/gemini/v9/models"), requestedPaths);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void opaqueGenerationRoutesShouldUseGetGatewayOnlyFallback() throws Exception {
        List<ProbeCase> cases = List.of(
                new ProbeCase("opaque-openai", new OpenAiProviderProbe(), "openai",
                        modelWithSuffix("openai-compatible-text", "openai", "openai", "/opaque-submit"),
                        "", "GET", "/", 200, "gateway", null, false),
                new ProbeCase("opaque-deepseek", new DeepSeekProbe(), "deepseek",
                        modelWithSuffix("openai-compatible-text", "deepseek", "deepseek", "/opaque-submit"),
                        "", "GET", "/", 200, "gateway", null, false),
                new ProbeCase("opaque-gemini", new GeminiProbe(), "gemini",
                        modelWithSuffix("gemini-text", "gemini", "gemini", "/opaque-submit"),
                        "", "GET", "/", 200, "gateway", null, false),
              new ProbeCase("opaque-minimax-tts", new MinimaxProbe(), "minimax",
                      modelWithSuffix(MinimaxTtsConstants.PROTOCOL_TTS, "speech", "speech", "/opaque-submit"),
                      "", "GET", "/", 200, "gateway", null, false),
              new ProbeCase("blank-minimax-tts", new MinimaxProbe(), "minimax",
                      modelWithSuffix(MinimaxTtsConstants.PROTOCOL_TTS, "speech", "speech", ""),
                      "", "GET", "/", 200, "gateway", null, false));
        for (ProbeCase probeCase : cases) {
            CapturedRequest captured = new CapturedRequest();
            ProbeResult result = execute(probeCase, 200, "gateway", captured);
            assertTrue(result.isOk(), probeCase.name());
            assertEquals("仅网关可达", result.getMessage(), probeCase.name());
            assertEquals("未验证密钥或模型", result.getDetail(), probeCase.name());
            assertEquals("GET", captured.method(), probeCase.name());
            assertEquals("/", captured.path(), probeCase.name());
        }
    }

    @Test
    void shouldAcceptOnlyKnownMissingTaskAndKlingValidationResponses() throws Exception {
        ProbeCase kling = findCase("kling");
        ProbeResult klingMissing = execute(kling, 404,
                "{\"code\":1203,\"message\":\"task not found\"}", new CapturedRequest());
        ProbeResult klingValidation = execute(kling, 400,
                "{\"code\":1200,\"message\":\"request parameter invalid\"}", new CapturedRequest());
        ProbeResult klingUnknown = execute(kling, 400,
                "{\"code\":1999,\"message\":\"unknown\"}", new CapturedRequest());

        ProbeCase agnes = findCase("agnes-video");
        ProbeResult agnesMissing = execute(agnes, 404,
                "{\"error\":{\"code\":\"video_not_found\",\"message\":\"Video not found\"}}",
                new CapturedRequest());
        ProbeResult agnesDetailMissing = execute(agnes, 404,
                "{\"detail\":\"Video not found\"}", new CapturedRequest());
        ProbeResult agnesRoute = execute(agnes, 404, "{\"detail\":\"Not Found\"}",
                new CapturedRequest());
        ProbeResult viduMissing = execute(findCase("vidu-video"), 404,
                "{\"error\":{\"code\":\"task_not_found\"}}", new CapturedRequest());
        ProbeResult genericRouteCode = execute(findCase("vidu-video"), 404,
                "{\"code\":\"not_found\",\"message\":\"Not Found\"}", new CapturedRequest());
        ProbeResult genericNestedRouteCode = execute(findCase("agnes-video"), 404,
                "{\"error\":{\"code\":\"resource_not_found\",\"message\":\"Not Found\"}}",
                new CapturedRequest());

        assertTrue(klingMissing.isOk());
        assertTrue(klingValidation.isOk());
        assertFalse(klingUnknown.isOk());
        assertTrue(agnesMissing.isOk());
        assertTrue(agnesDetailMissing.isOk());
        assertFalse(agnesRoute.isOk());
        assertTrue(viduMissing.isOk());
        assertFalse(genericRouteCode.isOk());
        assertFalse(genericNestedRouteCode.isOk());
    }

    @Test
    void shouldRequireReturnedModelToMatchConfiguredRealModel() throws Exception {
        ProbeResult openAiMismatch = execute(findCase("openai-text"), 200,
                "{\"id\":\"another-model\",\"object\":\"model\"}", new CapturedRequest());
        ProbeResult deepSeekMissing = execute(findCase("deepseek"), 200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"another-model\"}]}",
                new CapturedRequest());
        ProbeResult geminiMismatch = execute(findCase("gemini-text"), 200,
                "{\"name\":\"models/another-model\"}", new CapturedRequest());

        assertFalse(openAiMismatch.isOk());
        assertFalse(deepSeekMissing.isOk());
        assertFalse(geminiMismatch.isOk());
    }

    @Test
    void shouldRejectExplicitModelMissingResponses() throws Exception {
        ProbeResult openAiMissing = execute(findCase("openai-text"), 404,
                "{\"error\":{\"code\":\"model_not_found\",\"message\":\"The model does not exist\"}}",
                new CapturedRequest());
        ProbeResult geminiMissing = execute(findCase("gemini-text"), 404,
                "{\"error\":{\"status\":\"NOT_FOUND\",\"message\":\"Model gemini-test is not found\"}}",
                new CapturedRequest());
        ProbeResult openAiPlainMissing = execute(findCase("openai-text"), 404,
                "The model gpt-test does not exist", new CapturedRequest());

        assertFalse(openAiMissing.isOk());
        assertEquals("模型不可用", openAiMissing.getMessage());
        assertFalse(geminiMissing.isOk());
        assertEquals("模型不可用", geminiMissing.getMessage());
        assertFalse(openAiPlainMissing.isOk());
        assertEquals("模型不可用", openAiPlainMissing.getMessage());
    }

    @Test
    void shouldKeepTheGatewayPureAndAcceptProxyPrefixesInRelativePaths() {
        assertEquals("https://proxy.example/proxy/api/v3/contents/generations/tasks",
                ProbeHttpSupport.buildUrl("https://proxy.example",
                        "/proxy/api/v3/contents/generations/tasks"));
        assertEquals("https://proxy.example/v1/models/gpt-test",
                ProbeHttpSupport.buildUrl("https://proxy.example", "/v1/models/gpt-test"));
        assertEquals("https://proxy.example/v1beta/models/gemini-test",
                ProbeHttpSupport.buildUrl("https://proxy.example",
                        "/v1beta/models/gemini-test"));
        assertEquals("https://proxy.example/v2/query/video_generation",
                ProbeHttpSupport.buildUrl("https://proxy.example",
                        "/v2/query/video_generation"));
        assertEquals("https://proxy.example/v1/models",
                ProbeHttpSupport.buildUrl("https://proxy.example", "/v1/models"));
        assertThrows(IllegalArgumentException.class,
                () -> ProbeHttpSupport.buildUrl("https://proxy.example/v1", "/models"));
    }

    private ProbeResult execute(ProbeCase probeCase, int status, String responseBody,
                                CapturedRequest captured) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        server.createContext("/", exchange -> handle(exchange, status, responseBody, captured, handlerFailure));
        server.start();
        try {
            AidAiProvider provider = provider(probeCase.providerCode(),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            if (probeCase.customAuth()) {
                provider.setAuthHeader("X-Probe-Auth");
                provider.setAuthPrefix("Key ");
            }
            ProbeResult result = probeCase.probe().probe(probeCase.model(), provider);
            if (handlerFailure.get() != null) {
                throw new AssertionError(handlerFailure.get());
            }
            return result;
        } finally {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange, int status, String responseBody,
                        CapturedRequest captured, AtomicReference<Throwable> handlerFailure) {
        try {
            captured.capture(exchange);
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            String contentType = responseBody.stripLeading().startsWith("{")
                    ? "application/json" : "text/plain";
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
        } catch (Throwable throwable) {
            handlerFailure.set(throwable);
        } finally {
            exchange.close();
        }
    }

    private void assertAuth(ProbeCase probeCase, CapturedRequest captured) {
        if (probeCase.name().startsWith("gemini")) {
            assertEquals(API_KEY, captured.header("x-goog-api-key"), probeCase.name());
            return;
        }
        if (probeCase.name().startsWith("jimeng")) {
            String authorization = captured.header("Authorization");
            assertNotNull(authorization, probeCase.name());
            assertTrue(authorization.startsWith(JimengConstants.SIGN_ALGORITHM), authorization);
            return;
        }
        if (probeCase.customAuth()) {
            assertEquals("Key " + API_KEY, captured.header("X-Probe-Auth"), probeCase.name());
            return;
        }
        String expectedPrefix = probeCase.name().startsWith("vidu") ? "Token " : "Bearer ";
        assertEquals(expectedPrefix + API_KEY, captured.header("Authorization"), probeCase.name());
    }

    private void assertReadOnlyRoute(ProbeCase probeCase, CapturedRequest captured) {
        if (probeCase.expectedPath() != null) {
            assertEquals(probeCase.expectedPath(), captured.path(), probeCase.name());
        } else if (probeCase.name().equals("dashscope-video")) {
            assertTrue(captured.path().startsWith("/proxy/dash/tasks/"), captured.path());
        } else if (probeCase.name().startsWith("vidu-")) {
            assertTrue(captured.path().startsWith("/proxy/vidu/tasks/"), captured.path());
            assertTrue(captured.path().endsWith("/creations"), captured.path());
        }
        if (probeCase.name().equals("volcengine") || probeCase.name().equals("minimax-h3")) {
            assertEquals("page_num=1&page_size=1", captured.query(), probeCase.name());
        } else if (probeCase.name().equals("kling")) {
            assertTrue(captured.query().startsWith("external_task_ids=aid-probe-"), captured.query());
        } else if (probeCase.name().equals("agnes-video") || probeCase.name().equals("agnes-text")
                || probeCase.name().equals("agnes-image")) {
            assertTrue(captured.query().startsWith("video_id=aid-probe-"), captured.query());
        } else if (probeCase.name().startsWith("jimeng")) {
            assertTrue(captured.query().contains("Action=CVSync2AsyncGetResult"), captured.query());
            assertTrue(captured.query().contains("Version=2022-08-31"), captured.query());
        } else if (probeCase.name().equals("gemini-provider-list")) {
            assertEquals("pageSize=1", captured.query(), probeCase.name());
        }
    }

    private void assertVerificationMessage(ProbeCase probeCase, ProbeResult result) {
        if (probeCase.name().startsWith("generic-") || probeCase.name().endsWith("-fallback")) {
            assertEquals("仅网关可达", result.getMessage(), probeCase.name());
            assertEquals("未验证密钥或模型", result.getDetail(), probeCase.name());
        } else if (probeCase.name().equals("agnes-text") || probeCase.name().equals("agnes-image")) {
            assertEquals("仅供应商鉴权正常", result.getMessage());
            assertEquals("未验证当前模型权限", result.getDetail());
        } else if (probeCase.name().endsWith("provider-list")) {
            assertEquals("鉴权查询正常", result.getMessage(), probeCase.name());
        }
    }

    private ProbeCase findCase(String name) {
        return probeCases().stream().filter(item -> item.name().equals(name)).findFirst().orElseThrow();
    }

    private List<ProbeCase> probeCases() {
        List<ProbeCase> cases = new ArrayList<>();
        cases.add(new ProbeCase("volcengine", new VolcengineProbe(), "volcengine",
                model("openai-compatible-text", "doubao-seedance-2.0", "doubao-seedance-2-0"),
                "", "GET", "/proxy/ark/v9/tasks",
                200, "{\"items\":[],\"total\":0}", null, false));
        cases.add(new ProbeCase("minimax-h3", new MinimaxProbe(), "minimax",
                model(MinimaxH3Constants.PROTOCOL_VIDEO, "minimax-h3", "MiniMax-H3"),
                "", "GET", "/proxy/minimax/v9/query/video_generation",
                200, "{\"items\":[],\"total\":0}", null, false));
        cases.add(new ProbeCase("minimax-tts", new MinimaxProbe(), "minimax",
                modelWithSuffix(MinimaxTtsConstants.PROTOCOL_TTS, "speech-test", "speech-test",
                        "/proxy/minimax/v9/t2a_v2"),
                "", "POST", "/proxy/minimax/v9/get_voice",
                200, "{\"system_voice\":[],\"base_resp\":{\"status_code\":0,\"status_msg\":\"success\"}}",
                "\"voice_type\":\"system\"", false));
        cases.add(new ProbeCase("kling", new KlingProbe(), "kling",
                model("kling-video", "kling-test", "kling-test"),
                "", "GET", "/proxy/kling/v9/tasks", 200, "{\"code\":0,\"data\":[]}", null, true));
        cases.add(new ProbeCase("agnes-video", new AgnesProbe(), "agnes",
                model("agnes-video", "agnes-video-test", "agnes-video-test"),
                "", "GET", "/proxy/agnes/v9/tasks", 200,
                "{\"id\":\"task-test\",\"video_id\":\"video-test\",\"status\":\"queued\"}", null, false));
        cases.add(new ProbeCase("agnes-text", new AgnesProbe(), "agnes",
                model("openai-compatible-text", "agnes-text-test", "agnes-text-test"),
                "", "GET", "/proxy/agnes/v9/tasks", 200,
                "{\"id\":\"task-test\",\"video_id\":\"video-test\",\"status\":\"queued\"}", null, false));
        cases.add(new ProbeCase("agnes-image", new AgnesProbe(), "agnes",
                model("agnes-image", "agnes-image-test", "agnes-image-test"),
                "", "GET", "/proxy/agnes/v9/tasks", 200,
                "{\"id\":\"task-test\",\"video_id\":\"video-test\",\"status\":\"queued\"}", null, false));
        cases.add(new ProbeCase("openai-text", new OpenAiProviderProbe(), "openai",
                modelWithSuffix("openai-compatible-text", "display-gpt", "gpt-test",
                        "/proxy/openai/v9/chat/completions"),
                "", "GET", "/proxy/openai/v9/models/gpt-test", 200,
                "{\"id\":\"gpt-test\",\"object\":\"model\"}", null, false));
        cases.add(new ProbeCase("openai-image", new OpenAiProviderProbe(), "openai",
                modelWithSuffix("openai-image", "display-image", "gpt-image-test",
                        "/proxy/openai/v9/images/{operation}"),
                "", "GET", "/proxy/openai/v9/models/gpt-image-test", 200,
                "{\"id\":\"gpt-image-test\",\"object\":\"model\"}", null, false));
        cases.add(new ProbeCase("openai-provider-list", new OpenAiProviderProbe(), "openai",
                null, "", "GET", "/v1/models", 200,
                "{\"object\":\"list\",\"data\":[]}", null, false));
        cases.add(new ProbeCase("deepseek", new DeepSeekProbe(), "deepseek",
                modelWithSuffix("openai-compatible-text", "display-deepseek", "deepseek-test",
                        "/proxy/deepseek/v9/chat/completions"),
                "", "GET", "/proxy/deepseek/v9/models", 200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"deepseek-test\"}]}", null, false));
        cases.add(new ProbeCase("deepseek-provider-list", new DeepSeekProbe(), "deepseek",
                null, "", "GET", "/models", 200,
                "{\"object\":\"list\",\"data\":[]}", null, false));
        cases.add(new ProbeCase("gemini-text", new GeminiProbe(), "gemini",
                modelWithSuffix("gemini-text", "display-gemini", "gemini-test",
                        "/proxy/gemini/v9/models/{model}:generateContent"),
                "", "GET", "/proxy/gemini/v9/models/gemini-test", 200,
                "{\"name\":\"models/gemini-test\"}", null, false));
        cases.add(new ProbeCase("gemini-image", new GeminiProbe(), "gemini",
                modelWithSuffix("gemini-image", "display-gemini-image", "gemini-image-test",
                        "/proxy/gemini/v9/models/{model}:generateContent"),
                "", "GET", "/proxy/gemini/v9/models/gemini-image-test", 200,
                "{\"name\":\"models/gemini-image-test\"}", null, false));
        cases.add(new ProbeCase("gemini-provider-list", new GeminiProbe(), "gemini",
                null, "", "GET", "/v1beta/models", 200,
                "{\"models\":[]}", null, false));
        cases.add(new ProbeCase("jimeng-image", new JimengProbe(), "jimeng",
                model(JimengConstants.PROTOCOL_IMAGE, JimengConstants.MODEL_CODE_V46,
                        JimengConstants.MODEL_CODE_V46),
                "", "POST", "/proxy/jimeng/v9/visual", 200,
                "{\"code\":10000,\"data\":{\"status\":\"not_found\"}}",
                "\"req_key\":\"" + JimengConstants.REQ_KEY_V46 + "\"", false));
        cases.add(new ProbeCase("jimeng-video", new JimengProbe(), "jimeng",
                model(JimengConstants.PROTOCOL_VIDEO, JimengConstants.VIDEO_MODEL_CODE_V30,
                        JimengConstants.VIDEO_MODEL_CODE_V30),
                "", "POST", "/proxy/jimeng/v9/visual", 200,
                "{\"code\":10000,\"data\":{\"status\":\"not_found\"}}",
                "\"req_key\":\"" + JimengConstants.VIDEO_REQ_KEY_V30_T2V_720 + "\"", false));
        cases.add(new ProbeCase("generic-text", new OpenAiCompatibleTextProbe(), "proxy",
                model("openai-compatible-text", "proxy-model", "proxy-model"),
                "", "GET", "/", 200, "gateway", null, false));
        cases.add(new ProbeCase("generic-image", new OpenAiImageProbe(), "proxy",
                model("openai-image", "proxy-image", "proxy-image"),
                "", "GET", "/", 200, "gateway", null, false));
        cases.add(new ProbeCase("agnes-image-fallback", new AgnesImageProbe(), "proxy",
                model("agnes-image", "agnes-image", "agnes-image"),
                "", "GET", "/", 200, "gateway", null, false));
        cases.add(new ProbeCase("agnes-video-fallback", new AgnesVideoProbe(), "proxy",
                model("agnes-video", "agnes-video", "agnes-video"),
                "", "GET", "/", 200, "gateway", null, false));
        cases.add(new ProbeCase("dashscope-video", new DashscopeVideoProbe(), "dashscope",
                model("dashscope-video", "wan-video", "wan-video"),
                "", "GET", null, 200, "{\"output\":{\"task_status\":\"PENDING\"}}", null, false));
        cases.add(new ProbeCase("vidu-video", new ViduVideoProbe(), "vidu",
                model("vidu-video", "vidu-test", "vidu-test"),
                "", "GET", null, 200, "{\"status\":\"processing\"}", null, false));
        cases.add(new ProbeCase("vidu-image", new ViduImageProbe(), "vidu",
                model("vidu-image", "vidu-image-test", "vidu-image-test"),
                "", "GET", null, 200, "{\"status\":\"processing\"}", null, false));
        return cases;
    }

    private AidAiProvider provider(String providerCode, String baseUrl) {
        AidAiProvider provider = new AidAiProvider();
        provider.setProviderCode(providerCode);
        provider.setBaseUrl(baseUrl);
        provider.setApiKey(API_KEY);
        provider.setApiSecret(API_SECRET);
        provider.setTaskQuerySuffix(switch (providerCode) {
            case "volcengine" -> "/proxy/ark/v9/tasks/%s";
            case "minimax" -> "/proxy/minimax/v9/query/video_generation/%s";
            case "kling" -> "/proxy/kling/v9/tasks?external_task_ids=%s";
            case "agnes" -> "/proxy/agnes/v9/tasks?video_id=%s";
            case "dashscope" -> "/proxy/dash/tasks/%s";
            case "vidu" -> "/proxy/vidu/tasks/%s/creations";
            default -> null;
        });
        return provider;
    }

    private AidAiModel model(String protocol, String modelCode, String realModelCode) {
        AidAiModel model = new AidAiModel();
        model.setProtocol(protocol);
        model.setModelCode(modelCode);
        model.setRealModelCode(realModelCode);
        if (JimengConstants.PROTOCOL_IMAGE.equals(protocol) || JimengConstants.PROTOCOL_VIDEO.equals(protocol)) {
            model.setApiSuffix("/proxy/jimeng/v9/visual");
        }
        return model;
    }

    private AidAiModel modelWithSuffix(String protocol, String modelCode,
                                       String realModelCode, String apiSuffix) {
        AidAiModel model = model(protocol, modelCode, realModelCode);
        model.setApiSuffix(apiSuffix);
        return model;
    }

    private record ProbeCase(String name, ProviderProbe probe, String providerCode, AidAiModel model,
                             String basePrefix, String method, String expectedPath, int successStatus,
                             String successBody, String expectedBodyFragment, boolean customAuth) {
    }

    private record ResponseSpec(int status, String body) {
    }

    private record RouteFallbackCase(String probeName, int status, String body) {
    }

    private static final class CapturedRequest {
        private String method;
        private String path;
        private String query;
        private String body;
        private com.sun.net.httpserver.Headers headers;

        private void capture(HttpExchange exchange) throws IOException {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            query = exchange.getRequestURI().getRawQuery();
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            headers = exchange.getRequestHeaders();
        }

        private String method() {
            return method;
        }

        private String path() {
            return path;
        }

        private String body() {
            return body;
        }

        private String query() {
            return query;
        }

        private String header(String name) {
            return headers == null ? null : headers.getFirst(name);
        }
    }
}
