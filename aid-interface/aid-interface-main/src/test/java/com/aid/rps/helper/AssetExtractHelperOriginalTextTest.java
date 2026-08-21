package com.aid.rps.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicScriptService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

class AssetExtractHelperOriginalTextTest
{
    private static final Long PROJECT_ID = 10L;
    private static final Long USER_ID = 20L;

    private AssetExtractHelper helper;
    private IAidComicScriptService scriptService;
    private IAidComicEpisodeService episodeService;

    @BeforeAll
    static void initializeMybatisMetadata()
    {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        assistant.setCurrentNamespace(AssetExtractHelperOriginalTextTest.class.getName());
        TableInfoHelper.initTableInfo(assistant, AidComicScript.class);
        TableInfoHelper.initTableInfo(assistant, AidComicEpisode.class);
    }

    @BeforeEach
    void setUp()
    {
        helper = new AssetExtractHelper();
        scriptService = mock(IAidComicScriptService.class);
        episodeService = mock(IAidComicEpisodeService.class);
        ReflectionTestUtils.setField(helper, "scriptService", scriptService);
        ReflectionTestUtils.setField(helper, "episodeService", episodeService);
    }

    @Test
    void singleScriptIgnoresStaleSimplifiedText()
    {
        AidComicScript script = script(1L, "当前正文", "已删除的制作规范");
        when(scriptService.getOne(any(Wrapper.class), eq(false))).thenReturn(script);

        assertEquals("当前正文", helper.loadScriptContent(PROJECT_ID, 1L, USER_ID));
    }

    @Test
    void allScriptsAndCharacterCountUseOnlyOriginalText()
    {
        List<AidComicScript> scripts = List.of(
                script(1L, "第一集正文", "第一集旧内容"),
                script(2L, "第二集正文", "第二集旧内容"));
        when(scriptService.list(any(Wrapper.class))).thenReturn(scripts);

        assertEquals("第一集正文\n\n---\n\n第二集正文", helper.loadAllScriptsContent(PROJECT_ID, USER_ID));
        assertEquals("第一集正文".length() + "第二集正文".length(),
                helper.countScriptCharacters(PROJECT_ID, null, USER_ID));
    }

    @Test
    void groupedScriptsIgnoreStaleSimplifiedText()
    {
        AidComicEpisode first = episode(1L, 1);
        AidComicEpisode second = episode(2L, 2);
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(scriptService.getOne(any(Wrapper.class), eq(false)))
                .thenReturn(script(1L, "第一集正文", "第一集旧内容"))
                .thenReturn(script(2L, "第二集正文", "第二集旧内容"));

        List<String> groups = helper.loadGroupedScriptsContent(PROJECT_ID, 2, USER_ID);

        assertEquals(1, groups.size());
        assertTrue(groups.get(0).contains("第一集正文"));
        assertTrue(groups.get(0).contains("第二集正文"));
        assertFalse(groups.get(0).contains("旧内容"));
    }

    private static AidComicScript script(Long episodeId, String originalText, String simplifiedText)
    {
        AidComicScript script = new AidComicScript();
        script.setId(episodeId);
        script.setEpisodeId(episodeId);
        script.setOriginalText(originalText);
        script.setSimplifiedText(simplifiedText);
        return script;
    }

    private static AidComicEpisode episode(Long id, int episodeNo)
    {
        AidComicEpisode episode = new AidComicEpisode();
        episode.setId(id);
        episode.setEpisodeNo((long) episodeNo);
        return episode;
    }
}
