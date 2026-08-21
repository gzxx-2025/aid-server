package com.aid.aid.service.impl;

import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.mapper.AidAiProviderMapper;
import com.aid.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AidAiProviderServiceImplKlingValidationTest {

    private final AidAiProviderMapper mapper = mock(AidAiProviderMapper.class);
    private final TestableAidAiProviderService service = createService();

    @Test
    void klingProxyBaseUrlCanBeSavedAndTrailingSlashIsNormalized() {
        AidAiProvider provider = klingProvider();
        provider.setBaseUrl("https://api.bananarouter.com/");

        assertEquals(1, service.insertAidAiProvider(provider));
        assertEquals("https://api.bananarouter.com", provider.getBaseUrl());
        verify(mapper).insert(same(provider));
    }

    @Test
    void taskQueryPathIsNormalizedAndReturnedByTheSavedEntity() {
        AidAiProvider provider = klingProvider();
        provider.setTaskQuerySuffix("  /proxy/kling/v8/tasks?id=%s  ");

        assertEquals(1, service.insertAidAiProvider(provider));
        assertEquals("/proxy/kling/v8/tasks?id=%s", provider.getTaskQuerySuffix());
        verify(mapper).insert(same(provider));
    }

    @Test
    void invalidTaskQueryPathsAreRejectedBeforeSave() {
        for (String invalid : new String[]{
            "https://evil.test/tasks?id=%s",
            "/tasks?id=%s#fragment",
            "/tasks/%2e%2e/%s",
            "/tasks//%s",
            "/tasks?id=%s%0d%0aX-Test:1",
            "/tasks"
        }) {
            AidAiProvider provider = klingProvider();
            provider.setTaskQuerySuffix(invalid);
            assertThrows(ServiceException.class, () -> service.insertAidAiProvider(provider), invalid);
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void invalidBaseGatewayShapesAreRejectedBeforeSave() {
        for (String invalid : new String[]{
            "ftp://api.bananarouter.com",
            "https://user:pass@api.bananarouter.com",
            "https://api.bananarouter.com//",
            "https://api.bananarouter.com/proxy",
            "https://api.bananarouter.com?route=kling",
            "https://api.bananarouter.com#fragment"
        }) {
            AidAiProvider provider = klingProvider();
            provider.setBaseUrl(invalid);
            assertThrows(ServiceException.class, () -> service.insertAidAiProvider(provider), invalid);
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void malformedKlingScheduleStrategyReturnsControlledBusinessError() {
        AidAiProvider provider = klingProvider();
        provider.setScheduleStrategyJson("{not-json");

        assertThrows(ServiceException.class, () -> service.validateKlingConfiguration(provider));
    }

    @Test
    void nonKlingProviderDoesNotUseKlingStrategyParser() {
        AidAiProvider provider = new AidAiProvider();
        provider.setProviderCode("other");
        provider.setScheduleStrategyJson("{not-json");

        assertDoesNotThrow(() -> service.validateKlingConfiguration(provider));
    }

    @Test
    void klingCallbackRequiresHttpsWhileAcceptingTheOfficialHttpsRoute() {
        AidAiProvider provider = klingProvider();
        provider.setSupportsCallback(true);
        provider.setApiSecret("whsec_" + Base64.getEncoder().encodeToString(
            "callback-secret".getBytes(StandardCharsets.UTF_8)));
        provider.setScheduleStrategyJson("{\"dispatchMode\":\"CALLBACK_FIRST\",\"supportsCallback\":true,"
            + "\"callbackBaseUrl\":\"http://aid.test/api/media/callback/kling\"}");

        assertThrows(ServiceException.class, () -> service.validateKlingConfiguration(provider));

        provider.setScheduleStrategyJson("{\"dispatchMode\":\"CALLBACK_FIRST\",\"supportsCallback\":true,"
            + "\"callbackBaseUrl\":\"https://aid.test/api/media/callback/kling\"}");
        assertDoesNotThrow(() -> service.validateKlingConfiguration(provider));
    }

    private AidAiProvider klingProvider() {
        AidAiProvider provider = new AidAiProvider();
        provider.setId(21L);
        provider.setProviderCode("kling");
        provider.setBaseUrl("https://api-beijing.klingai.com");
        return provider;
    }

    private TestableAidAiProviderService createService() {
        TestableAidAiProviderService value = new TestableAidAiProviderService();
        value.setBaseMapper(mapper);
        when(mapper.insert(any(AidAiProvider.class))).thenReturn(1);
        return value;
    }

    private static final class TestableAidAiProviderService extends AidAiProviderServiceImpl {
        private void setBaseMapper(AidAiProviderMapper mapper) {
            this.baseMapper = mapper;
        }
    }
}
