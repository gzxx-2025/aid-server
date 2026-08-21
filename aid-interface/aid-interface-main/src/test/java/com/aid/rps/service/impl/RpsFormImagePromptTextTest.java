package com.aid.rps.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.rps.dto.RpsFormImageListRequest;
import com.aid.rps.vo.RpsFormImageDetailVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

class RpsFormImagePromptTextTest
{
    private static final Long USER_ID = 71L;
    private static final Long PROJECT_ID = 81L;
    private static final String PUBLIC_STYLE = "公开水墨风格";
    private static final String HIDDEN_STYLE = "HIDDEN_STYLE_MUST_NOT_LEAK";
    private static final String CDN_DOMAIN = "https://cdn.example.test";
    private static final String RELATIVE_REFERENCE_URL = "/aid/2026/08/09/reference.png";
    private static final String REFERENCE_URL = "https://internal.example/reference.png";
    private static final String FINAL_PROMPT = "SYSTEM_TEMPLATE\n图片比例：16:9\n" + REFERENCE_URL;

    private RpsFormImageBusinessServiceImpl service;
    private IAidRolePropSceneFormImageService imageService;
    private IAidRolePropSceneFormService formService;
    private IAidRolePropSceneService assetService;
    private IAidComicProjectService projectService;
    private IAidExtractTaskService taskService;

    @BeforeEach
    void setUp()
    {
        initTableInfo(AidRolePropSceneFormImage.class);
        initTableInfo(AidRolePropSceneForm.class);
        initTableInfo(AidRolePropScene.class);
        initTableInfo(AidComicProject.class);
        initTableInfo(AidExtractTask.class);

        service = new RpsFormImageBusinessServiceImpl();
        imageService = mock(IAidRolePropSceneFormImageService.class);
        formService = mock(IAidRolePropSceneFormService.class);
        assetService = mock(IAidRolePropSceneService.class);
        projectService = mock(IAidComicProjectService.class);
        taskService = mock(IAidExtractTaskService.class);
        OssConfigManager ossConfigManager = mock(OssConfigManager.class);
        OssProperties ossProperties = new OssProperties();
        ossProperties.setUploadMode("oss");
        ossProperties.setCdnDomain(CDN_DOMAIN + "/");
        when(ossConfigManager.getOssProperties()).thenReturn(ossProperties);

        ReflectionTestUtils.setField(service, "rpsFormImageService", imageService);
        ReflectionTestUtils.setField(service, "rpsFormService", formService);
        ReflectionTestUtils.setField(service, "rpsService", assetService);
        ReflectionTestUtils.setField(service, "projectService", projectService);
        ReflectionTestUtils.setField(service, "extractTaskService", taskService);
        ReflectionTestUtils.setField(service, "mediaUrlResolver", new MediaUrlResolver(ossConfigManager));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listResolvesAllAllowedSourcesWithBatchQueriesAndNoSensitivePromptLeak()
    {
        AidRolePropScene character = asset(101L, "character", "角色甲");
        AidRolePropScene scene = asset(102L, "scene", "旧宅");
        AidRolePropScene prop = asset(103L, "prop", "青铜匕首");

        AidRolePropSceneForm characterForm = form(201L, 101L,
                "{\"descriptions\":\"银发青年，身着黑色长袍\",\"internal\":\"DO_NOT_RETURN\"}");
        AidRolePropSceneForm sceneForm = form(202L, 102L,
                "{\"title\":\"旧宅\",\"prompt\":\"斑驳旧宅四视图正文\",\"viewpoints\":{\"north\":\"内部数据\"}}");
        AidRolePropSceneForm propForm = form(203L, 103L,
                "[{\"title\":\"青铜匕首\",\"prompt\":\"青铜匕首纯白背景正文\",\"promptType\":\"text_to_image\"}]");

        List<AidRolePropSceneFormImage> images = List.of(
                image(1L, 201L, 101L, "ai_auto", null),
                image(2L, 202L, 102L, "ai_auto", null),
                image(3L, 203L, 103L, "ai_auto", null),
                image(4L, 201L, 101L, "upload", null),
                image(5L, 201L, 101L, "ai_builder", null),
                image(6L, 201L, 101L, "ai_edit_chat", "9001"),
                image(7L, 201L, 101L, "ai_edit_chat", "9002"),
                image(8L, 201L, 101L, "ai_multi_view", null),
                image(9L, 201L, 101L, "official", null),
                image(10L, 201L, 101L, "migrate", null));
        // 历史图片可能缺 project_id，必须从已批量加载的主资产 projectId 补齐公开风格。
        images.get(0).setProjectId(null);
        images.get(5).setPromptSnapshot(FINAL_PROMPT);
        images.get(5).setReferenceImages("[\"" + RELATIVE_REFERENCE_URL + "\",\""
                + REFERENCE_URL + "\"]");

        AidComicProject project = new AidComicProject();
        project.setId(PROJECT_ID);
        project.setVideoStyleValue(PUBLIC_STYLE);
        project.setHiddenStylePromptJson(HIDDEN_STYLE);

        AidExtractTask validTask = task(9001L,
                "{\"rawPrompt\":\"  把人物手中的雨伞改成黑色长剑  \","
                        + "\"referenceImages\":[\"" + REFERENCE_URL + "\"],"
                        + "\"finalPromptSummary\":\"" + FINAL_PROMPT.replace("\\", "\\\\")
                        .replace("\n", "\\n") + "\"}");
        AidExtractTask invalidTask = task(9002L,
                "{\"prompt\":\"INTERNAL_FINAL_PROMPT\",\"referenceImages\":[\""
                        + REFERENCE_URL + "\"]}");

        when(imageService.list(any(LambdaQueryWrapper.class))).thenReturn(images);
        when(formService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(characterForm, sceneForm, propForm));
        when(assetService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(character, scene, prop));
        when(projectService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(project));
        when(taskService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(validTask, invalidTask));

        RpsFormImageListRequest request = new RpsFormImageListRequest();
        request.setFormId(201L);
        List<RpsFormImageDetailVO> result = service.queryImageList(request, USER_ID);

        assertEquals("银发青年，身着黑色长袍\n\n使用风格：" + PUBLIC_STYLE,
                result.get(0).getPromptText());
        assertEquals("斑驳旧宅四视图正文\n\n使用风格：" + PUBLIC_STYLE,
                result.get(1).getPromptText());
        assertEquals("青铜匕首纯白背景正文\n\n使用风格：" + PUBLIC_STYLE,
                result.get(2).getPromptText());
        assertNull(result.get(3).getPromptText());
        assertNull(result.get(4).getPromptText());
        assertEquals("  把人物手中的雨伞改成黑色长剑  ", result.get(5).getPromptText());
        assertNull(result.get(6).getPromptText());
        assertNull(result.get(7).getPromptText());
        assertNull(result.get(8).getPromptText());
        assertNull(result.get(9).getPromptText());
        assertEquals(List.of(CDN_DOMAIN + RELATIVE_REFERENCE_URL, REFERENCE_URL),
                result.get(5).getReferenceImages());
        assertNull(result.get(0).getReferenceImages());

        for (RpsFormImageDetailVO item : result)
        {
            String promptText = item.getPromptText();
            if (promptText != null)
            {
                assertFalse(promptText.contains(HIDDEN_STYLE));
                assertFalse(promptText.contains(REFERENCE_URL));
                assertFalse(promptText.contains("SYSTEM_TEMPLATE"));
                assertFalse(promptText.contains("internal"));
                assertFalse(promptText.startsWith("{") || promptText.startsWith("["));
            }
        }

        verify(imageService, times(1)).list(any(LambdaQueryWrapper.class));
        verify(formService, times(1)).list(any(LambdaQueryWrapper.class));
        verify(assetService, times(1)).list(any(LambdaQueryWrapper.class));
        verify(projectService, times(1)).list(any(LambdaQueryWrapper.class));
        verify(taskService, times(1)).list(any(LambdaQueryWrapper.class));

        ArgumentCaptor<LambdaQueryWrapper<AidRolePropSceneFormImage>> imageQueryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(imageService).list(imageQueryCaptor.capture());
        String imageSelect = normalizeSelect(imageQueryCaptor.getValue().getSqlSelect());
        assertTrue(imageSelect.contains("batchno"));
        assertFalse(imageSelect.contains("promptsnapshot"));

        ArgumentCaptor<LambdaQueryWrapper<AidComicProject>> projectQueryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(projectService).list(projectQueryCaptor.capture());
        String projectSelect = normalizeSelect(projectQueryCaptor.getValue().getSqlSelect());
        assertTrue(projectSelect.contains("videostylevalue"));
        assertFalse(projectSelect.contains("hiddenstylepromptjson"));

        ArgumentCaptor<LambdaQueryWrapper<AidExtractTask>> taskQueryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(taskService).list(taskQueryCaptor.capture());
        String taskSelect = normalizeSelect(taskQueryCaptor.getValue().getSqlSelect());
        assertTrue(taskSelect.contains("inputsnapshot"));
        assertFalse(taskSelect.contains("resultdata"));
    }

    @Test
    void nullAndBlankProjectStylesReturnBusinessPromptWithoutFailing()
    {
        AidRolePropScene character = asset(101L, "character", "角色甲");
        AidRolePropSceneForm characterForm = form(201L, 101L,
                "{\"descriptions\":\"完整人物特征\"}");
        AidRolePropSceneFormImage characterImage = image(1L, 201L, 101L, "ai_auto", null);

        AidComicProject nullStyleProject = new AidComicProject();
        nullStyleProject.setId(PROJECT_ID);
        nullStyleProject.setVideoStyleValue(null);

        when(imageService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(characterImage));
        when(formService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(characterForm));
        when(assetService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(character));
        when(projectService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(nullStyleProject));

        RpsFormImageListRequest request = new RpsFormImageListRequest();
        request.setFormId(201L);
        List<RpsFormImageDetailVO> nullStyleResult = service.queryImageList(request, USER_ID);
        assertEquals("完整人物特征", nullStyleResult.get(0).getPromptText());

        nullStyleProject.setVideoStyleValue("   ");
        List<RpsFormImageDetailVO> blankStyleResult = service.queryImageList(request, USER_ID);
        assertEquals("完整人物特征", blankStyleResult.get(0).getPromptText());
    }

    @Test
    void malformedSnapshotsAndStructuredPromptsReturnNullInsteadOfRawContent()
    {
        assertNull(invokeExtractRawPrompt(null));
        assertNull(invokeExtractRawPrompt("not-json"));
        assertNull(invokeExtractRawPrompt("{\"rawPrompt\":123}"));
        assertNull(invokeExtractRawPrompt("{\"prompt\":\"internal template\"}"));

        String malformedScene = ReflectionTestUtils.invokeMethod(
                service, "extractScenePropBusinessPrompt", "{\"prompt\":");
        String missingScenePrompt = ReflectionTestUtils.invokeMethod(
                service, "extractScenePropBusinessPrompt", "{\"title\":\"secret metadata\"}");
        String malformedCharacter = ReflectionTestUtils.invokeMethod(
                service, "extractCharacterBusinessPrompt", "{\"descriptions\":", 0);

        assertNull(malformedScene);
        assertNull(missingScenePrompt);
        assertNull(malformedCharacter);
    }

    private String invokeExtractRawPrompt(String inputSnapshot)
    {
        return ReflectionTestUtils.invokeMethod(service, "extractRawPrompt", inputSnapshot);
    }

    private AidRolePropScene asset(Long id, String assetType, String name)
    {
        AidRolePropScene asset = new AidRolePropScene();
        asset.setId(id);
        asset.setAssetType(assetType);
        asset.setName(name);
        asset.setProjectId(PROJECT_ID);
        return asset;
    }

    private AidRolePropSceneForm form(Long id, Long assetId, String promptText)
    {
        AidRolePropSceneForm form = new AidRolePropSceneForm();
        form.setId(id);
        form.setAssetId(assetId);
        form.setName("形态" + id);
        form.setPromptText(promptText);
        return form;
    }

    private AidRolePropSceneFormImage image(Long id, Long formId, Long assetId,
                                            String sourceType, String batchNo)
    {
        AidRolePropSceneFormImage image = new AidRolePropSceneFormImage();
        image.setId(id);
        image.setFormId(formId);
        image.setAssetId(assetId);
        image.setProjectId(PROJECT_ID);
        image.setSourceType(sourceType);
        image.setBatchNo(batchNo);
        image.setDescriptionIndex(0);
        image.setIsSplitSource(0);
        image.setIsSplitChild(0);
        return image;
    }

    private AidExtractTask task(Long id, String inputSnapshot)
    {
        AidExtractTask task = new AidExtractTask();
        task.setId(id);
        task.setInputSnapshot(inputSnapshot);
        return task;
    }

    private void initTableInfo(Class<?> entityClass)
    {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new Configuration(), "rps-form-image-prompt-test-" + entityClass.getSimpleName());
        assistant.setCurrentNamespace("rps-form-image-prompt-test-" + entityClass.getSimpleName());
        TableInfoHelper.initTableInfo(assistant, entityClass);
    }

    private String normalizeSelect(String sqlSelect)
    {
        return sqlSelect.replace("_", "").toLowerCase();
    }
}
