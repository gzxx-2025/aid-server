package com.aid.common.aid.rocketmq.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * RocketMQ 连接配置属性
 * 从 application.yml 的 rocketmq 前缀绑定，
 * 与 rocketmq-spring-boot-starter 标准配置结构一致。
 * enabled/mqType 开关从数据库读取，由 RocketMqConfigManager 管理。
 *
 * @author 视觉AID
 */
@Data
@ConfigurationProperties(prefix = "rocketmq")
public class RocketMqProperties
{

    /** NameServer地址 */
    private String nameServer;

    /** ACL AccessKey（可选，服务端开启ACL时需要） */
    private String accessKey;

    /** ACL SecretKey（可选，服务端开启ACL时需要） */
    private String secretKey;

    /** 生产者配置 */
    private Producer producer = new Producer();

    /**
     * 快捷获取生产者Group
     */
    public String getProducerGroup()
    {
        return producer != null ? producer.getGroup() : null;
    }

    /**
     * 快捷获取发送超时时间
     */
    public Integer getSendMsgTimeout()
    {
        return producer != null ? producer.getSendMessageTimeout() : null;
    }

    /**
     * 快捷获取消息最大大小
     */
    public Integer getMaxMessageSize()
    {
        return producer != null ? producer.getMaxMessageSize() : null;
    }

    /**
     * 快捷获取同步发送失败重试次数
     */
    public Integer getRetryTimesWhenSendFailed()
    {
        return producer != null ? producer.getRetryTimesWhenSendFailed() : null;
    }

    /**
     * 快捷获取异步发送失败重试次数
     */
    public Integer getRetryTimesWhenSendAsyncFailed()
    {
        return producer != null ? producer.getRetryTimesWhenSendAsyncFailed() : null;
    }

    /**
     * 生产者嵌套配置（对应 yml 中 rocketmq.producer.*）
     */
    @Data
    public static class Producer
    {

        /** 生产者Group（必须指定） */
        private String group;

        /** 发送消息超时时间(毫秒)，默认3000 */
        private Integer sendMessageTimeout = 3000;

        /** 消息最大大小(字节)，默认4MB */
        private Integer maxMessageSize = 4194304;

        /** 同步发送失败重试次数，默认2 */
        private Integer retryTimesWhenSendFailed = 2;

        /** 异步发送失败重试次数，默认2 */
        private Integer retryTimesWhenSendAsyncFailed = 2;

    }

}
