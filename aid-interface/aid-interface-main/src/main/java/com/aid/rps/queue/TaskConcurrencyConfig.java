package com.aid.rps.queue;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.AidConfig;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.aid.service.IAidConfigService;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 任务排队 / 多维并发上限解析器（全局/用户/模型/服务商四维，均带 5 秒本地缓存）。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskConcurrencyConfig
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String CONFIG_CATEGORY = "taskq";
    private static final String CFG_GLOBAL_LIMIT = "taskq_concurrent_limit_global";
    private static final String CFG_USER_LIMIT = "taskq_concurrent_limit_user";
    private static final String CFG_USER_LIMIT_PREFIX = "taskq_concurrent_limit_user_";

    /** 默认全局并发上限 */
    private static final int DEFAULT_GLOBAL_LIMIT = 10;
    /** 默认单用户并发上限 */
    private static final int DEFAULT_USER_LIMIT = 2;
    /** 表示"不限制"的并发值 */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    /** 本地缓存刷新间隔（毫秒） */
    private static final long CACHE_TTL_MS = 5000L;

    private final IAidConfigService aidConfigService;
    private final IAidAiModelService aidAiModelService;
    private final IAidAiProviderService aidAiProviderService;

    // 全局/用户配置缓存
    private volatile long cfgCacheTime = 0L;
    private volatile int cachedGlobalLimit = DEFAULT_GLOBAL_LIMIT;
    private volatile int cachedUserLimit = DEFAULT_USER_LIMIT;

    // 模型/服务商上限缓存（key→[limit, expireMillis]，用 long 防溢出）
    private final Map<String, long[]> modelLimitCache = new ConcurrentHashMap<>();
    private final Map<Long, long[]> providerLimitCache = new ConcurrentHashMap<>();
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

    /**
     * 模型并发上限。
     *
     * @param modelCode 模型编码
     * @return 上限；缺省或 <=0 返回 {@link #UNLIMITED}
     */
    public int getModelLimit(String modelCode)
    {
        if (StrUtil.isBlank(modelCode))
        {
            return UNLIMITED;
        }
        long now = System.currentTimeMillis();
        long[] cached = modelLimitCache.get(modelCode);
        if (cached != null && cached[1] > now)
        {
            return (int) cached[0];
        }
        int limit = UNLIMITED;
        try
        {
            // 仅查必要字段：schedule_strategy_json
            AidAiModel model = aidAiModelService.getOne(
                    Wrappers.<AidAiModel>lambdaQuery()
                            .select(AidAiModel::getModelCode, AidAiModel::getScheduleStrategyJson)
                            .eq(AidAiModel::getModelCode, modelCode)
                            .last("LIMIT 1"));
            if (model != null)
            {
                limit = parseMaxConcurrency(model.getScheduleStrategyJson());
            }
        }
        catch (Exception e)
        {
            log.warn("读取模型并发上限失败, modelCode={}, 默认不限: {}", modelCode, e.getMessage());
        }
        modelLimitCache.put(modelCode, new long[]{limit, now + CACHE_TTL_MS});
        return limit;
    }

    /**
     * 服务商并发上限。
     *
     * @param providerId 服务商ID
     * @return 上限；缺省或 <=0 返回 {@link #UNLIMITED}
     */
    public int getProviderLimit(Long providerId)
    {
        if (Objects.isNull(providerId))
        {
            return UNLIMITED;
        }
        long now = System.currentTimeMillis();
        long[] cached = providerLimitCache.get(providerId);
        if (cached != null && cached[1] > now)
        {
            return (int) cached[0];
        }
        int limit = UNLIMITED;
        try
        {
            AidAiProvider provider = aidAiProviderService.getOne(
                    Wrappers.<AidAiProvider>lambdaQuery()
                            .select(AidAiProvider::getId, AidAiProvider::getScheduleStrategyJson)
                            .eq(AidAiProvider::getId, providerId)
                            .last("LIMIT 1"));
            if (provider != null)
            {
                limit = parseMaxConcurrency(provider.getScheduleStrategyJson());
            }
        }
        catch (Exception e)
        {
            log.warn("读取服务商并发上限失败, providerId={}, 默认不限: {}", providerId, e.getMessage());
        }
        providerLimitCache.put(providerId, new long[]{limit, now + CACHE_TTL_MS});
        return limit;
    }

    /**
     * 解析模型 / 服务商所属 providerId（用于服务商维度名额）。
     */
    public Long resolveProviderId(String modelCode)
    {
        if (StrUtil.isBlank(modelCode))
        {
            return null;
        }
        try
        {
            AidAiModel model = aidAiModelService.getOne(
                    Wrappers.<AidAiModel>lambdaQuery()
                            .select(AidAiModel::getModelCode, AidAiModel::getProviderId)
                            .eq(AidAiModel::getModelCode, modelCode)
                            .last("LIMIT 1"));
            return model != null ? model.getProviderId() : null;
        }
        catch (Exception e)
        {
            log.warn("解析模型 providerId 失败, modelCode={}: {}", modelCode, e.getMessage());
            return null;
        }
    }

    /**
     * 从 schedule_strategy_json 解析 maxConcurrency（静态工具，供调度与监控共用）。
     *
     * @param scheduleStrategyJson 模型 / 服务商的调度策略 JSON
     * @return 并发上限；缺省/非法/<=0 返回 {@link #UNLIMITED}
     */
    public static int parseMaxConcurrencyFromJson(String scheduleStrategyJson)
    {
        if (StrUtil.isBlank(scheduleStrategyJson))
        {
            return UNLIMITED;
        }
        try
        {
            JsonNode node = OBJECT_MAPPER.readTree(scheduleStrategyJson);
            JsonNode mc = node.get("maxConcurrency");
            if (mc == null || !mc.isNumber())
            {
                return UNLIMITED;
            }
            int v = mc.asInt();
            return v > 0 ? v : UNLIMITED;
        }
        catch (Exception e)
        {
            log.warn("解析 schedule_strategy_json 失败, 默认不限: {}", e.getMessage());
            return UNLIMITED;
        }
    }

    /** 从 schedule_strategy_json 解析 maxConcurrency（缺省/非法/<=0 视为不限制） */
    private int parseMaxConcurrency(String scheduleStrategyJson)
    {
        return parseMaxConcurrencyFromJson(scheduleStrategyJson);
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
