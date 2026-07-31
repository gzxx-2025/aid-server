package com.aid.framework.config;

import cn.hutool.core.util.StrUtil;
import org.redisson.config.BaseConfig;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.cache.annotation.CachingConfigurerSupport;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * redis配置
 * 
 * @author AID
 */
@SuppressWarnings("deprecation")
@Configuration
@EnableCaching
public class RedisConfig extends CachingConfigurerSupport
{
    /**
     * 空白密码归一化为 null：yml 占位符 ${REDIS_PASSWORD:} 会解析成空串，
     * Redisson 对非 null 密码一律发送 AUTH，无密码 Redis 会报
     * "ERR Client sent AUTH, but no password is set"，此处统一兜底
     */
    @Bean
    public RedissonAutoConfigurationCustomizer blankPasswordCustomizer()
    {
        return config -> {
            // 按部署模式取对应的服务端配置（单机 / 集群 / 哨兵），不改变已有模式
            BaseConfig<?> serverConfig;
            if (config.isClusterConfig())
            {
                serverConfig = config.useClusterServers();
            }
            else if (config.isSentinelConfig())
            {
                serverConfig = config.useSentinelServers();
            }
            else
            {
                serverConfig = config.useSingleServer();
            }
            if (StrUtil.isBlank(serverConfig.getPassword()))
            {
                serverConfig.setPassword(null);
            }
        };
    }

    @Bean
    @SuppressWarnings(value = { "unchecked", "rawtypes" })
    public RedisTemplate<Object, Object> redisTemplate(RedisConnectionFactory connectionFactory)
    {
        RedisTemplate<Object, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        FastJson2JsonRedisSerializer serializer = new FastJson2JsonRedisSerializer(Object.class);

        // 使用StringRedisSerializer来序列化和反序列化redis的key值
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);

        // Hash的key也采用StringRedisSerializer的序列化方式
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public DefaultRedisScript<Long> limitScript()
    {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(limitScriptText());
        redisScript.setResultType(Long.class);
        return redisScript;
    }

    /**
     * 限流脚本
     */
    private String limitScriptText()
    {
        return "local key = KEYS[1]\n" +
                "local count = tonumber(ARGV[1])\n" +
                "local time = tonumber(ARGV[2])\n" +
                "local current = redis.call('get', key);\n" +
                "if current and tonumber(current) > count then\n" +
                "    return tonumber(current);\n" +
                "end\n" +
                "current = redis.call('incr', key)\n" +
                "if tonumber(current) == 1 then\n" +
                "    redis.call('expire', key, time)\n" +
                "end\n" +
                "return tonumber(current);";
    }
}
