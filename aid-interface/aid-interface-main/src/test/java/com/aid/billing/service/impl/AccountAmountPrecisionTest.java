package com.aid.billing.service.impl;

import com.aid.aid.domain.AidBalanceLog;
import com.aid.aid.service.IAidBalanceLogService;
import com.aid.billing.enums.BillingConstants;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountAmountPrecisionTest
{
    @BeforeAll
    static void initializeMybatisMetadata()
    {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        assistant.setCurrentNamespace(AidBalanceLog.class.getName());
        TableInfoHelper.initTableInfo(assistant, AidBalanceLog.class);
    }

    @Test
    void shouldNormalizeAccountAmountsAndKeepSettlementBalanced()
    {
        BigDecimal frozen = BillingConstants.normalizeAccountAmount(new BigDecimal("0.065112"));
        BigDecimal actual = BillingConstants.normalizeAccountAmount(new BigDecimal("0.058551"));
        BigDecimal refund = frozen.subtract(actual);

        assertEquals(new BigDecimal("0.0651"), frozen);
        assertEquals(new BigDecimal("0.0586"), actual);
        assertEquals(new BigDecimal("0.0065"), refund);
        assertEquals(0, frozen.compareTo(actual.add(refund)));
    }

    @Test
    void shouldUseHistoricalConsumedLogDuringCompensationRetry()
    {
        AccountUpdateServiceImpl service = new AccountUpdateServiceImpl();
        IAidBalanceLogService balanceLogService = mock(IAidBalanceLogService.class);
        ReflectionTestUtils.setField(service, "aidBalanceLogService", balanceLogService);

        AidBalanceLog historicalConsume = new AidBalanceLog();
        historicalConsume.setAmount(new BigDecimal("-0.06"));
        when(balanceLogService.list(org.mockito.ArgumentMatchers.<Wrapper<AidBalanceLog>>any()))
                .thenReturn(List.of(historicalConsume));

        BigDecimal consumed = service.resolveConsumedAmount(
                "trace-id", new BigDecimal("0.0586"));

        assertEquals(new BigDecimal("0.0600"), consumed);
    }
}
