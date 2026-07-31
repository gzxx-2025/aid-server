package com.aid.storyboard.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.aid.domain.vo.AiModelConfigVo;
import com.aid.storyboard.support.StoryboardDurationResolver.Resolution;

class StoryboardDurationResolverTest
{
    @Test
    void shouldParseOnlyPositiveIntegerRecommendation()
    {
        assertEquals(6, StoryboardDurationResolver.parseRecommendedDuration("{\"视频时长建议秒\":6}"));
        assertEquals(8, StoryboardDurationResolver.parseRecommendedDuration("{\"视频时长建议秒\":\"8\"}"));
        assertNull(StoryboardDurationResolver.parseRecommendedDuration("{\"视频时长建议秒\":0}"));
        assertNull(StoryboardDurationResolver.parseRecommendedDuration("{\"视频时长建议秒\":6.5}"));
        assertNull(StoryboardDurationResolver.parseRecommendedDuration("not-json"));
    }

    @Test
    void shouldPreferRequestForSingleRecommendedMode()
    {
        AiModelConfigVo model = model("[4,8,12]", 4, 4);

        Resolution result = StoryboardDurationResolver.resolve(5, 10, true, true, model);

        assertEquals(8, result.durationSeconds());
        assertEquals(StoryboardDurationResolver.SOURCE_REQUEST, result.source());
    }

    @Test
    void shouldPreferSuggestionForBatchRecommendedMode()
    {
        AiModelConfigVo model = model("[4,8,12]", 4, 4);

        Resolution result = StoryboardDurationResolver.resolve(5, 10, true, false, model);

        assertEquals(12, result.durationSeconds());
        assertEquals(StoryboardDurationResolver.SOURCE_STORYBOARD_SUGGESTION, result.source());
    }

    @Test
    void shouldRoundUpAndClampToDurationOptions()
    {
        AiModelConfigVo model = model("[12,4,8]", 4, 4);

        assertEquals(4, StoryboardDurationResolver.normalize(4, model));
        assertEquals(8, StoryboardDurationResolver.normalize(6, model));
        assertEquals(12, StoryboardDurationResolver.normalize(20, model));
    }

    @Test
    void shouldKeepCandidateWhenModelHasNoDurationOptions()
    {
        AiModelConfigVo model = model(null, 5, 5);

        assertEquals(7, StoryboardDurationResolver.normalize(7, model));
    }

    @Test
    void shouldResolveModelDefaultByConfiguredPriority()
    {
        AiModelConfigVo capabilityDefault = model("[4,8,12]", 6, 4);
        AiModelConfigVo tableDefault = model("[4,8,12]", null, 6);
        AiModelConfigVo firstOption = model("[8,4,12]", null, null);

        assertEquals(8, StoryboardDurationResolver.resolveModelDefaultDuration(capabilityDefault));
        assertEquals(8, StoryboardDurationResolver.resolveModelDefaultDuration(tableDefault));
        assertEquals(8, StoryboardDurationResolver.resolveModelDefaultDuration(firstOption));
    }

    private AiModelConfigVo model(String options, Integer capabilityDefault, Integer tableDefault)
    {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setDefaultDurationSeconds(tableDefault);
        if (options != null || capabilityDefault != null)
        {
            String optionsJson = options == null ? "[]" : options;
            String defaultJson = capabilityDefault == null
                    ? "" : ",\"defaultDurationSeconds\":" + capabilityDefault;
            model.setCapabilityJson("{\"durationOptions\":" + optionsJson + defaultJson + "}");
        }
        return model;
    }
}
