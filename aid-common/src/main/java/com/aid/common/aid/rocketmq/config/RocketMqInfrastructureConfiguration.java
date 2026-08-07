package com.aid.common.aid.rocketmq.config;

import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * RocketMQ 基础设施装配入口。
 * 只有部署配置显式设置 {@code rocketmq.enabled=true} 时，才导入官方自动配置并初始化生产者、消费者。
 * 数据库中的业务开关继续由 {@link RocketMqConfigManager} 管理，两层开关必须同时开启才会派发消息。
 *
 * @author AID
 */
@Configuration
@ConditionalOnProperty(prefix = "rocketmq", name = "enabled", havingValue = "true", matchIfMissing = false)
@Import(RocketMQAutoConfiguration.class)
public class RocketMqInfrastructureConfiguration
{
}
