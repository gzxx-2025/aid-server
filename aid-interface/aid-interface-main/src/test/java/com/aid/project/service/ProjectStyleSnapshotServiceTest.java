package com.aid.project.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aid.aid.domain.AidComicAsset;
import com.aid.aid.domain.AidUserComicAsset;
import com.aid.aid.service.IAidComicAssetService;
import com.aid.aid.service.IAidUserComicAssetService;
import com.aid.aid.util.HiddenStylePromptJsonUtils;
import com.aid.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

class ProjectStyleSnapshotServiceTest
{
    private ProjectStyleSnapshotService snapshotService;

    private IAidComicAssetService comicAssetService;

    private IAidUserComicAssetService userComicAssetService;

    @BeforeAll
    static void initializeMybatisMetadata()
    {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        assistant.setCurrentNamespace(ProjectStyleSnapshotServiceTest.class.getName());
        TableInfoHelper.initTableInfo(assistant, AidComicAsset.class);
        TableInfoHelper.initTableInfo(assistant, AidUserComicAsset.class);
    }

    @BeforeEach
    void setUp()
    {
        snapshotService = new ProjectStyleSnapshotService();
        comicAssetService = mock(IAidComicAssetService.class);
        userComicAssetService = mock(IAidUserComicAssetService.class);
        ReflectionTestUtils.setField(snapshotService, "comicAssetService", comicAssetService);
        ReflectionTestUtils.setField(snapshotService, "userComicAssetService", userComicAssetService);
    }

    @Test
    void resolvesOfficialStyleAndNormalizesItsHiddenSnapshot()
    {
        AidComicAsset asset = new AidComicAsset();
        asset.setId(31L);
        asset.setAssetName("Pastoral 3D");
        asset.setPromptText("PUBLIC_OFFICIAL_STYLE");
        asset.setHiddenStylePromptJson("{\"character\":\"HIDDEN_OFFICIAL_CHARACTER\"}");
        when(comicAssetService.getOne(argThat(wrapper -> containsValues(
                getParamValues(wrapper), 31L, "style", "0")), eq(false))).thenReturn(asset);

        ProjectStyleSnapshotService.ResolvedProjectStyle result =
                snapshotService.resolve(" OFFICIAL ", 31L, 9L);

        assertEquals("Pastoral 3D", result.styleName());
        assertEquals("PUBLIC_OFFICIAL_STYLE", result.publicPrompt());
        assertEquals("HIDDEN_OFFICIAL_CHARACTER", HiddenStylePromptJsonUtils.resolve(
                result.hiddenPromptJson(), HiddenStylePromptJsonUtils.KEY_CHARACTER, "fallback"));
        assertTrue(result.hiddenPromptJson().contains("\"scene\":\"\""));
        assertTrue(result.hiddenPromptJson().contains("\"prop\":\"\""));
        verify(userComicAssetService, never()).getOne(org.mockito.ArgumentMatchers.any(), eq(false));
    }

    @Test
    void resolvesOnlyOwnedCustomStyleAndBackfillsLegacyHiddenPrompt()
    {
        AidUserComicAsset asset = new AidUserComicAsset();
        asset.setId(52L);
        asset.setUserId(88L);
        asset.setAssetName("My style");
        asset.setPromptText("CUSTOM_PUBLIC_PROMPT");
        when(userComicAssetService.getOne(argThat(wrapper -> containsValues(
                getParamValues(wrapper), 52L, 88L, "style", "0")), eq(false))).thenReturn(asset);

        ProjectStyleSnapshotService.ResolvedProjectStyle result =
                snapshotService.resolve("custom", 52L, 88L);

        assertEquals("My style", result.styleName());
        assertEquals("CUSTOM_PUBLIC_PROMPT", result.publicPrompt());
        assertEquals("CUSTOM_PUBLIC_PROMPT", HiddenStylePromptJsonUtils.resolve(
                result.hiddenPromptJson(), HiddenStylePromptJsonUtils.KEY_CHARACTER, "fallback"));
        assertEquals("", HiddenStylePromptJsonUtils.resolve(
                result.hiddenPromptJson(), HiddenStylePromptJsonUtils.KEY_SCENE, ""));
        verify(comicAssetService, never()).getOne(org.mockito.ArgumentMatchers.any(), eq(false));
    }

    @Test
    void rejectsCustomStyleWhenOwnershipFilteredQueryFindsNothing()
    {
        when(userComicAssetService.getOne(org.mockito.ArgumentMatchers.any(), eq(false))).thenReturn(null);

        assertThrows(ServiceException.class,
                () -> snapshotService.resolve("custom", 52L, 999L));

        verify(userComicAssetService).getOne(argThat(wrapper -> containsValues(
                getParamValues(wrapper), 52L, 999L, "style", "0")), eq(false));
    }

    private Map<String, Object> getParamValues(Wrapper<?> wrapper)
    {
        if (wrapper instanceof AbstractWrapper<?, ?, ?> abstractWrapper)
        {
            // MyBatis-Plus 在首次生成 SQL 片段时才填充参数映射。
            abstractWrapper.getSqlSegment();
            return abstractWrapper.getParamNameValuePairs();
        }
        return Map.of();
    }

    private boolean containsValues(Map<String, Object> params, Object... expectedValues)
    {
        for (Object expectedValue : expectedValues)
        {
            if (!params.containsValue(expectedValue))
            {
                return false;
            }
        }
        return true;
    }
}
