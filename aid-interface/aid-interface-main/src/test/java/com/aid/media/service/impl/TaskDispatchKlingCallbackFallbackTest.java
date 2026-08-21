package com.aid.media.service.impl;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.compose.service.ComposeCompletionService;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.enums.DispatchMode;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.enums.MediaType;
import com.aid.media.service.TaskCompletionService;
import com.aid.rps.queue.MediaGenFanInSupport;
import com.aid.service.IAiModelConfigService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class TaskDispatchKlingCallbackFallbackTest {

    @Test
    void missingCallbackUrlFallsBackToPolling() {
        AiModelConfigVo model = callbackModel(null, validSecret());
        AidMediaTask task = videoTask();

        service().initDispatchSchedule(task, model);

        assertEquals(DispatchMode.POLL_ONLY.name(), task.getDispatchMode());
        assertEquals(MediaTaskStatus.WAIT_POLL.name(), task.getStatus());
    }

    @Test
    void byokApiKeyWithoutOwnWebhookSecretFallsBackToPolling() {
        AiModelConfigVo model = callbackModel("https://aid.test/api/media/callback/kling", null);
        AidMediaTask task = videoTask();

        service().initDispatchSchedule(task, model);

        assertEquals(DispatchMode.POLL_ONLY.name(), task.getDispatchMode());
        assertEquals(MediaTaskStatus.WAIT_POLL.name(), task.getStatus());
    }

    @Test
    void invalidByokWebhookSecretFallsBackToPolling() {
        AiModelConfigVo model = callbackModel("https://aid.test/api/media/callback/kling", "whsec_invalid***");
        AidMediaTask task = videoTask();

        service().initDispatchSchedule(task, model);

        assertEquals(DispatchMode.POLL_ONLY.name(), task.getDispatchMode());
        assertEquals(MediaTaskStatus.WAIT_POLL.name(), task.getStatus());
    }

    @Test
    void byokApiKeyWithOwnValidWebhookSecretWaitsForCallback() {
        AiModelConfigVo model = callbackModel("https://aid.test/api/media/callback/kling", validSecret());
        AidMediaTask task = videoTask();

        service().initDispatchSchedule(task, model);

        assertEquals(DispatchMode.CALLBACK_FIRST.name(), task.getDispatchMode());
        assertEquals(MediaTaskStatus.WAIT_CALLBACK.name(), task.getStatus());
    }

    private TaskDispatchServiceImpl service() {
        return new TaskDispatchServiceImpl(mock(AidMediaTaskMapper.class), mock(IAiModelConfigService.class),
            mock(TaskCompletionService.class), List.of(), List.of(), List.of(), List.of(),
            mock(MediaGenFanInSupport.class), mock(ComposeCompletionService.class));
    }

    private AidMediaTask videoTask() {
        AidMediaTask task = new AidMediaTask();
        task.setId(1L);
        task.setMediaType(MediaType.VIDEO.name());
        return task;
    }

    private AiModelConfigVo callbackModel(String callbackUrl, String secret) {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setModelCode("kling-3.0-omni-i2v");
        model.setProviderCode("kling");
        model.setApiKey("user-api-key");
        model.setSupportsCallback(true);
        model.setApiSecret(secret);
        String callbackPart = callbackUrl == null ? "" : ",\"callbackBaseUrl\":\"" + callbackUrl + "\"";
        model.setScheduleStrategyJson("{\"dispatchMode\":\"CALLBACK_FIRST\",\"supportsCallback\":true," +
            "\"firstPollDelaySeconds\":10" + callbackPart + "}");
        return model;
    }

    private String validSecret() {
        return "whsec_" + Base64.getEncoder().encodeToString("callback-secret".getBytes(StandardCharsets.UTF_8));
    }
}
