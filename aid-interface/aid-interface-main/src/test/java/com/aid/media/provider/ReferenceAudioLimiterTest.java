package com.aid.media.provider;

import java.util.ArrayList;
import java.util.List;

import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.ReferenceAudioInput;
import com.aid.media.provider.ReferenceAudioLimiter.ReferenceAudioCapability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceAudioLimiterTest {

    private static final String FULL_CAPABILITY = "{\"supportsAudio\":true,\"supportsReferenceAudio\":true,"
            + "\"maxReferenceAudios\":2,\"referenceAudioMinDurationSeconds\":3,"
            + "\"referenceAudioMaxDurationSeconds\":30,\"referenceAudioMaxTotalDurationSeconds\":60,"
            + "\"referenceAudioFormats\":[\"wav\",\"mp3\"]}";

    @Test
    void shouldTreatMissingOrBrokenCapabilityAsUnusable() {
        assertFalse(ReferenceAudioLimiter.readCapabilityJson(null).isUsable());
        assertFalse(ReferenceAudioLimiter.readCapabilityJson("not-json").isUsable());
        assertFalse(ReferenceAudioLimiter.readCapabilityJson("{}").isUsable());
    }

    @Test
    void shouldFlagEnabledButIncompleteCapability() {
        // 开了能力位却没配上限与格式：属于配置不完整，不能报成「数量超限」
        ReferenceAudioCapability capability = ReferenceAudioLimiter
                .readCapabilityJson("{\"supportsReferenceAudio\":true}");

        assertFalse(capability.isUsable());
        assertTrue(capability.isIncomplete());
    }

    @Test
    void shouldAcceptOnlyWhitelistedFormatAndDurationRange() {
        ReferenceAudioCapability capability = ReferenceAudioLimiter.readCapabilityJson(FULL_CAPABILITY);

        assertTrue(capability.isUsable());
        assertTrue(capability.acceptsFormat("WAV"));
        assertFalse(capability.acceptsFormat("m4a"));
        assertTrue(capability.acceptsDuration(5000));
        assertFalse(capability.acceptsDuration(2000));
        assertFalse(capability.acceptsDuration(31000));
        assertFalse(capability.acceptsDuration(null));
    }

    @Test
    void shouldTruncateOverLimitAndReindexFromOne() {
        List<ReferenceAudioInput> audios = new ArrayList<>();
        audios.add(audio(7, "https://cdn.example.com/a.wav"));
        audios.add(audio(9, "https://cdn.example.com/b.wav"));
        audios.add(audio(11, "https://cdn.example.com/c.wav"));

        List<ReferenceAudioInput> limited = ReferenceAudioLimiter.limit(audios, model(FULL_CAPABILITY), "UT");

        assertEquals(2, limited.size());
        assertEquals(1, limited.get(0).getIndex());
        assertEquals(2, limited.get(1).getIndex());
        assertEquals("https://cdn.example.com/a.wav", limited.get(0).getSampleUrl());
    }

    @Test
    void shouldOnlyAllowProbeableFormats() {
        assertTrue(ReferenceAudioLimiter.isProbeableFormat("MP3"));
        assertTrue(ReferenceAudioLimiter.isProbeableFormat("wav"));
        assertFalse(ReferenceAudioLimiter.isProbeableFormat("aac"));
        assertFalse(ReferenceAudioLimiter.isProbeableFormat(" "));
    }

    private static ReferenceAudioInput audio(int index, String url) {
        ReferenceAudioInput input = new ReferenceAudioInput();
        input.setIndex(index);
        input.setSampleUrl(url);
        input.setFormat("wav");
        input.setDurationMs(5000);
        input.setSourceType(ReferenceAudioInput.SOURCE_VOICE_SAMPLE);
        return input;
    }

    private static AiModelConfigVo model(String capabilityJson) {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setModelCode("ut-video");
        model.setCapabilityJson(capabilityJson);
        return model;
    }
}
