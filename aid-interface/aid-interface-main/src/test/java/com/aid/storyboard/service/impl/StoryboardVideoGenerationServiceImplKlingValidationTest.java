package com.aid.storyboard.service.impl;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.KlingConstants;
import com.aid.storyboard.video.VideoReferencePlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void videoBatchPrecheckIncludesAudioModeSkuDimension() {
        Map<String, Object> nativeAudio = StoryboardVideoGenerationServiceImpl
            .buildVideoPrecheckBillingParams("16:9", "720P", true, 15);
        Map<String, Object> muted = StoryboardVideoGenerationServiceImpl
            .buildVideoPrecheckBillingParams("16:9", "720P", false, 15);

        assertEquals("native", nativeAudio.get("audioMode"));
        assertEquals("off", muted.get("audioMode"));
        assertEquals(Boolean.TRUE, nativeAudio.get("audio"));
        assertEquals("720p", nativeAudio.get("resolution"));
    }

    private AiModelConfigVo standardMultiModel() {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setProviderCode(KlingConstants.PROVIDER_CODE);
        model.setCapabilityJson("{\"klingScenario\":\"standard_multi\",\"minReferenceImages\":1}");
        return model;
    }
}
