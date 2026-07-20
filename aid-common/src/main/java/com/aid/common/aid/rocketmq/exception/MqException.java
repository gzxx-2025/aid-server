package com.aid.common.aid.rocketmq.exception;

import java.io.Serial;

/**
 * 消息队列异常类
 *
 * @author 视觉AID
 */
public class MqException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public MqException(String msg) {
        super(msg);
    }

}
