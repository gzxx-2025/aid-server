package com.aid.storyboard.video.impl;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ViduVideoReferenceStrategyMultiFrameTest {

    private final ViduVideoReferenceStrategy strategy = new ViduVideoReferenceStrategy();

    @Test
    void acceptsOneStartAndTwoKeyImages() {
        VideoReferencePlan plan = strategy.assemble(context("base", references(2), 10));

        assertEquals("base", plan.getFirstFrameImageUrl());
        assertEquals(2, ((List<?>) plan.getExtraOptions().get("key_images")).size());
    }

    @Test
    void rejectsMoreThanNineKeyImagesInsteadOfClipping() {
        assertThrows(ServiceException.class,
                () -> strategy.assemble(context("base", references(10), 10)));
    }

    private VideoReferenceContext context(String base, List<ResolvedReference> references, int max) {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setCapabilityJson("{\"sceneRules\":{\"multiFrame\":{}}}");
        return new VideoReferenceContext("move", null, references, base, config, false, max);
    }

    private List<ResolvedReference> references(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new ResolvedReference(i, "ref-" + i, "asset-" + i,
                        "character", false, "url-" + i))
                .toList();
    }
}
