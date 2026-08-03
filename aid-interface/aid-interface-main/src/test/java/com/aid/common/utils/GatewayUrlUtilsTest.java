package com.aid.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayUrlUtilsTest {

    @Test
    void shouldAcceptAndNormalizeHostOnlyGateway() {
        assertTrue(GatewayUrlUtils.isBaseGatewayUrl("https://api.example.com"));
        assertTrue(GatewayUrlUtils.isBaseGatewayUrl(" http://localhost:8080/ "));
        assertEquals("https://api.example.com", GatewayUrlUtils.normalizeBaseGatewayUrl(" https://api.example.com/ "));
    }

    @Test
    void shouldRejectNonGatewayPartsAndInvalidPorts() {
        assertFalse(GatewayUrlUtils.isBaseGatewayUrl("https://api.example.com/v1"));
        assertFalse(GatewayUrlUtils.isBaseGatewayUrl("https://api.example.com?token=x"));
        assertFalse(GatewayUrlUtils.isBaseGatewayUrl("https://user@api.example.com"));
        assertFalse(GatewayUrlUtils.isBaseGatewayUrl("https://api.example.com:0"));
        assertFalse(GatewayUrlUtils.isBaseGatewayUrl("ftp://api.example.com"));
    }
}
