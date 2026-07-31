package com.aid.rps.assembler;

import com.aid.aid.domain.AidExtractTask;
import com.aid.common.error.TaskErrorCode;
import com.aid.rps.dto.TaskDetailVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TaskDetailAssemblerTest
{
    @Test
    void shouldNormalizeDiagnosticsOnSuccessfulTask()
    {
        AidExtractTask task = new AidExtractTask();
        task.setId(100L);
        task.setStatus("SUCCEEDED");
        task.setErrorMessage("OSS 持久化失败：SocketTimeoutException");

        TaskDetailVO result = TaskDetailAssembler.toDetailVO(task);

        assertEquals(TaskErrorCode.OSS_PERSIST_FAILED.name(), result.getErrorCode());
        assertEquals("生成结果保存失败，重新生成", result.getErrorMessage());
        assertFalse(result.getErrorMessage().contains("SocketTimeoutException"));
    }
}
