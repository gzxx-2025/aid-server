package com.aid.rps.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AssetExtractCardSourceValidationTest
{
    @Test
    void allowsGeneratedUploadedAndEditedCharacterImages()
    {
        assertTrue(AssetExtractServiceImpl.supportsCardImageSource("ai_auto"));
        assertTrue(AssetExtractServiceImpl.supportsCardImageSource("upload"));
        assertTrue(AssetExtractServiceImpl.supportsCardImageSource("ai_edit_chat"));
    }

    @Test
    void rejectsUnsupportedImageSources()
    {
        assertFalse(AssetExtractServiceImpl.supportsCardImageSource("official"));
        assertFalse(AssetExtractServiceImpl.supportsCardImageSource("ai_builder"));
        assertFalse(AssetExtractServiceImpl.supportsCardImageSource(null));
    }

    @Test
    void resolvesInitialUseStateBySourceImageType()
    {
        assertEquals(1, AssetExtractServiceImpl.resolveCardImageInitialIsUse("ai_auto"));
        assertEquals(0, AssetExtractServiceImpl.resolveCardImageInitialIsUse("upload"));
        assertEquals(1, AssetExtractServiceImpl.resolveCardImageInitialIsUse("ai_edit_chat"));
    }
}
