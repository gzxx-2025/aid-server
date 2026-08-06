package com.aid.aid.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aid.aid.domain.AidConfig;
import com.aid.aid.domain.vo.AdminBrandConfigVO;
import com.aid.aid.service.IAidConfigService;

/**
 * 后台平台品牌配置服务测试。
 *
 * @author 视觉AID
 */
class AdminBrandConfigServiceImplTest
{
    private IAidConfigService aidConfigService;

    private AdminBrandConfigServiceImpl service;

    @BeforeEach
    void setUp()
    {
        aidConfigService = mock(IAidConfigService.class);
        service = new AdminBrandConfigServiceImpl();
        ReflectionTestUtils.setField(service, "aidConfigService", aidConfigService);
    }

    @Test
    void shouldLoadSiteNameAndBrandImages()
    {
        AidConfig logo = config(AdminBrandConfigServiceImpl.KEY_PLATFORM_LOGO, "/brand/logo.png");
        AidConfig favicon = config(AdminBrandConfigServiceImpl.KEY_FAVICON, "/brand/favicon.ico");
        when(aidConfigService.selectAidConfigList(any(AidConfig.class))).thenReturn(List.of(logo, favicon));
        when(aidConfigService.getConfigValue(
                AdminBrandConfigServiceImpl.BASIC_CATEGORY,
                AdminBrandConfigServiceImpl.KEY_SITE_NAME)).thenReturn(" 视觉AID ");

        AdminBrandConfigVO result = service.getPublicConfig();

        assertEquals("视觉AID", result.getSiteName());
        assertEquals("/brand/logo.png", result.getPlatformLogoUrl());
        assertEquals("/brand/favicon.ico", result.getFaviconUrl());
    }

    @Test
    void shouldKeepBrandImagesWhenSiteNameCannotBeRead()
    {
        AidConfig logo = config(AdminBrandConfigServiceImpl.KEY_PLATFORM_LOGO, "/brand/logo.png");
        when(aidConfigService.selectAidConfigList(any(AidConfig.class))).thenReturn(List.of(logo));
        when(aidConfigService.getConfigValue(
                AdminBrandConfigServiceImpl.BASIC_CATEGORY,
                AdminBrandConfigServiceImpl.KEY_SITE_NAME)).thenThrow(new RuntimeException("未配置"));

        AdminBrandConfigVO result = service.getPublicConfig();

        assertNull(result.getSiteName());
        assertEquals("/brand/logo.png", result.getPlatformLogoUrl());
    }

    private AidConfig config(String name, String value)
    {
        AidConfig config = new AidConfig();
        config.setConfigName(name);
        config.setConfigValue(value);
        return config;
    }
}
