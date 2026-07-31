package com.aid.media.util;

import java.util.ArrayList;
import java.util.List;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceAudioInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCapabilityValidatorReferenceAudioTest {

    private static final String ENABLED = "{\"supportsAudio\":true,\"supportsReferenceAudio\":true,"
            + "\"maxReferenceAudios\":2,\"referenceAudioMinDurationSeconds\":3,"
            + "\"referenceAudioMaxDurationSeconds\":30,\"referenceAudioMaxTotalDurationSeconds\":20,"
            + "\"referenceAudioFormats\":[\"wav\",\"mp3\"]}";

    private static final String DISABLED = "{\"supportsAudio\":true,\"supportsReferenceAudio\":false}";

    @Test
    void shouldDropImplicitReferencesWhenCapabilityOff() {
        // 提示词占位推导出的引用属于隐式来源：能力未开启时降级丢弃，不能阻断出片
        MediaVideoGenerateRequest request = request(true, voiceSample("https://cdn.example.com/a.wav", 5000));

        ModelCapabilityValidator.normalizeAndValidateReferenceAudios(model(DISABLED), request);

        assertTrue(request.getReferenceAudios().isEmpty());
    }

    @Test
    void shouldFailExplicitReferencesWhenCapabilityOff() {
        // 用户显式选择的音频记录必须报错，否则用户以为已生效
        MediaVideoGenerateRequest request = request(true, audioRecord("https://cdn.example.com/a.wav", 5000));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> ModelCapabilityValidator.normalizeAndValidateReferenceAudios(model(DISABLED), request));

        assertEquals("模型不支持参考音频", ex.getMessage());
    }

    @Test
    void shouldDropImplicitReferencesWhenAudioSwitchOff() {
        // 用户没开生成声音时，自动推导的参考音频不得升级成强制项
        MediaVideoGenerateRequest request = request(false, voiceSample("https://cdn.example.com/a.wav", 5000));

        ModelCapabilityValidator.normalizeAndValidateReferenceAudios(model(ENABLED), request);

        assertTrue(request.getReferenceAudios().isEmpty());
    }

    @Test
    void shouldMergeDuplicateReferencesAndReindex() {
        MediaVideoGenerateRequest request = request(true,
                voiceSample("https://cdn.example.com/a.wav", 5000),
                voiceSample("https://cdn.example.com/a.wav", 5000),
                voiceSample("https://cdn.example.com/b.wav", 5000));

        ModelCapabilityValidator.normalizeAndValidateReferenceAudios(model(ENABLED), request);

        assertEquals(2, request.getReferenceAudios().size());
        assertEquals(1, request.getReferenceAudios().get(0).getIndex());
        assertEquals(2, request.getReferenceAudios().get(1).getIndex());
    }

    @Test
    void shouldDropImplicitReferenceWithUnsupportedFormatOrDuration() {
        MediaVideoGenerateRequest request = request(true,
                voiceSample("https://cdn.example.com/a.m4a", 5000),
                voiceSample("https://cdn.example.com/b.wav", 1000),
                voiceSample("https://cdn.example.com/c.wav", 5000));
        request.getReferenceAudios().get(0).setFormat("m4a");

        ModelCapabilityValidator.normalizeAndValidateReferenceAudios(model(ENABLED), request);

        assertEquals(1, request.getReferenceAudios().size());
        assertEquals("https://cdn.example.com/c.wav", request.getReferenceAudios().get(0).getSampleUrl());
    }

    @Test
    void shouldStopAtTotalDurationLimitWithoutFailing() {
        // 总时长上限 20 秒：第三条超出后剔除，前两条照常下发
        MediaVideoGenerateRequest request = request(true,
                voiceSample("https://cdn.example.com/a.wav", 9000),
                voiceSample("https://cdn.example.com/b.wav", 9000),
                voiceSample("https://cdn.example.com/c.wav", 9000));

        ModelCapabilityValidator.normalizeAndValidateReferenceAudios(model(ENABLED), request);

        assertEquals(2, request.getReferenceAudios().size());
    }

    @Test
    void shouldIgnoreLipSyncRequest() {
        MediaVideoGenerateRequest request = request(true, audioRecord("https://cdn.example.com/a.wav", 5000));
        request.getOptions().put("video_url", "https://cdn.example.com/v.mp4");
        request.getOptions().put("audio_url", "https://cdn.example.com/a.wav");

        ModelCapabilityValidator.normalizeAndValidateReferenceAudios(model(DISABLED), request);

        assertEquals(1, request.getReferenceAudios().size());
    }

    private static MediaVideoGenerateRequest request(boolean audio, ReferenceAudioInput... audios) {
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setAudio(audio);
        request.setOptions(new java.util.LinkedHashMap<>());
        List<ReferenceAudioInput> list = new ArrayList<>();
        for (ReferenceAudioInput item : audios) {
            list.add(item);
        }
        request.setReferenceAudios(list);
        return request;
    }

    private static ReferenceAudioInput voiceSample(String url, int durationMs) {
        return input(url, durationMs, ReferenceAudioInput.SOURCE_VOICE_SAMPLE);
    }

    private static ReferenceAudioInput audioRecord(String url, int durationMs) {
        return input(url, durationMs, ReferenceAudioInput.SOURCE_AUDIO_RECORD);
    }

    private static ReferenceAudioInput input(String url, int durationMs, String sourceType) {
        ReferenceAudioInput input = new ReferenceAudioInput();
        input.setSampleUrl(url);
        input.setDurationMs(durationMs);
        input.setFormat("wav");
        input.setSourceType(sourceType);
        input.setName("音频-测试");
        return input;
    }

    private static AiModelConfigVo model(String capabilityJson) {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setModelCode("ut-video");
        model.setCapabilityJson(capabilityJson);
        return model;
    }
}
