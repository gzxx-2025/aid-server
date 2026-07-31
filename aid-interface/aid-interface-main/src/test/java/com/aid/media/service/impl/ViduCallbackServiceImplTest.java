package com.aid.media.service.impl;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.service.IAidMediaTaskService;
import com.aid.common.core.redis.RedisCache;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.ViduConstants;
import com.aid.media.dto.ViduCallbackContext;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.ViduCallbackSignatureUtil;
import com.aid.media.service.TaskCompletionService;
import com.aid.media.service.TaskDispatchService;
import com.aid.service.IAiModelConfigService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ViduCallbackServiceImplTest {

    private static final String CALLBACK_URL = "https://aid.example.com/api/media/callback/vidu";
    private static final String SECRET = "secret-key";

    private IAidMediaTaskService mediaTaskService;
    private TaskCompletionService completionService;
    private IAiModelConfigService modelConfigService;
    private RedisCache redisCache;
    private TaskDispatchService taskDispatchService;
    private ViduCallbackServiceImpl callbackService;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        assistant.setCurrentNamespace(ViduCallbackServiceImplTest.class.getName());
        TableInfoHelper.initTableInfo(assistant, AidMediaTask.class);
    }

    @BeforeEach
    void setUp() {
        mediaTaskService = mock(IAidMediaTaskService.class);
        completionService = mock(TaskCompletionService.class);
        modelConfigService = mock(IAiModelConfigService.class);
        redisCache = mock(RedisCache.class);
        taskDispatchService = mock(TaskDispatchService.class);
        callbackService = new ViduCallbackServiceImpl(
            mediaTaskService, completionService, modelConfigService, redisCache, taskDispatchService);

        AidMediaTask task = new AidMediaTask();
        task.setId(10L);
        task.setUserId(20L);
        task.setModelName("vidu-model");
        task.setProtocol(ViduConstants.PROTOCOL_VIDEO);
        task.setProviderTaskId("provider-task");
        when(mediaTaskService.getOne(any(), eq(false))).thenReturn(task);
        when(modelConfigService.selectByModelCodeForUser("vidu-model", 20L)).thenReturn(modelConfig());
        when(redisCache.setCacheObjectIfAbsent(
            anyString(), eq("1"), eq(30L), eq(TimeUnit.MINUTES))).thenReturn(true);
    }

    @Test
    void shouldLeaveSucceededCallbackForPollingWhenOutputIsMissing() {
        String rawBody = "{\"id\":\"provider-task\",\"state\":\"success\","
            + "\"callback_url\":\"https://input.example.com/not-output.mp4\"}";

        callbackService.handleViduCallback(rawBody, signedContext());

        verify(completionService, never()).completeTask(anyLong(), any());
        verify(redisCache, never()).setCacheObjectIfAbsent(
            anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void shouldCompleteSucceededCallbackWithExplicitCreationUrl() {
        String rawBody = "{\"id\":\"provider-task\",\"state\":\"success\","
            + "\"creations\":[{\"url\":\"https://output.example.com/result.mp4\"}]}";
        when(completionService.completeTask(eq(10L), any())).thenReturn(true);

        callbackService.handleViduCallback(rawBody, signedContext());

        ArgumentCaptor<ProviderTaskResult> captor = ArgumentCaptor.forClass(ProviderTaskResult.class);
        verify(completionService).completeTask(eq(10L), captor.capture());
        assertEquals("SUCCEEDED", captor.getValue().getStatus());
        assertEquals("https://output.example.com/result.mp4", captor.getValue().getResultUrl());
        verify(redisCache).setCacheObjectIfAbsent(
            anyString(), eq("1"), eq(30L), eq(TimeUnit.MINUTES));
    }

    @Test
    void shouldRecordProgressForProcessingCallback() {
        String rawBody = "{\"id\":\"provider-task\",\"state\":\"processing\"}";

        callbackService.handleViduCallback(rawBody, signedContext());

        verify(taskDispatchService).markUpstreamProgress(any(AidMediaTask.class));
        verify(completionService, never()).completeTask(anyLong(), any());
    }

    @Test
    void shouldIgnoreUnknownCallbackStateWithoutRenewingProgress() {
        String rawBody = "{\"id\":\"provider-task\",\"state\":\"processing_done\"}";

        callbackService.handleViduCallback(rawBody, signedContext());

        verify(taskDispatchService, never()).markUpstreamProgress(any(AidMediaTask.class));
        verify(completionService, never()).completeTask(anyLong(), any());
    }

    @Test
    void shouldRejectTerminalCallbackWithoutNonce() {
        String rawBody = "{\"id\":\"provider-task\",\"state\":\"failed\","
            + "\"message\":\"upstream failed\"}";
        ViduCallbackContext context = signedContext();
        context.getHeaders().remove(ViduConstants.HDR_REQUEST_NONCE);
        resign(context);

        callbackService.handleViduCallback(rawBody, context);

        verify(completionService, never()).completeTask(anyLong(), any());
    }

    @Test
    void shouldRejectCallbackWhenNonceIsNotSigned() {
        String rawBody = "{\"id\":\"provider-task\",\"state\":\"failed\","
            + "\"message\":\"upstream failed\"}";
        ViduCallbackContext context = signedContext();
        context.putHeader(ViduConstants.HDR_HMAC_SIGNED_HEADERS, "content-type");
        resign(context);

        callbackService.handleViduCallback(rawBody, context);

        verify(completionService, never()).completeTask(anyLong(), any());
        verify(redisCache, never()).setCacheObjectIfAbsent(
            anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void shouldRejectCallbackWhenNonceCacheIsUnavailable() {
        String rawBody = "{\"id\":\"provider-task\",\"state\":\"failed\","
            + "\"message\":\"upstream failed\"}";
        when(redisCache.setCacheObjectIfAbsent(
            anyString(), eq("1"), eq(30L), eq(TimeUnit.MINUTES)))
            .thenThrow(new IllegalStateException("redis unavailable"));

        callbackService.handleViduCallback(rawBody, signedContext());

        verify(completionService, never()).completeTask(anyLong(), any());
    }

    @Test
    void shouldRejectCallbackInPollOnlyMode() {
        AiModelConfigVo pollOnlyModel = modelConfig();
        pollOnlyModel.setScheduleStrategyJson(
            "{\"dispatchMode\":\"POLL_ONLY\",\"callbackBaseUrl\":\"" + CALLBACK_URL + "\"}");
        when(modelConfigService.selectByModelCodeForUser("vidu-model", 20L)).thenReturn(pollOnlyModel);

        callbackService.handleViduCallback(
            "{\"id\":\"provider-task\",\"state\":\"failed\"}", signedContext());

        verify(completionService, never()).completeTask(anyLong(), any());
        verify(redisCache, never()).setCacheObjectIfAbsent(
            anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    private AiModelConfigVo modelConfig() {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setProviderCode(ViduConstants.PROVIDER_CODE);
        model.setApiKey(SECRET);
        model.setSupportsCallback(true);
        model.setScheduleStrategyJson("{\"dispatchMode\":\"CALLBACK_FIRST\"}");
        model.setProviderScheduleStrategyJson(
            "{\"callbackBaseUrl\":\"" + CALLBACK_URL + "\"}");
        return model;
    }

    private ViduCallbackContext signedContext() {
        ViduCallbackContext context = new ViduCallbackContext();
        context.putHeader("Date", ZonedDateTime.now(ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME));
        context.putHeader(ViduConstants.HDR_HMAC_ALGORITHM, ViduConstants.HMAC_ALGORITHM_VALUE);
        context.putHeader(ViduConstants.HDR_HMAC_ACCESS_KEY, ViduConstants.CALLBACK_ACCESS_KEY);
        context.putHeader(ViduConstants.HDR_REQUEST_NONCE, "nonce-1");
        context.putHeader(ViduConstants.HDR_HMAC_SIGNED_HEADERS, ViduConstants.HDR_REQUEST_NONCE);
        resign(context);
        return context;
    }

    private void resign(ViduCallbackContext context) {
        String signature = ViduCallbackSignatureUtil.sign(
            "POST",
            CALLBACK_URL,
            context.getHeader("Date"),
            context.getHeader(ViduConstants.HDR_HMAC_SIGNED_HEADERS),
            SECRET,
            context::getHeader);
        context.putHeader(ViduConstants.HDR_HMAC_SIGNATURE, signature);
    }
}
