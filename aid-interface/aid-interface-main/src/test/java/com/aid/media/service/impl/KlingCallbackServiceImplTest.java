package com.aid.media.service.impl;

import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.aid.service.IAidMediaTaskService;
import com.aid.common.core.redis.RedisCache;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.KlingConstants;
import com.aid.media.dto.KlingCallbackContext;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.service.KlingCallbackResult;
import com.aid.media.service.TaskCompletionService;
import com.aid.media.service.TaskDispatchService;
import com.aid.service.IAiModelConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KlingCallbackServiceImplTest {

    private IAidMediaTaskService mediaTaskService;
    private IAidAiProviderService providerService;
    private IAiModelConfigService modelConfigService;
    private TaskCompletionService completionService;
    private TaskDispatchService dispatchService;
    private RedisCache redisCache;
    private KlingCallbackServiceImpl service;

    @BeforeAll
    static void initMybatisMetadata() {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "kling-callback-test");
        assistant.setCurrentNamespace("com.aid.media.service.impl.KlingCallbackServiceImplTest");
        TableInfoHelper.initTableInfo(assistant, AidMediaTask.class);
        MapperBuilderAssistant providerAssistant = new MapperBuilderAssistant(configuration, "kling-provider-test");
        providerAssistant.setCurrentNamespace("com.aid.media.service.impl.KlingCallbackServiceImplTest.provider");
        TableInfoHelper.initTableInfo(providerAssistant, AidAiProvider.class);
    }

    @BeforeEach
    void setUp() {
        mediaTaskService = mock(IAidMediaTaskService.class);
        providerService = mock(IAidAiProviderService.class);
        modelConfigService = mock(IAiModelConfigService.class);
        completionService = mock(TaskCompletionService.class);
        dispatchService = mock(TaskDispatchService.class);
        redisCache = mock(RedisCache.class);
        service = new KlingCallbackServiceImpl(mediaTaskService, providerService, modelConfigService, completionService,
            dispatchService, redisCache);
        when(providerService.list(org.mockito.ArgumentMatchers.<Wrapper<AidAiProvider>>any()))
            .thenReturn(List.of(provider()));
        when(mediaTaskService.list(org.mockito.ArgumentMatchers.<Wrapper<AidMediaTask>>any()))
            .thenReturn(List.of(activeTask()));
        when(modelConfigService.selectByModelCodeForUser(any(), anyLong())).thenReturn(model());
        when(redisCache.setCacheObjectIfAbsent(any(), any(), anyLong(), any())).thenReturn(true);
    }

    @Test
    void acceptsNewCallbackSchema() {
        String body = "{\"id\":\"provider-1\",\"status\":\"succeeded\",\"outputs\":[{"
            + "\"type\":\"video\",\"url\":\"https://cdn.test/new.mp4\",\"duration\":\"5.1\"}]}";

        assertEquals(KlingCallbackResult.ACCEPTED, service.handleKlingCallback(body, signedContext(body)));

        ArgumentCaptor<ProviderTaskResult> result = ArgumentCaptor.forClass(ProviderTaskResult.class);
        verify(completionService).completeTask(eq(1L), result.capture());
        assertEquals("https://cdn.test/new.mp4", result.getValue().getResultUrl());
        assertEquals(6, result.getValue().getVideoDurationSeconds());
    }

    @Test
    void acceptsLegacyOmniCallbackSchema() {
        String body = "{\"task_id\":\"provider-1\",\"task_status\":\"succeed\",\"task_result\":{"
            + "\"videos\":[{\"url\":\"https://cdn.test/legacy.mp4\",\"duration\":\"8\"}]}}";

        assertEquals(KlingCallbackResult.ACCEPTED, service.handleKlingCallback(body, signedContext(body)));

        ArgumentCaptor<ProviderTaskResult> result = ArgumentCaptor.forClass(ProviderTaskResult.class);
        verify(completionService).completeTask(eq(1L), result.capture());
        assertEquals("https://cdn.test/legacy.mp4", result.getValue().getResultUrl());
        assertEquals(8, result.getValue().getVideoDurationSeconds());
    }

    @Test
    void preservesSafetyFailureRawMessageButCompletesWithSafeCallbackMessage() {
        String upstream = "Your prompt was blocked by the content safety policy. Please adjust your prompt and try again.";
        String body = "{\"id\":\"provider-1\",\"status\":\"failed\",\"message\":\"" + upstream + "\"}";

        assertEquals(KlingCallbackResult.ACCEPTED, service.handleKlingCallback(body, signedContext(body)));

        ArgumentCaptor<ProviderTaskResult> result = ArgumentCaptor.forClass(ProviderTaskResult.class);
        verify(completionService).completeTask(eq(1L), result.capture());
        assertEquals("生成内容未通过安全校验", result.getValue().getErrorMessage());
        assertEquals(upstream, result.getValue().getRawErrorMessage());
    }

    @Test
    void duplicateWebhookIsIdempotent() {
        when(redisCache.getCacheObject(any())).thenReturn("COMPLETED");
        when(redisCache.setCacheObjectIfAbsent(any(), any(), anyLong(), any())).thenReturn(false);
        String body = "{\"id\":\"provider-1\",\"status\":\"processing\"}";

        assertEquals(KlingCallbackResult.ACCEPTED, service.handleKlingCallback(body, signedContext(body)));
        verify(completionService, never()).completeTask(anyLong(), any());
        verify(dispatchService, never()).markUpstreamProgress(any());
    }

    @Test
    void sameWebhookCanRetryAfterSucceededPayloadInitiallyMissesOutput() {
        String webhookId = "webhook-retry-missing-output";
        String incomplete = "{\"id\":\"provider-1\",\"status\":\"succeeded\",\"outputs\":[]}";
        String complete = "{\"id\":\"provider-1\",\"status\":\"succeeded\",\"outputs\":[{"
            + "\"type\":\"video\",\"url\":\"https://cdn.test/retry.mp4\",\"duration\":\"5\"}]}";

        assertEquals(KlingCallbackResult.RETRYABLE_INTERNAL,
            service.handleKlingCallback(incomplete, signedContext(incomplete, webhookId)));
        assertEquals(KlingCallbackResult.ACCEPTED,
            service.handleKlingCallback(complete, signedContext(complete, webhookId)));

        verify(redisCache, times(1)).setCacheObjectIfAbsent(any(), any(), anyLong(), any());
        verify(completionService, times(1)).completeTask(eq(1L), any());
    }

    @Test
    void sameWebhookCanRetryAfterProcessingServiceThrows() {
        String webhookId = "webhook-retry-processing";
        String body = "{\"id\":\"provider-1\",\"status\":\"processing\"}";
        doThrow(new IllegalStateException("temporary failure")).doNothing()
            .when(dispatchService).markUpstreamProgress(any());

        assertEquals(KlingCallbackResult.RETRYABLE_INTERNAL,
            service.handleKlingCallback(body, signedContext(body, webhookId)));
        assertEquals(KlingCallbackResult.ACCEPTED,
            service.handleKlingCallback(body, signedContext(body, webhookId)));

        verify(dispatchService, times(2)).markUpstreamProgress(any());
        verify(redisCache, times(2)).setCacheObjectIfAbsent(any(), any(), anyLong(), any());
        verify(redisCache, times(2)).deleteObjectIfValueEquals(any(), any());
    }

    @Test
    void idempotencyStoreFailureRequestsRetry() {
        doThrow(new IllegalStateException("redis unavailable")).when(redisCache)
            .setCacheObjectIfAbsent(any(), any(), anyLong(), any());
        String body = "{\"id\":\"provider-1\",\"status\":\"processing\"}";

        assertEquals(KlingCallbackResult.RETRYABLE_INTERNAL,
            service.handleKlingCallback(body, signedContext(body)));
    }

    @Test
    void unknownTaskWithValidSignatureIsAcceptedAfterAuthentication() {
        when(mediaTaskService.list(org.mockito.ArgumentMatchers.<Wrapper<AidMediaTask>>any())).thenReturn(List.of());
        String body = "{\"id\":\"unknown-provider-task\",\"status\":\"processing\"}";

        assertEquals(KlingCallbackResult.ACCEPTED, service.handleKlingCallback(body, signedContext(body)));

        verify(providerService).list(org.mockito.ArgumentMatchers.<Wrapper<AidAiProvider>>any());
        verify(modelConfigService, never()).selectByModelCodeForUser(any(), anyLong());
        verify(redisCache, never()).setCacheObjectIfAbsent(any(), any(), anyLong(), any());
    }

    @Test
    void unknownTaskWithInvalidSignatureIsRejectedWithoutResolvingUserConfig() {
        when(mediaTaskService.list(org.mockito.ArgumentMatchers.<Wrapper<AidMediaTask>>any())).thenReturn(List.of());
        String body = "{\"id\":\"unknown-provider-task\",\"status\":\"processing\"}";
        KlingCallbackContext invalid = signedContext(body);
        invalid.putHeader(KlingConstants.CALLBACK_HEADER_SIGNATURE, "v1,invalid");

        assertEquals(KlingCallbackResult.INVALID_SIGNATURE_OR_PAYLOAD,
            service.handleKlingCallback(body, invalid));

        verify(mediaTaskService).list(org.mockito.ArgumentMatchers.<Wrapper<AidMediaTask>>any());
        verify(modelConfigService, never()).selectByModelCodeForUser(any(), anyLong());
    }

    @Test
    void completedTaskDuplicateStillRequiresSignature() {
        when(mediaTaskService.list(org.mockito.ArgumentMatchers.<Wrapper<AidMediaTask>>any())).thenReturn(List.of());
        String body = "{\"id\":\"completed-provider-task\",\"status\":\"succeeded\"}";
        KlingCallbackContext invalid = signedContext(body);
        invalid.putHeader(KlingConstants.CALLBACK_HEADER_SIGNATURE, "v1,invalid");

        assertEquals(KlingCallbackResult.INVALID_SIGNATURE_OR_PAYLOAD,
            service.handleKlingCallback(body, invalid));
        verify(mediaTaskService, times(1)).list(org.mockito.ArgumentMatchers.<Wrapper<AidMediaTask>>any());

        assertEquals(KlingCallbackResult.ACCEPTED,
            service.handleKlingCallback(body, signedContext(body, "completed-valid")));
        verify(mediaTaskService, times(2)).list(org.mockito.ArgumentMatchers.<Wrapper<AidMediaTask>>any());
    }

    @Test
    void byokCallbackUsesTaskOwnersEffectiveWebhookSecret() {
        String byokSecret = secret("user-callback-secret");
        when(providerService.list(org.mockito.ArgumentMatchers.<Wrapper<AidAiProvider>>any())).thenReturn(List.of());
        when(modelConfigService.selectByModelCodeForUser("kling-3.0-omni-i2v", 2L))
            .thenReturn(model(byokSecret));
        String body = "{\"id\":\"provider-1\",\"status\":\"processing\"}";

        assertEquals(KlingCallbackResult.ACCEPTED,
            service.handleKlingCallback(body, signedContext(body, "byok-valid", byokSecret)));

        verify(dispatchService).markUpstreamProgress(any());
    }

    @Test
    void byokCallbackRejectsDifferentWebhookSecret() {
        String byokSecret = secret("user-callback-secret");
        when(modelConfigService.selectByModelCodeForUser("kling-3.0-omni-i2v", 2L))
            .thenReturn(model(byokSecret));
        String body = "{\"id\":\"provider-1\",\"status\":\"processing\"}";

        assertEquals(KlingCallbackResult.INVALID_SIGNATURE_OR_PAYLOAD,
            service.handleKlingCallback(body, signedContext(body, "byok-wrong", secret())));

        verify(dispatchService, never()).markUpstreamProgress(any());
        verify(completionService, never()).completeTask(anyLong(), any());
    }

    @Test
    void terminalByokTaskDuplicateAuthenticatesWithTaskSecret() {
        String byokSecret = secret("user-callback-secret");
        AidMediaTask terminal = activeTask();
        terminal.setStatus(MediaTaskStatus.SUCCEEDED.name());
        when(mediaTaskService.list(org.mockito.ArgumentMatchers.<Wrapper<AidMediaTask>>any()))
            .thenReturn(List.of(terminal));
        when(modelConfigService.selectByModelCodeForUser("kling-3.0-omni-i2v", 2L))
            .thenReturn(model(byokSecret));
        String body = "{\"id\":\"provider-1\",\"status\":\"succeeded\"}";

        assertEquals(KlingCallbackResult.ACCEPTED,
            service.handleKlingCallback(body, signedContext(body, "byok-terminal", byokSecret)));

        verify(completionService, never()).completeTask(anyLong(), any());
        verify(redisCache, never()).setCacheObjectIfAbsent(any(), any(), anyLong(), any());
    }

    private AidMediaTask activeTask() {
        AidMediaTask task = new AidMediaTask();
        task.setId(1L);
        task.setUserId(2L);
        task.setModelName("kling-3.0-omni-i2v");
        task.setProviderTaskId("provider-1");
        task.setProtocol(KlingConstants.PROTOCOL_VIDEO);
        task.setStatus(MediaTaskStatus.WAIT_CALLBACK.name());
        return task;
    }

    private AiModelConfigVo model() {
        return model(secret());
    }

    private AiModelConfigVo model(String callbackSecret) {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setProviderCode(KlingConstants.PROVIDER_CODE);
        model.setSupportsCallback(true);
        model.setApiSecret(callbackSecret);
        model.setScheduleStrategyJson("{\"dispatchMode\":\"CALLBACK_FIRST\",\"supportsCallback\":true,"
            + "\"callbackBaseUrl\":\"https://aid.test/api/media/callback/kling\"}");
        return model;
    }

    private AidAiProvider provider() {
        AidAiProvider provider = new AidAiProvider();
        provider.setProviderCode(KlingConstants.PROVIDER_CODE);
        provider.setApiSecret(secret());
        return provider;
    }

    private KlingCallbackContext signedContext(String body) {
        return signedContext(body, "webhook-" + body.hashCode());
    }

    private KlingCallbackContext signedContext(String body, String id) {
        return signedContext(body, id, secret());
    }

    private KlingCallbackContext signedContext(String body, String id, String callbackSecret) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        KlingCallbackContext context = new KlingCallbackContext();
        context.putHeader(KlingConstants.CALLBACK_HEADER_ID, id);
        context.putHeader(KlingConstants.CALLBACK_HEADER_TIMESTAMP, timestamp);
        context.putHeader(KlingConstants.CALLBACK_HEADER_SIGNATURE,
            "v1," + sign(id + "." + timestamp + "." + body, callbackSecret));
        return context;
    }

    private String secret() {
        return secret("callback-secret");
    }

    private String secret(String value) {
        return "whsec_" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String payload, String callbackSecret) {
        try {
            byte[] key = Base64.getDecoder().decode(callbackSecret.substring("whsec_".length()));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
