package com.aid.common.aid.wxpay.config;

import cn.hutool.core.util.StrUtil;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.aid.wxpay.properties.WxpayProperties;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages WeChat Pay configuration loaded from aid_config.
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxpayConfigManager {

    private final ConfigService configService;

    @Getter
    private final Map<String, String> configCache = new HashMap<>();

    @Getter
    private WxpayProperties currentProperties;

    private volatile Config wxpayConfig;

    private volatile boolean initialized = false;

    public void init() {
        if (!initialized) {
            refresh();
        }
    }

    public void refresh() {
        log.info("刷新微信支付配置...");

        Map<String, String> allConfig = configService.getConfigValues("wxpay");
        configCache.clear();
        if (allConfig != null) {
            configCache.putAll(allConfig);
        }

        currentProperties = buildWxpayProperties();
        this.wxpayConfig = null;

        initialized = true;
        log.info("微信支付配置刷新完成: appId={}, mchId={}, enabled={}, publicKeyMode={}",
                currentProperties.getAppId(), currentProperties.getMchId(), currentProperties.getEnabled(),
                isPublicKeyMode(currentProperties));
    }

    public WxpayProperties getWxpayProperties() {
        init();
        return currentProperties;
    }

    public Config getConfig() {
        init();
        Config config = this.wxpayConfig;
        if (config == null) {
            synchronized (this) {
                config = this.wxpayConfig;
                if (config == null) {
                    config = buildConfig(currentProperties);
                    this.wxpayConfig = config;
                    log.info("微信支付 SDK Config 已构建并缓存: mchId={}, publicKeyMode={}",
                            currentProperties.getMchId(), isPublicKeyMode(currentProperties));
                }
            }
        }
        return config;
    }

    public boolean isEnabled() {
        init();
        return Boolean.parseBoolean(getCacheValue("enabled", "false"));
    }

    public Map<String, String> getCurrentConfig() {
        init();
        Map<String, String> result = new HashMap<>(configCache);
        maskLongValue(result, "privateKey", 20);
        maskLongValue(result, "publicKey", 20);
        maskApiV3Key(result);
        return result;
    }

    private Config buildConfig(WxpayProperties properties) {
        if (isPublicKeyModePartiallyConfigured(properties)) {
            log.error("微信支付公钥模式配置不完整: mchId={}, hasPublicKeyId={}, hasPublicKey={}",
                    properties.getMchId(), StrUtil.isNotBlank(properties.getPublicKeyId()),
                    StrUtil.isNotBlank(properties.getPublicKey()));
            throw new IllegalStateException("公钥未配全");
        }

        if (isPublicKeyMode(properties)) {
            return new RSAPublicKeyConfig.Builder()
                    .merchantId(properties.getMchId())
                    .privateKey(properties.getPrivateKey())
                    .merchantSerialNumber(properties.getSerialNo())
                    .publicKeyId(properties.getPublicKeyId())
                    .publicKey(properties.getPublicKey())
                    .apiV3Key(properties.getApiV3Key())
                    .build();
        }

        return new RSAAutoCertificateConfig.Builder()
                .merchantId(properties.getMchId())
                .privateKey(properties.getPrivateKey())
                .merchantSerialNumber(properties.getSerialNo())
                .apiV3Key(properties.getApiV3Key())
                .build();
    }

    private boolean isPublicKeyMode(WxpayProperties properties) {
        return StrUtil.isNotBlank(properties.getPublicKeyId()) && StrUtil.isNotBlank(properties.getPublicKey());
    }

    private boolean isPublicKeyModePartiallyConfigured(WxpayProperties properties) {
        boolean hasPublicKeyId = StrUtil.isNotBlank(properties.getPublicKeyId());
        boolean hasPublicKey = StrUtil.isNotBlank(properties.getPublicKey());
        return hasPublicKeyId ^ hasPublicKey;
    }

    private void maskLongValue(Map<String, String> result, String key, int visiblePrefix) {
        if (!result.containsKey(key)) {
            return;
        }
        String value = result.get(key);
        if (value != null && value.length() > visiblePrefix) {
            result.put(key, value.substring(0, visiblePrefix) + "****");
        }
    }

    private void maskApiV3Key(Map<String, String> result) {
        if (!result.containsKey("apiV3Key")) {
            return;
        }
        String apiV3Key = result.get("apiV3Key");
        if (apiV3Key != null && apiV3Key.length() > 8) {
            result.put("apiV3Key", apiV3Key.substring(0, 4) + "****" + apiV3Key.substring(apiV3Key.length() - 4));
        }
    }

    private String getCacheValue(String key, String defaultValue) {
        String value = configCache.get(key);
        return value != null ? value : defaultValue;
    }

    private boolean getCacheBoolean(String key, boolean defaultValue) {
        String value = configCache.get(key);
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    private WxpayProperties buildWxpayProperties() {
        WxpayProperties properties = new WxpayProperties();
        properties.setEnabled(getCacheBoolean("enabled", false));
        properties.setAppId(getCacheValue("appId", ""));
        properties.setMchId(getCacheValue("mchId", ""));
        properties.setApiV3Key(getCacheValue("apiV3Key", ""));
        properties.setPrivateKey(getCacheValue("privateKey", ""));
        properties.setSerialNo(getCacheValue("serialNo", ""));
        properties.setPublicKeyId(getCacheValue("publicKeyId", ""));
        properties.setPublicKey(getCacheValue("publicKey", ""));
        properties.setNotifyUrl(getCacheValue("notifyUrl", ""));
        return properties;
    }
}
