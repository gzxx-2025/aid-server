package com.aid.rps.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.common.exception.ServiceException;
import com.aid.rps.dto.ExtractTaskMessage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;

class TaskQueueServiceMqDispatchLockTest
{
    private static final Long TASK_ID = 901L;
    private static final Long PROJECT_ID = 902L;
    private static final Long EPISODE_ID = 903L;
    private static final Long USER_ID = 904L;
    private static final String MODEL_CODE = "model-code";
    private static final String TASK_TYPE = "asset_extract";
    private static final String DISPATCH_TOKEN = "dispatch-token";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TaskQueueService service;
    private RedissonClient redissonClient;
    private RLock dispatchLock;
    private IAidExtractTaskService extractTaskService;
    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception
    {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new Configuration(), "task-queue-mq-lock-test");
        assistant.setCurrentNamespace("task-queue-mq-lock-test");
        TableInfoHelper.initTableInfo(assistant, AidExtractTask.class);

        service = new TaskQueueService();
        redissonClient = mock(RedissonClient.class);
        dispatchLock = mock(RLock.class);
        extractTaskService = mock(IAidExtractTaskService.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);

        ReflectionTestUtils.setField(service, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(service, "extractTaskService", extractTaskService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);

        when(redissonClient.getLock(TaskQueueKeys.dispatchLockKey(TASK_ID))).thenReturn(dispatchLock);
        when(dispatchLock.isHeldByCurrentThread()).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(TaskQueueKeys.ctxKey(TASK_ID)))
                .thenReturn(objectMapper.writeValueAsString(receipt(DISPATCH_TOKEN)));
        when(extractTaskService.getOne(any(), eq(false))).thenReturn(pendingTask(DISPATCH_TOKEN));
    }

    @AfterEach
    void clearInterruptedFlag()
    {
        Thread.interrupted();
    }

    @Test
    void waitsForProducerLockThenResolvesCurrentDispatchToken() throws Exception
    {
        CountDownLatch consumerStartedWaiting = new CountDownLatch(1);
        CountDownLatch producerReleasedLock = new CountDownLatch(1);
        when(dispatchLock.tryLock(10L, TimeUnit.SECONDS)).thenAnswer(invocation -> {
            consumerStartedWaiting.countDown();
            return producerReleasedLock.await(2, TimeUnit.SECONDS);
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> result = executor.submit(
                () -> service.resolveMqConsumerDispatchToken(message(DISPATCH_TOKEN)));
        try
        {
            assertTrue(consumerStartedWaiting.await(1, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            verifyNoInteractions(extractTaskService);

            producerReleasedLock.countDown();

            assertEquals(DISPATCH_TOKEN, result.get(1, TimeUnit.SECONDS));
            verify(dispatchLock).tryLock(10L, TimeUnit.SECONDS);
            verify(dispatchLock).unlock();
        }
        finally
        {
            producerReleasedLock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void lockTimeoutTriggersMqRetryBeforeReadingTaskOrReceipt() throws Exception
    {
        when(dispatchLock.tryLock(10L, TimeUnit.SECONDS)).thenReturn(false);

        assertThrows(ServiceException.class,
                () -> service.resolveMqConsumerDispatchToken(message(DISPATCH_TOKEN)));

        verifyNoInteractions(extractTaskService);
        verify(stringRedisTemplate, never()).opsForValue();
        verify(dispatchLock, never()).unlock();
    }

    @Test
    void interruptedLockWaitRestoresThreadInterruptedFlag() throws Exception
    {
        assertFalse(Thread.currentThread().isInterrupted());
        when(dispatchLock.tryLock(10L, TimeUnit.SECONDS)).thenThrow(new InterruptedException());

        assertThrows(ServiceException.class,
                () -> service.resolveMqConsumerDispatchToken(message(DISPATCH_TOKEN)));

        assertTrue(Thread.currentThread().isInterrupted());
        verifyNoInteractions(extractTaskService);
        verify(stringRedisTemplate, never()).opsForValue();
        verify(dispatchLock, never()).unlock();
    }

    @Test
    void mismatchedMessageTokenIsAcknowledgedAsStale() throws Exception
    {
        when(dispatchLock.tryLock(10L, TimeUnit.SECONDS)).thenReturn(true);

        assertNull(service.resolveMqConsumerDispatchToken(message("stale-token")));

        verify(dispatchLock).unlock();
    }

    private ExtractTaskMessage message(String dispatchToken)
    {
        return ExtractTaskMessage.builder()
                .taskId(TASK_ID)
                .projectId(PROJECT_ID)
                .episodeId(EPISODE_ID)
                .userId(USER_ID)
                .modelCode(MODEL_CODE)
                .taskType(TASK_TYPE)
                .dispatchToken(dispatchToken)
                .build();
    }

    private QueuedTaskContext receipt(String dispatchToken)
    {
        return QueuedTaskContext.builder()
                .taskId(TASK_ID)
                .projectId(PROJECT_ID)
                .episodeId(EPISODE_ID)
                .userId(USER_ID)
                .modelCode(MODEL_CODE)
                .taskType(TASK_TYPE)
                .dispatchMode(MqTaskDispatchExecutor.MODE)
                .dispatchToken(dispatchToken)
                .build();
    }

    private AidExtractTask pendingTask(String dispatchToken)
    {
        AidExtractTask task = new AidExtractTask();
        task.setId(TASK_ID);
        task.setProjectId(PROJECT_ID);
        task.setEpisodeId(EPISODE_ID);
        task.setUserId(USER_ID);
        task.setModelCode(MODEL_CODE);
        task.setTaskType(TASK_TYPE);
        task.setStatus("PENDING");
        task.setBillingTraceId(dispatchToken);
        return task;
    }
}
