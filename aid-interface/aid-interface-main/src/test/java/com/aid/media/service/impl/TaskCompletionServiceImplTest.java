package com.aid.media.service.impl;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.billing.service.BillingFacadeService;
import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.compose.service.ComposeCompletionService;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.enums.MediaType;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.service.MediaConcurrencyLimiter;
import com.aid.media.service.MediaTaskArchiveService;
import com.aid.modelhealth.service.ModelHealthRecorder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.springframework.context.ApplicationEventPublisher;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskCompletionServiceImplTest {

    @BeforeAll
    static void initMybatisMetadata() {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "task-completion-test");
        assistant.setCurrentNamespace("com.aid.media.service.impl.TaskCompletionServiceImplTest");
        TableInfoHelper.initTableInfo(assistant, AidMediaTask.class);
    }

    @Test
    void casWinnerRecordsKlingRawFailureExactlyOnce() {
        Fixture fixture = new Fixture();
        when(fixture.mapper.update(any(), any())).thenReturn(1);
        when(fixture.billingFacadeService.refundBilling(any())).thenReturn(true);

        assertTrue(fixture.service.completeTask(1L, failedResult()));

        verify(fixture.failureRecorder, times(1)).record(
            eq(1L), eq("kling-3.0-omni-i2v"), eq("raw provider failure"));
    }

    @Test
    void casLoserDoesNotRecordKlingRawFailure() {
        Fixture fixture = new Fixture();
        when(fixture.mapper.update(any(), any())).thenReturn(0);

        assertFalse(fixture.service.completeTask(1L, failedResult()));

        verify(fixture.failureRecorder, never()).record(any(), any(), any());
    }

    @Test
    void successfulVideoPassesCompletionTokensToBillingSettlement() {
        Fixture fixture = new Fixture();
        when(fixture.mapper.update(any(), any())).thenReturn(1);
        when(fixture.billingFacadeService.settleBilling(any(), any())).thenReturn(true);
        ProviderTaskResult result = ProviderTaskResult.builder()
                .status(MediaTaskStatus.SUCCEEDED.name())
                .resultUrl("https://cdn.test/video.mp4")
                .completionTokens(194400)
                .totalTokens(194400)
                .build();

        assertTrue(fixture.service.completeTask(1L, result));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> usage = ArgumentCaptor.forClass(Map.class);
        verify(fixture.billingFacadeService).settleBilling(any(), usage.capture());
        assertEquals(194400, usage.getValue().get("completion_tokens"));
        assertEquals(194400, usage.getValue().get("output_tokens"));
        assertEquals(194400, usage.getValue().get("total_tokens"));
    }

    private static ProviderTaskResult failedResult() {
        return ProviderTaskResult.builder()
            .status(MediaTaskStatus.FAILED.name())
            .errorMessage("上游任务执行失败")
            .rawErrorMessage("raw provider failure")
            .build();
    }

    private static AidMediaTask activeTask() {
        AidMediaTask task = new AidMediaTask();
        task.setId(1L);
        task.setUserId(2L);
        task.setStatus(MediaTaskStatus.WAIT_POLL.name());
        task.setModelName("kling-3.0-omni-i2v");
        task.setMediaType(MediaType.VIDEO.name());
        task.setRequestJson("{\"payloadCompacted\":true}");
        return task;
    }

    private static final class Fixture {
        private final AidMediaTaskMapper mapper = mock(AidMediaTaskMapper.class);
        private final BillingFacadeService billingFacadeService = mock(BillingFacadeService.class);
        private final MediaConcurrencyLimiter concurrencyLimiter = mock(MediaConcurrencyLimiter.class);
        private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        private final ComposeCompletionService composeCompletionService = mock(ComposeCompletionService.class);
        private final OssConfigManager ossConfigManager = mock(OssConfigManager.class);
        private final MediaTaskArchiveService archiveService = mock(MediaTaskArchiveService.class);
        private final ModelHealthRecorder modelHealthRecorder = mock(ModelHealthRecorder.class);
        private final KlingTerminalFailureRecorder failureRecorder = mock(KlingTerminalFailureRecorder.class);
        private final TaskCompletionServiceImpl service;

        private Fixture() {
            MediaTaskArchiveService.PreparedTerminalPayload payload =
                mock(MediaTaskArchiveService.PreparedTerminalPayload.class);
            when(payload.getRequestJson()).thenReturn("{\"payloadCompacted\":true}");
            when(payload.getResponseJson()).thenReturn("");
            when(archiveService.prepareTerminalPayload(any(), any(), any())).thenReturn(payload);
            when(mapper.selectById(1L)).thenReturn(activeTask());
            service = new TaskCompletionServiceImpl(mapper, billingFacadeService, concurrencyLimiter,
                eventPublisher, composeCompletionService, ossConfigManager, archiveService,
                modelHealthRecorder, failureRecorder);
        }
    }
}
