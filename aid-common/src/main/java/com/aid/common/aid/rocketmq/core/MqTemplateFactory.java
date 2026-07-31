package com.aid.common.aid.rocketmq.core;

import java.util.Map;
import java.util.Objects;

import com.aid.common.aid.rocketmq.config.RocketMqConfigManager;
import com.aid.common.aid.rocketmq.config.properties.RocketMqProperties;
import com.aid.common.aid.rocketmq.entity.MqResult;
import com.aid.common.aid.rocketmq.exception.MqException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 消息队列模板工厂
 * 连接参数从 application.yml 读取（{@link RocketMqProperties}），
 * 开关（enabled/mqType）从数据库读取（{@link RocketMqConfigManager}）。
 * 当前支持: rocketmq（redis暂未实现）
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqTemplateFactory
{

    private final RocketMqConfigManager mqConfigManager;

    private final RocketMqProperties mqProperties;

    /** RocketMQ模板实例；rocketmq.enabled=false 时不装配，故用 ObjectProvider 可选获取 */
    private final ObjectProvider<MqTemplate> mqTemplateProvider;

    /**
     * 获取消息队列模板实例
     */
    public MqTemplate getTemplate()
    {
        if (!mqConfigManager.isEnabled())
        {
            throw new MqException("消息队列服务未启用");
        }

        String mqType = mqConfigManager.getMqType();

        switch (mqType.toLowerCase())
        {
            case "rocketmq":
                MqTemplate template = mqTemplateProvider.getIfAvailable();
                if (Objects.isNull(template))
                {
                    // rocketmq.enabled=false（未部署 MQ 基础设施）但数据库开关开了 MQ 模式
                    log.error("RocketMQ 基础设施未装配（rocketmq.enabled=false），请改回本地模式或部署 RocketMQ");
                    throw new MqException("消息队列未部署");
                }
                return template;
            case "redis":
                throw new MqException("Redis消息队列暂未实现，请选择 RocketMQ");
            default:
                throw new MqException("不支持的消息队列类型: " + mqType);
        }
    }

    /** 同步发送消息 */
    public MqResult send(String topic, String tag, String key, String body)
    {
        return getTemplate().send(topic, tag, key, body);
    }

    /** 发送延迟消息 */
    public MqResult sendDelay(String topic, String tag, String key, String body, long delayLevel)
    {
        return getTemplate().sendDelay(topic, tag, key, body, delayLevel);
    }

    /**
     * 刷新开关配置（配置更新后调用，仅刷新数据库开关）
     */
    public void refresh()
    {
        mqConfigManager.refresh();
        log.info("消息队列开关配置已刷新");
    }

    /** 获取当前开关配置信息（供前端展示） */
    public Map<String, String> getCurrentConfig()
    {
        return mqConfigManager.getCurrentConfig();
    }

}
