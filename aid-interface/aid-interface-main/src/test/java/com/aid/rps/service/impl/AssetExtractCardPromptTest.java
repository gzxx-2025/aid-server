package com.aid.rps.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aid.aid.domain.AidComicProject;
import com.aid.rps.helper.AssetExtractHelper;

class AssetExtractCardPromptTest
{
    @Test
    void replacesLegacyHardcodedRatioWithFinalGenerationRatio()
    {
        AssetExtractServiceImpl service = new AssetExtractServiceImpl();
        AssetExtractHelper helper = mock(AssetExtractHelper.class);
        when(helper.loadPromptByName("aid_character_form_image_builder"))
                .thenReturn("生成 21:9 超宽角色设定卡，比例占位 {aspect_ratio}");
        ReflectionTestUtils.setField(service, "helper", helper);

        AidComicProject project = new AidComicProject();
        project.setVideoStyleType("二维动画");
        project.setVideoStyleValue("赛璐璐风格");
        String prompt = ReflectionTestUtils.invokeMethod(service,
                "buildCardImagePrompt", project, "16:9");

        assertTrue(prompt.contains("生成 16:9 超宽角色设定卡"));
        assertTrue(prompt.contains("比例占位 16:9"));
        assertTrue(prompt.contains("画布比例：16:9"));
        assertFalse(prompt.contains("21:9"));
    }
}
