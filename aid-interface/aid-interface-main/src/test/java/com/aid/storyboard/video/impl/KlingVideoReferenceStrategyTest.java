package com.aid.storyboard.video.impl;

import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.KlingConstants;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KlingVideoReferenceStrategyTest {

    private final KlingVideoReferenceStrategy strategy = new KlingVideoReferenceStrategy();

    @Test
    void omniReferenceReservesOneImageSlotForNonblankBaseFrame() {
        VideoReferencePlan withBase = strategy.assemble(context("https://cdn.test/base.png", 6));
        VideoReferencePlan withoutBase = strategy.assemble(context(" ", 7));

        assertEquals(6, withBase.getReferenceImageUrls().size());
        assertEquals("https://cdn.test/base.png", withBase.getFirstFrameImageUrl());
        assertEquals(7, withoutBase.getReferenceImageUrls().size());
    }

    @Test
    void omniReferenceRejectsOverLimitInsteadOfClipping() {
        assertThrows(com.aid.common.exception.ServiceException.class,
                () -> strategy.assemble(context("https://cdn.test/base.png", 7)));
        assertThrows(com.aid.common.exception.ServiceException.class,
                () -> strategy.assemble(context(" ", 8)));
    }

    private VideoReferenceContext context(String baseImageUrl, int referenceCount) {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setCapabilityJson("{\"klingScenario\":\"" + KlingConstants.SCENARIO_OMNI_REFERENCE + "\"}");
        List<ResolvedReference> references = new ArrayList<>();
        for (int index = 1; index <= referenceCount; index++) {
            references.add(new ResolvedReference(index, "form-" + index, "asset-" + index,
                "character", false, "https://cdn.test/ref-" + index + ".png"));
        }
        return new VideoReferenceContext("test prompt", null, references, baseImageUrl,
            config, false, 7);
    }
}
