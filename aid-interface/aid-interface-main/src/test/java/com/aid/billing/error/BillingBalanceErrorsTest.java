package com.aid.billing.error;

import com.aid.common.error.TaskErrorCode;
import com.aid.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillingBalanceErrorsTest
{
    @Test
    void shouldKeepPreholdAndNonPreholdBalanceSemanticsSeparate()
    {
        ServiceException prehold = BillingBalanceErrors.preholdNotEnough();
        ServiceException direct = BillingBalanceErrors.balanceNotEnough();

        assertEquals("预扣余额不足", prehold.getMessage());
        assertEquals(TaskErrorCode.USER_PREHOLD_BALANCE_NOT_ENOUGH.name(), prehold.getDetailMessage());
        assertEquals("余额不足", direct.getMessage());
        assertEquals(TaskErrorCode.USER_BALANCE_NOT_ENOUGH.name(), direct.getDetailMessage());
        assertTrue(BillingBalanceErrors.isPreholdNotEnough(new RuntimeException(prehold)));
        assertFalse(BillingBalanceErrors.isPreholdNotEnough(direct));
        assertFalse(BillingBalanceErrors.isPreholdNotEnough(new ServiceException("预扣余额不足")));
    }
}
