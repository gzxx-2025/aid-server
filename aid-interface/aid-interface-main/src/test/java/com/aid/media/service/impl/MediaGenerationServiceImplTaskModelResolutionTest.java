package com.aid.media.service.impl;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.compose.ComposeConstants;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.enums.MediaType;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.VideoProviderClient;
import com.aid.media.service.TaskDispatchService;
import com.aid.service.IAiModelConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaGenerationServiceImplTaskModelResolutionTest {

    @Test
    void queuedAsyncKlingTaskUsesOwnerCredentialsAndNeverPlatformCredentials() {
        IAiModelConfigService configService = mock(IAiModelConfigService.class);
        MediaGenerationServiceImpl service = serviceWithConfig(configService);
        AidMediaTask task = videoTask(MediaTaskStatus.QUEUED.name());
        AiModelConfigVo byok = config("byok-key");
        AiModelConfigVo platform = config("platform-key");
        when(configService.selectByModelCodeForUser(task.getModelName(), task.getUserId())).thenReturn(byok);
        when(configService.selectByModelCode(task.getModelName())).thenReturn(platform);

        AiModelConfigVo resolved = service.resolveTaskModelConfig(task);

        assertSame(byok, resolved);
        verify(configService).selectByModelCodeForUser(task.getModelName(), task.getUserId());
        verify(configService, never()).selectByModelCode(task.getModelName());
    }

    @Test
    void acceptedAsyncTaskDispatchSnapshotUsesOwnerCredentials() {
        IAiModelConfigService configService = mock(IAiModelConfigService.class);
        MediaGenerationServiceImpl service = serviceWithConfig(configService);
        TaskDispatchService dispatchService = mock(TaskDispatchService.class);
        ReflectionTestUtils.setField(service, "taskDispatchService", dispatchService);
        AidMediaTask task = videoTask(MediaTaskStatus.PENDING.name());
        AiModelConfigVo byok = config("byok-key");
        when(configService.selectByModelCodeForUser(task.getModelName(), task.getUserId())).thenReturn(byok);
        ProviderSubmitResult submitResult = ProviderSubmitResult.builder()
            .providerTaskId("upstream-1")
            .rawResponse("{}")
            .build();

        Boolean won = ReflectionTestUtils.invokeMethod(service, "handleSubmitResult", task, submitResult);

        assertTrue(Boolean.TRUE.equals(won));
        verify(dispatchService).initDispatchSchedule(task, byok);
        verify(configService, never()).selectByModelCode(task.getModelName());
    }

    @Test
    void processingTaskQueryUsesSameOwnerCredentialChain() {
        IAiModelConfigService configService = mock(IAiModelConfigService.class);
        MediaGenerationServiceImpl service = serviceWithConfig(configService);
        AidMediaTask task = videoTask(MediaTaskStatus.PROCESSING.name());
        task.setProviderTaskId("upstream-1");
        AiModelConfigVo byok = config("byok-key");
        when(configService.selectByModelCodeForUser(task.getModelName(), task.getUserId())).thenReturn(byok);
        VideoProviderClient client = mock(VideoProviderClient.class);
        when(client.protocol()).thenReturn(task.getProtocol());
        when(client.query(byok, task.getProviderTaskId())).thenReturn(null);
        ReflectionTestUtils.setField(service, "videoProviderClients", List.of(client));

        ReflectionTestUtils.invokeMethod(service, "refreshProcessingTask", task, true, false);

        verify(client).query(byok, task.getProviderTaskId());
        verify(configService).selectByModelCodeForUser(task.getModelName(), task.getUserId());
        verify(configService, never()).selectByModelCode(task.getModelName());
    }

    @Test
    void composeTaskKeepsPlatformConfigurationResolver() {
        IAiModelConfigService configService = mock(IAiModelConfigService.class);
        MediaGenerationServiceImpl service = serviceWithConfig(configService);
        AidMediaTask task = videoTask(MediaTaskStatus.QUEUED.name());
        task.setMediaType(ComposeConstants.MEDIA_TYPE_COMPOSE);
        AiModelConfigVo platform = config("platform-key");
        when(configService.selectByModelCode(task.getModelName())).thenReturn(platform);

        assertSame(platform, service.resolveTaskModelConfig(task));

        verify(configService).selectByModelCode(task.getModelName());
        verify(configService, never()).selectByModelCodeForUser(task.getModelName(), task.getUserId());
    }

    private MediaGenerationServiceImpl serviceWithConfig(IAiModelConfigService configService) {
        MediaGenerationServiceImpl service = mock(MediaGenerationServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "aiModelConfigService", configService);
        return service;
    }

    private AidMediaTask videoTask(String status) {
        AidMediaTask task = new AidMediaTask();
        task.setId(1L);
        task.setUserId(42L);
        task.setModelName("kling-3.0-omni-i2v");
        task.setMediaType(MediaType.VIDEO.name());
        task.setProtocol("kling-video");
        task.setStatus(status);
        return task;
    }

    private AiModelConfigVo config(String apiKey) {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setModelCode("kling-3.0-omni-i2v");
        config.setProviderCode("kling");
        config.setApiKey(apiKey);
        return config;
    }
}
