package com.aid.model.service.impl;

import com.aid.aid.domain.AidAiModel;
import com.aid.billing.service.IBillingDetailQueryService;
import com.aid.model.vo.AiModelVO;
import com.aid.model.vo.CapabilityVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelBusinessServiceImplCapabilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiModelBusinessServiceImpl service = new AiModelBusinessServiceImpl(
        null, null, null, null, null, null);

    @Test
    void exposesEveryKlingScenarioAndItsUserFacingConstraints() throws Exception {
        List<String> scenarios = List.of(
            "turbo_i2v", "standard_i2v", "standard_multi", "omni_t2v", "omni_i2v",
            "omni_first_last", "omni_reference", "omni_feature_video", "omni_edit");
        for (String scenario : scenarios) {
            CapabilityVO capability = parse("{\"klingScenario\":\"" + scenario
                + "\",\"audioModeOptions\":[\"off\",\"native\"],\"supportsVoiceControl\":false}");
            assertEquals(scenario, capability.getKlingScenario());
            assertEquals(List.of("off", "native"), capability.getAudioModeOptions());
            assertEquals(Boolean.FALSE, capability.getSupportsVoiceControl());
        }

        CapabilityVO capability = parse("{"
            + "\"requiresConfiguredBilling\":true,"
            + "\"klingScenario\":\"omni_feature_video\","
            + "\"audioModeOptions\":[\"off\"],"
            + "\"supportsElements\":true,\"maxElements\":4,\"elementTypeRequired\":true,"
            + "\"supportsVideoInput\":true,\"maxReferenceVideos\":1,"
            + "\"referenceVideoRules\":{\"maxVideoCharacterElements\":1,"
            + "\"maxReferenceImagesAndMultiImageElements\":4,"
            + "\"forbidVideoCharacterWithReferenceImages\":true,\"forbidMixedElementTypes\":true},"
            + "\"supportsVoiceControl\":false}");

        assertEquals(Boolean.TRUE, capability.getSupportsElements());
        assertEquals(4, capability.getMaxElements());
        assertEquals(Boolean.TRUE, capability.getElementTypeRequired());
        assertEquals(Boolean.TRUE, capability.getSupportsVideoInput());
        assertEquals(1, capability.getMaxReferenceVideos());
        assertEquals(1, ((Number) capability.getReferenceVideoRules()
            .get("maxVideoCharacterElements")).intValue());
        assertEquals(Boolean.TRUE, capability.getReferenceVideoRules()
            .get("forbidMixedElementTypes"));

        JsonNode serialized = MAPPER.readTree(MAPPER.writeValueAsString(capability));
        assertEquals("omni_feature_video", serialized.path("klingScenario").asText());
        assertTrue(serialized.path("supportsVideoInput").asBoolean());
        assertTrue(serialized.path("referenceVideoRules")
            .path("forbidVideoCharacterWithReferenceImages").asBoolean());
        assertFalse(serialized.has("requiresConfiguredBilling"));
    }

    @Test
    void legacyCapabilityKeepsExistingFieldsWithoutInventingKlingConstraints() {
        CapabilityVO capability = parse("{\"sizeOptions\":[\"720P\"],"
            + "\"supportsAudio\":true,\"defaultDurationSeconds\":5}");

        assertEquals(List.of("720P"), capability.getSizeOptions());
        assertEquals(Boolean.TRUE, capability.getSupportsAudio());
        assertEquals(5, capability.getDefaultDurationSeconds());
        assertNull(capability.getKlingScenario());
        assertNull(capability.getSupportsElements());
        assertNull(capability.getSupportsVideoInput());
        assertNull(capability.getReferenceVideoRules());
    }

    @Test
    void publicModelVoAlwaysReturnsExplicitFreeBoolean() {
        IBillingDetailQueryService billing = mock(IBillingDetailQueryService.class);
        when(billing.displayCostCredits(any(), eq(BigDecimal.ONE))).thenReturn(BigDecimal.TEN);
        AiModelBusinessServiceImpl modelService = new AiModelBusinessServiceImpl(
                null, null, null, billing, null, null);
        AidAiModel model = new AidAiModel();
        model.setModelType("text");

        AiModelVO historical = ReflectionTestUtils.invokeMethod(
                modelService, "buildModelVo", model, "provider", null, BigDecimal.ONE);
        assertEquals(Boolean.FALSE, historical.getIsFree());

        model.setIsFree(true);
        AiModelVO free = ReflectionTestUtils.invokeMethod(
                modelService, "buildModelVo", model, "provider", null, BigDecimal.ONE);
        assertEquals(Boolean.TRUE, free.getIsFree());
        assertEquals(BigDecimal.TEN, free.getCostCredits());
    }

    private CapabilityVO parse(String json) {
        return ReflectionTestUtils.invokeMethod(
            service, "parseOrDefaultCapability", "video", json);
    }
}
