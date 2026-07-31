package com.aid.media.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ViduStatusMapperTest
{
    @Test
    void shouldMapOnlyOfficialExactStates()
    {
        assertEquals("PROCESSING", ViduStatusMapper.normalizeStatus("created"));
        assertEquals("PROCESSING", ViduStatusMapper.normalizeStatus("queueing"));
        assertEquals("PROCESSING", ViduStatusMapper.normalizeStatus("processing"));
        assertEquals("SUCCEEDED", ViduStatusMapper.normalizeStatus("success"));
        assertEquals("FAILED", ViduStatusMapper.normalizeStatus("failed"));
    }

    @Test
    void shouldNotInferStateFromKeywords()
    {
        assertFalse(ViduStatusMapper.isKnownState("processing_done"));
        assertFalse(ViduStatusMapper.isKnownState("successful"));
        assertFalse(ViduStatusMapper.isKnownState("failed_retryable"));
        assertEquals("PROCESSING", ViduStatusMapper.normalizeStatus("processing_done"));
    }

    @Test
    void shouldRecognizeOnlyDocumentedTerminalStates()
    {
        assertTrue(ViduStatusMapper.isTerminalState("success"));
        assertTrue(ViduStatusMapper.isTerminalState("failed"));
        assertFalse(ViduStatusMapper.isTerminalState("processing"));
        assertFalse(ViduStatusMapper.isTerminalState("unknown"));
    }
}
