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
    private IAidAgentService agentService;

    @BeforeEach
    void setUp() {
        resolver = new ProjectGenConfigResolverImpl();
        configMapper = mock(AidProjectGenConfigMapper.class);
        modelBusinessService = mock(IAiModelBusinessService.class);
        modelConfigService = mock(IAiModelConfigService.class);
        matrixResolver = mock(IGenAgentMatrixResolver.class);
        IAidComicProjectService projectService = mock(IAidComicProjectService.class);
        agentService = mock(IAidAgentService.class);

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

        AidAgent cardAgent = new AidAgent();
        cardAgent.setAgentCode("card-agent");
        cardAgent.setBizCategoryCode(ProjectGenConfigScene.CHARACTER_CARD_IMAGE.getSceneCode());
        cardAgent.setStatus(1);
        when(agentService.getByAgentCode("card-agent")).thenReturn(cardAgent);
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

    @Test
    void characterCardDefaultsTo16By9WhenGenerationPoolRatioIsBlank() {
        when(configMapper.selectOne(any())).thenReturn(null);
        when(matrixResolver.resolve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(cardMatrix("card-model", "2K", null));
        when(modelBusinessService.listAvailableModelsByFuncCode(anyString()))
                .thenReturn(List.of(modelVo("card-model")));
        when(modelConfigService.selectByModelCode("card-model"))
                .thenReturn(cardModel("card-model"));

        ResolvedSceneConfig result = resolveCard();

        assertEquals("16:9", result.getAspectRatio());
    }

    @Test
    void characterCardUsesGenerationPool21By9() {
        when(configMapper.selectOne(any())).thenReturn(null);
        when(matrixResolver.resolve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(cardMatrix("card-model", "2K", "21:9"));
        when(modelBusinessService.listAvailableModelsByFuncCode(anyString()))
                .thenReturn(List.of(modelVo("card-model")));
        when(modelConfigService.selectByModelCode("card-model"))
                .thenReturn(cardModel("card-model"));

        ResolvedSceneConfig result = resolveCard();

        assertEquals("21:9", result.getAspectRatio());
    }

    @Test
    void characterCardKeepsSaved21By9() {
        when(configMapper.selectOne(any())).thenReturn(savedCard("card-model", "2K", "21:9"));
        when(matrixResolver.resolve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(cardMatrix("card-model", "2K", "16:9"));
        when(modelBusinessService.listAvailableModelsByFuncCode(anyString()))
                .thenReturn(List.of(modelVo("card-model")));
        when(modelConfigService.selectByModelCode("card-model"))
                .thenReturn(cardModel("card-model"));

        ResolvedSceneConfig result = resolveCard();

        assertEquals("21:9", result.getAspectRatio());
    }

    @Test
    void characterCardUsesPoolRatioAfterSavedModelBecomesUnavailable() {
        when(configMapper.selectOne(any())).thenReturn(savedCard("disabled-card-model", "2K", "16:9"));
        when(matrixResolver.resolve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(cardMatrix("card-model", "2K", "21:9"));
        when(modelBusinessService.listAvailableModelsByFuncCode(anyString()))
                .thenReturn(List.of(modelVo("card-model")));
        when(modelConfigService.selectByModelCode("card-model"))
                .thenReturn(cardModel("card-model"));

        ResolvedSceneConfig result = resolveCard();

        assertEquals("card-model", result.getModelCode());
        assertEquals("21:9", result.getAspectRatio());
    }

    private ResolvedSceneConfig resolve() {
        return resolver.resolve(1L, 9L, ProjectGenConfigScene.CHARACTER_IMAGE,
                null, null, null, null);
    }

    private ResolvedSceneConfig resolveCard() {
        return resolver.resolve(1L, 9L, ProjectGenConfigScene.CHARACTER_CARD_IMAGE,
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

    private AidProjectGenConfig savedCard(String modelCode, String resolution, String aspectRatio) {
        AidProjectGenConfig config = saved(modelCode, resolution, aspectRatio);
        config.setAgentCode("card-agent");
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

    private GenAgentMatrixResult cardMatrix(String modelCode, String resolution, String aspectRatio) {
        return GenAgentMatrixResult.builder()
                .configured(true)
                .agentCode("card-agent")
                .modelCode(modelCode)
                .resolution(resolution)
                .aspectRatio(aspectRatio)
                .build();
    }

    private AiModelConfigVo cardModel(String modelCode) {
        return model(modelCode, true,
                "{\"sizeOptions\":[\"1K\",\"2K\"],\"defaultSize\":\"2K\","
                        + "\"aspectRatioOptions\":[\"16:9\",\"21:9\"],"
                        + "\"defaultAspectRatio\":\"16:9\","
                        + "\"sceneRules\":{\"imageToImage\":{\"sizeOptions\":[\"1K\",\"2K\"],"
                        + "\"aspectRatioOptions\":[\"16:9\",\"21:9\"]}}}");
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
