package com.aid.compose.exception;

/**
 * 合成提交结果暂时无法确认（网络超时、上游 5xx 等）。
 * 此类异常不能直接判任务失败；云端提交使用幂等键，任务应退回队列后重试确认。
 */
public class ComposeUpstreamUnavailableException extends RuntimeException
{
    public ComposeUpstreamUnavailableException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public ComposeUpstreamUnavailableException(String message)
    {
        super(message);
    }
}
