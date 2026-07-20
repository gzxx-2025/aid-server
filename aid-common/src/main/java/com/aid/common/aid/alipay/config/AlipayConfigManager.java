package com.aid.common.aid.alipay.config;

import com.aid.common.aid.alipay.properties.AlipayProperties;
import com.aid.common.aid.core.service.ConfigService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 支付宝配置管理器
 * - 配置从数据库加载到内存
 * - 手动刷新机制，避免频繁查询数据库
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlipayConfigManager {

    private final ConfigService configService;

    /**
     * 内存缓存的所有配置
     */
    @Getter
    private final Map<String, String> configCache = new HashMap<>();

    /**
     * 当前使用的支付宝配置
     */
    @Getter
    private AlipayProperties currentProperties;

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
        log.info("刷新支付宝配置...");

        // 一次性获取alipay分类的所有配置
        Map<String, String> allConfig = configService.getConfigValues("alipay");
        configCache.clear();
        if (allConfig != null) {
            configCache.putAll(allConfig);
        }

        // 构建AlipayProperties对象
        currentProperties = buildAlipayProperties();

        initialized = true;
        log.info("支付宝配置刷新完成: appId={}, enabled={}", currentProperties.getAppId(), currentProperties.getEnabled());
    }

    /**
     * 获取支付宝配置
     */
    public AlipayProperties getAlipayProperties() {
        init();
        return currentProperties;
    }

    /**
     * 判断是否启用
     */
    public boolean isEnabled() {
        init();
        return Boolean.parseBoolean(getCacheValue("enabled", "false"));
    }

    /**
     * 获取当前生效的配置（供前端展示）
     */
    public Map<String, String> getCurrentConfig() {
        init();
        Map<String, String> result = new HashMap<>(configCache);
        // 脱敏敏感信息
        if (result.containsKey("privateKey")) {
            String privateKey = result.get("privateKey");
            if (privateKey != null && privateKey.length() > 20) {
                result.put("privateKey", privateKey.substring(0, 20) + "****");
            }
        }
        if (result.containsKey("alipayPublicKey")) {
            String publicKey = result.get("alipayPublicKey");
            if (publicKey != null && publicKey.length() > 20) {
                result.put("alipayPublicKey", publicKey.substring(0, 20) + "****");
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

    private AlipayProperties buildAlipayProperties() {
        AlipayProperties properties = new AlipayProperties();
        properties.setEnabled(getCacheBoolean("enabled", false));
        properties.setAppId(getCacheValue("appId", ""));
        properties.setPid(getCacheValue("pid", ""));
        properties.setPrivateKey(getCacheValue("privateKey", ""));
        properties.setAlipayPublicKey(getCacheValue("alipayPublicKey", ""));
        properties.setNotifyUrl(getCacheValue("notifyUrl", ""));
        properties.setReturnUrl(getCacheValue("returnUrl", ""));
        properties.setSandbox(getCacheBoolean("sandbox", false));
        properties.setSignType(getCacheValue("signType", "RSA2"));
        properties.setCharset(getCacheValue("charset", "UTF-8"));
        properties.setFormat(getCacheValue("format", "json"));
        return properties;
    }
}
