package com.aid.common.aid.rocketmq.core;

import com.aid.common.aid.rocketmq.entity.MqResult;
import com.aid.common.aid.rocketmq.exception.MqException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 消息队列模板实现，未部署 RocketMQ 的环境通过 rocketmq.enabled=false 不装配。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rocketmq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RocketMqTemplate implements MqTemplate
{

    private final RocketMQTemplate rocketMQTemplate;

    /** 同步发送超时时间（毫秒） */
    private static final long SEND_TIMEOUT_MS = 5000L;

    @Override
    public MqResult send(String topic, String tag, String key, String body)
    {
        String destination = topic + ":" + tag;
        Message<String> message = MessageBuilder.withPayload(body)
                .setHeader("KEYS", key)
                .build();

        // 同步发送，等待 Broker 确认
        SendResult sendResult;
        try
        {
            sendResult = rocketMQTemplate.syncSend(destination, message, SEND_TIMEOUT_MS);
        }
        catch (Exception e)
        {
            log.error("消息发送失败: topic={}, tag={}, key={}, error={}", topic, tag, key, e.getMessage(), e);
            throw new MqException("消息发送失败: " + e.getMessage());
        }

        return evaluateSendResult(sendResult, topic, tag, key, false);
    }

    @Override
    public MqResult sendDelay(String topic, String tag, String key, String body, long delayLevel)
    {
        String destination = topic + ":" + tag;
        Message<String> message = MessageBuilder.withPayload(body)
                .setHeader("KEYS", key)
                .build();

        // 同步发送延迟消息，等待 Broker 确认
        SendResult sendResult;
        try
        {
            sendResult = rocketMQTemplate.syncSend(destination, message, SEND_TIMEOUT_MS, (int) delayLevel);
        }
        catch (Exception e)
        {
            log.error("延迟消息发送失败: topic={}, tag={}, key={}, error={}", topic, tag, key, e.getMessage(), e);
            throw new MqException("延迟消息发送失败: " + e.getMessage());
        }

        return evaluateSendResult(sendResult, topic, tag, key, true);
    }

    /**
     * 扩展的发送结果判断。
     *
     * @param sendResult RocketMQ 发送结果
     * @param topic      topic 名称
     * @param tag        tag
     * @param key        业务 key
     * @param delay      是否为延迟消息
     */
    private MqResult evaluateSendResult(SendResult sendResult, String topic, String tag, String key, boolean delay)
    {
        SendStatus status = sendResult.getSendStatus();
        String prefix = delay ? "延迟消息" : "消息";

        switch (status)
        {
            case SEND_OK:
                log.info("{}发送成功: msgId={}, topic={}, tag={}", prefix, sendResult.getMsgId(), topic, tag);
                return MqResult.builder()
                        .success(true)
                        .messageId(sendResult.getMsgId())
                        .topic(topic)
                        .build();

            case FLUSH_SLAVE_TIMEOUT:
            case SLAVE_NOT_AVAILABLE:
                // Master 已成功保存，Slave 异常不代表消息丢失；C 端链路仍视为成功，避免业务误重试
                log.warn("{}发送状态可接受但需告警: status={}, msgId={}, topic={}, tag={}, key={}",
                        prefix, status, sendResult.getMsgId(), topic, tag, key);
                return MqResult.builder()
                        .success(true)
                        .messageId(sendResult.getMsgId())
                        .topic(topic)
                        .build();

            case FLUSH_DISK_TIMEOUT:
            default:
                // 同步刷盘超时视为潜在丢失，或其它未知状态一律按失败抛异常让业务重试
                log.error("{}发送状态异常，视为失败: status={}, msgId={}, topic={}, tag={}, key={}",
                        prefix, status, sendResult.getMsgId(), topic, tag, key);
                throw new MqException(prefix + "发送状态异常: " + status);
        }
    }

}
