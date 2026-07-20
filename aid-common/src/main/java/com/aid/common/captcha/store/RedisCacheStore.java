package com.aid.common.captcha.store;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.aid.common.captcha.config.CaptchaProperties;
import com.aid.common.core.redis.RedisCache;

import cloud.tianai.captcha.cache.CacheStore;
import cloud.tianai.captcha.common.AnyMap;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于 Redis 的 tianai-captcha 验证数据缓存实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class RedisCacheStore implements CacheStore {

    @Resource
    private RedisCache redisCache;

    /**
     * 拼接验证数据缓存 key。
     */
    private String dataKey(String key) {
        // 统一前缀，便于运维排查
        return CaptchaProperties.REDIS_CAPTCHA_PREFIX + ":data:" + key;
    }

    /**
     * 拼接计数 key。
     */
    private String incrKey(String key) {
        return CaptchaProperties.REDIS_CAPTCHA_PREFIX + ":incr:" + key;
    }

    @Override
    public AnyMap getCache(String key) {
        // 读取但不删除
        String json = redisCache.getCacheObject(dataKey(key));
        return toAnyMap(json);
    }

    @Override
    public AnyMap getAndRemoveCache(String key) {
        // 验证码一次性：读取后立即删除
        String redisKey = dataKey(key);
        String json = redisCache.getCacheObject(redisKey);
        if (Objects.nonNull(json)) {
            redisCache.deleteObject(redisKey);
        }
        return toAnyMap(json);
    }

    @Override
    public boolean setCache(String key, AnyMap data, Long expire, TimeUnit timeUnit) {
        try {
            // AnyMap 实现了 Map，直接序列化为普通 JSON 对象字符串
            String json = JSON.toJSONString(data);
            redisCache.setCacheObject(dataKey(key), json, (int) timeUnit.toSeconds(expire), TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            // 缓存失败先 log，再返回 false 由上层 tianai 抛出业务异常
            log.error("验证码数据缓存失败: key={}", key, e);
            return false;
        }
    }

    @Override
    public Long incr(String key, long delta, Long expire, TimeUnit timeUnit) {
        try {
            String redisKey = incrKey(key);
            // 原子自增
            Long val = redisCache.redisTemplate.opsForValue().increment(redisKey, delta);
            // 首次创建时设置过期时间
            if (Objects.nonNull(val) && val == delta) {
                redisCache.expire(redisKey, timeUnit.toSeconds(expire), TimeUnit.SECONDS);
            }
            return val;
        } catch (Exception e) {
            log.error("验证码计数自增失败: key={}", key, e);
            return delta;
        }
    }

    @Override
    public Long getLong(String key) {
        try {
            Object val = redisCache.getCacheObject(incrKey(key));
            if (Objects.isNull(val)) {
                return null;
            }
            if (val instanceof Number) {
                return ((Number) val).longValue();
            }
            // 兜底：字符串数字解析
            return Long.parseLong(String.valueOf(val));
        } catch (Exception e) {
            log.error("验证码计数读取失败: key={}", key, e);
            return null;
        }
    }

    /**
     * JSON 字符串还原为 AnyMap。
     */
    @SuppressWarnings("unchecked")
    private AnyMap toAnyMap(String json) {
        if (Objects.isNull(json) || json.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> map = JSON.parseObject(json, Map.class);
            return AnyMap.of(map);
        } catch (Exception e) {
            // 解析失败视为无缓存，先 log 留痕
            log.error("验证码数据解析失败: json={}", json, e);
            return null;
        }
    }

    @Override
    public void close() {
        // 基于共享 RedisCache，无需释放本地资源
    }
}
