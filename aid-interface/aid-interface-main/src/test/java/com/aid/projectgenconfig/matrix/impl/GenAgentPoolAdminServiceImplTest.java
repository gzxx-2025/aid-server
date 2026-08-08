package com.aid.projectgenconfig.matrix.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.aid.aid.domain.AidAgent;
import com.aid.agent.IAidAgentService;
import com.aid.model.service.IAiModelBusinessService;
import com.aid.model.vo.AiModelVO;
import com.aid.model.vo.CapabilityVO;
import com.aid.projectgenconfig.matrix.vo.GenPoolModelOptionVO;
import com.aid.projectgenconfig.matrix.vo.GenPoolOptionsVO;

class GenAgentPoolAdminServiceImplTest
{
    @Test
    void returnsSceneSpecificModelCapabilitiesForCharacterCard()
    {
        GenAgentPoolAdminServiceImpl service = new GenAgentPoolAdminServiceImpl();
        IAidAgentService agentService = mock(IAidAgentService.class);
        IAiModelBusinessService modelService = mock(IAiModelBusinessService.class);
        when(agentService.list(org.mockito.ArgumentMatchers.<Wrapper<AidAgent>>any())).thenReturn(List.of());
        when(modelService.listAvailableModelsByFuncCode("main_character_card_image"))
                .thenReturn(List.of(cardModel()));
        ReflectionTestUtils.setField(service, "aidAgentService", agentService);
        ReflectionTestUtils.setField(service, "aiModelBusinessService", modelService);

        GenPoolOptionsVO result = service.getOptions("main_character_card_image");

        assertEquals(1, result.getModels().size());
        GenPoolModelOptionVO option = result.getModels().get(0);
        assertEquals(List.of("1K", "2K"), option.getSizeOptions());
        assertEquals(List.of("16:9", "21:9"), option.getAspectRatioOptions());
        assertEquals("1K", option.getDefaultSize());
        assertEquals("21:9", option.getDefaultAspectRatio());
        assertTrue(option.getSupportsSizePreset());
        assertTrue(option.getSupportsAspectRatio());
    }

    private AiModelVO cardModel()
    {
        CapabilityVO capability = new CapabilityVO();
        capability.setSizeOptions(List.of("1K", "2K", "4K"));
        capability.setDefaultSize("2K");
        capability.setAspectRatioOptions(List.of("1:1", "16:9", "21:9"));
        capability.setDefaultAspectRatio("1:1");
        capability.setSceneRules(Map.of("imageToImage", Map.of(
                "sizeOptions", List.of("1K", "2K"),
                "defaultSize", "1K",
                "aspectRatioOptions", List.of("16:9", "21:9"),
                "defaultAspectRatio", "21:9",
                "supportsSizePreset", true,
                "supportsAspectRatio", true)));

        AiModelVO model = new AiModelVO();
        model.setModelCode("card-model");
        model.setModelName("设定卡模型");
        model.setSupportsSizePreset(true);
        model.setSupportsAspectRatio(true);
        model.setCapability(capability);
        return model;
    }
}
