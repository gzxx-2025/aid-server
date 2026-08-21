package com.aid.billing.util;

import com.aid.billing.dto.BillingInput;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.KlingConstants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.KlingVideoRequestBuilder;
import com.aid.media.util.ModelCapabilityValidator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KlingBillingInputExtractorTest {

    @Test
    void actualBaseVideoFieldsOverrideSpoofableBillingHints() {
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setModelName("kling-3.0-omni-edit");
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("baseVideoUrl", "https://cdn.test/base.mp4");
        options.put("inputVideoCount", 0);
        options.put("generateMode", "TEXT_TO_VIDEO");
        options.put("audioMode", "original");
        options.put("size", "4k");
        options.put("duration", 9);
        request.setOptions(options);

        BillingInput input = BillingInputExtractor.fromVideoRequest(request);

        assertEquals(1, input.getParams().get("inputVideoCount"));
        assertEquals("VIDEO_TO_VIDEO", input.getParams().get("generateMode"));
        assertEquals("original", input.getParams().get("audioMode"));
        assertEquals("4K", input.getParams().get("resolution"));
        assertEquals(9, input.getParams().get("duration"));
    }

    @Test
    void fakeInputVideoCountDoesNotCreateVideoBillingSemantics() {
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setModelName("kling-3.0-omni-t2v");
        request.setOptions(Map.of("inputVideoCount", 1, "generateMode", "VIDEO_TO_VIDEO"));

        BillingInput input = BillingInputExtractor.fromVideoRequest(request);

        assertEquals(0, input.getParams().get("inputVideoCount"));
        assertEquals("TEXT_TO_VIDEO", input.getParams().get("generateMode"));
    }

    @Test
    void existingEdgeModeOverrideRemainsCompatible() {
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setModelName("vidu-q2");
        request.setOptions(Map.of("generateMode", "EDGE_TO_VIDEO"));

        BillingInput input = BillingInputExtractor.fromVideoRequest(request);

        assertEquals("EDGE_TO_VIDEO", input.getParams().get("generateMode"));
    }

    @Test
    void existingMultiModeOverrideRemainsCompatible() {
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setModelName("vidu-q2");
        request.setOptions(Map.of("generateMode", "multi_to_video"));

        BillingInput input = BillingInputExtractor.fromVideoRequest(request);

        assertEquals("MULTI_TO_VIDEO", input.getParams().get("generateMode"));
    }

    @Test
    void klingAudioDefaultsStayAlignedAcrossNormalizationBuilderAndBilling() {
        AiModelConfigVo editConfig = klingConfig(KlingConstants.SCENARIO_OMNI_EDIT,
            KlingConstants.PATH_OMNI, false, false);
        MediaVideoGenerateRequest edit = klingRequest("kling-3.0-omni-edit",
            Map.of("baseVideoUrl", "https://cdn.test/base.mp4"));
        ModelCapabilityValidator.normalizeAndValidateVideoAudio(editConfig, edit);
        Map<?, ?> editSettings = (Map<?, ?>) KlingVideoRequestBuilder.build(editConfig, edit).get("settings");
        assertNull(edit.getAudio());
        assertEquals("off", editSettings.get("audio"));
        assertEquals("off", BillingInputExtractor.fromVideoRequest(edit).getParams().get("audioMode"));

        MediaVideoGenerateRequest original = klingRequest("kling-3.0-omni-edit",
            Map.of("baseVideoUrl", "https://cdn.test/base.mp4", "audioMode", "original"));
        ModelCapabilityValidator.normalizeAndValidateVideoAudio(editConfig, original);
        Map<?, ?> originalSettings = (Map<?, ?>) KlingVideoRequestBuilder.build(editConfig, original).get("settings");
        assertNull(original.getAudio());
        assertEquals("original", originalSettings.get("audio"));
        assertEquals("original", BillingInputExtractor.fromVideoRequest(original).getParams().get("audioMode"));

        AiModelConfigVo textConfig = klingConfig(KlingConstants.SCENARIO_OMNI_T2V,
            KlingConstants.PATH_OMNI, true, false);
        MediaVideoGenerateRequest text = klingRequest("kling-3.0-omni-t2v", Map.of());
        ModelCapabilityValidator.normalizeAndValidateVideoAudio(textConfig, text);
        Map<?, ?> textSettings = (Map<?, ?>) KlingVideoRequestBuilder.build(textConfig, text).get("settings");
        assertEquals("off", textSettings.get("audio"));
        assertEquals("off", BillingInputExtractor.fromVideoRequest(text).getParams().get("audioMode"));
    }

    @Test
    void omniEditRejectsNativeAudioBooleanButAllowsOriginalMode() {
        AiModelConfigVo editConfig = klingConfig(KlingConstants.SCENARIO_OMNI_EDIT,
            KlingConstants.PATH_OMNI, false, false);
        MediaVideoGenerateRequest nativeAudio = klingRequest("kling-3.0-omni-edit",
            Map.of("baseVideoUrl", "https://cdn.test/base.mp4"));
        nativeAudio.setAudio(true);

        assertThrows(ServiceException.class,
            () -> ModelCapabilityValidator.normalizeAndValidateVideoAudio(editConfig, nativeAudio));

        MediaVideoGenerateRequest original = klingRequest("kling-3.0-omni-edit",
            Map.of("baseVideoUrl", "https://cdn.test/base.mp4", "audioMode", "original"));
        ModelCapabilityValidator.normalizeAndValidateVideoAudio(editConfig, original);

        Map<?, ?> settings = (Map<?, ?>) KlingVideoRequestBuilder.build(editConfig, original).get("settings");
        assertNull(original.getAudio());
        assertEquals("original", settings.get("audio"));
        assertEquals(false, BillingInputExtractor.fromVideoRequest(original).getParams().get("audio"));
    }

    @Test
    void supportedLegacyCapabilityWithoutDefaultAudioStillDefaultsOn() {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setCapabilityJson("{\"supportsAudio\":true}");
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();

        ModelCapabilityValidator.normalizeAndValidateVideoAudio(config, request);

        assertEquals(true, request.getAudio());
        assertEquals(true, request.getOptions().get("generate_audio"));
    }

    private AiModelConfigVo klingConfig(String scenario, String path,
                                        boolean supportsAudio, boolean defaultAudio) {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setApiSuffix(path);
        config.setCapabilityJson("{\"klingScenario\":\"" + scenario
            + "\",\"supportsAudio\":" + supportsAudio
            + ",\"defaultAudio\":" + defaultAudio + "}");
        return config;
    }

    private MediaVideoGenerateRequest klingRequest(String modelName, Map<String, Object> options) {
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setModelName(modelName);
        request.setPrompt("test prompt");
        request.setDurationSeconds(5);
        request.setOptions(options);
        return request;
    }
}
