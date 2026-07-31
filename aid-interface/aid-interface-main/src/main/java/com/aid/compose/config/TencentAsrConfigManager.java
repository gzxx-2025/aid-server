package com.aid.compose.config;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.aid.common.aid.core.service.ConfigService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** 腾讯云录音文件识别配置管理器。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TencentAsrConfigManager {

    public static final String CATEGORY = "tencent_asr";

    private static final int MIN_SENTENCE_LENGTH = 6;
    private static final int MAX_SENTENCE_LENGTH = 40;
    private static final int MIN_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 600;
    private static final int MIN_ATTEMPTS = 1;
    private static final int MAX_ATTEMPTS = 3;

    private final ConfigService configService;

    @Getter
    private final Map<String, String> configCache = new HashMap<>();

    private volatile TencentAsrProperties currentProperties;
    private volatile boolean initialized;

    /** 首次使用时加载配置。 */
    public void init() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    refresh();
                }
            }
        }
    }

    /** 后台保存后主动刷新内存配置。 */
    public synchronized void refresh() {
        log.info("刷新腾讯云语音识别配置");
        Map<String, String> allConfig = null;
        try {
            allConfig = configService.getConfigValues(CATEGORY);
        } catch (Exception ex) {
            log.warn("腾讯云语音识别配置不存在, category={}", CATEGORY);
        }
        configCache.clear();
        if (CollectionUtil.isNotEmpty(allConfig)) {
            configCache.putAll(allConfig);
        }
        currentProperties = buildProperties();
        initialized = true;
        log.info("腾讯云语音识别配置刷新完成, enabled={}, configured={}",
                currentProperties.getEnabled(), isConfiguredInternal(currentProperties));
    }

    public TencentAsrProperties getProperties() {
        init();
        return currentProperties;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(getProperties().getEnabled());
    }

    public boolean isConfigured() {
        return isConfiguredInternal(getProperties());
    }

    private boolean isConfiguredInternal(TencentAsrProperties properties) {
        return Boolean.TRUE.equals(properties.getEnabled())
                && StrUtil.isNotBlank(properties.getSecretId())
                && StrUtil.isNotBlank(properties.getSecretKey());
    }

    private TencentAsrProperties buildProperties() {
        TencentAsrProperties properties = new TencentAsrProperties();
        properties.setEnabled(getBoolean("enabled", false));
        properties.setSecretId(getString("secretId", ""));
        properties.setSecretKey(getString("secretKey", ""));
        properties.setRegion(getString("region", "ap-guangzhou"));
        properties.setEngineModelType(getString("engineModelType", "16k_zh_en_2.0"));
        properties.setSentenceMaxLength(limit(getInt("sentenceMaxLength", 10),
                MIN_SENTENCE_LENGTH, MAX_SENTENCE_LENGTH));
        properties.setSpeakerDiarization(limit(getInt("speakerDiarization", 0), 0, 1));
        properties.setHotwordId(getString("hotwordId", ""));
        properties.setHotwordList(getString("hotwordList", ""));
        properties.setTimeoutSeconds(limit(getInt("timeoutSeconds", 180),
                MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS));
        properties.setMaxAttempts(limit(getInt("maxAttempts", 2), MIN_ATTEMPTS, MAX_ATTEMPTS));
        return properties;
    }

    private String getString(String key, String defaultValue) {
        String value = configCache.get(key);
        return StrUtil.isBlank(value) ? defaultValue : value.trim();
    }

    private boolean getBoolean(String key, boolean defaultValue) {
        String value = configCache.get(key);
        return StrUtil.isBlank(value) ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    private int getInt(String key, int defaultValue) {
        String value = configCache.get(key);
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            log.warn("腾讯云语音识别配置非法, key={}, value={}", key, value);
            return defaultValue;
        }
    }

    private int limit(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
