package com.aid.media.service.impl;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.billing.service.BillingRecordMetadataService;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.common.core.redis.RedisCache;
import com.aid.media.enums.MediaBillingStatus;
import com.aid.notify.wechat.service.IWechatNotifyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaBillingFreeFlowTest
{
    private final AidMediaTaskMapper mapper = mock(AidMediaTaskMapper.class);
    private final IAccountUpdateService accountUpdateService = mock(IAccountUpdateService.class);
    private MediaBillingServiceImpl service;

    @BeforeAll
    static void initMybatisMetadata()
    {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "media-billing-free-test");
        assistant.setCurrentNamespace("com.aid.media.service.impl.MediaBillingFreeFlowTest");
        TableInfoHelper.initTableInfo(assistant, AidMediaTask.class);
    }

    @BeforeEach
    void setUp()
    {
        service = new MediaBillingServiceImpl();
        ReflectionTestUtils.setField(service, "aidMediaTaskMapper", mapper);
        ReflectionTestUtils.setField(service, "accountUpdateService", accountUpdateService);
        ReflectionTestUtils.setField(service, "billingRecordMetadataService", mock(BillingRecordMetadataService.class));
        ReflectionTestUtils.setField(service, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(service, "wechatNotifyService", mock(IWechatNotifyService.class));
        when(mapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void freeSuccessChangesOnlyTaskBillingState()
    {
        AidMediaTask task = freeTask(MediaBillingStatus.FROZEN.name());

        assertTrue(service.settleBilling(task));
        assertEquals(MediaBillingStatus.SUCCESS.name(), task.getBillingStatus());
        verify(accountUpdateService, never()).settle(any(), any(), any(), any(), any());
    }

    @Test
    void freeFailureDoesNotCreateRefund()
    {
        AidMediaTask task = freeTask(MediaBillingStatus.FROZEN.name());

        assertTrue(service.refundBilling(task));
        assertEquals(MediaBillingStatus.FAILED.name(), task.getBillingStatus());
        verify(accountUpdateService, never()).refund(any(), any(), any(), any(), any());
    }

    private AidMediaTask freeTask(String billingStatus)
    {
        AidMediaTask task = new AidMediaTask();
        task.setId(9L);
        task.setUserId(3L);
        task.setBillingStatus(billingStatus);
        task.setBillingTraceId("free-trace");
        task.setFrozenAmount(BigDecimal.ZERO);
        return task;
    }
}
