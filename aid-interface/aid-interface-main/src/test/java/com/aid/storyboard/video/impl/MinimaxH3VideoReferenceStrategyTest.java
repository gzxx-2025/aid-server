package com.aid.storyboard.video.impl;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.MinimaxH3Constants;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimaxH3VideoReferenceStrategyTest {

    private final MinimaxH3VideoReferenceStrategy strategy = new MinimaxH3VideoReferenceStrategy();

    @Test
    void firstLastSceneUsesDistinctBaseAndReferenceAsFrames() {
        VideoReferencePlan plan = strategy.assemble(context(MinimaxH3Constants.MODEL_I2V_FIRST_LAST,
            "base", List.of(reference(1, "tail"))));
        assertEquals("base", plan.getFirstFrameImageUrl());
        assertEquals("tail", plan.getExtraOptions().get("lastFrameImageUrl"));
    }

    @Test
    void firstLastSceneFailsBeforeSubmissionWhenTailIsMissing() {
        assertConciseFailure("请提供尾帧图片", () -> strategy.assemble(context(
            MinimaxH3Constants.MODEL_I2V_FIRST_LAST, "base", List.of())));
    }

    @Test
    void allStoryboardValidationMessagesStayConcise() {
        assertConciseFailure("模型场景配置无效",
            () -> strategy.assemble(context("minimax-h3-unknown", null, List.of())));
        assertConciseFailure("请提供首尾帧图片",
            () -> strategy.assemble(context(MinimaxH3Constants.MODEL_I2V_FIRST_LAST,
                null, List.of(reference(1, "first")))));
        assertConciseFailure("首尾帧图片不能相同",
            () -> strategy.assemble(context(MinimaxH3Constants.MODEL_I2V_FIRST_LAST,
                "same", List.of(reference(1, "same")))));
    }

    @Test
    void lastFrameSceneCarriesTailThroughProviderOptions() {
        VideoReferencePlan plan = strategy.assemble(context(MinimaxH3Constants.MODEL_I2V_LAST,
            null, List.of(reference(1, "tail"))));

        assertNull(plan.getFirstFrameImageUrl());
        assertEquals("tail", plan.getExtraOptions().get("lastFrameImageUrl"));
    }

    private VideoReferenceContext context(String modelCode, String base, List<ResolvedReference> references) {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setModelCode(modelCode);
        return new VideoReferenceContext("prompt", null, references, base, config, false, 9);
    }

    private ResolvedReference reference(int n, String url) {
        return new ResolvedReference(n, "form-" + n, "asset-" + n, "character", false, url);
    }

    private void assertConciseFailure(String expectedMessage, Executable executable) {
        ServiceException exception = assertThrows(ServiceException.class, executable);
        assertEquals(expectedMessage, exception.getMessage());
        assertTrue(exception.getMessage().codePointCount(0, exception.getMessage().length()) <= 12);
    }
}
