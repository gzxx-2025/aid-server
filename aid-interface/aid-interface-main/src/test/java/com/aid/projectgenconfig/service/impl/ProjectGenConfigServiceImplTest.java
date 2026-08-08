package com.aid.projectgenconfig.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aid.projectgenconfig.enums.ProjectGenConfigScene;
import com.aid.projectgenconfig.vo.ProjectGenConfigVO;

class ProjectGenConfigServiceImplTest
{
    @Test
    void defaultCharacterCardConfigKeepsGenerationPool21By9()
    {
        ProjectGenConfigVO config = ProjectGenConfigVO.builder()
                .sceneCode(ProjectGenConfigScene.CHARACTER_CARD_IMAGE.getSceneCode())
                .modelCode("card-model")
                .aspectRatio("21:9")
                .source("default")
                .build();

        ReflectionTestUtils.invokeMethod(new ProjectGenConfigServiceImpl(),
                "applyCharacterCardDefaultAspectRatio", config, ProjectGenConfigScene.CHARACTER_CARD_IMAGE);

        assertEquals("21:9", config.getAspectRatio());
    }

    @Test
    void savedCharacterCardConfigKeeps21By9()
    {
        ProjectGenConfigVO config = ProjectGenConfigVO.builder()
                .sceneCode(ProjectGenConfigScene.CHARACTER_CARD_IMAGE.getSceneCode())
                .modelCode("card-model")
                .aspectRatio("21:9")
                .source("project")
                .build();

        ReflectionTestUtils.invokeMethod(new ProjectGenConfigServiceImpl(),
                "applyCharacterCardDefaultAspectRatio", config, ProjectGenConfigScene.CHARACTER_CARD_IMAGE);

        assertEquals("21:9", config.getAspectRatio());
    }

    @Test
    void savedCharacterCardConfigWithoutRatioReturns16By9()
    {
        ProjectGenConfigVO config = ProjectGenConfigVO.builder()
                .sceneCode(ProjectGenConfigScene.CHARACTER_CARD_IMAGE.getSceneCode())
                .modelCode("card-model")
                .source("project")
                .build();

        ReflectionTestUtils.invokeMethod(new ProjectGenConfigServiceImpl(),
                "applyCharacterCardDefaultAspectRatio", config, ProjectGenConfigScene.CHARACTER_CARD_IMAGE);

        assertEquals("16:9", config.getAspectRatio());
    }

    @Test
    void defaultCharacterCardConfigWithoutPoolRatioReturns16By9()
    {
        ProjectGenConfigVO config = ProjectGenConfigVO.builder()
                .sceneCode(ProjectGenConfigScene.CHARACTER_CARD_IMAGE.getSceneCode())
                .modelCode("card-model")
                .source("default")
                .build();

        ReflectionTestUtils.invokeMethod(new ProjectGenConfigServiceImpl(),
                "applyCharacterCardDefaultAspectRatio", config, ProjectGenConfigScene.CHARACTER_CARD_IMAGE);

        assertEquals("16:9", config.getAspectRatio());
    }
}
