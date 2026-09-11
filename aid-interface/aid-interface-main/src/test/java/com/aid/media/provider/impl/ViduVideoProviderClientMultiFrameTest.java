package com.aid.media.provider.impl;

import com.aid.common.exception.ServiceException;
import com.aid.media.constants.ViduConstants;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ViduVideoProviderClientMultiFrameTest {

    @Test
    void acceptsExactlyTwoThroughNineKeyFrames() {
        assertDoesNotThrow(() -> ViduVideoProviderClient.validateMultiFrameBody(body(2)));
        assertDoesNotThrow(() -> ViduVideoProviderClient.validateMultiFrameBody(body(9)));
    }

    @Test
    void rejectsMissingStartTooFewTooManyAndBlankKeyFrameBeforeUpstream() {
        Map<String, Object> missingStart = body(2);
        missingStart.remove(ViduConstants.JSON_START_IMAGE);
        assertThrows(ServiceException.class,
                () -> ViduVideoProviderClient.validateMultiFrameBody(missingStart));
        assertThrows(ServiceException.class,
                () -> ViduVideoProviderClient.validateMultiFrameBody(body(1)));
        assertThrows(ServiceException.class,
                () -> ViduVideoProviderClient.validateMultiFrameBody(body(10)));
        Map<String, Object> blank = body(2);
        blank.put(ViduConstants.JSON_IMAGE_SETTINGS,
                List.of(Map.of(ViduConstants.JSON_KEY_IMAGE, "k1"), Map.of()));
        assertThrows(ServiceException.class,
                () -> ViduVideoProviderClient.validateMultiFrameBody(blank));
    }

    private Map<String, Object> body(int keyFrameCount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(ViduConstants.JSON_START_IMAGE, "start");
        body.put(ViduConstants.JSON_IMAGE_SETTINGS, java.util.stream.IntStream.range(0, keyFrameCount)
                .mapToObj(index -> Map.<String, Object>of(ViduConstants.JSON_KEY_IMAGE, "key-" + index))
                .toList());
        return body;
    }
}
