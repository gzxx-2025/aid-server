package com.aid.common.aid.rocketmq.entity;

import lombok.Builder;
import lombok.Data;

/**
 * 消息队列发送结果
 *
 * @author 视觉AID
 */
@Data
@Builder
public class MqResult {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 消息ID
     */
    private String messageId;

    /**
     * 目标Topic
     */
    private String topic;

    /**
     * 错误信息（失败时有值）
     */
    private String errorMessage;

}
