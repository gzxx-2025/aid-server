package com.aid.common.moderation.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.moderation.properties.ImageModerationProperties;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 图片内容安全审查配置管理器
 * - 从 aid_config(category=image_moderation) 读取配置
 * - 内存缓存 5 秒，避免频繁查询数据库
 * - 读取失败时容错返回禁用态，不抛异常
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageModerationConfigManager
{
    /**
     * 配置分类
     */
    private static final String CATEGORY = "image_moderation";

    /**
     * 缓存有效期（毫秒）
     */
    private static final long CACHE_TTL_MS = 5000L;

    /**
     * 通用配置服务
     */
    private final ConfigService configService;

    /**
     * 缓存的配置属性
     */
    private volatile ImageModerationProperties cachedProperties;

    /**
     * 缓存的原始配置（用于脱敏展示）
     */
    private volatile Map<String, String> cachedRaw = new HashMap<>();

    /**
     * 上次刷新时间戳（毫秒）
     */
    private volatile long lastLoadTime = 0L;

    /**
     * 获取图片审查配置（带 5 秒缓存）
     *
     * @return 配置属性
     */
    public ImageModerationProperties getProperties()
    {
        long now = System.currentTimeMillis();
        // 缓存未过期且已加载，直接返回
        if (Objects.nonNull(cachedProperties) && (now - lastLoadTime) < CACHE_TTL_MS)
        {
            return cachedProperties;
        }
        synchronized (this)
        {
            // 双重检查，避免并发重复加载
            if (Objects.nonNull(cachedProperties) && (System.currentTimeMillis() - lastLoadTime) < CACHE_TTL_MS)
            {
                return cachedProperties;
            }
            load();
            return cachedProperties;
        }
    }

    /**
     * 获取当前生效的配置（脱敏后供前端展示）
     *
     * @return 脱敏后的配置 Map
     */
    public Map<String, String> getCurrentConfig()
    {
        // 触发一次加载以保证缓存最新
        getProperties();
        Map<String, String> result = new HashMap<>(cachedRaw);
        // 脱敏：腾讯云 SecretKey
        desensitize(result, "tencentSecretKey");
        return result;
    }

    /**
     * 从数据库加载配置，失败时回退到禁用态
     */
    private void load()
    {
        Map<String, String> raw = new HashMap<>();
        try
        {
            Map<String, String> allConfig = configService.getConfigValues(CATEGORY);
            if (!CollectionUtil.isEmpty(allConfig))
            {
                raw.putAll(allConfig);
            }
        }
        catch (Exception e)
        {
            // 读取失败容错：记录日志并返回禁用态，避免影响主站
            log.error("加载图片审查配置失败，按禁用态处理：{}", e.getMessage(), e);
        }

        ImageModerationProperties properties = buildProperties(raw);
        this.cachedRaw = raw;
        this.cachedProperties = properties;
        this.lastLoadTime = System.currentTimeMillis();
    }

    /**
     * 将原始配置 Map 构建为属性对象
     *
     * @param raw 原始配置
     * @return 属性对象
     */
    private ImageModerationProperties buildProperties(Map<String, String> raw)
    {
        ImageModerationProperties properties = new ImageModerationProperties();
        properties.setEnabled(getBoolean(raw, "enabled", false));
        properties.setProvider(getString(raw, "provider", "tencent"));
        properties.setTencentRegion(getString(raw, "tencentRegion", "ap-shanghai"));
        properties.setTencentSecretId(getString(raw, "tencentSecretId", ""));
        properties.setTencentSecretKey(getString(raw, "tencentSecretKey", ""));
        properties.setPrioritizeFileUrl(getBoolean(raw, "prioritizeFileUrl", true));
        properties.setModerationStage(getString(raw, "moderationStage", "AFTER_UPLOAD"));
        properties.setBlockOnSuggestionReview(getBoolean(raw, "blockOnSuggestionReview", true));
        properties.setFailOpenOnError(getBoolean(raw, "failOpenOnError", true));
        properties.setLogPassed(getBoolean(raw, "logPassed", false));
        properties.setLogRetentionDays(getInt(raw, "logRetentionDays", 90));
        return properties;
    }

    /**
     * 取字符串配置
     *
     * @param raw          原始配置
     * @param key          键
     * @param defaultValue 默认值
     * @return 配置值
     */
    private String getString(Map<String, String> raw, String key, String defaultValue)
    {
        String value = raw.get(key);
        return StrUtil.isBlank(value) ? defaultValue : value.trim();
    }

    /**
     * 取布尔配置
     *
     * @param raw          原始配置
     * @param key          键
     * @param defaultValue 默认值
     * @return 配置值
     */
    private boolean getBoolean(Map<String, String> raw, String key, boolean defaultValue)
    {
        String value = raw.get(key);
        if (StrUtil.isBlank(value))
        {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * 取整型配置（容错）
     *
     * @param raw          原始配置
     * @param key          键
     * @param defaultValue 默认值
     * @return 配置值
     */
    private int getInt(Map<String, String> raw, String key, int defaultValue)
    {
        String value = raw.get(key);
        if (StrUtil.isBlank(value))
        {
            return defaultValue;
        }
        try
        {
            return Integer.parseInt(value.trim());
        }
        catch (NumberFormatException e)
        {
            // 非法数值回退默认值
            log.warn("图片审查配置项{}的值{}无法转换为数字，使用默认值{}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * 脱敏处理：保留前后各 2 位，中间打码
     *
     * @param map 配置 Map
     * @param key 需要脱敏的键
     */
    private void desensitize(Map<String, String> map, String key)
    {
        String value = map.get(key);
        if (StrUtil.isNotBlank(value) && value.length() > 4)
        {
            map.put(key, value.substring(0, 2) + "****" + value.substring(value.length() - 2));
        }
        else if (StrUtil.isNotBlank(value))
        {
            // 过短的密钥整体打码
            map.put(key, "****");
        }
    }
}
