package com.aid.media.provider.impl;

import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.provider.ProviderSubmitResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 Seedream 可配置 HTTP 传输与官方 SDK DTO 的 JSON 契约。 */
class VolcengineImageProviderClientEndpointTest {

    @Test
    void rawHttpTransportPreservesSdkRequestAndResponseContract() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, requestBody, authorization, handlerFailure));
        server.start();
        try {
            AiModelConfigVo config = new AiModelConfigVo();
            config.setModelCode("seedream-contract");
            config.setRealModelCode("doubao-seedream-5-0-pro-260628");
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            config.setApiSuffix("/proxy/ark/v9/images/generations");
            config.setApiKey("test-key");
            config.setCapabilityJson("{\"maxReferenceImages\":10}");

            MediaImageGenerateRequest request = new MediaImageGenerateRequest();
            request.setPrompt("draw a local contract");
            request.setSize("1K");
            request.setOptions(Map.of("aspectRatio", "1:1"));

            ProviderSubmitResult result = new VolcengineImageProviderClient().submit(config, request);

            if (handlerFailure.get() != null) {
                throw new AssertionError(handlerFailure.get());
            }
            assertEquals("https://cdn.test/local.png", result.getDirectUrl());
            assertEquals(1, result.getResultCount());
            assertEquals("Bearer test-key", authorization.get());
            JsonNode body = new ObjectMapper().readTree(requestBody.get());
            assertEquals("doubao-seedream-5-0-pro-260628", body.path("model").asText());
            assertEquals("draw a local contract", body.path("prompt").asText());
            assertEquals("1024x1024", body.path("size").asText());
            assertEquals("url", body.path("response_format").asText());
        } finally {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange,
                        AtomicReference<String> requestBody,
                        AtomicReference<String> authorization,
                        AtomicReference<Throwable> handlerFailure) {
        try {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("/proxy/ark/v9/images/generations", exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = ("{\"model\":\"doubao-seedream-5-0-pro-260628\",\"created\":1,"
                    + "\"data\":[{\"url\":\"https://cdn.test/local.png\",\"size\":\"1024x1024\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        } catch (Throwable throwable) {
            handlerFailure.set(throwable);
        } finally {
            exchange.close();
        }
    }
}
