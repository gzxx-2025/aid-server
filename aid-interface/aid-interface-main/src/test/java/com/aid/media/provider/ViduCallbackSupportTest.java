package com.aid.media.provider;

import com.aid.domain.vo.AiModelConfigVo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViduCallbackSupportTest {

    @Test
    void shouldRequireCallbackCapabilityBeforeReturningUrl() {
        AiModelConfigVo model = model(false,
            "{\"supportsCallback\":true,\"callbackBaseUrl\":\"https://aid.example.com/api/media/callback/vidu\"}");

        assertFalse(ViduCallbackSupport.isCallbackEnabled(model));
        assertNull(ViduCallbackSupport.resolveCallbackBaseUrl(model));
    }

    @Test
    void shouldHonorModelCallbackOverride() {
        AiModelConfigVo model = model(true,
            "{\"supportsCallback\":false,\"callbackBaseUrl\":\"https://aid.example.com/api/media/callback/vidu\"}");

        assertFalse(ViduCallbackSupport.isCallbackEnabled(model));
        assertNull(ViduCallbackSupport.resolveCallbackBaseUrl(model));
    }

    @Test
    void shouldResolveValidModelUrlBeforeProviderUrl() {
        AiModelConfigVo model = model(true,
            "{\"dispatchMode\":\"CALLBACK_FIRST\",\"callbackBaseUrl\":\"https://model.example.com/api/media/callback/vidu\"}");
        model.setProviderScheduleStrategyJson(
            "{\"callbackBaseUrl\":\"https://provider.example.com/api/media/callback/vidu\"}");

        assertTrue(ViduCallbackSupport.isCallbackEnabled(model));
        assertEquals("https://model.example.com/api/media/callback/vidu",
            ViduCallbackSupport.resolveCallbackBaseUrl(model));
        assertEquals("https://model.example.com/api/media/callback/vidu",
            ViduCallbackSupport.resolveCallbackUrlForSubmission(model));
    }

    @Test
    void shouldNotSubmitCallbackUrlInPollOnlyMode() {
        AiModelConfigVo model = model(true,
            "{\"dispatchMode\":\"POLL_ONLY\",\"callbackBaseUrl\":\"https://aid.example.com/api/media/callback/vidu\"}");

        assertTrue(ViduCallbackSupport.isCallbackEnabled(model));
        assertFalse(ViduCallbackSupport.isCallbackDispatchEnabled(model));
        assertNull(ViduCallbackSupport.resolveCallbackUrlForSubmission(model));
    }

    @Test
    void shouldRejectUnsafeCallbackUrls() {
        assertFalse(ViduCallbackSupport.isValidCallbackBaseUrl("ftp://aid.example.com/callback"));
        assertFalse(ViduCallbackSupport.isValidCallbackBaseUrl("https://user@aid.example.com/callback"));
        assertFalse(ViduCallbackSupport.isValidCallbackBaseUrl("https://aid.example.com/callback#fragment"));
        assertTrue(ViduCallbackSupport.isValidCallbackBaseUrl(
            "https://aid.example.com/api/media/callback/vidu?source=vidu"));
    }

    private AiModelConfigVo model(boolean supportsCallback, String scheduleStrategyJson) {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setSupportsCallback(supportsCallback);
        model.setScheduleStrategyJson(scheduleStrategyJson);
        return model;
    }
}
