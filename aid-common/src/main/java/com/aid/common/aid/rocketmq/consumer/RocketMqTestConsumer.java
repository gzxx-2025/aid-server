package com.aid.common.aid.rocketmq.consumer;

import com.aid.common.aid.rocketmq.config.properties.RocketMqProperties;
import com.aid.common.aid.rocketmq.exception.MqException;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RocketMQ 测试消费者
 * - 用于「测试连接」功能：发送消息后立即消费验证
 * - 每次测试创建新实例，测试完毕调用close关闭
 *
 * @author 视觉AID
 */
@Slf4j
public class RocketMqTestConsumer {

    private static final String TEST_CONSUMER_GROUP = "test_consumer_group";

    private final RocketMqProperties properties;
    private DefaultMQPushConsumer consumer;

    public RocketMqTestConsumer(RocketMqProperties mqProperties) {
        this.properties = mqProperties;
    }

    /**
     * 订阅指定Topic并尝试消费一条消息
     *
     * @param topic 目标Topic
     * @return 消费到的消息内容，null表示超时未收到
     */
    public String consumeOne(String topic) {
        try {
            consumer = new DefaultMQPushConsumer(TEST_CONSUMER_GROUP);
            consumer.setNamesrvAddr(properties.getNameServer());
            consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            consumer.subscribe(topic, "*");

            AtomicReference<String> result = new AtomicReference<>(null);
            CountDownLatch latch = new CountDownLatch(1);

            consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
                if (!msgs.isEmpty()) {
                    MessageExt msg = msgs.get(0);
                    String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                    log.info("测试消费成功: msgId={}", msg.getMsgId());
                    result.set(body);
                    latch.countDown();
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });

            consumer.start();
            log.info("测试消费者已启动: topic={}", topic);

            // 等待消费到一条消息，最多10秒
            latch.await(10, TimeUnit.SECONDS);

            if (result.get() == null) {
                log.warn("测试消费超时: topic={}", topic);
            }
            return result.get();
        } catch (Exception e) {
            log.error("测试消费失败: {}", e.getMessage(), e);
            throw new MqException("消费测试失败: " + e.getMessage());
        }
    }

    /**
     * 关闭消费者
     */
    public void close() {
        if (consumer != null) {
            consumer.shutdown();
            log.info("测试消费者已关闭");
        }
    }
}
