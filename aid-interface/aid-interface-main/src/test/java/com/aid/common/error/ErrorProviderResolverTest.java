package com.aid.common.error;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ErrorProviderResolverTest
{
    @BeforeAll
    static void initializeMybatisMetadata()
    {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        assistant.setCurrentNamespace(ErrorProviderResolverTest.class.getName());
        TableInfoHelper.initTableInfo(assistant, AidAiModel.class);
        TableInfoHelper.initTableInfo(assistant, AidAiProvider.class);
    }

    @Test
    void shouldResolveAndCacheProviderCodeByModelCode()
    {
        IAidAiModelService modelService = mock(IAidAiModelService.class);
        IAidAiProviderService providerService = mock(IAidAiProviderService.class);
        AidAiModel model = new AidAiModel();
        model.setId(1L);
        model.setProviderId(2L);
        AidAiProvider provider = new AidAiProvider();
        provider.setId(2L);
        provider.setProviderCode("volcengine");
        when(modelService.getOne(any(), eq(false))).thenReturn(model);
        when(providerService.getOne(any(), eq(false))).thenReturn(provider);

        ErrorProviderResolver resolver = new ErrorProviderResolver(modelService, providerService);

        assertEquals("volcengine", resolver.resolve("seedream-model"));
        assertEquals("volcengine", resolver.resolve("seedream-model"));
        verify(modelService).getOne(any(), eq(false));
        verify(providerService).getOne(any(), eq(false));
    }

    @Test
    void shouldNotCacheTransientQueryFailure()
    {
        IAidAiModelService modelService = mock(IAidAiModelService.class);
        IAidAiProviderService providerService = mock(IAidAiProviderService.class);
        when(modelService.getOne(any(), eq(false)))
                .thenThrow(new IllegalStateException("database unavailable"));

        ErrorProviderResolver resolver = new ErrorProviderResolver(modelService, providerService);

        assertNull(resolver.resolve("seedream-model"));
        assertNull(resolver.resolve("seedream-model"));
        verify(modelService, times(2)).getOne(any(), eq(false));
    }
}
