package com.aid.media.provider.impl;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.KlingConstants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KlingVideoProviderClientTest {

    @Test
    void baseUrlAllowsOfficialAndProxyOriginsButRejectsUnsafeGatewayShapes() {
        for (String safe : List.of(
            "https://api-beijing.klingai.com",
            "https://api.bananarouter.com",
            "https://api.bananarouter.com/",
            "https://api-singapore.klingai.com.evil.test",
            "http://proxy.test:80",
            "http://localhost:8080",
            "https://proxy.test:8443")) {
            AiModelConfigVo config = new AiModelConfigVo();
            config.setBaseUrl(safe);
            config.setApiSuffix(KlingConstants.PATH_OMNI);
            config.setTaskQuerySuffix("/tasks?task_ids=%s");
            assertDoesNotThrow(() -> KlingVideoProviderClient.validateBaseUrl(config), safe);
        }

        for (String unsafe : List.of(
            "ftp://api.bananarouter.com",
            "https://user:pass@api.bananarouter.com",
            "https://api.bananarouter.com//",
            "https://api.bananarouter.com/proxy",
            "https://api.bananarouter.com?route=kling",
            "https://api.bananarouter.com#fragment")) {
            AiModelConfigVo config = new AiModelConfigVo();
            config.setBaseUrl(unsafe);
            config.setApiSuffix(KlingConstants.PATH_OMNI);
            config.setTaskQuerySuffix("/tasks?task_ids=%s");
            assertThrows(ServiceException.class, () -> KlingVideoProviderClient.validateBaseUrl(config), unsafe);
        }
    }

    @Test
    void submitAndQueryUrlsUseTheEffectiveProxyBaseUrl() {
        AiModelConfigVo proxy = routingConfig("https://api.bananarouter.com/");

        assertEquals("https://api.bananarouter.com" + KlingConstants.PATH_OMNI,
            KlingVideoProviderClient.buildSubmitUrl(proxy));
        assertEquals("https://api.bananarouter.com/tasks?task_ids=task%2F1",
            KlingVideoProviderClient.buildQueryUrl(proxy, "task/1"));
        AiModelConfigVo official = routingConfig("https://api-beijing.klingai.com");
        assertEquals("https://api-beijing.klingai.com" + KlingConstants.PATH_OMNI,
            KlingVideoProviderClient.buildSubmitUrl(official));
        assertEquals("https://api-beijing.klingai.com/tasks?task_ids=official-task",
            KlingVideoProviderClient.buildQueryUrl(official, "official-task"));
    }

    @Test
    void proxyRoutingAcceptsConfiguredPathsButRejectsUnsafeRelativePaths() {
        AiModelConfigVo config = routingConfig("https://api.bananarouter.com");

        config.setApiSuffix("/proxy/vendor/custom-create");
        assertEquals("https://api.bananarouter.com/proxy/vendor/custom-create",
            KlingVideoProviderClient.buildSubmitUrl(config));

        for (String invalid : List.of("/api/../tasks", "/api/%2e%2e/tasks",
            "https://evil.test" + KlingConstants.PATH_OMNI)) {
            config.setApiSuffix(invalid);
            assertThrows(ServiceException.class, () -> KlingVideoProviderClient.buildSubmitUrl(config), invalid);
        }

        config.setApiSuffix(KlingConstants.PATH_OMNI);
        for (String invalid : List.of(
            "/tasks?task_ids=%s&redirect=https://evil.test",
            "https://evil.test/tasks?task_ids=%s")) {
            config.setTaskQuerySuffix(invalid);
            assertThrows(ServiceException.class,
                () -> KlingVideoProviderClient.buildQueryUrl(config, "task-1"), invalid);
        }
    }

    @Test
    void submitRetriesOnlyExplicitNotAcceptedResponses() {
        assertTrue(KlingVideoProviderClient.isExplicitlyNotAccepted(1302));
        assertTrue(KlingVideoProviderClient.isExplicitlyNotAccepted(1303));
        assertFalse(KlingVideoProviderClient.isExplicitlyNotAccepted(5000));
        assertFalse(KlingVideoProviderClient.isExplicitlyNotAccepted(5001));
        assertFalse(KlingVideoProviderClient.isExplicitlyNotAccepted(-1));
    }

    @Test
    void sanitizerUsesActualTwoImagesInsteadOfConfiguredMaximumSeven() {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setCapabilityJson("{\"klingScenario\":\"omni_reference\",\"maxReferenceImages\":7}");
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setImageUrl("first");
        request.setOptions(Map.of("referenceImages", List.of("ref")));
        request.setPrompt("@图片1[A] @图片2[B] @图片3[C]");
        int actual = KlingVideoProviderClient.countActuallyDispatchedImages(config, request);
        assertEquals(2, actual);
        String cleaned = ReferencePromptSanitizer.sanitizePreservingSubjectRefs(request.getPrompt(), actual, 0);
        assertEquals("图片1 图片2 C", cleaned);
        assertFalse(cleaned.contains("图片3"));
    }

    @Test
    void emptyPreferredReferenceListFallsBackToImagesForCountAndBody() {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setApiSuffix(KlingConstants.PATH_OMNI);
        config.setCapabilityJson("{\"klingScenario\":\"omni_reference\",\"maxReferenceImages\":7}");
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setPrompt("@\u56fe\u72471[reference]");
        request.setDurationSeconds(5);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("referenceImages", List.of());
        options.put("images", List.of(" ", "https://cdn.test/ref.png", "https://cdn.test/ref.png"));
        request.setOptions(options);

        Map<String, Object> body = KlingVideoProviderClient.prepareSubmissionBody(config, request);
        List<?> contents = (List<?>) body.get("contents");

        assertEquals(1, KlingVideoProviderClient.countActuallyDispatchedImages(config, request));
        assertEquals("@image_1", ((Map<?, ?>) contents.get(0)).get("text"));
        assertEquals("refer_image", ((Map<?, ?>) contents.get(1)).get("type"));
    }

    @Test
    void parsesNewAndLegacySubmitTaskIds() {
        ProviderSubmitResult current = KlingVideoProviderClient.parseSubmitResponse(
            "{\"code\":0,\"data\":{\"id\":\"new-task\"}}");
        ProviderSubmitResult legacy = KlingVideoProviderClient.parseSubmitResponse(
            "{\"code\":0,\"data\":{\"task_id\":\"legacy-task\"}}");

        assertEquals("new-task", current.getProviderTaskId());
        assertEquals("legacy-task", legacy.getProviderTaskId());
    }

    @Test
    void submitPolicyRejectionKeepsSafeMessageAndMinimalAuditDetail() {
        ServiceException failure = assertThrows(ServiceException.class,
            () -> KlingVideoProviderClient.parseSubmitResponse(
                "{\"code\":1300,\"message\":\"blocked by risk control\"," +
                    "\"request_id\":\"request-\\n1\",\"api_key\":\"must-not-be-recorded\"}"));

        assertEquals("输入内容未通过安全校验", failure.getMessage());
        assertEquals(200, failure.getCode());
        assertTrue(failure.getDetailMessage().contains("blocked by risk control"));
        assertTrue(failure.getDetailMessage().contains("request-1"));
        assertFalse(failure.getDetailMessage().contains("\\n"));
        assertFalse(failure.getDetailMessage().contains("must-not-be-recorded"));
    }

    @Test
    void proxySafetyMessageIsRecognizedEvenWithoutOfficialBusinessCode() {
        ServiceException failure = KlingVideoProviderClient.submissionRejectedException(400, -1,
            "{\"message\":\"Your prompt was blocked by the content safety policy.\"}");

        assertEquals("输入内容未通过安全校验", failure.getMessage());
        assertEquals(400, failure.getCode());
    }

    @Test
    void parsesNewTaskArraySuccess() {
        ProviderTaskResult result = KlingVideoProviderClient.parseQueryResponse(200,
            "{\"code\":0,\"data\":[{\"id\":\"task-1\",\"status\":\"succeeded\",\"outputs\":[{"
                + "\"type\":\"video\",\"url\":\"https://cdn.test/new.mp4\",\"duration\":\"5.2\"}]}]}",
            "task-1");

        assertEquals(KlingConstants.TASK_STATUS_SUCCEEDED, result.getStatus());
        assertEquals("https://cdn.test/new.mp4", result.getResultUrl());
        assertEquals(6, result.getVideoDurationSeconds());
        assertEquals(Boolean.TRUE, result.getTerminalConfirmed());
    }

    @Test
    void parsesLegacyOmniTaskResult() {
        ProviderTaskResult result = KlingVideoProviderClient.parseQueryResponse(200,
            "{\"code\":0,\"data\":{\"task_id\":\"task-1\",\"task_status\":\"succeed\","
                + "\"task_result\":{\"videos\":[{\"url\":\"https://cdn.test/old.mp4\",\"duration\":\"8\"}]}}}",
            "task-1");

        assertEquals(KlingConstants.TASK_STATUS_SUCCEEDED, result.getStatus());
        assertEquals("https://cdn.test/old.mp4", result.getResultUrl());
        assertEquals(8, result.getVideoDurationSeconds());
    }

    @Test
    void mapsProcessingAndFailedPollingStates() {
        ProviderTaskResult processing = KlingVideoProviderClient.parseQueryResponse(200,
            "{\"code\":0,\"data\":[{\"status\":\"processing\"}]}", "task-1");
        ProviderTaskResult failed = KlingVideoProviderClient.parseQueryResponse(200,
            "{\"code\":0,\"data\":[{\"status\":\"failed\",\"message\":\"provider failure\"}]}", "task-1");

        assertEquals(KlingConstants.TASK_STATUS_PROCESSING, processing.getStatus());
        assertEquals(Boolean.FALSE, processing.getTerminalConfirmed());
        assertEquals(KlingConstants.TASK_STATUS_FAILED, failed.getStatus());
        assertEquals(Boolean.TRUE, failed.getTerminalConfirmed());
        assertEquals("上游任务执行失败", failed.getErrorMessage());
        assertEquals("provider failure", failed.getRawErrorMessage());
    }

    @Test
    void preservesSafetyFailureRawMessageButReturnsSafePollingMessage() {
        String upstream = "Your prompt was blocked by the content safety policy. Please adjust your prompt and try again.";

        ProviderTaskResult result = KlingVideoProviderClient.parseQueryResponse(200,
            "{\"code\":0,\"data\":[{\"status\":\"failed\",\"message\":\"" + upstream + "\"}]}",
            "task-1");

        assertEquals("生成内容未通过安全校验", result.getErrorMessage());
        assertEquals(upstream, result.getRawErrorMessage());
    }

    @Test
    void succeededPollingWithoutOutputRemainsNonTerminalAnomaly() {
        ProviderTaskResult result = KlingVideoProviderClient.parseQueryResponse(200,
            "{\"code\":0,\"data\":[{\"status\":\"succeeded\",\"outputs\":[]}]}", "task-1");

        assertEquals(KlingConstants.TASK_STATUS_PROCESSING, result.getStatus());
        assertEquals(Boolean.FALSE, result.getQuerySuccessful());
        assertEquals(Boolean.FALSE, result.getTerminalConfirmed());
    }

    private AiModelConfigVo routingConfig(String baseUrl) {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setModelCode("kling-3.0-omni-t2v");
        config.setBaseUrl(baseUrl);
        config.setApiSuffix(KlingConstants.PATH_OMNI);
        config.setTaskQuerySuffix("/tasks?task_ids=%s");
        return config;
    }
}
