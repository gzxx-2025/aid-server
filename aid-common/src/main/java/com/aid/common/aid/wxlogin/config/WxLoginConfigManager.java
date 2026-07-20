package com.aid.common.aid.wxlogin.config;

import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.aid.wxlogin.properties.WxLoginProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 微信公众号登录配置管理器
 * - 配置从数据库加载到内存
 * - 手动刷新机制，避免频繁查询数据库
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxLoginConfigManager {

    private final ConfigService configService;

    /**
     * 配置分类名称（对应 aid_config 表的 category 字段）
     */
    private static final String CONFIG_CATEGORY = "wxLogin";

    /**
     * 内存缓存的所有配置
     */
    @Getter
    private final Map<String, String> configCache = new HashMap<>();

    /**
     * 当前使用的微信登录配置
     */
    @Getter
    private WxLoginProperties currentProperties;

    /**
     * 初始化标识
     */
    private volatile boolean initialized = false;

    /**
     * 初始化配置（首次使用时调用）
     */
    public void init() {
        if (!initialized) {
            refresh();
        }
    }

    /**
     * 刷新配置（从数据库重新加载）
     * 在配置页面点击"刷新配置"时调用
     */
    public void refresh() {
        log.info("刷新微信公众号登录配置...");

        // 一次性获取 wxLogin 分类的所有配置
        Map<String, String> allConfig = configService.getConfigValues(CONFIG_CATEGORY);
        configCache.clear();
        if (allConfig != null) {
            configCache.putAll(allConfig);
        }

        // 构建 WxLoginProperties 对象
        currentProperties = buildWxLoginProperties();

        initialized = true;
        log.info("微信公众号登录配置刷新完成: enabled={}, appId={}",
                currentProperties.getEnabled(),
                maskAppId(currentProperties.getAppId()));
    }

    /**
     * 获取微信登录配置
     */
    public WxLoginProperties getWxLoginProperties() {
        init();
        return currentProperties;
    }

    /**
     * 判断是否启用
     */
    public boolean isEnabled() {
        init();
        return Boolean.TRUE.equals(currentProperties.getEnabled());
    }

    /**
     * 获取 AppId
     */
    public String getAppId() {
        init();
        return currentProperties.getAppId();
    }

    /**
     * 获取 Secret
     */
    public String getSecret() {
        init();
        return currentProperties.getSecret();
    }

    /**
     * 获取 Token
     */
    public String getToken() {
        init();
        return currentProperties.getToken();
    }

    /**
     * Get EncodingAESKey.
     */
    public String getEncodingAesKey() {
        init();
        return currentProperties.getEncodingAesKey();
    }

    /**
     * 获取当前生效的配置（供前端展示）
     */
    public Map<String, String> getCurrentConfig() {
        init();
        Map<String, String> result = new HashMap<>(configCache);
        // 脱敏敏感信息
        maskConfigValue(result, "wxLoginSecret");
        maskConfigValue(result, "wxLoginEncodingAESKey");
        maskConfigValue(result, "encodingAESKey");
        return result;
    }
    private String getCacheValue(String key, String defaultValue) {
        String value = configCache.get(key);
        return value != null ? value : defaultValue;
    }

    private int getCacheInt(String key, int defaultValue) {
        String value = configCache.get(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getCacheBoolean(String key, boolean defaultValue) {
        String value = configCache.get(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    private WxLoginProperties buildWxLoginProperties() {
        WxLoginProperties properties = new WxLoginProperties();
        properties.setEnabled(getCacheBoolean("enabled", false));
        properties.setAppId(getCacheValue("wxLoginAppId", ""));
        properties.setSecret(getCacheValue("wxLoginSecret", ""));
        properties.setToken(getCacheValue("wxLoginToken", "aid"));
        properties.setEncodingAesKey(getCacheValue("wxLoginEncodingAESKey", getCacheValue("encodingAESKey", "")));
        properties.setQrcodeExpireSeconds(getCacheInt("qrcodeExpireSeconds", 300));
        return properties;
    }

    private void maskConfigValue(Map<String, String> config, String key) {
        String value = config.get(key);
        if (value == null || value.isEmpty()) {
            return;
        }
        if (value.length() > 8) {
            config.put(key, value.substring(0, 4) + "****" + value.substring(value.length() - 4));
            return;
        }
        config.put(key, "****");
    }

    /**
     * 脱敏 AppId（显示前4位和后4位）
     */
    private String maskAppId(String appId) {
        if (appId == null || appId.length() <= 8) {
            return appId;
        }
        return appId.substring(0, 4) + "****" + appId.substring(appId.length() - 4);
    }
}
