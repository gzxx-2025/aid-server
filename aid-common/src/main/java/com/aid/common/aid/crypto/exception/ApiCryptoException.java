package com.aid.common.aid.crypto.exception;

/**
 * 接口加解密异常。
 *
 * 统一在加解密链路抛出；由全局异常处理器捕获后，对外返回用户友好短文案（不超过 6 字），
 * 内部 cause 仅用于服务端日志排查，绝不透传给前端。
 *
 * @author 视觉AID
 */
public class ApiCryptoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ApiCryptoException(String message) {
        super(message);
    }

    public ApiCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
