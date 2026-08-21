package com.aid.media.service.impl;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.common.exception.ServiceException;
import com.aid.media.constants.KlingConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class KlingSubmissionFailureRecorderTest {

    @Test
    void recordsKlingRejectedSubmissionWithMediaTaskContext() {
        KlingTerminalFailureRecorder delegate = mock(KlingTerminalFailureRecorder.class);
        KlingSubmissionFailureRecorder recorder = new KlingSubmissionFailureRecorder(delegate);
        AidMediaTask task = task(KlingConstants.PROTOCOL_VIDEO);
        ServiceException failure = new ServiceException("输入内容未通过安全校验", 400)
            .setDetailMessage("{\"business_code\":1300,\"request_id\":\"request-1\"}");

        assertTrue(recorder.record(task, failure));

        verify(delegate).record(101L, "kling-3.0-omni-reference", 400,
            failure.getDetailMessage());
    }

    @Test
    void ignoresNonKlingOrNonUpstreamFailures() {
        KlingTerminalFailureRecorder delegate = mock(KlingTerminalFailureRecorder.class);
        KlingSubmissionFailureRecorder recorder = new KlingSubmissionFailureRecorder(delegate);

        assertFalse(recorder.record(task("other-video"), new ServiceException("失败", 400)
            .setDetailMessage("detail")));
        assertFalse(recorder.record(task(KlingConstants.PROTOCOL_VIDEO),
            new ServiceException("本地校验失败")));

        verifyNoInteractions(delegate);
    }

    private AidMediaTask task(String protocol) {
        AidMediaTask task = new AidMediaTask();
        task.setId(101L);
        task.setProtocol(protocol);
        task.setModelName("kling-3.0-omni-reference");
        return task;
    }
}
