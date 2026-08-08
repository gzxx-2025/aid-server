package com.aid.rps.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.util.HiddenStylePromptJsonUtils;
import com.aid.project.service.ProjectStyleSnapshotService;
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
        ReflectionTestUtils.setField(service, "projectStyleSnapshotService", new ProjectStyleSnapshotService());

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

    @Test
    void characterFinalPromptsUseHiddenSnapshotButDigestKeepsPublicStyle()
    {
        AssetExtractServiceImpl service = new AssetExtractServiceImpl();
        AssetExtractHelper helper = mock(AssetExtractHelper.class);
        when(helper.loadPromptByName("aid_character_form_image_builder")).thenReturn("角色设定卡");
        when(helper.loadPromptByName("aid_character_form_image_background_white")).thenReturn("白底角色");
        ReflectionTestUtils.setField(service, "helper", helper);
        ReflectionTestUtils.setField(service, "projectStyleSnapshotService", new ProjectStyleSnapshotService());

        AidComicProject project = new AidComicProject();
        project.setVideoStyleType("田园经营3D");
        project.setVideoStyleValue("公开中文田园风格");
        project.setHiddenStylePromptJson(HiddenStylePromptJsonUtils.fromCharacterPrompt("hidden pastoral 3D"));

        String cardPrompt = ReflectionTestUtils.invokeMethod(service,
                "buildCardImagePrompt", project, "16:9");
        String cardDigest = ReflectionTestUtils.invokeMethod(service,
                "buildCardImagePromptDigest", project, "16:9");

        AidRolePropScene asset = new AidRolePropScene();
        asset.setAssetType("character");
        AidRolePropSceneForm form = new AidRolePropSceneForm();
        form.setPromptText("{\"descriptions\":\"年轻女性，白色帆布鞋，图片风格：旧院线电影风格\"}");
        String whiteBackgroundPrompt = ReflectionTestUtils.invokeMethod(service,
                "buildCharacterFormImagePrompt", project, asset, form);

        assertTrue(cardPrompt.contains("hidden pastoral 3D"));
        assertFalse(cardPrompt.contains("公开中文田园风格"));
        assertTrue(whiteBackgroundPrompt.contains("hidden pastoral 3D"));
        assertFalse(whiteBackgroundPrompt.contains("公开中文田园风格"));
        assertTrue(whiteBackgroundPrompt.indexOf("hidden pastoral 3D")
                > whiteBackgroundPrompt.indexOf("旧院线电影风格"));
        assertTrue(cardDigest.contains("公开中文田园风格"));
        assertFalse(cardDigest.contains("hidden pastoral 3D"));
    }

    @Test
    void characterFinalPromptFallsBackWhenSnapshotIsInvalid()
    {
        AssetExtractServiceImpl service = new AssetExtractServiceImpl();
        AssetExtractHelper helper = mock(AssetExtractHelper.class);
        when(helper.loadPromptByName("aid_character_form_image_builder")).thenReturn("角色设定卡");
        ReflectionTestUtils.setField(service, "helper", helper);
        ReflectionTestUtils.setField(service, "projectStyleSnapshotService", new ProjectStyleSnapshotService());

        AidComicProject project = new AidComicProject();
        project.setVideoStyleType("旧项目风格");
        project.setVideoStyleValue("公开回退提示词");
        project.setHiddenStylePromptJson("invalid-json");

        String prompt = ReflectionTestUtils.invokeMethod(service,
                "buildCardImagePrompt", project, "16:9");

        assertTrue(prompt.contains("公开回退提示词"));
    }
}
