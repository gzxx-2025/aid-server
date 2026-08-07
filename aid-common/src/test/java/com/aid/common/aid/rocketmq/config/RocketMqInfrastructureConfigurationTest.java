package com.aid.common.aid.rocketmq.config;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RocketMqInfrastructureConfigurationTest
{
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RocketMqInfrastructureConfiguration.class);

    @Test
    void shouldNotInitializeRocketMqWhenSwitchIsMissing()
    {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(RocketMQAutoConfiguration.class)
                .doesNotHaveBean(DefaultMQProducer.class)
                .doesNotHaveBean(RocketMQTemplate.class));
    }

    @Test
    void shouldNotInitializeRocketMqWhenSwitchIsFalse()
    {
        contextRunner
                .withPropertyValues("rocketmq.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(RocketMQAutoConfiguration.class)
                        .doesNotHaveBean(DefaultMQProducer.class)
                        .doesNotHaveBean(RocketMQTemplate.class));
    }
}
