package com.aid.rps.queue;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidConfig;
import com.aid.aid.service.IAidConfigService;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 任务排队并发上限解析器（全局 / 用户两维，均带 5 秒本地缓存）。
 *
 * 只解析业务批次任务的并发上限；供应商 / 模型维度的上游请求并发由
 * {@link com.aid.media.service.MediaConcurrencyLimiter} 唯一持有，本类不再涉及。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskConcurrencyConfig
{
    private static final String CONFIG_CATEGORY = "taskq";
    private static final String CFG_GLOBAL_LIMIT = "taskq_concurrent_limit_global";
    private static final String CFG_USER_LIMIT = "taskq_concurrent_limit_user";
    private static final String CFG_USER_LIMIT_PREFIX = "taskq_concurrent_limit_user_";

    /** 默认全局并发上限 */
    private static final int DEFAULT_GLOBAL_LIMIT = 10;
    /** 默认单用户并发上限 */
    private static final int DEFAULT_USER_LIMIT = 2;

    /** 本地缓存刷新间隔（毫秒） */
    private static final long CACHE_TTL_MS = 5000L;

    private final IAidConfigService aidConfigService;

    // 全局/用户配置缓存
    private volatile long cfgCacheTime = 0L;
    private volatile int cachedGlobalLimit = DEFAULT_GLOBAL_LIMIT;
    private volatile int cachedUserLimit = DEFAULT_USER_LIMIT;

    /** 用户专属上限缓存（userId→[limit, expireMillis]，用 long 防溢出） */
    private final Map<Long, long[]> userLimitOverrideCache = new ConcurrentHashMap<>();

    /** 全局并发上限 */
    public int getGlobalLimit()
    {
        refreshBaseConfig();
        return cachedGlobalLimit;
    }

    /** 单用户并发上限（支持按 userId 覆盖：taskq_concurrent_limit_user_{userId}） */
    public int getUserLimit(Long userId)
    {
        refreshBaseConfig();
        if (Objects.isNull(userId))
        {
            return cachedUserLimit;
        }
        long[] cached = userLimitOverrideCache.get(userId);
        long now = System.currentTimeMillis();
        if (cached != null && cached[1] > now)
        {
            // -1 表示该用户无专属覆盖，回退默认
            return cached[0] > 0 ? (int) cached[0] : cachedUserLimit;
        }
        int override = readConfigInt(CFG_USER_LIMIT_PREFIX + userId, -1);
        userLimitOverrideCache.put(userId, new long[]{override, now + CACHE_TTL_MS});
        return override > 0 ? override : cachedUserLimit;
    }

    private void refreshBaseConfig()
    {
        long now = System.currentTimeMillis();
        if (now - cfgCacheTime < CACHE_TTL_MS)
        {
            return;
        }
        cachedGlobalLimit = readConfigInt(CFG_GLOBAL_LIMIT, DEFAULT_GLOBAL_LIMIT);
        cachedUserLimit = readConfigInt(CFG_USER_LIMIT, DEFAULT_USER_LIMIT);
        cfgCacheTime = now;
    }

    /**
     * 读取 aid_config(category=taskq) 下指定 config_name 的整数值。
     */
    private int readConfigInt(String configName, int defaultValue)
    {
        try
        {
            LambdaQueryWrapper<AidConfig> wrapper = Wrappers.lambdaQuery();
            wrapper.eq(AidConfig::getCategory, CONFIG_CATEGORY);
            wrapper.eq(AidConfig::getConfigName, configName);
            wrapper.select(AidConfig::getConfigValue);
            wrapper.last("LIMIT 1");
            AidConfig cfg = aidConfigService.getOne(wrapper, false);
            if (cfg != null && StrUtil.isNotBlank(cfg.getConfigValue()))
            {
                int v = Integer.parseInt(cfg.getConfigValue().trim());
                return v > 0 ? v : defaultValue;
            }
        }
        catch (Exception e)
        {
            log.warn("读取并发配置失败, configName={}, 用默认值 {}: {}", configName, defaultValue, e.getMessage());
        }
        return defaultValue;
    }
}
