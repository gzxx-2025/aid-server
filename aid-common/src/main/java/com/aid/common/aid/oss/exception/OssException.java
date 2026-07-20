package com.aid.common.aid.oss.exception;

import java.io.Serial;

/**
 * OSS自定义异常
 *
 * @author 视觉AID
 */
public class OssException extends RuntimeException
{
    @Serial
    private static final long serialVersionUID = 1L;

    public OssException(String message)
    {
        super(message);
    }

    public OssException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
