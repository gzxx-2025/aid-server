package com.aid.media.yyz.controller;

import com.aid.media.service.KlingCallbackResult;
import com.aid.media.service.KlingCallbackService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KlingCallbackControllerTest {

    @Test
    void badSignatureIsNeverAcknowledgedWithHttp200() {
        KlingCallbackService service = mock(KlingCallbackService.class);
        when(service.handleKlingCallback(anyString(), any())).thenReturn(KlingCallbackResult.INVALID_SIGNATURE_OR_PAYLOAD);
        KlingCallbackController controller = new KlingCallbackController(service);
        assertEquals(400, controller.onCallback("{}", request()).getStatusCode().value());
    }

    @Test
    void acceptedAndDuplicateCallbacksReturnHttp200() {
        KlingCallbackService service = mock(KlingCallbackService.class);
        when(service.handleKlingCallback(anyString(), any())).thenReturn(KlingCallbackResult.ACCEPTED);
        KlingCallbackController controller = new KlingCallbackController(service);
        assertEquals(200, controller.onCallback("{\"id\":\"1\"}", request()).getStatusCode().value());
        assertEquals(200, controller.onCallback("{\"id\":\"1\"}", request()).getStatusCode().value());
    }

    private HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        return request;
    }
}
