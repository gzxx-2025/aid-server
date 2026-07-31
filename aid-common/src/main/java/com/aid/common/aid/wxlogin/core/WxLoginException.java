package com.aid.common.aid.wxlogin.core;

/**
 * 微信登录异常
 *
 * @author 视觉AID
 */
public class WxLoginException extends RuntimeException {

    public WxLoginException(String message) {
        super(message);
    }

    public WxLoginException(String message, Throwable cause) {
        super(message, cause);
    }
}
