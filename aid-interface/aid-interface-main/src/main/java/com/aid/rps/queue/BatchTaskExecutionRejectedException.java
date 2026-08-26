package com.aid.rps.queue;

/**
 * 批量任务在受控提交周期内失去业务提交权。
 *
 * @author 视觉AID
 */
public class BatchTaskExecutionRejectedException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public BatchTaskExecutionRejectedException()
    {
        super("任务已停止");
    }
}
