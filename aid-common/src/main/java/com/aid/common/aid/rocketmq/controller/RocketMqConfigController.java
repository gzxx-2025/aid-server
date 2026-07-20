package com.aid.common.aid.rocketmq.controller;

import java.util.Map;
import java.util.UUID;

import com.aid.common.aid.rocketmq.config.RocketMqConfigManager;
import com.aid.common.aid.rocketmq.config.properties.RocketMqProperties;
import com.aid.common.aid.rocketmq.consumer.RocketMqTestConsumer;
import com.aid.common.aid.rocketmq.core.MqTemplateFactory;
import com.aid.common.aid.rocketmq.entity.MqResult;
import com.aid.common.core.domain.AjaxResult;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息队列配置Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/mq/config")
public class RocketMqConfigController
{

    @Autowired
    private MqTemplateFactory mqTemplateFactory;

    @Autowired
    private RocketMqConfigManager mqConfigManager;

    @Autowired
    private RocketMqProperties mqProperties;

    /** 刷新消息队列开关配置 */
    @PreAuthorize("@ss.hasPermi('mq:config:refresh')")
    @PostMapping("/refresh")
    public AjaxResult refresh()
    {
        mqTemplateFactory.refresh();
        return AjaxResult.success();
    }

    /** 获取当前生效的消息队列开关配置 */
    @PreAuthorize("@ss.hasPermi('mq:config:query')")
    @GetMapping("/current")
    public AjaxResult getCurrentConfig()
    {
        Map<String, String> config = mqTemplateFactory.getCurrentConfig();
        return AjaxResult.success(config);
    }

    /** 测试Topic */
    private static final String TEST_TOPIC = "AID_TEST_TOPIC";
    /** 测试消息内容 */
    private static final String TEST_BODY = "AID RocketMQ连接测试消息";

    /**
     * 测试连接：发送一条消息，然后尝试消费验证
     */
    @PreAuthorize("@ss.hasPermi('mq:config:test')")
    @PostMapping("/testSend")
    public AjaxResult testSend()
    {
        // 生成唯一标识，用于验证消费到的是刚发的消息
        String testKey = "test_" + UUID.randomUUID().toString().substring(0, 8);
        String testBody = TEST_BODY + " [testKey=" + testKey + "]";

        // 第一步：同步发送消息，失败直接抛异常
        MqResult sendResult;
        try
        {
            sendResult = mqTemplateFactory.send(TEST_TOPIC, "test", testKey, testBody);
        }
        catch (Exception e)
        {
            return AjaxResult.error("发送失败: " + e.getMessage());
        }

        // 第二步：消费验证
        RocketMqTestConsumer testConsumer = new RocketMqTestConsumer(mqProperties);
        try
        {
            String consumedBody = testConsumer.consumeOne(TEST_TOPIC);

            if (consumedBody != null && consumedBody.contains(testKey))
            {
                return AjaxResult.success("连接正常 - 发送并消费成功，messageId: " + sendResult.getMessageId());
            }
            if (consumedBody != null)
            {
                return AjaxResult.success("发送成功(messageId: " + sendResult.getMessageId() + ")，消费到其他消息: " + consumedBody);
            }
            return AjaxResult.success("发送成功(messageId: " + sendResult.getMessageId() + ")，但消费超时未收到消息（可能Topic不存在或消费者Group尚未初始化）");
        }
        finally
        {
            testConsumer.close();
        }
    }
}
