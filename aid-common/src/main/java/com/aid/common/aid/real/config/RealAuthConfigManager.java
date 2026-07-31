package com.aid.common.aid.real.config;

import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.aid.real.properties.RealAuthProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 实名认证配置管理器
 * - 配置从数据库加载到内存
 * - 手动刷新机制，避免频繁查询数据库
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealAuthConfigManager {

    private final ConfigService configService;

    /**
     * 内存缓存的所有配置
     */
    @Getter
    private final Map<String, String> configCache = new HashMap<>();

    /**
     * 当前使用的实名认证配置
     */
    @Getter
    private RealAuthProperties currentProperties;

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
     */
    public void refresh() {
        log.info("刷新实名认证配置...");

        // 获取 realAuth 分类的所有配置
        Map<String, String> allConfig = configService.getConfigValues("realAuth");
        configCache.clear();
        if (allConfig != null) {
            configCache.putAll(allConfig);
        }

        // 构建 RealAuthProperties 对象
        currentProperties = buildRealAuthProperties();

        initialized = true;
        log.info("实名认证配置刷新完成: enabled={}, authType={}",
                currentProperties.getEnabled(), currentProperties.getAuthType());
    }

    /**
     * 获取实名认证配置
     */
    public RealAuthProperties getRealAuthProperties() {
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
     * 获取当前生效的配置（供前端展示）
     */
    public Map<String, String> getCurrentConfig() {
        init();
        Map<String, String> result = new HashMap<>(configCache);
        // 脱敏敏感信息
        if (result.containsKey("appCode")) {
            String appCode = result.get("appCode");
            if (appCode != null && appCode.length() > 4) {
                result.put("appCode", appCode.substring(0, 4) + "****");
            }
        }
        return result;
    }
    private String getCacheValue(String key, String defaultValue) {
        String value = configCache.get(key);
        return value != null ? value : defaultValue;
    }

    private boolean getCacheBoolean(String key, boolean defaultValue) {
        String value = configCache.get(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    private RealAuthProperties buildRealAuthProperties() {
        RealAuthProperties properties = new RealAuthProperties();
        properties.setEnabled(getCacheBoolean("enabled", false));
        properties.setAuthType(getCacheValue("authType", "twoFactor"));
        properties.setAppCode(getCacheValue("appCode", ""));
        return properties;
    }
}
