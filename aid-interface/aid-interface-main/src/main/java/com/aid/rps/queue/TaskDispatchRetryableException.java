package com.aid.rps.queue;

/**
 * 派发「可重试」信号：遇临时性、可恢复的资源不足（如本地线程池打满）时抛出，与真正派发失败区分。
 *
 * @author 视觉AID
 */
public class TaskDispatchRetryableException extends RuntimeException
{
    public TaskDispatchRetryableException(String message)
    {
        super(message);
    }
}
