package com.aid.aid.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aid.aid.service.IAidConfigService;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.utils.ip.IpUtils;

/**
 * 基于 Redis 的简单 IP 限流（固定窗口）。
 *
 * @author 视觉AID
 */
@Component
public class IpRateLimitGuard {

    private static final String KEY_PREFIX = "rate_limit:";

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private IAidConfigService aidConfigService;

    /**
     * 按 aid_config 动态阈值做 IP 限流（窗口固定 60s）。
     *
     * @param bucket     业务桶名（如 login / admin_entry_verify）
     * @param category   配置分类（如 admin_entry）
     * @param configKey  配置名（每分钟最大次数）
     * @param defaultMax 配置缺失/非法时的默认每分钟次数
     * @return true=放行；false=已超限。配置值 &lt;=0 视为不限流（放行）。
     */
    public boolean allowByConfig(String bucket, String category, String configKey, int defaultMax) {
        int max = readMax(category, configKey, defaultMax);
        if (max <= 0) {
            // 配置为 0 或负数：视为关闭限流
            return true;
        }
        return allow(bucket, max, 60);
    }

    /** 读取每分钟最大次数配置，缺失/非法返回默认值 */
    private int readMax(String category, String configKey, int defaultMax) {
        try {
            String v = aidConfigService.getConfigValue(category, configKey);
            if (v == null || v.trim().isEmpty()) {
                return defaultMax;
            }
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return defaultMax;
        }
    }

    /**
     * 判断当前请求 IP 在指定桶内是否允许通过。
     *
     * @param bucket        业务桶名（如 login / admin_entry_verify）
     * @param maxCount      窗口内最大次数
     * @param windowSeconds 窗口秒数
     * @return true=放行；false=已超限
     */
    public boolean allow(String bucket, int maxCount, int windowSeconds) {
        String ip = safeIp();
        String key = KEY_PREFIX + bucket + ":" + ip;
        try {
            Long count = redisCache.redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return true;
            }
            // 首次计数时设置窗口过期时间
            if (count == 1L) {
                redisCache.expire(key, windowSeconds);
            }
            return count <= maxCount;
        } catch (Exception e) {
            // 缓存异常时放行，避免误伤正常用户
            return true;
        }
    }

    private String safeIp() {
        try {
            String ip = IpUtils.getIpAddr();
            return ip == null || ip.isEmpty() ? "unknown" : ip;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
