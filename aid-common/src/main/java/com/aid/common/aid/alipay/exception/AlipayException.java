package com.aid.common.aid.alipay.exception;

import java.io.Serial;

/**
 * 支付宝异常类
 *
 * @author 视觉AID
 */
public class AlipayException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 错误码
     */
    private String code;

    /**
     * 错误子码
     */
    private String subCode;

    /**
     * 错误描述
     */
    private String subMsg;

    public AlipayException(String message) {
        super(message);
    }

    public AlipayException(String message, Throwable cause) {
        super(message, cause);
    }

    public AlipayException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AlipayException(String code, String subCode, String subMsg) {
        super(subMsg);
        this.code = code;
        this.subCode = subCode;
        this.subMsg = subMsg;
    }

    public String getCode() {
        return code;
    }

    public String getSubCode() {
        return subCode;
    }

    public String getSubMsg() {
        return subMsg;
    }
}
