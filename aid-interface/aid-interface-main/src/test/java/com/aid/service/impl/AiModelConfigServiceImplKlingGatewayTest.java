package com.aid.service.impl;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.AidUserAiConfig;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.aid.service.IAidUserAiConfigService;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.KlingConstants;
import com.aid.upgrade.gateway.OfficialGatewayConfig;
import com.aid.upgrade.gateway.OfficialGatewayConfigProvider;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelConfigServiceImplKlingGatewayTest {

    private IAidAiModelService modelService;
    private IAidAiProviderService providerService;
    private IAidUserAiConfigService userConfigService;
    private OfficialGatewayConfigProvider gatewayProvider;
    private OfficialGatewayConfig gateway;
    private AidAiProvider provider;
    private AiModelConfigServiceImpl service;

    @BeforeAll
    static void initMybatisMetadata() {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant modelAssistant = new MapperBuilderAssistant(configuration, "model-config-test");
        modelAssistant.setCurrentNamespace("com.aid.service.impl.AiModelConfigServiceImplKlingGatewayTest.model");
        TableInfoHelper.initTableInfo(modelAssistant, AidAiModel.class);
        MapperBuilderAssistant userAssistant = new MapperBuilderAssistant(configuration, "user-config-test");
        userAssistant.setCurrentNamespace("com.aid.service.impl.AiModelConfigServiceImplKlingGatewayTest.user");
        TableInfoHelper.initTableInfo(userAssistant, AidUserAiConfig.class);
    }

    @BeforeEach
    void setUp() {
        modelService = mock(IAidAiModelService.class);
        providerService = mock(IAidAiProviderService.class);
        userConfigService = mock(IAidUserAiConfigService.class);
        gatewayProvider = mock(OfficialGatewayConfigProvider.class);
        gateway = enabledGateway();
        provider = klingProvider();
        service = new AiModelConfigServiceImpl(modelService, providerService, userConfigService, gatewayProvider);

        when(modelService.getOne(any(), eq(false))).thenReturn(model());
        when(providerService.getById(7L)).thenReturn(provider);
        when(gatewayProvider.getConfig()).thenReturn(gateway);
    }

    @Test
    void enabledUnifiedGatewayDoesNotRewriteKlingConfiguredProxy() {
        provider.setBaseUrl("https://provider-proxy.test:8443");

        AiModelConfigVo result = service.selectByModelCodeForUser("kling-3.0-omni-i2v", 99L);

        assertEquals("https://provider-proxy.test:8443", result.getBaseUrl());
        assertEquals("provider-api-key", result.getApiKey());
        assertEquals(validSecret("platform-webhook-secret"), result.getApiSecret());
    }

    @Test
    void compatibleProviderStillHonorsUnifiedGatewayAndExplicitExclusion() {
        provider.setProviderCode("vidu");
        provider.setBaseUrl("https://api.vidu.test");

        AiModelConfigVo routed = service.selectByModelCodeForUser("kling-3.0-omni-i2v", 99L);
        assertEquals("https://gateway.test/vidu", routed.getBaseUrl());
        assertEquals("gateway-api-key", routed.getApiKey());

        gateway.setExcludedProviderIds(Set.of(7L));
        AiModelConfigVo excluded = service.selectByModelCodeForUser("kling-3.0-omni-i2v", 99L);
        assertEquals("https://api.vidu.test", excluded.getBaseUrl());
        assertEquals("provider-api-key", excluded.getApiKey());
    }

    @Test
    void enabledUserOverrideRemainsHighestPriorityForKling() {
        String userSecret = validSecret("user-webhook-secret");
        AidUserAiConfig userConfig = new AidUserAiConfig();
        userConfig.setIsEnable("0");
        userConfig.setCustomBaseUrl("https://api.bananarouter.com");
        userConfig.setCustomApiKey("user-api-key");
        userConfig.setCustomApiSecret(userSecret);
        when(userConfigService.getOne(any(), eq(false))).thenReturn(userConfig);

        AiModelConfigVo result = service.selectByModelCodeForUser("kling-3.0-omni-i2v", 99L);

        assertEquals("https://api.bananarouter.com", result.getBaseUrl());
        assertEquals("user-api-key", result.getApiKey());
        assertEquals(userSecret, result.getApiSecret());
    }

    @Test
    void klingByokKeyWithoutOwnWebhookSecretDoesNotInheritPlatformSecret() {
        AidUserAiConfig userConfig = new AidUserAiConfig();
        userConfig.setIsEnable("0");
        userConfig.setCustomApiKey("user-api-key");
        when(userConfigService.getOne(any(), eq(false))).thenReturn(userConfig);

        AiModelConfigVo result = service.selectByModelCodeForUser("kling-3.0-omni-i2v", 99L);

        assertEquals("user-api-key", result.getApiKey());
        assertNull(result.getApiSecret());
    }

    @Test
    void klingSecretWithoutByokKeyDoesNotReplacePlatformCredentialSource() {
        AidUserAiConfig userConfig = new AidUserAiConfig();
        userConfig.setIsEnable("0");
        userConfig.setCustomApiSecret(validSecret("unrelated-user-secret"));
        when(userConfigService.getOne(any(), eq(false))).thenReturn(userConfig);

        AiModelConfigVo result = service.selectByModelCodeForUser("kling-3.0-omni-i2v", 99L);

        assertEquals("provider-api-key", result.getApiKey());
        assertEquals(validSecret("platform-webhook-secret"), result.getApiSecret());
    }

    private AidAiModel model() {
        AidAiModel model = new AidAiModel();
        model.setId(3L);
        model.setProviderId(7L);
        model.setModelCode("kling-3.0-omni-i2v");
        model.setStatus("0");
        model.setDelFlag("0");
        return model;
    }

    private AidAiProvider klingProvider() {
        AidAiProvider value = new AidAiProvider();
        value.setId(7L);
        value.setProviderCode(KlingConstants.PROVIDER_CODE);
        value.setBaseUrl("https://api-beijing.klingai.com");
        value.setApiKey("provider-api-key");
        value.setApiSecret(validSecret("platform-webhook-secret"));
        value.setStatus("0");
        return value;
    }

    private OfficialGatewayConfig enabledGateway() {
        OfficialGatewayConfig value = new OfficialGatewayConfig();
        value.setEnabled(true);
        value.setBaseUrl("https://gateway.test/{provider}");
        value.setApiKey("gateway-api-key");
        value.setExcludedModelIds(Set.of());
        value.setExcludedProviderIds(Set.of());
        return value;
    }

    private String validSecret(String value) {
        return "whsec_" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
