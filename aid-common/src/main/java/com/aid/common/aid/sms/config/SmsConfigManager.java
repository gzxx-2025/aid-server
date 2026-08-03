package com.aid.common.aid.sms.config;

import cn.hutool.core.util.StrUtil;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.aid.sms.config.properties.SmsProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短信配置管理器
 * - 配置从数据库加载到内存
 * - 手动刷新机制，避免频繁查询数据库
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsConfigManager {

    private final ConfigService configService;

    /**
     * 内存缓存的所有配置
     */
    @Getter
    private final Map<String, String> configCache = new ConcurrentHashMap<>();

    /**
     * 当前使用的短信配置
     */
    @Getter
    private volatile SmsProperties currentProperties;

    /**
     * 当前的服务商类型
     */
    @Getter
    private volatile String currentProviderType;

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
    public synchronized void refresh() {
        log.info("刷新短信配置...");

        // 一次性获取sms分类的所有配置
        Map<String, String> allConfig = configService.getConfigValues("sms");
        configCache.clear();
        if (allConfig != null) {
            configCache.putAll(allConfig);
        }

        // 构建Properties对象
        currentProperties = buildProperties();
        currentProviderType = StrUtil.blankToDefault(getCacheValue("providerType", "aliyun"), "aliyun")
                .trim().toLowerCase(Locale.ROOT);

        initialized = true;
        log.info("短信配置刷新完成: providerType={}, enabled={}", currentProviderType, currentProperties.getEnabled());
    }

    /**
     * 获取当前配置的短信属性
     */
    public SmsProperties getSmsProperties() {
        init();
        return currentProperties;
    }

    /**
     * 获取服务商类型
     */
    public String getProviderType() {
        init();
        return currentProviderType;
    }

    /**
     * 判断是否启用
     */
    public boolean isEnabled() {
        init();
        return Boolean.TRUE.equals(currentProperties.getEnabled());
    }

    /**
     * 获取默认模板ID
     */
    public String getDefaultTemplateId() {
        init();
        return getCacheValue("defaultTemplateId", "");
    }

    /**
     * 获取验证码参数名
     */
    public String getCodeParamName() {
        init();
        return StrUtil.blankToDefault(getCacheValue("codeParamName", "code"), "code").trim();
    }

    /**
     * 获取当前生效的配置（供前端展示）
     */
    public Map<String, String> getCurrentConfig() {
        init();
        Map<String, String> result = new HashMap<>(configCache);
        // 脱敏敏感信息
        if (result.containsKey("accessKeySecret")) {
            result.put("accessKeySecret", maskSecret(result.get("accessKeySecret")));
        }
        if (result.containsKey("smsBaoApiKey")) {
            result.put("smsBaoApiKey", maskSecret(result.get("smsBaoApiKey")));
        }
        return result;
    }

    /** 密钥展示脱敏，配置真实值只保留在内存配置对象中。 */
    private String maskSecret(String secret) {
        if (StrUtil.isBlank(secret)) {
            return secret;
        }
        if (secret.length() <= 8) {
            return "****";
        }
        return secret.substring(0, 4) + "****" + secret.substring(secret.length() - 4);
    }

    private String getCacheValue(String key, String defaultValue) {
        String value = configCache.get(key);
        return value != null ? value : defaultValue;
    }

    private SmsProperties buildProperties() {
        SmsProperties properties = new SmsProperties();
        properties.setEnabled(Boolean.parseBoolean(getCacheValue("enabled", "false")));
        properties.setEndpoint(getCacheValue("endpoint", ""));
        properties.setAccessKeyId(getCacheValue("accessKeyId", ""));
        properties.setAccessKeySecret(getCacheValue("accessKeySecret", ""));
        properties.setSignName(getCacheValue("signName", ""));
        properties.setSdkAppId(getCacheValue("sdkAppId", ""));
        properties.setSmsBaoUsername(getCacheValue("smsBaoUsername", ""));
        properties.setSmsBaoApiKey(getCacheValue("smsBaoApiKey", ""));
        properties.setSmsBaoProductId(getCacheValue("smsBaoProductId", ""));
        properties.setSmsBaoContentTemplate(getCacheValue(
                "smsBaoContentTemplate", "【视觉AID】您的验证码是{code}"));
        return properties;
    }
}
