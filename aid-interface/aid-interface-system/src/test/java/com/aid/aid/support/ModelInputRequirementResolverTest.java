package com.aid.aid.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelInputRequirementResolverTest {

    @Test
    void seedancePureAudioReferenceDoesNotClaimImageIsRequired() {
        String capability = "{\"minReferenceImages\":0,\"maxReferenceImages\":30,"
                + "\"supportsReferenceAudio\":true,\"supportsVideoInput\":true}";

        assertEquals(ModelInputRequirementResolver.IMAGE_OPTIONAL,
                ModelInputRequirementResolver.resolve("video", "reference_to_video", capability, true));
    }

    @Test
    void referenceVideoWithMinimumImageStillRequiresImage() {
        String capability = "{\"minReferenceImages\":1,\"maxReferenceImages\":7,"
                + "\"supportsVideoInput\":true}";

        assertEquals(ModelInputRequirementResolver.IMAGE_REQUIRED,
                ModelInputRequirementResolver.resolve("video", "reference_to_video", capability, true));
    }
}
