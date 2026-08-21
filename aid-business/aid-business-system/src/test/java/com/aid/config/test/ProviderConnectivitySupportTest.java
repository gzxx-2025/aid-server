package com.aid.config.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.aid.model.probe.ProbeResult;
import com.sun.net.httpserver.HttpServer;

class ProviderConnectivitySupportTest {

    @Test
    void fallbackShouldReportOnlyGatewayReachabilityForSuccessfulResponse() throws Exception {
        ProbeResult result = execute(200, "gateway");

        assertTrue(result.isOk());
        assertEquals("仅网关可达", result.getMessage());
        assertEquals("未验证密钥或模型", result.getDetail());
    }

    @Test
    void fallbackShouldRejectAuthRateLimitAndServerFailures() throws Exception {
        assertFalse(execute(401, "unauthorized").isOk());
        assertFalse(execute(403, "forbidden").isOk());
        assertFalse(execute(429, "rate limited").isOk());
        assertFalse(execute(500, "server error").isOk());
        assertFalse(execute(503, "unavailable").isOk());
    }

    @Test
    void rootNotFoundShouldOnlyReportGatewayReachability() throws Exception {
        ProbeResult result = execute(404, "<!DOCTYPE HTML><html><body>404. Page not found.</body></html>");

        assertTrue(result.isOk());
        assertEquals("仅网关可达", result.getMessage());
        assertEquals("代理未开放只读探测接口，未验证密钥或模型", result.getDetail());
    }

    private ProbeResult execute(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            return ProviderConnectivitySupport.checkBaseUrl(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test");
        } finally {
            server.stop(0);
        }
    }
}
