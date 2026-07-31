package com.aid.common.aid.rocketmq.config;

import java.util.HashMap;
import java.util.Map;

import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.aid.rocketmq.config.properties.RocketMqProperties;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 消息队列开关管理器
 * 仅从数据库读取 mqType 和 enabled 开关配置。
 * 连接参数（nameServer、accessKey 等）从 application.yml 读取，
 * 由 {@link com.aid.common.aid.rocketmq.config.properties.RocketMqProperties} 管理。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(RocketMqProperties.class)
public class RocketMqConfigManager
{

    private final ConfigService configService;

    /** 配置分类名 */
    private static final String CATEGORY = "mq";

    /** 内存缓存的开关配置 */
    @Getter
    private final Map<String, String> configCache = new HashMap<>();

    /** 消息队列类型: rocketmq / redis */
    @Getter
    private String currentMqType;

    /** 是否启用 */
    private Boolean enabled;

    /** 初始化标识 */
    private volatile boolean initialized = false;

    /** 初始化配置（首次使用时调用） */
    public void init()
    {
        if (!initialized)
        {
            refresh();
        }
    }

    /**
     * 刷新配置（从数据库重新加载 mqType 和 enabled）
     * 在配置页面点击"刷新配置"时调用
     */
    public void refresh()
    {
        log.info("刷新消息队列开关配置...");

        Map<String, String> allConfig = configService.getConfigValues(CATEGORY);
        configCache.clear();
        if (allConfig != null)
        {
            configCache.putAll(allConfig);
        }

        currentMqType = getCacheValue("mqType", "rocketmq");
        enabled = Boolean.parseBoolean(getCacheValue("enabled", "false"));

        initialized = true;
        log.info("消息队列开关配置刷新完成: mqType={}, enabled={}", currentMqType, enabled);
    }

    /** 判断是否启用 */
    public boolean isEnabled()
    {
        init();
        return Boolean.TRUE.equals(enabled);
    }

    /** 获取消息队列类型 */
    public String getMqType()
    {
        init();
        return currentMqType;
    }

    /** 判断当前是否为RocketMQ模式 */
    public boolean isRocketMq()
    {
        init();
        return "rocketmq".equalsIgnoreCase(currentMqType);
    }

    /** 判断当前是否为Redis模式 */
    public boolean isRedis()
    {
        init();
        return "redis".equalsIgnoreCase(currentMqType);
    }

    /**
     * 获取当前生效的开关配置（供前端展示）
     */
    public Map<String, String> getCurrentConfig()
    {
        init();
        return new HashMap<>(configCache);
    }
    private String getCacheValue(String key, String defaultValue)
    {
        String value = configCache.get(key);
        return value != null ? value.trim() : defaultValue;
    }
}
