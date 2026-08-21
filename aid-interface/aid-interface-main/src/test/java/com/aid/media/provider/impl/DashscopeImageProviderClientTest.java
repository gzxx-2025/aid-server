package com.aid.media.provider.impl;

import com.aid.media.constants.DashscopeConstants;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.domain.vo.AiModelConfigVo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashscopeImageProviderClientTest {

    private final DashscopeImageProviderClient client = new DashscopeImageProviderClient();

    @Test
    void qwenImageUsesOfficialAsyncProtocol() throws Exception {
        assertAsyncProtocol(DashscopeConstants.MODEL_QWEN_IMAGE);
    }

    @Test
    void qwenImagePlusUsesOfficialAsyncProtocol() throws Exception {
        assertAsyncProtocol(DashscopeConstants.MODEL_QWEN_IMAGE_PLUS);
    }

    @Test
    void qwenImagePlusSnapshotRemainsSynchronous() throws Exception {
        String snapshot = "qwen-image-plus-2026-01-09";

        Object dialect = resolveDialect(snapshot);
        Map<String, String> headers = extraHeaders(dialect);

        assertEquals("QwenSyncMultimodalDialect", dialect.getClass().getSimpleName());
        assertTrue(headers.isEmpty());
    }

    @Test
    void dashscopeOfficialStatusesKeepUnknownNonTerminal() throws Exception {
        assertEquals(DashscopeConstants.TASK_STATUS_PROCESSING, normalizeStatus("PENDING"));
        assertEquals(DashscopeConstants.TASK_STATUS_PROCESSING, normalizeStatus("RUNNING"));
        assertEquals(DashscopeConstants.TASK_STATUS_SUCCEEDED, normalizeStatus("SUCCEEDED"));
        assertEquals(DashscopeConstants.TASK_STATUS_FAILED, normalizeStatus("FAILED"));
        assertEquals(DashscopeConstants.TASK_STATUS_FAILED, normalizeStatus("CANCELED"));
        assertTrue(isAuthoritativeStatus("PENDING"));
        assertTrue(isAuthoritativeStatus("RUNNING"));
        assertTrue(isAuthoritativeStatus("SUCCEEDED"));
        assertTrue(isAuthoritativeStatus("FAILED"));
        assertTrue(isAuthoritativeStatus("CANCELED"));
        assertFalse(isAuthoritativeStatus("UNKNOWN"));
        assertFalse(isTerminalStatus("UNKNOWN"));
    }

    private void assertAsyncProtocol(String modelName) throws Exception {
        MediaImageGenerateRequest request = new MediaImageGenerateRequest();
        request.setPrompt("一只橘猫");
        request.setNegativePrompt("低画质");

        Object dialect = resolveDialect(modelName);
        Map<String, Object> body = buildSubmitBody(dialect, "  " + modelName.toUpperCase() + "  ", request);
        Map<String, Object> input = castMap(body.get(DashscopeConstants.JSON_INPUT));
        Map<String, Object> parameters = castMap(body.get(DashscopeConstants.JSON_PARAMETERS));
        Map<String, String> headers = extraHeaders(dialect);

        assertEquals("QwenImageAsyncDialect", dialect.getClass().getSimpleName());
        AiModelConfigVo config = new AiModelConfigVo();
        config.setBaseUrl("https://proxy.example.test");
        config.setApiSuffix("/proxy/dash/v9/qwen-image");
        assertEquals("https://proxy.example.test/proxy/dash/v9/qwen-image",
                DashscopeImageProviderClient.buildSubmitUrl(config));
        assertEquals(DashscopeConstants.HEADER_ASYNC_ENABLE,
                headers.get(DashscopeConstants.HEADER_ASYNC));
        assertEquals(modelName, body.get(DashscopeConstants.JSON_MODEL));
        assertEquals("一只橘猫", input.get(DashscopeConstants.JSON_PROMPT));
        assertFalse(input.containsKey(DashscopeConstants.JSON_NEGATIVE_PROMPT));
        assertEquals("低画质", parameters.get(DashscopeConstants.JSON_NEGATIVE_PROMPT));
        assertEquals(DashscopeConstants.QWEN_IMAGE_DEFAULT_SIZE,
                parameters.get(DashscopeConstants.JSON_SIZE));
        assertEquals(DashscopeConstants.DEFAULT_N, parameters.get(DashscopeConstants.JSON_N));
    }

    private Object resolveDialect(String modelName) throws Exception {
        Method method = DashscopeImageProviderClient.class.getDeclaredMethod("resolveDialect", String.class);
        method.setAccessible(true);
        return method.invoke(client, modelName);
    }

    private String buildApiUrl(String baseUrl, String apiSuffix) throws Exception {
        Method method = DashscopeImageProviderClient.class.getDeclaredMethod(
                "buildApiUrl", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(client, baseUrl, apiSuffix);
    }

    private String normalizeStatus(String status) throws Exception {
        return (String) invokeStatusMethod("normalizeStatus", status);
    }

    private boolean isAuthoritativeStatus(String status) throws Exception {
        return (Boolean) invokeStatusMethod("isAuthoritativeStatus", status);
    }

    private boolean isTerminalStatus(String status) throws Exception {
        return (Boolean) invokeStatusMethod("isTerminalStatus", status);
    }

    private Object invokeStatusMethod(String methodName, String status) throws Exception {
        Method method = DashscopeImageProviderClient.class.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return method.invoke(client, status);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSubmitBody(Object dialect, String modelName,
                                                MediaImageGenerateRequest request) throws Exception {
        Method method = dialect.getClass().getDeclaredMethod(
                "buildSubmitBody", String.class, MediaImageGenerateRequest.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(dialect, modelName, request);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> extraHeaders(Object dialect) throws Exception {
        Method method = dialect.getClass().getDeclaredMethod("extraHeaders");
        method.setAccessible(true);
        return (Map<String, String>) method.invoke(dialect);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
