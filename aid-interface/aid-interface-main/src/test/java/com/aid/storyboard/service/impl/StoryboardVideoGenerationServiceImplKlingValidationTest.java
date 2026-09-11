package com.aid.storyboard.service.impl;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.KlingConstants;
import com.aid.storyboard.video.VideoReferencePlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoryboardVideoGenerationServiceImplKlingValidationTest {

    @Test
    void standardMultiAcceptsPlannedBaseImageAsItsFirstFrameMinimum() {
        AiModelConfigVo model = standardMultiModel();
        VideoReferencePlan plan = VideoReferencePlan.of(
            "测试提示词", List.of(), "https://cdn.test/base.png");

        assertDoesNotThrow(() -> StoryboardVideoGenerationServiceImpl
            .validateKlingPlannedReferenceInputs(model, plan));
    }

    @Test
    void standardMultiDoesNotTreatUnsentGenericReferenceAsFirstFrame() {
        AiModelConfigVo model = standardMultiModel();
        VideoReferencePlan plan = VideoReferencePlan.of(
            "测试提示词", List.of("https://cdn.test/reference.png"), null);

        assertThrows(ServiceException.class, () -> StoryboardVideoGenerationServiceImpl
            .validateKlingPlannedReferenceInputs(model, plan));
    }

    private AiModelConfigVo standardMultiModel() {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setProviderCode(KlingConstants.PROVIDER_CODE);
        model.setCapabilityJson("{\"klingScenario\":\"standard_multi\",\"minReferenceImages\":1}");
        return model;
    }
}
