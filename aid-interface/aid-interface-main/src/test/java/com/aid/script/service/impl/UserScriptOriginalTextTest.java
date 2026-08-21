package com.aid.script.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidComicScriptService;
import com.aid.script.dto.UserScriptSaveRequest;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.fasterxml.jackson.databind.ObjectMapper;

class UserScriptOriginalTextTest
{
    private static final Long PROJECT_ID = 10L;
    private static final Long USER_ID = 20L;

    private UserScriptBusinessServiceImpl service;
    private IAidComicScriptService scriptService;
    private IAidComicProjectService projectService;

    @BeforeAll
    static void initializeMybatisMetadata()
    {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        assistant.setCurrentNamespace(UserScriptOriginalTextTest.class.getName());
        TableInfoHelper.initTableInfo(assistant, AidComicScript.class);
        TableInfoHelper.initTableInfo(assistant, AidComicProject.class);
    }

    @BeforeEach
    void setUp()
    {
        service = new UserScriptBusinessServiceImpl();
        scriptService = mock(IAidComicScriptService.class);
        projectService = mock(IAidComicProjectService.class);
        ReflectionTestUtils.setField(service, "aidComicScriptService", scriptService);
        ReflectionTestUtils.setField(service, "aidComicProjectService", projectService);
        ReflectionTestUtils.setField(service, "aidComicEpisodeService", mock(IAidComicEpisodeService.class));

        AidComicProject project = new AidComicProject();
        project.setId(PROJECT_ID);
        project.setProjectType("movie");
        when(projectService.getOne(any(Wrapper.class))).thenReturn(project);
    }

    @Test
    void savingNewVersionDoesNotInheritSimplifiedText()
    {
        AidComicScript current = new AidComicScript();
        current.setId(1L);
        current.setProjectId(PROJECT_ID);
        current.setEpisodeId(0L);
        current.setComicVersion(3);
        current.setIsExtracted(1);
        current.setSimplifiedText("已删除的制作规范");
        when(scriptService.getOne(any(Wrapper.class))).thenReturn(current);
        when(scriptService.update(any(Wrapper.class))).thenReturn(true);
        when(scriptService.save(any(AidComicScript.class))).thenReturn(true);

        UserScriptSaveRequest request = new UserScriptSaveRequest();
        request.setProjectId(PROJECT_ID);
        request.setEpisodeId(0L);
        request.setOriginalText("当前正文");

        AidComicScript saved = service.saveUserScript(request, USER_ID);

        ArgumentCaptor<AidComicScript> captor = ArgumentCaptor.forClass(AidComicScript.class);
        verify(scriptService).save(captor.capture());
        assertEquals("当前正文", saved.getOriginalText());
        assertEquals(4, saved.getComicVersion());
        assertNull(saved.getSimplifiedText());
        assertNull(captor.getValue().getSimplifiedText());
    }

    @Test
    void compatibilityFieldIsNotPartOfJsonApi() throws Exception
    {
        ObjectMapper objectMapper = new ObjectMapper();
        AidComicScript script = new AidComicScript();
        script.setOriginalText("当前正文");
        script.setSimplifiedText("旧内容");

        String json = objectMapper.writeValueAsString(script);
        AidComicScript parsed = objectMapper.readValue(
                "{\"originalText\":\"新正文\",\"simplifiedText\":\"外部旧内容\"}",
                AidComicScript.class);

        assertFalse(json.contains("simplifiedText"));
        assertEquals("新正文", parsed.getOriginalText());
        assertNull(parsed.getSimplifiedText());
    }

    @Test
    void compatibilityColumnIsMappedButExcludedFromCrud()
    {
        TableInfo tableInfo = TableInfoHelper.getTableInfo(AidComicScript.class);
        TableFieldInfo fieldInfo = tableInfo.getFieldList().stream()
                .filter(field -> "simplifiedText".equals(field.getProperty()))
                .findFirst()
                .orElseThrow();

        assertEquals("simplified_text", fieldInfo.getColumn());
        assertFalse(fieldInfo.isSelect());
        assertSame(com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER, fieldInfo.getInsertStrategy());
        assertSame(com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER, fieldInfo.getUpdateStrategy());
    }
}
