package com.aid.common.aid.rocketmq.utils;

import com.aid.common.aid.rocketmq.core.MqTemplateFactory;
import com.aid.common.aid.rocketmq.entity.MqResult;
import com.aid.common.aid.rocketmq.exception.MqException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 消息队列工具类
 *
 * @author 视觉AID
 */
@Component
public class MqUtils {

    private static MqTemplateFactory mqTemplateFactory;

    public MqUtils(MqTemplateFactory factory) {
        MqUtils.mqTemplateFactory = factory;
    }

    /**
     * 同步发送消息。
     *
     * @param topic 目标Topic
     * @param tag   消息Tag
     * @param key   消息Key
     * @param body  消息体
     * @return 发送结果
     */
    public static MqResult send(String topic, String tag, String key, String body) {
        checkInit();
        return mqTemplateFactory.send(topic, tag, key, body);
    }

    /**
     * 发送延迟消息。
     *
     * @param topic      目标Topic
     * @param tag        消息Tag
     * @param key        消息Key
     * @param body       消息体
     * @param delayLevel 延迟级别（秒）
     * @return 发送结果
     */
    public static MqResult sendDelay(String topic, String tag, String key, String body, long delayLevel) {
        checkInit();
        return mqTemplateFactory.sendDelay(topic, tag, key, body, delayLevel);
    }

    /**
     * 刷新配置（配置页面点击"刷新配置"时调用）
     */
    public static void refresh() {
        if (mqTemplateFactory != null) {
            mqTemplateFactory.refresh();
        }
    }

    /**
     * 获取当前配置信息（供前端展示）
     */
    public static Map<String, String> getCurrentConfig() {
        checkInit();
        return mqTemplateFactory.getCurrentConfig();
    }

    private static void checkInit() {
        if (mqTemplateFactory == null) {
            throw new MqException("消息队列服务未初始化");
        }
    }
}
