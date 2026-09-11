package com.aid.storyboard.video.impl;

import com.aid.domain.vo.AiModelConfigVo;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgnesVideoReferenceStrategyTest {

    private final AgnesVideoReferenceStrategy strategy = new AgnesVideoReferenceStrategy();

    @Test
    void textCapabilityDoesNotDispatchStoryboardImages() {
        VideoReferencePlan plan = strategy.assemble(context("text_to_video", "base",
                List.of(reference(1, "ref"))));

        assertNull(plan.getFirstFrameImageUrl());
        assertTrue(plan.getReferenceImageUrls().isEmpty());
    }

    @Test
    void firstFrameCapabilityKeepsBaseAndTextifiesAutomaticPromptReferences() {
        VideoReferencePlan plan = strategy.assemble(context("image_to_video", "base",
                List.of(reference(1, "ref-1"), reference(2, "ref-2"))));

        assertEquals("base", plan.getFirstFrameImageUrl());
        assertTrue(plan.getReferenceImageUrls().isEmpty());
    }

    @Test
    void referenceCapabilityDispatchesBaseAndReferencesWithoutChangingMode() {
        VideoReferencePlan plan = strategy.assemble(context("reference_to_video", "base",
                List.of(reference(1, "ref-1"), reference(2, "ref-2"))));

        assertNull(plan.getFirstFrameImageUrl());
        assertEquals(List.of("base", "ref-1", "ref-2"), plan.getReferenceImageUrls());
    }

    @Test
    void referenceCapabilityRejectsInsteadOfClippingOverLimit() {
        assertThrows(com.aid.common.exception.ServiceException.class,
                () -> strategy.assemble(context("reference_to_video", "base",
                        List.of(reference(1, "ref-1"), reference(2, "ref-2"),
                                reference(3, "ref-3"), reference(4, "ref-4"),
                                reference(5, "ref-5")))));
    }

    private VideoReferenceContext context(String capability, String base,
                                          List<ResolvedReference> references) {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setCapabilityCode(capability);
        config.setGenerateMode(capability);
        return new VideoReferenceContext("move @图片1[a] and @图片2[b]", null,
                references, base, config, false, 5);
    }

    private ResolvedReference reference(int index, String url) {
        return new ResolvedReference(index, "ref-" + index, "asset-" + index,
                "character", false, url);
    }
}
