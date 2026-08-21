package com.aid.storyboard.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.aid.common.error.TaskErrorCode;
import com.aid.common.exception.ServiceException;

class StoryboardStepChainServiceTest
{
    private final StoryboardStepChainService service = new StoryboardStepChainService();

    @Test
    void shouldReturnNestedBusinessErrorForVideoChain()
    {
        RuntimeException wrapped = new RuntimeException(new ServiceException("请选择参考图"));

        String message = service.resolveChainFailureMessage("video", wrapped);

        assertEquals("请选择参考图", message);
    }

    @Test
    void shouldUseSafeFallbackWhenFailureCauseMissing()
    {
        String message = service.resolveChainFailureMessage("video", null);

        assertEquals("视频提交失败", message);
    }

    @Test
    void shouldExposeExactPreholdBalanceMessageForChainFailure()
    {
        ServiceException prehold = new ServiceException("预扣余额不足")
                .setDetailMessage(TaskErrorCode.USER_PREHOLD_BALANCE_NOT_ENOUGH.name());

        String message = service.resolveChainFailureMessage("video", new RuntimeException(prehold));

        assertEquals("预扣余额不足", message);
    }
}
