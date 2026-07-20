package com.aid.common.aid.real.exception;

/**
 * 实名认证异常
 *
 * @author 视觉AID
 */
public class RealAuthException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RealAuthException(String message) {
        super(message);
    }

    public RealAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
