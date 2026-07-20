package com.aid.common.exception.email;

import com.aid.common.exception.base.BaseException;

/**
 * 邮件发送限制异常类
 *
 * @author 视觉AID
 */
public class EmailSendLimitException extends BaseException
{
    private static final long serialVersionUID = 1L;

    /**
     * 剩余等待时间（秒）
     */
    private Long remainingSeconds;

    /**
     * 构造方法
     *
     * @param remainingSeconds 剩余等待时间（秒）
     */
    public EmailSendLimitException(Long remainingSeconds)
    {
        // 调用父类构造，异常消息不超过6个字
        super("发送频繁");
        this.remainingSeconds = remainingSeconds;
    }

    /**
     * 获取剩余等待时间
     *
     * @return 剩余秒数
     */
    public Long getRemainingSeconds()
    {
        return remainingSeconds;
    }
}
