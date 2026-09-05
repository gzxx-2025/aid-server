package com.aid.project.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.exception.ServiceException;
import com.aid.project.dto.UserProjectCreateRequest;
import com.aid.project.dto.UserProjectUpdateRequest;
import com.aid.project.service.ProjectStyleSnapshotService;
import com.aid.project.vo.UserProjectVO;
import com.aid.projectgenconfig.service.IProjectGenConfigService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

class UserProjectBusinessStyleSnapshotTest
{
    private UserProjectBusinessServiceImpl service;
    private IAidComicProjectService projectService;
    private ProjectStyleSnapshotService styleSnapshotService;
    private IAidRolePropSceneService rolePropSceneService;

    @BeforeAll
    static void initializeMybatisMetadata()
    {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        assistant.setCurrentNamespace(UserProjectBusinessStyleSnapshotTest.class.getName());
        TableInfoHelper.initTableInfo(assistant, AidComicProject.class);
        TableInfoHelper.initTableInfo(assistant, AidComicEpisode.class);
        TableInfoHelper.initTableInfo(assistant, AidExtractTask.class);
        TableInfoHelper.initTableInfo(assistant, AidRolePropScene.class);
    }

    @BeforeEach
    void setUp()
    {
        service = new UserProjectBusinessServiceImpl();
        projectService = mock(IAidComicProjectService.class);
        styleSnapshotService = mock(ProjectStyleSnapshotService.class);
        rolePropSceneService = mock(IAidRolePropSceneService.class);
        ReflectionTestUtils.setField(service, "aidComicProjectService", projectService);
        ReflectionTestUtils.setField(service, "projectStyleSnapshotService", styleSnapshotService);
        ReflectionTestUtils.setField(service, "rolePropSceneService", rolePropSceneService);
        ReflectionTestUtils.setField(service, "extractTaskService", mock(IAidExtractTaskService.class));
        ReflectionTestUtils.setField(service, "aidComicEpisodeService", mock(IAidComicEpisodeService.class));
        ReflectionTestUtils.setField(service, "aidStoryboardService", mock(IAidStoryboardService.class));
        ReflectionTestUtils.setField(service, "projectGenConfigService", mock(IProjectGenConfigService.class));
    }

    @Test
    void createCopiesServerResolvedStyleSnapshot()
    {
        String hiddenJson = "{\"character\":\"hidden 3D\",\"scene\":\"\",\"prop\":\"\"}";
        when(styleSnapshotService.resolve("official", 31L, 9L))
                .thenReturn(new ProjectStyleSnapshotService.ResolvedProjectStyle(
                        "田园经营3D", "公开中文风格", hiddenJson));
        when(projectService.save(any(AidComicProject.class))).thenReturn(true);

        UserProjectCreateRequest request = new UserProjectCreateRequest();
        request.setProjectName("测试项目");
        request.setProjectType("series");
        request.setStyleSource("official");
        request.setStyleAssetId(31L);

        AidComicProject result = service.insertUserProject(request, 9L);

        assertEquals("田园经营3D", result.getVideoStyleType());
        assertEquals("公开中文风格", result.getVideoStyleValue());
        assertEquals("official", result.getStyleSource());
        assertEquals(31L, result.getStyleAssetId());
        assertEquals(hiddenJson, result.getHiddenStylePromptJson());
    }

    @Test
    void realStyleSwitchIsRejectedWhenProjectAlreadyHasAsset()
    {
        AidComicProject current = new AidComicProject();
        current.setId(100L);
        current.setUserId(9L);
        current.setProjectType("movie");
        current.setVideoStyleType("旧风格");
        current.setVideoStyleValue("旧公开提示词");
        current.setHiddenStylePromptJson("{\"character\":\"old\",\"scene\":\"\",\"prop\":\"\"}");
        current.setStatus(0);
        when(projectService.getOne(any(Wrapper.class))).thenReturn(current);
        when(styleSnapshotService.resolve("official", 31L, 9L))
                .thenReturn(new ProjectStyleSnapshotService.ResolvedProjectStyle(
                        "新风格", "新公开提示词",
                        "{\"character\":\"new\",\"scene\":\"\",\"prop\":\"\"}"));
        AidRolePropScene asset = new AidRolePropScene();
        asset.setId(1L);
        when(rolePropSceneService.getOne(any(Wrapper.class), eq(false))).thenReturn(asset);

        UserProjectUpdateRequest request = new UserProjectUpdateRequest();
        request.setId(100L);
        request.setStyleSource("official");
        request.setStyleAssetId(31L);

        assertThrows(ServiceException.class, () -> service.updateUserProject(request, 9L));
        assertEquals("旧风格", current.getVideoStyleType());
        assertEquals("old", com.aid.aid.util.HiddenStylePromptJsonUtils.resolve(
                current.getHiddenStylePromptJson(), "character", ""));
    }

    @Test
    void seriesProjectWithEpisodesCanUpdateContentSettingsBeforeAssetsExist()
    {
        AidComicProject current = new AidComicProject();
        current.setId(100L);
        current.setUserId(9L);
        current.setProjectType("series");
        current.setAspectRatio("16:9");
        current.setScriptType("plot");
        current.setVideoStyleType("旧风格");
        current.setVideoStyleValue("旧公开提示词");
        current.setStyleSource("official");
        current.setStyleAssetId(30L);
        current.setHiddenStylePromptJson("{\"character\":\"old\",\"scene\":\"\",\"prop\":\"\"}");
        current.setDefaultGenMode("economy");
        current.setDefaultCreationMode("i2v");
        current.setStatus(0);
        when(projectService.getOne(any(Wrapper.class))).thenReturn(current);
        when(projectService.update(any(AidComicProject.class), any(Wrapper.class))).thenReturn(true);
        when(styleSnapshotService.resolve("official", 31L, 9L))
                .thenReturn(new ProjectStyleSnapshotService.ResolvedProjectStyle(
                        "新风格", "新公开提示词",
                        "{\"character\":\"new\",\"scene\":\"\",\"prop\":\"\"}"));

        IAidComicEpisodeService episodeService = (IAidComicEpisodeService) ReflectionTestUtils.getField(
                service, "aidComicEpisodeService");
        when(episodeService.count(any(Wrapper.class))).thenReturn(3L);

        UserProjectUpdateRequest request = new UserProjectUpdateRequest();
        request.setId(100L);
        request.setAspectRatio("9:16");
        request.setScriptType("monologue");
        request.setStyleSource("official");
        request.setStyleAssetId(31L);
        request.setDefaultGenMode("performance");
        request.setDefaultCreationMode("multi");

        AidComicProject result = service.updateUserProject(request, 9L);

        assertEquals("9:16", result.getAspectRatio());
        assertEquals("monologue", result.getScriptType());
        assertEquals("新风格", result.getVideoStyleType());
        assertEquals("新公开提示词", result.getVideoStyleValue());
        assertEquals("official", result.getStyleSource());
        assertEquals(31L, result.getStyleAssetId());
        assertEquals("new", com.aid.aid.util.HiddenStylePromptJsonUtils.resolve(
                result.getHiddenStylePromptJson(), "character", ""));
        assertEquals("performance", result.getDefaultGenMode());
        assertEquals("multi", result.getDefaultCreationMode());
        verifyNoInteractions(episodeService);
    }

    @Test
    void projectVoExposesStableStyleReference()
    {
        AidComicProject project = new AidComicProject();
        project.setId(241L);
        project.setStyleSource("custom");
        project.setStyleAssetId(86L);

        UserProjectVO result = ReflectionTestUtils.invokeMethod(
                service, "buildProjectVO", project, null, null, false);

        assertEquals("custom", result.getStyleSource());
        assertEquals(86L, result.getStyleAssetId());
    }

    @Test
    void unchangedLegacyStyleFieldsPreserveStableReference()
    {
        AidComicProject current = new AidComicProject();
        current.setId(100L);
        current.setUserId(9L);
        current.setProjectType("movie");
        current.setVideoStyleType("当前风格");
        current.setVideoStyleValue("当前公开提示词");
        current.setStyleSource("custom");
        current.setStyleAssetId(86L);
        current.setHiddenStylePromptJson("{\"character\":\"hidden\",\"scene\":\"\",\"prop\":\"\"}");
        current.setStatus(0);
        when(projectService.getOne(any(Wrapper.class))).thenReturn(current);
        when(projectService.update(any(AidComicProject.class), any(Wrapper.class))).thenReturn(true);

        AidRolePropScene asset = new AidRolePropScene();
        asset.setId(1L);
        when(rolePropSceneService.getOne(any(Wrapper.class), eq(false))).thenReturn(asset);

        UserProjectUpdateRequest request = new UserProjectUpdateRequest();
        request.setId(100L);
        request.setVideoStyleType("当前风格");
        request.setVideoStyleValue("当前公开提示词");

        AidComicProject result = service.updateUserProject(request, 9L);

        assertEquals("custom", result.getStyleSource());
        assertEquals(86L, result.getStyleAssetId());
    }
}
