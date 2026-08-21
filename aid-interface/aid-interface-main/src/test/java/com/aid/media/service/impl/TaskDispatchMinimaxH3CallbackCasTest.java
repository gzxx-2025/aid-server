package com.aid.media.service.impl;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.compose.service.ComposeCompletionService;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.MinimaxH3Constants;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.enums.MediaType;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.VideoProviderClient;
import com.aid.media.service.TaskCompletionService;
import com.aid.media.util.TaskLivenessDecider;
import com.aid.rps.queue.MediaGenFanInSupport;
import com.aid.service.IAiModelConfigService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskDispatchMinimaxH3CallbackCasTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        assistant.setCurrentNamespace(TaskDispatchMinimaxH3CallbackCasTest.class.getName());
        TableInfoHelper.initTableInfo(assistant, AidMediaTask.class);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void anonymousCallbackWakeUsesWaitCallbackCasAndOnlyFirstUpdateWins() {
        AidMediaTaskMapper mapper = mock(AidMediaTaskMapper.class);
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1, 0);
        TaskDispatchServiceImpl service = new TaskDispatchServiceImpl(
            mapper, mock(IAiModelConfigService.class), mock(TaskCompletionService.class),
            List.of(), List.of(), List.of(), List.of(),
            mock(MediaGenFanInSupport.class), mock(ComposeCompletionService.class));
        AidMediaTask task = new AidMediaTask();
        task.setId(10L);
        task.setUserId(20L);

        assertTrue(service.scheduleImmediatePollIfWaitingCallback(task));
        assertFalse(service.scheduleImmediatePollIfWaitingCallback(task));

        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        for (LambdaUpdateWrapper<AidMediaTask> wrapper : captor.getAllValues()) {
            String whereSql = wrapper.getSqlSegment();
            String setSql = wrapper.getSqlSet();
            Map<String, Object> values = wrapper.getParamNameValuePairs();
            assertTrue(whereSql.contains("id") && whereSql.contains("status"));
            assertTrue(setSql.contains("status") && setSql.contains("next_poll_time"));
            assertTrue(values.containsValue(10L));
            assertTrue(values.containsValue(MediaTaskStatus.WAIT_CALLBACK.name()));
            assertTrue(values.containsValue(MediaTaskStatus.WAIT_POLL.name()));
        }
    }

    @Test
    void onlyExpiredH3SucceededAnomalyUsesSafeRefundPath() {
        AidMediaTask task = new AidMediaTask();
        task.setProtocol(MinimaxH3Constants.PROTOCOL_VIDEO);
        ProviderTaskResult succeededButIncomplete = ProviderTaskResult.builder()
            .status(MediaTaskStatus.PROCESSING.name())
            .providerStatus(MinimaxH3Constants.STATUS_SUCCEEDED)
            .querySuccessful(Boolean.FALSE)
            .terminalConfirmed(Boolean.FALSE)
            .build();

        assertTrue(TaskDispatchServiceImpl.shouldRefundExpiredMinimaxTerminalAnomaly(
            task, TaskLivenessDecider.Verdict.EXPIRED, succeededButIncomplete));
        assertFalse(TaskDispatchServiceImpl.shouldRefundExpiredMinimaxTerminalAnomaly(
            task, TaskLivenessDecider.Verdict.STALLED, succeededButIncomplete));
        task.setProtocol("other-video");
        assertFalse(TaskDispatchServiceImpl.shouldRefundExpiredMinimaxTerminalAnomaly(
            task, TaskLivenessDecider.Verdict.EXPIRED, succeededButIncomplete));
    }

    @Test
    void expiredH3SucceededAnomalyClosesFailedThroughCompletionForFullRefund() {
        AidMediaTaskMapper mapper = mock(AidMediaTaskMapper.class);
        IAiModelConfigService modelConfigService = mock(IAiModelConfigService.class);
        TaskCompletionService completionService = mock(TaskCompletionService.class);
        VideoProviderClient videoClient = mock(VideoProviderClient.class);
        TaskDispatchServiceImpl service = new TaskDispatchServiceImpl(
            mapper, modelConfigService, completionService, List.of(), List.of(videoClient), List.of(), List.of(),
            mock(MediaGenFanInSupport.class), mock(ComposeCompletionService.class));

        long old = System.currentTimeMillis() - 30_000L;
        AidMediaTask task = new AidMediaTask();
        task.setId(10L);
        task.setUserId(20L);
        task.setModelName(MinimaxH3Constants.MODEL_REFERENCE);
        task.setMediaType(MediaType.VIDEO.name());
        task.setProtocol(MinimaxH3Constants.PROTOCOL_VIDEO);
        task.setProviderTaskId("provider-task");
        task.setStatus(MediaTaskStatus.WAIT_POLL.name());
        task.setScheduleSnapshotJson("{\"maxLifeSeconds\":10,\"progressTimeoutSeconds\":5}");
        task.setUpstreamAcceptTime(new Date(old));
        task.setLastProgressTime(new Date(old));
        task.setCreateTime(new Date(old));
        task.setUpdateTime(new Date(old));
        when(mapper.selectList(any())).thenReturn(List.of(task));
        when(modelConfigService.selectByModelCodeForUser(task.getModelName(), task.getUserId()))
            .thenReturn(new AiModelConfigVo());
        when(videoClient.protocol()).thenReturn(MinimaxH3Constants.PROTOCOL_VIDEO);
        when(videoClient.supportsProtocol(MinimaxH3Constants.PROTOCOL_VIDEO)).thenReturn(true);
        when(videoClient.query(any(), eq("provider-task"))).thenReturn(ProviderTaskResult.builder()
            .status(MediaTaskStatus.PROCESSING.name())
            .providerStatus(MinimaxH3Constants.STATUS_SUCCEEDED)
            .errorMessage("输入用量尚未就绪")
            .querySuccessful(Boolean.FALSE)
            .terminalConfirmed(Boolean.FALSE)
            .build());
        when(completionService.completeTask(eq(10L), any())).thenReturn(true);

        assertEquals(1, service.closeTimeoutTasks(10));
        ArgumentCaptor<ProviderTaskResult> resultCaptor = ArgumentCaptor.forClass(ProviderTaskResult.class);
        verify(completionService).completeTask(eq(10L), resultCaptor.capture());
        assertEquals(MediaTaskStatus.FAILED.name(), resultCaptor.getValue().getStatus());
        assertEquals(Boolean.TRUE, resultCaptor.getValue().getTerminalConfirmed());
        assertEquals("结算结果超时", resultCaptor.getValue().getErrorMessage());
        assertTrue(resultCaptor.getValue().getErrorMessage().codePointCount(
            0, resultCaptor.getValue().getErrorMessage().length()) <= 12);
    }
}
