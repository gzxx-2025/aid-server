package com.aid.config.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.common.config.test.ConfigTestRequest;
import com.aid.common.config.test.ConfigTestResult;
import com.aid.model.probe.ProbeResult;
import com.aid.model.probe.ProviderProbe;
import com.aid.model.probe.impl.DeepSeekProbe;
import com.aid.model.probe.impl.GeminiProbe;
import com.aid.model.probe.impl.OpenAiProviderProbe;
import com.sun.net.httpserver.HttpServer;

class AiConnectivityTesterProbeSelectionTest {

    private static final Long MODEL_ID = 11L;
    private static final Long PROVIDER_ID = 22L;

    @Test
    void modelTestShouldPreferProviderMetadataProbeOverProtocolProbe() {
        IAidAiModelService modelService = mock(IAidAiModelService.class);
        IAidAiProviderService providerService = mock(IAidAiProviderService.class);
        ProviderProbe openAiProbe = probe(null, "openai", "模型权限正常");
        ProviderProbe genericProbe = probe("openai-compatible-text", null, "不应执行");
        AidAiModel model = model("openai-compatible-text");
        AidAiProvider provider = provider("openai");
        when(modelService.selectAidAiModelById(MODEL_ID)).thenReturn(model);
        when(providerService.selectAidAiProviderById(PROVIDER_ID)).thenReturn(provider);

        AiModelConnectivityTester tester = new AiModelConnectivityTester(
                modelService, providerService, List.of(genericProbe, openAiProbe));
        ConfigTestResult result = tester.test(request("modelId", MODEL_ID));

        assertTrue(result.isSuccess());
        assertEquals("模型权限正常", result.getMessage());
        verify(openAiProbe).probe(model, provider);
        verify(genericProbe, never()).probe(any(), any());
    }

    @Test
    void modelTestShouldFallbackToProtocolWhenProviderProbeDoesNotSupportModel() {
        IAidAiModelService modelService = mock(IAidAiModelService.class);
        IAidAiProviderService providerService = mock(IAidAiProviderService.class);
        ProviderProbe providerProbe = probe(null, "volcengine", "不应执行");
        ProviderProbe protocolProbe = probe("openai-compatible-text", null, "仅网关可达");
        AidAiModel model = model("openai-compatible-text");
        AidAiProvider provider = provider("volcengine");
        when(providerProbe.supportsModel(model)).thenReturn(false);
        when(modelService.selectAidAiModelById(MODEL_ID)).thenReturn(model);
        when(providerService.selectAidAiProviderById(PROVIDER_ID)).thenReturn(provider);

        AiModelConnectivityTester tester = new AiModelConnectivityTester(
                modelService, providerService, List.of(providerProbe, protocolProbe));
        ConfigTestResult result = tester.test(request("modelId", MODEL_ID));

        assertTrue(result.isSuccess());
        assertEquals("仅网关可达", result.getMessage());
        verify(protocolProbe).probe(model, provider);
        verify(providerProbe, never()).probe(any(), any());
    }

    @Test
    void providerTestShouldPassEnabledModelToProviderProbe() {
        IAidAiModelService modelService = mock(IAidAiModelService.class);
        IAidAiProviderService providerService = mock(IAidAiProviderService.class);
        ProviderProbe jimengProbe = probe(null, "jimeng", "鉴权查询正常");
        AidAiModel unsupportedModel = model("other-image");
        unsupportedModel.setRealModelCode("unknown-model");
        AidAiModel enabledModel = model("jimeng-image");
        enabledModel.setRealModelCode("jimeng-image-4.6");
        AidAiProvider provider = provider("jimeng");
        when(providerService.selectAidAiProviderById(PROVIDER_ID)).thenReturn(provider);
        when(modelService.selectAidAiModelList(any())).thenReturn(List.of(unsupportedModel, enabledModel));
        when(jimengProbe.supportsModel(unsupportedModel)).thenReturn(false);
        when(jimengProbe.supportsModel(enabledModel)).thenReturn(true);

        AiProviderConnectivityTester tester = new AiProviderConnectivityTester(
                providerService, modelService, List.of(jimengProbe));
        ConfigTestResult result = tester.test(request("providerId", PROVIDER_ID));

        assertTrue(result.isSuccess());
        ArgumentCaptor<AidAiModel> modelCaptor = ArgumentCaptor.forClass(AidAiModel.class);
        verify(jimengProbe).probe(modelCaptor.capture(), any());
        assertSame(enabledModel, modelCaptor.getValue());
    }

    @Test
    void providerTestShouldFallbackToDisabledJimengModelForReadOnlyQuery() {
        IAidAiModelService modelService = mock(IAidAiModelService.class);
        IAidAiProviderService providerService = mock(IAidAiProviderService.class);
        ProviderProbe jimengProbe = probe(null, "jimeng", "鉴权查询正常");
        AidAiModel disabledModel = model("jimeng-image");
        disabledModel.setRealModelCode("jimeng-image-4.6");
        disabledModel.setStatus("1");
        AidAiProvider provider = provider("jimeng");
        when(providerService.selectAidAiProviderById(PROVIDER_ID)).thenReturn(provider);
        when(modelService.selectAidAiModelList(any())).thenReturn(List.of(disabledModel));
        when(jimengProbe.requiresModel()).thenReturn(true);
        when(jimengProbe.supportsModel(disabledModel)).thenReturn(true);

        AiProviderConnectivityTester tester = new AiProviderConnectivityTester(
                providerService, modelService, List.of(jimengProbe));
        ConfigTestResult result = tester.test(request("providerId", PROVIDER_ID));

        assertTrue(result.isSuccess());
        ArgumentCaptor<AidAiModel> modelCaptor = ArgumentCaptor.forClass(AidAiModel.class);
        verify(jimengProbe).probe(modelCaptor.capture(), any());
        assertSame(disabledModel, modelCaptor.getValue());
    }

    @Test
    void providerTestWithoutEnabledModelShouldUseProviderMetadataList() throws Exception {
        List<ProviderListCase> cases = List.of(
                new ProviderListCase("openai", new OpenAiProviderProbe(), "/v1/models", null,
                        "{\"object\":\"list\",\"data\":[]}", "Authorization", "Bearer test-key"),
                new ProviderListCase("deepseek", new DeepSeekProbe(), "/models", null,
                        "{\"object\":\"list\",\"data\":[]}", "Authorization", "Bearer test-key"),
                new ProviderListCase("gemini", new GeminiProbe(), "/v1beta/models", "pageSize=1",
                        "{\"models\":[]}", "x-goog-api-key", "test-key")
        );
        for (ProviderListCase probeCase : cases) {
            assertProviderListProbe(probeCase);
        }
    }

    @Test
    void volcengineTtsFallbackShouldNotClaimKeyOrModelVerification() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "gateway".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            IAidAiModelService modelService = mock(IAidAiModelService.class);
            IAidAiProviderService providerService = mock(IAidAiProviderService.class);
            AidAiModel model = model("volcengine-tts");
            AidAiProvider provider = provider("volcengine_tts");
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            when(modelService.selectAidAiModelById(MODEL_ID)).thenReturn(model);
            when(providerService.selectAidAiProviderById(PROVIDER_ID)).thenReturn(provider);

            AiModelConnectivityTester tester = new AiModelConnectivityTester(
                    modelService, providerService, List.of());
            ConfigTestResult result = tester.test(request("modelId", MODEL_ID));

            assertTrue(result.isSuccess());
            assertEquals("仅网关可达", result.getMessage());
            assertEquals("未验证密钥或模型", result.getDetails());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void volcengineTtsRootNotFoundShouldOnlyClaimGatewayReachability() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "<!DOCTYPE HTML><html><body>404. Page not found.</body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            IAidAiModelService modelService = mock(IAidAiModelService.class);
            IAidAiProviderService providerService = mock(IAidAiProviderService.class);
            AidAiModel model = model("volcengine-tts");
            AidAiProvider provider = provider("volcengine_tts");
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            when(modelService.selectAidAiModelById(MODEL_ID)).thenReturn(model);
            when(providerService.selectAidAiProviderById(PROVIDER_ID)).thenReturn(provider);

            AiModelConnectivityTester tester = new AiModelConnectivityTester(
                    modelService, providerService, List.of());
            ConfigTestResult result = tester.test(request("modelId", MODEL_ID));

            assertTrue(result.isSuccess());
            assertEquals("仅网关可达", result.getMessage());
            assertEquals("代理未开放只读探测接口，未验证密钥或模型", result.getDetails());
        } finally {
            server.stop(0);
        }
    }

    private ProviderProbe probe(String protocol, String providerCode, String message) {
        ProviderProbe probe = mock(ProviderProbe.class);
        when(probe.protocol()).thenReturn(protocol);
        when(probe.providerCode()).thenReturn(providerCode);
        when(probe.supportsModel(any())).thenReturn(true);
        when(probe.probe(any(), any())).thenReturn(ProbeResult.ok(message));
        return probe;
    }

    private void assertProviderListProbe(ProviderListCase probeCase) throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getRawQuery());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst(probeCase.authHeader()));
            byte[] body = probeCase.responseBody().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            IAidAiModelService modelService = mock(IAidAiModelService.class);
            IAidAiProviderService providerService = mock(IAidAiProviderService.class);
            AidAiProvider provider = provider(probeCase.providerCode());
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            when(providerService.selectAidAiProviderById(PROVIDER_ID)).thenReturn(provider);
            when(modelService.selectAidAiModelList(any())).thenReturn(List.of());

            AiProviderConnectivityTester tester = new AiProviderConnectivityTester(
                    providerService, modelService, List.of(probeCase.probe()));
            ConfigTestResult result = tester.test(request("providerId", PROVIDER_ID));

            assertTrue(result.isSuccess(), probeCase.providerCode() + ": " + result.getMessage());
            assertEquals("鉴权查询正常", result.getMessage(), probeCase.providerCode());
            assertEquals("GET", method.get(), probeCase.providerCode());
            assertEquals(probeCase.expectedPath(), path.get(), probeCase.providerCode());
            assertEquals(probeCase.expectedQuery(), query.get(), probeCase.providerCode());
            assertEquals("", requestBody.get(), probeCase.providerCode());
            assertEquals(probeCase.expectedAuth(), auth.get(), probeCase.providerCode());
        } finally {
            server.stop(0);
        }
    }

    private AidAiModel model(String protocol) {
        AidAiModel model = new AidAiModel();
        model.setProviderId(PROVIDER_ID);
        model.setProtocol(protocol);
        model.setRealModelCode("real-model");
        return model;
    }

    private AidAiProvider provider(String providerCode) {
        AidAiProvider provider = new AidAiProvider();
        provider.setProviderCode(providerCode);
        provider.setApiKey("test-key");
        provider.setBaseUrl("http://127.0.0.1");
        return provider;
    }

    private ConfigTestRequest request(String key, Long id) {
        ConfigTestRequest request = new ConfigTestRequest();
        request.setPayload(Map.of(key, id));
        return request;
    }

    private record ProviderListCase(String providerCode, ProviderProbe probe,
                                    String expectedPath, String expectedQuery, String responseBody,
                                    String authHeader, String expectedAuth) {
    }
}
