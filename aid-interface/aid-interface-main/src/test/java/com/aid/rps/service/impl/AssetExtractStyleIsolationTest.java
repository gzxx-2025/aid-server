package com.aid.rps.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.util.HiddenStylePromptJsonUtils;
import com.aid.project.service.ProjectStyleSnapshotService;

class AssetExtractStyleIsolationTest
{
    private AssetExtractServiceImpl service;

    private IAidComicProjectService projectService;

    private AidComicProject project;

    @BeforeEach
    void setUp()
    {
        service = new AssetExtractServiceImpl();
        projectService = mock(IAidComicProjectService.class);
        ReflectionTestUtils.setField(service, "projectService", projectService);
        ReflectionTestUtils.setField(service, "projectStyleSnapshotService", new ProjectStyleSnapshotService());

        project = new AidComicProject();
        project.setId(73L);
        project.setVideoStyleType("Pastoral 3D");
        project.setVideoStyleValue("PUBLIC_STYLE_FOR_SCENE_AND_PROP");
        project.setHiddenStylePromptJson(
                HiddenStylePromptJsonUtils.fromCharacterPrompt("HIDDEN_CHARACTER_ONLY"));
        when(projectService.selectAidComicProjectById(73L)).thenReturn(project);
    }

    @Test
    void characterFormDigestKeepsPublicStyleSnapshot()
    {
        AidRolePropScene character = new AidRolePropScene();
        character.setProjectId(73L);
        character.setAssetType("character");
        character.setName("Character A");
        AidRolePropSceneForm form = new AidRolePropSceneForm();
        form.setPromptText("{\"descriptions\":\"young woman\"}");

        String digest = ReflectionTestUtils.invokeMethod(
                service, "buildFormImagePromptDigest", character, form);

        assertTrue(digest.contains("PUBLIC_STYLE_FOR_SCENE_AND_PROP"));
        assertFalse(digest.contains("HIDDEN_CHARACTER_ONLY"));
        assertFalse(digest.contains("[art_style_name]"));
        assertFalse(digest.contains("Pastoral 3D"));
    }

    @Test
    void visualStylistUsesHiddenCharacterStyleWithoutPersistingStyleName()
    {
        AidRolePropScene character = new AidRolePropScene();
        character.setId(19L);
        character.setProjectId(73L);
        character.setAssetType("character");
        character.setName("Character A");
        character.setProfileData("{}");

        Map<String, String> inputs = ReflectionTestUtils.invokeMethod(
                service, "buildVisualStylistInputs", character);

        assertEquals("HIDDEN_CHARACTER_ONLY", inputs.get("art_style_prompt"));
        assertTrue(inputs.containsKey("character_profiles"));
        assertFalse(inputs.containsKey("art_style_name"));
        assertFalse(inputs.containsValue("Pastoral 3D"));
        assertFalse(inputs.containsValue("PUBLIC_STYLE_FOR_SCENE_AND_PROP"));
    }

    @Test
    void sceneAndPropInputsContinueUsingPublicStyleOnly()
    {
        AidRolePropScene scene = new AidRolePropScene();
        scene.setProjectId(73L);
        scene.setAssetType("scene");
        scene.setName("Farmhouse");
        scene.setSummary("quiet farm");
        scene.setIntroduction("wooden farmhouse and wheat field");

        AidRolePropScene prop = new AidRolePropScene();
        prop.setProjectId(73L);
        prop.setAssetType("prop");
        prop.setName("Wooden hoe");
        prop.setIntroduction("weathered wooden handle");

        Map<String, String> sceneInputs = ReflectionTestUtils.invokeMethod(
                service, "collectSceneStylistInputs", scene);
        Map<String, String> propInputs = ReflectionTestUtils.invokeMethod(
                service, "collectPropStylistVariables", prop);
        String legacyResolverResult = ReflectionTestUtils.invokeMethod(
                service, "resolveProjectArtStylePrompt", project);

        assertEquals("PUBLIC_STYLE_FOR_SCENE_AND_PROP", sceneInputs.get("art_style_prompt"));
        assertEquals("PUBLIC_STYLE_FOR_SCENE_AND_PROP", propInputs.get("art_style_prompt"));
        assertEquals("PUBLIC_STYLE_FOR_SCENE_AND_PROP", legacyResolverResult);
        assertFalse(sceneInputs.containsValue("HIDDEN_CHARACTER_ONLY"));
        assertFalse(propInputs.containsValue("HIDDEN_CHARACTER_ONLY"));
    }
}
