package com.aid.aid.service.impl;

import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.dto.ProviderUpstreamTaskQuery;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderUpstreamOperationsServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildsExactTaskIdSearchAndEncodesEachId() {
        ProviderUpstreamTaskQuery query = search("task_ids", "task/1,task 2");
        assertEquals("/proxy/kling/v9/tasks?task_ids=task%2F1,task+2",
            ProviderUpstreamOperationsServiceImpl.buildExactSearchPath(
                "/proxy/kling/v9/tasks?task_ids=%s", query));
    }

    @Test
    void buildsExactExternalTaskIdSearch() {
        ProviderUpstreamTaskQuery query = search("external_task_ids", "order-1");
        assertEquals("/proxy/kling/v9/tasks?external_task_ids=order-1",
            ProviderUpstreamOperationsServiceImpl.buildExactSearchPath(
                "/proxy/kling/v9/tasks?task_ids=%s", query));
    }

    @Test
    void derivesAdminOperationPathsFromConfiguredTaskTemplate() {
        String kling = "/proxy/kling/v9/tasks?task_ids=%s";
        assertEquals("/proxy/kling/v9/tasks",
            ProviderUpstreamOperationsServiceImpl.buildTaskCollectionPath(kling));
        assertEquals("/proxy/kling/v9/account/costs",
            ProviderUpstreamOperationsServiceImpl.buildKlingBalancePath(kling));
        assertEquals("/proxy/minimax/v9/query/video_generation",
            ProviderUpstreamOperationsServiceImpl.buildTaskCollectionPath(
                "/proxy/minimax/v9/query/video_generation/%s"));
        assertThrows(ServiceException.class,
            () -> ProviderUpstreamOperationsServiceImpl.buildTaskCollectionPath(
                "https://evil.example/tasks/%s"));
    }

    @Test
    void adminTaskQueriesUseConfiguredGatewayAndProxyPrefix() throws Exception {
        List<String> requested = new ArrayList<>();
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                requested.add(exchange.getRequestURI().toString());
                boolean minimax = exchange.getRequestURI().getPath().contains("/minimax/");
                byte[] response = (minimax ? "{\"items\":[],\"total\":0}"
                    : "{\"code\":0,\"data\":[]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Throwable throwable) {
                handlerFailure.set(throwable);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            AidAiProvider kling = provider(1L, "kling", baseUrl,
                "/proxy/kling/v9/tasks?task_ids=%s");
            AidAiProvider minimax = provider(2L, "minimax", baseUrl,
                "/proxy/minimax/v9/query/video_generation/%s");
            IAidAiProviderService providerService = mock(IAidAiProviderService.class);
            when(providerService.selectAidAiProviderById(1L)).thenReturn(kling);
            when(providerService.selectAidAiProviderById(2L)).thenReturn(minimax);
            ProviderUpstreamOperationsServiceImpl service =
                new ProviderUpstreamOperationsServiceImpl(providerService);

            service.tasks(1L, search("task_ids", "task/1"));
            service.tasks(2L, new ProviderUpstreamTaskQuery());

            if (handlerFailure.get() != null) {
                throw new AssertionError(handlerFailure.get());
            }
            assertEquals(2, requested.size());
            assertEquals("/proxy/kling/v9/tasks?task_ids=task%2F1", requested.get(0));
            assertTrue(requested.get(1).startsWith(
                "/proxy/minimax/v9/query/video_generation?page_num=1&page_size=20"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMissingOrMalformedExactSearchKeyword() {
        String template = "/proxy/kling/v9/tasks?task_ids=%s";
        assertThrows(ServiceException.class, () -> ProviderUpstreamOperationsServiceImpl.buildExactSearchPath(
            template, search("task_ids", "")));
        assertThrows(ServiceException.class, () -> ProviderUpstreamOperationsServiceImpl.buildExactSearchPath(
            template, search("task_ids", "a,,b")));
        assertThrows(ServiceException.class, () -> ProviderUpstreamOperationsServiceImpl.buildExactSearchPath(
            template, search("unknown", "a")));
    }

    @Test
    void parsesOfficialHasMoreInsteadOfInferringFromCursor() throws Exception {
        Map<String, Object> noMore = ProviderUpstreamOperationsServiceImpl.parseTaskPage(
            MAPPER.readTree("{\"result\":[],\"next_cursor\":\"still-present\",\"has_more\":false}"));
        assertEquals(false, noMore.get("hasMore"));
        assertEquals("still-present", noMore.get("nextCursor"));

        Map<String, Object> more = ProviderUpstreamOperationsServiceImpl.parseTaskPage(
            MAPPER.readTree("{\"result\":[],\"next_cursor\":\"\",\"has_more\":true}"));
        assertEquals(true, more.get("hasMore"));
    }

    @Test
    void taskListTimeRangeRemainsJsonNumber() throws Exception {
        ProviderUpstreamTaskQuery query = new ProviderUpstreamTaskQuery();
        query.setStartTime(1000L);
        query.setEndTime(2000L);

        Map<String, Object> body = ProviderUpstreamOperationsServiceImpl.buildTaskListBody(query, 3000L);
        String json = MAPPER.writeValueAsString(body);

        assertEquals(true, MAPPER.readTree(json).path("start_time").isIntegralNumber());
        assertEquals(true, MAPPER.readTree(json).path("end_time").isIntegralNumber());
        assertEquals(1000L, body.get("start_time"));
        assertEquals(2000L, body.get("end_time"));
    }

    @Test
    void cursorTaskListKeepsFiltersAndLimitButDropsTimeRange() {
        ProviderUpstreamTaskQuery query = new ProviderUpstreamTaskQuery();
        query.setCursor(" next-page ");
        query.setStartTime(1000L);
        query.setEndTime(2000L);
        query.setLimit(50);
        query.setStatus("submitted,processing");
        query.setProductType("video");

        Map<String, Object> body = ProviderUpstreamOperationsServiceImpl.buildTaskListBody(query, 3000L);

        assertEquals("next-page", body.get("cursor"));
        assertEquals(50, body.get("limit"));
        assertEquals(false, body.containsKey("start_time"));
        assertEquals(false, body.containsKey("end_time"));
        assertEquals(List.of(
            Map.of("key", "status", "values", List.of("submitted", "processing")),
            Map.of("key", "product_type", "values", List.of("video"))
        ), body.get("filters"));
    }

    @Test
    void balanceResultValidatesInnerCodePreservesNullAndIsImmutable() throws Exception {
        Map<String, Object> result = ProviderUpstreamOperationsServiceImpl.parseBalanceData(
            MAPPER.readTree("{\"code\":0,\"msg\":\"success\",\"resource_pack_name\":null,"
                + "\"remaining_quantity\":\"1\"}"), 123L);

        assertEquals(true, result.containsKey("resource_pack_name"));
        assertEquals(null, result.get("resource_pack_name"));
        assertEquals(false, result.containsKey("code"));
        assertEquals(false, result.containsKey("msg"));
        assertThrows(UnsupportedOperationException.class, () -> result.put("extra", "value"));
    }

    @Test
    void balanceResultRejectsMissingOrNonzeroInnerCodeWithoutExposingProviderMessage() throws Exception {
        assertThrows(ServiceException.class, () -> ProviderUpstreamOperationsServiceImpl.parseBalanceData(
            MAPPER.readTree("{\"remaining_quantity\":\"1\"}"), 123L));

        ServiceException failure = assertThrows(ServiceException.class,
            () -> ProviderUpstreamOperationsServiceImpl.parseBalanceData(
                MAPPER.readTree("{\"code\":4001,\"msg\":\"secret provider detail\"}"), 123L));
        assertEquals("余额查询失败", failure.getMessage());
    }

    @Test
    void balanceLockIsScopedPerProvider() {
        ProviderUpstreamOperationsServiceImpl service = new ProviderUpstreamOperationsServiceImpl(
            mock(IAidAiProviderService.class));

        assertSame(service.balanceLock(1L), service.balanceLock(1L));
        assertNotSame(service.balanceLock(1L), service.balanceLock(2L));
    }

    @Test
    void minimaxLegacyRunningFilterMapsWithoutSendingUnsupportedCommaStatus() {
        ProviderUpstreamTaskQuery query = new ProviderUpstreamTaskQuery();
        query.setStatus("submitted,processing");
        query.setLimit(20);

        ProviderUpstreamOperationsServiceImpl.MinimaxCursor state =
            ProviderUpstreamOperationsServiceImpl.createMinimaxCursor(query);
        String path = ProviderUpstreamOperationsServiceImpl.buildMinimaxListPath(
            "/proxy/minimax/v9/query/video_generation/%s", state);

        assertEquals(List.of("queued", "running"), state.statuses());
        assertEquals(true, path.startsWith("/proxy/minimax/v9/query/video_generation?page_num=1&page_size=20"));
        assertEquals(false, path.contains("filter.status="));
        assertEquals(true, path.contains("filter.model=MiniMax-H3"));
        assertEquals(true, path.contains("filter.task_type=generation"));
    }

    @Test
    void minimaxCursorRoundTripsPageAndFiltersButRejectsGarbage() {
        ProviderUpstreamTaskQuery query = new ProviderUpstreamTaskQuery();
        query.setStatus("running");
        query.setSearchType("task_ids");
        query.setSearchValue("task/1,task 2");
        ProviderUpstreamOperationsServiceImpl.MinimaxCursor state =
            ProviderUpstreamOperationsServiceImpl.createMinimaxCursor(query).nextPage();

        String cursor = ProviderUpstreamOperationsServiceImpl.encodeMinimaxCursor(state);
        ProviderUpstreamOperationsServiceImpl.MinimaxCursor decoded =
            ProviderUpstreamOperationsServiceImpl.decodeMinimaxCursor(cursor);

        assertEquals(state, decoded);
        assertThrows(ServiceException.class,
            () -> ProviderUpstreamOperationsServiceImpl.decodeMinimaxCursor("not-a-cursor"));
    }

    @Test
    void minimaxAdminValidationMessagesStayConcise() {
        ProviderUpstreamTaskQuery invalidLimit = new ProviderUpstreamTaskQuery();
        invalidLimit.setLimit(101);
        assertConcise(assertThrows(ServiceException.class,
            () -> ProviderUpstreamOperationsServiceImpl.createMinimaxCursor(invalidLimit)));

        ProviderUpstreamTaskQuery invalidStatus = new ProviderUpstreamTaskQuery();
        invalidStatus.setStatus("unknown");
        assertConcise(assertThrows(ServiceException.class,
            () -> ProviderUpstreamOperationsServiceImpl.createMinimaxCursor(invalidStatus)));

        ProviderUpstreamTaskQuery invalidSearch = search("external_task_ids", "task-1");
        assertConcise(assertThrows(ServiceException.class,
            () -> ProviderUpstreamOperationsServiceImpl.createMinimaxCursor(invalidSearch)));
        assertConcise(assertThrows(ServiceException.class,
            () -> ProviderUpstreamOperationsServiceImpl.decodeMinimaxCursor("not-a-cursor")));
    }

    @Test
    void minimaxTaskFieldsAreNormalizedForExistingPopup() throws Exception {
        List<Map<String, Object>> result = ProviderUpstreamOperationsServiceImpl.normalizeMinimaxTasks(
            MAPPER.readTree("[{\"id\":\"t1\",\"status\":\"failed\",\"created_at\":100,"
                + "\"updated_at\":200,\"error\":{\"message\":\"failed\"}}]"), List.of());

        assertEquals("t1", result.get(0).get("task_id"));
        assertEquals(100000L, result.get(0).get("create_time"));
        assertEquals(200000L, result.get(0).get("update_time"));
        assertEquals("failed", result.get(0).get("message"));
        assertEquals("video", result.get(0).get("product_type"));
    }

    private ProviderUpstreamTaskQuery search(String type, String value) {
        ProviderUpstreamTaskQuery query = new ProviderUpstreamTaskQuery();
        query.setSearchType(type);
        query.setSearchValue(value);
        return query;
    }

    private AidAiProvider provider(Long id, String code, String baseUrl, String taskQuerySuffix) {
        AidAiProvider provider = new AidAiProvider();
        provider.setId(id);
        provider.setProviderCode(code);
        provider.setBaseUrl(baseUrl);
        provider.setTaskQuerySuffix(taskQuerySuffix);
        provider.setApiKey("test-key");
        return provider;
    }

    private void assertConcise(ServiceException exception) {
        String message = exception.getMessage();
        assertTrue(message != null && message.codePointCount(0, message.length()) <= 12,
            () -> "client message is too long: " + message);
    }
}
