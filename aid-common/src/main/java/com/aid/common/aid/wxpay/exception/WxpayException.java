package com.aid.common.aid.wxpay.exception;

import java.io.Serial;

/**
 * 微信支付异常类
 *
 * @author 视觉AID
 */
public class WxpayException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 错误码
     */
    private String code;

    /**
     * 错误描述
     */
    private String message;

    public WxpayException(String message) {
        super(message);
        this.message = message;
    }

    public WxpayException(String message, Throwable cause) {
        super(message, cause);
        this.message = message;
    }

    public WxpayException(String code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
