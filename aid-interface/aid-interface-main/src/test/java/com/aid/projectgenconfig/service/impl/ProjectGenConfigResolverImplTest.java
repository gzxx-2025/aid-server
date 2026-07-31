package com.aid.projectgenconfig.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aid.agent.IAidAgentService;
import com.aid.aid.domain.AidAgent;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidProjectGenConfig;
import com.aid.aid.mapper.AidProjectGenConfigMapper;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.model.service.IAiModelBusinessService;
import com.aid.model.vo.AiModelVO;
import com.aid.projectgenconfig.enums.ProjectGenConfigScene;
import com.aid.projectgenconfig.matrix.GenAgentMatrixResult;
import com.aid.projectgenconfig.matrix.IGenAgentMatrixResolver;
import com.aid.projectgenconfig.service.ResolvedSceneConfig;
import com.aid.service.IAiModelConfigService;

class ProjectGenConfigResolverImplTest {

    private ProjectGenConfigResolverImpl resolver;
    private AidProjectGenConfigMapper configMapper;
    private IAiModelBusinessService modelBusinessService;
    private IAiModelConfigService modelConfigService;
    private IGenAgentMatrixResolver matrixResolver;

    @BeforeEach
    void setUp() {
        resolver = new ProjectGenConfigResolverImpl();
        configMapper = mock(AidProjectGenConfigMapper.class);
        modelBusinessService = mock(IAiModelBusinessService.class);
        modelConfigService = mock(IAiModelConfigService.class);
        matrixResolver = mock(IGenAgentMatrixResolver.class);
        IAidComicProjectService projectService = mock(IAidComicProjectService.class);
        IAidAgentService agentService = mock(IAidAgentService.class);

        AidComicProject project = new AidComicProject();
        project.setId(1L);
        project.setProjectType("movie");
        project.setDefaultCreationMode("i2v");
        project.setDefaultGenMode("economy");
        project.setScriptType("plot");
        when(projectService.getOne(any())).thenReturn(project);

        AidAgent agent = new AidAgent();
        agent.setAgentCode("image-agent");
        agent.setBizCategoryCode(ProjectGenConfigScene.CHARACTER_IMAGE.getSceneCode());
        agent.setStatus(1);
        when(agentService.getByAgentCode("image-agent")).thenReturn(agent);
        when(matrixResolver.isAgentAllowed(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        ReflectionTestUtils.setField(resolver, "projectGenConfigMapper", configMapper);
        ReflectionTestUtils.setField(resolver, "projectService", projectService);
        ReflectionTestUtils.setField(resolver, "aidComicEpisodeService", mock(IAidComicEpisodeService.class));
        ReflectionTestUtils.setField(resolver, "aidAgentService", agentService);
        ReflectionTestUtils.setField(resolver, "aiModelBusinessService", modelBusinessService);
        ReflectionTestUtils.setField(resolver, "aiModelConfigService", modelConfigService);
        ReflectionTestUtils.setField(resolver, "genAgentMatrixResolver", matrixResolver);
    }

    @Test
    void fallsBackWhenSavedModelProviderIsDisabled() {
        when(configMapper.selectOne(any())).thenReturn(saved("gpt-image-2", "2K", "1:1"));
        GenAgentMatrixResult matrix = matrix("wan2.7-image", "1K", "1:1");
        when(matrixResolver.resolve(anyString(), anyString(), anyString(), anyString())).thenReturn(matrix);
        when(modelBusinessService.listAvailableModelsByFuncCode(anyString()))
                .thenReturn(List.of(modelVo("wan2.7-image")));
        when(modelConfigService.selectByModelCode("wan2.7-image"))
                .thenReturn(model("wan2.7-image", true,
                        "{\"sizeOptions\":[\"1K\",\"2K\"],\"defaultSize\":\"2K\","
                                + "\"aspectRatioOptions\":[\"1:1\",\"16:9\"],"
                                + "\"defaultAspectRatio\":\"1:1\","
                                + "\"sceneRules\":{\"textToImage\":{\"sizeOptions\":[\"1K\",\"2K\"]}}}"));

        ResolvedSceneConfig result = resolve();

        assertEquals("wan2.7-image", result.getModelCode());
        assertEquals("2K", result.getResolution());
        assertEquals("1:1", result.getAspectRatio());
    }

    @Test
    void repairsStaleSizeAndDropsUnsupportedPseudoRatio() {
        when(configMapper.selectOne(any())).thenReturn(saved("agnes-image-2.0-flash", "2K", "16:9"));
        when(matrixResolver.resolve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(matrix("agnes-image-2.0-flash", "1024x1024", null));
        when(modelBusinessService.listAvailableModelsByFuncCode(anyString()))
                .thenReturn(List.of(modelVo("agnes-image-2.0-flash")));
        when(modelConfigService.selectByModelCode("agnes-image-2.0-flash"))
                .thenReturn(model("agnes-image-2.0-flash", false,
                        "{\"sizeOptions\":[\"1024x768\",\"1024x1024\",\"768x1024\"],"
                                + "\"defaultSize\":\"1024x1024\","
                                + "\"sceneRules\":{\"textToImage\":{\"supportsAspectRatio\":false}}}"));

        ResolvedSceneConfig result = resolve();

        assertEquals("1024x1024", result.getResolution());
        assertNull(result.getAspectRatio());
    }

    private ResolvedSceneConfig resolve() {
        return resolver.resolve(1L, 9L, ProjectGenConfigScene.CHARACTER_IMAGE,
                null, null, null, null);
    }

    private AidProjectGenConfig saved(String modelCode, String resolution, String aspectRatio) {
        AidProjectGenConfig config = new AidProjectGenConfig();
        config.setAgentCode("image-agent");
        config.setModelCode(modelCode);
        config.setResolution(resolution);
        config.setAspectRatio(aspectRatio);
        return config;
    }

    private GenAgentMatrixResult matrix(String modelCode, String resolution, String aspectRatio) {
        return GenAgentMatrixResult.builder()
                .configured(true)
                .agentCode("image-agent")
                .modelCode(modelCode)
                .resolution(resolution)
                .aspectRatio(aspectRatio)
                .build();
    }

    private AiModelVO modelVo(String modelCode) {
        AiModelVO model = new AiModelVO();
        model.setModelCode(modelCode);
        return model;
    }

    private AiModelConfigVo model(String modelCode, boolean supportsAspectRatio, String capabilityJson) {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setModelCode(modelCode);
        model.setModelType("image");
        model.setSupportsAspectRatio(supportsAspectRatio);
        model.setCapabilityJson(capabilityJson);
        return model;
    }
}
