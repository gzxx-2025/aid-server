package com.aid.rps.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BatchTaskLocalOrchestratorTest {

    @Test
    void shouldResolveFailedWhenEveryItemFailed() {
        String resultJson = "{\"totalCount\":1,\"successCount\":0,\"failCount\":1," +
                "\"failedItems\":[{\"formId\":1160,\"message\":\"本次生成未完成，稍后重试\"}]}";

        assertEquals(BatchTaskLocalOrchestrator.BatchResultState.FAILED,
                BatchTaskLocalOrchestrator.resolveResultState(resultJson));
        assertEquals("本次生成未完成，稍后重试",
                BatchTaskLocalOrchestrator.resolveFailureMessage(resultJson, "图片生成失败"));
    }

    @Test
    void shouldResolvePartialFailedWhenOnlySomeItemsSucceeded() {
        String resultJson = "{\"totalCount\":3,\"successCount\":2,\"failCount\":1}";

        assertEquals(BatchTaskLocalOrchestrator.BatchResultState.PARTIAL_FAILED,
                BatchTaskLocalOrchestrator.resolveResultState(resultJson));
    }

    @Test
    void shouldResolveSucceededWhenEveryItemSucceeded() {
        String resultJson = "{\"totalCount\":3,\"successCount\":3,\"failCount\":0}";

        assertEquals(BatchTaskLocalOrchestrator.BatchResultState.SUCCEEDED,
                BatchTaskLocalOrchestrator.resolveResultState(resultJson));
    }

    @Test
    void shouldCountSkippedItemsAsCompleted() {
        String resultJson = "{\"totalCount\":3,\"successCount\":0,\"skipCount\":3,\"failCount\":0}";

        assertEquals(BatchTaskLocalOrchestrator.BatchResultState.SUCCEEDED,
                BatchTaskLocalOrchestrator.resolveResultState(resultJson));
    }
}
