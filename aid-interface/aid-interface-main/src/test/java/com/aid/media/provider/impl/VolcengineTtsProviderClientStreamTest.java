package com.aid.media.provider.impl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VolcengineTtsProviderClientStreamTest {

    @Test
    void parsesHeaderCodeAndPayloadFrames() {
        String body = "{\"header\":{\"code\":0},\"payload\":\"YWJj\"}\n"
                + "{\"header\":{\"code\":20000000}}";

        VolcengineTtsProviderClient.StreamParseResult result =
                VolcengineTtsProviderClient.parseStream(body);

        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), result.audio());
        assertEquals(null, result.errorCode());
    }

    @Test
    void preservesHeaderErrorCodeMessageAndFrame() {
        String frame = "{\"header\":{\"code\":45000000,\"message\":\"quota exhausted\"}}";

        VolcengineTtsProviderClient.StreamParseResult result =
                VolcengineTtsProviderClient.parseStream(frame);

        assertEquals(45000000, result.errorCode());
        assertEquals("quota exhausted", result.errorMessage());
        assertEquals(frame, result.errorLine());
        assertEquals("code=45000000, message=quota exhausted", result.errorText());
    }
}
