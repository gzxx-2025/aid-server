package com.aid.rps.sse;

import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AssetExtractSseManagerTest
{
    @Test
    void shouldExcludeRawMessageFromClientPayload()
    {
        TaskErrorResult result = TaskErrorResult.of(
                TaskErrorCode.UPSTREAM_NETWORK_ERROR,
                "<!doctype html><html>gateway error</html>");

        Map<String, Object> payload = AssetExtractSseManager.buildErrorPayload(result);

        assertFalse(payload.containsKey("rawMessage"));
        assertEquals(result.getUserMessage(), payload.get("userMessage"));
        assertEquals(result.getUserMessage(), payload.get("errorMessage"));
    }
}
