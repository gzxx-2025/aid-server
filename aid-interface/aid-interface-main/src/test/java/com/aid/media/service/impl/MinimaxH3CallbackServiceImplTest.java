package com.aid.media.service.impl;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.service.IAidMediaTaskService;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.MinimaxH3Constants;
import com.aid.media.service.TaskDispatchService;
import com.aid.service.IAiModelConfigService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinimaxH3CallbackServiceImplTest {

    private IAidMediaTaskService mediaTaskService;
    private IAiModelConfigService modelConfigService;
    private TaskDispatchService taskDispatchService;
    private MinimaxH3CallbackServiceImpl callbackService;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        assistant.setCurrentNamespace(MinimaxH3CallbackServiceImplTest.class.getName());
        TableInfoHelper.initTableInfo(assistant, AidMediaTask.class);
    }

    @BeforeEach
    void setUp() {
        mediaTaskService = mock(IAidMediaTaskService.class);
        modelConfigService = mock(IAiModelConfigService.class);
        taskDispatchService = mock(TaskDispatchService.class);
        callbackService = new MinimaxH3CallbackServiceImpl(
            mediaTaskService, modelConfigService, taskDispatchService);
    }

    @Test
    void shouldEchoChallengeWithoutLookingUpTask() {
        assertEquals("challenge-value",
            callbackService.handleCallback("{\"challenge\":\"challenge-value\"}"));

        verify(mediaTaskService, never()).getOne(any(), eq(false));
        verify(taskDispatchService, never()).scheduleImmediatePollIfWaitingCallback(any());
    }

    @Test
    void shouldOnlyWakeUnifiedPollerForValidNotification() {
        AidMediaTask task = task();
        when(mediaTaskService.getOne(any(), eq(false))).thenReturn(task);
        when(modelConfigService.selectByModelCodeForUser(task.getModelName(), task.getUserId()))
            .thenReturn(model());

        assertNull(callbackService.handleCallback(
            "{\"task\":{\"id\":\"provider-task\",\"status\":\"succeeded\"}}"));

        verify(taskDispatchService).scheduleImmediatePollIfWaitingCallback(task);
    }

    @Test
    void shouldWakeOnlyFirstOfRepeatedNotificationsAfterCas() {
        AidMediaTask task = task();
        // The first lookup observes WAIT_CALLBACK. scheduleImmediatePoll changes it to
        // WAIT_POLL with a CAS, so the second WAIT_CALLBACK-only lookup no longer matches.
        when(mediaTaskService.getOne(any(), eq(false))).thenReturn(task).thenReturn(null);
        when(modelConfigService.selectByModelCodeForUser(task.getModelName(), task.getUserId()))
            .thenReturn(model());
        String notification = "{\"task\":{\"id\":\"provider-task\",\"status\":\"running\"}}";

        callbackService.handleCallback(notification);
        callbackService.handleCallback(notification);

        verify(taskDispatchService, times(1)).scheduleImmediatePollIfWaitingCallback(task);
    }

    @Test
    void shouldIgnoreNotificationWhenCallbackUrlIsNotConfigured() {
        AidMediaTask task = task();
        AiModelConfigVo model = model();
        model.setScheduleStrategyJson("{\"dispatchMode\":\"CALLBACK_FIRST\",\"supportsCallback\":true}");
        when(mediaTaskService.getOne(any(), eq(false))).thenReturn(task);
        when(modelConfigService.selectByModelCodeForUser(task.getModelName(), task.getUserId()))
            .thenReturn(model);

        callbackService.handleCallback("{\"task_id\":\"provider-task\"}");

        verify(taskDispatchService, never()).scheduleImmediatePollIfWaitingCallback(any());
    }

    private AidMediaTask task() {
        AidMediaTask task = new AidMediaTask();
        task.setId(10L);
        task.setUserId(20L);
        task.setModelName(MinimaxH3Constants.MODEL_REFERENCE);
        task.setProtocol(MinimaxH3Constants.PROTOCOL_VIDEO);
        task.setProviderTaskId("provider-task");
        return task;
    }

    private AiModelConfigVo model() {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setModelCode(MinimaxH3Constants.MODEL_REFERENCE);
        model.setProviderCode(MinimaxH3Constants.PROVIDER_CODE);
        model.setProtocol(MinimaxH3Constants.PROTOCOL_VIDEO);
        model.setSupportsCallback(true);
        model.setScheduleStrategyJson("{\"dispatchMode\":\"CALLBACK_FIRST\",\"supportsCallback\":true,"
            + "\"callbackBaseUrl\":\"https://aid.example.com/api/media/callback/minimax-h3\"}");
        return model;
    }
}
