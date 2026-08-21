package com.aid.billing.error;

import java.util.Objects;

import com.aid.common.error.TaskErrorCode;
import com.aid.common.exception.ServiceException;

/** 统一构造带机器码的账户余额业务异常。 */
public final class BillingBalanceErrors
{
    private BillingBalanceErrors()
    {
    }

    public static ServiceException preholdNotEnough()
    {
        return new ServiceException("预扣余额不足")
                .setDetailMessage(TaskErrorCode.USER_PREHOLD_BALANCE_NOT_ENOUGH.name());
    }

    public static ServiceException balanceNotEnough()
    {
        return new ServiceException("余额不足")
                .setDetailMessage(TaskErrorCode.USER_BALANCE_NOT_ENOUGH.name());
    }

    /** 通过机器码识别异常链中的预扣余额不足，禁止业务逻辑依赖展示文案。 */
    public static boolean isPreholdNotEnough(Throwable throwable)
    {
        Throwable current = throwable;
        int depth = 0;
        while (Objects.nonNull(current) && depth < 10)
        {
            if (current instanceof ServiceException serviceException
                    && TaskErrorCode.USER_PREHOLD_BALANCE_NOT_ENOUGH.name()
                    .equals(serviceException.getDetailMessage()))
            {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }
}
