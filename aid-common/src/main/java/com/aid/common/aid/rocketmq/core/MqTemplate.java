package com.aid.common.aid.rocketmq.core;

import com.aid.common.aid.rocketmq.entity.MqResult;

/**
 * 消息队列模板接口
 *
 * @author 视觉AID
 */
public interface MqTemplate {

    /**
     * 同步发送消息。
     *
     * @param topic 目标Topic
     * @param tag   消息Tag
     * @param key   消息Key
     * @param body  消息体
     * @return 发送结果
     */
    MqResult send(String topic, String tag, String key, String body);

    /**
     * 发送延迟消息。
     *
     * @param topic      目标Topic
     * @param tag        消息Tag
     * @param key        消息Key
     * @param body       消息体
     * @param delayLevel 延迟级别
     * @return 发送结果
     */
    MqResult sendDelay(String topic, String tag, String key, String body, long delayLevel);

}
