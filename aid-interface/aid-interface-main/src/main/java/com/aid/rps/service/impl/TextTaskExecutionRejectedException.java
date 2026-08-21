package com.aid.rps.service.impl;

/**
 * 表示文本任务执行周期已失去业务提交权。
 *
 * @author 视觉AID
 */
public final class TextTaskExecutionRejectedException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public TextTaskExecutionRejectedException()
    {
        super("任务已失效");
    }

    static TextTaskExecutionRejectedException find(Throwable error)
    {
        Throwable current = error;
        while (current != null)
        {
            if (current instanceof TextTaskExecutionRejectedException rejected)
            {
                return rejected;
            }
            current = current.getCause();
        }
        return null;
    }
}
