package com.aid.common.moderation.exception;

/**
 * 图片内容安全审查异常
 *
 * @author 视觉AID
 */
public class ImageModerationException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    /**
     * 构造异常
     *
     * @param message 异常信息
     */
    public ImageModerationException(String message)
    {
        super(message);
    }

    /**
     * 构造异常
     *
     * @param message 异常信息
     * @param cause   原始异常
     */
    public ImageModerationException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
