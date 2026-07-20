package com.aid.compose.config;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.common.aid.core.service.ConfigService;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 腾讯云 MPS 配置管理器。
 * 复用 {@link ConfigService#getConfigValues(String)} 一次性读取 category=mps 的全部配置，
 * 封装为强类型 {@link MpsProperties}；并提供「是否已配置」判定与分辨率档单价解析能力。
 * 采用与 OssConfigManager / WxpayConfigManager 一致的内存缓存 + 手动刷新机制，避免频繁查库。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MpsConfigManager {

    /** 配置分类 */
    private static final String CATEGORY = "mps";

    /** 通用配置服务 */
    private final ConfigService configService;

    /** 内存缓存的所有配置 */
    @Getter
    private final Map<String, String> configCache = new HashMap<>();

    /** 当前生效的 MPS 配置 */
    @Getter
    private MpsProperties currentProperties;

    /** 初始化标识 */
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
        log.info("刷新MPS配置...");
        Map<String, String> allConfig = null;
        try {
            // getConfigValues 在 category 不存在时可能抛异常，此处兜底为「未配置」
            allConfig = configService.getConfigValues(CATEGORY);
        } catch (Exception e) {
            log.warn("MPS配置分类不存在或为空(category={}), 默认未启用", CATEGORY);
        }
        configCache.clear();
        if (!CollectionUtil.isEmpty(allConfig)) {
            configCache.putAll(allConfig);
        }
        currentProperties = buildProperties();
        initialized = true;
        log.info("MPS配置刷新完成: enabled={}, configured={}", currentProperties.getEnabled(), isConfigured());
    }

    /**
     * 获取 MPS 配置
     *
     * @return MPS 配置属性
     */
    public MpsProperties getMpsProperties() {
        init();
        return currentProperties;
    }

    /**
     * 是否已正确配置：开关开启且 SecretId/SecretKey 均非空。
     *
     * @return true=已配置可用
     */
    public boolean isConfigured() {
        init();
        return Boolean.TRUE.equals(currentProperties.getEnabled())
                && StrUtil.isNotBlank(currentProperties.getSecretId())
                && StrUtil.isNotBlank(currentProperties.getSecretKey());
    }

    /**
     * 解析分辨率档单价表（元/分钟）：key=分辨率档(大写)，value=原价。
     * pricingTiers 为空或解析失败时返回空表，由调用方决定兜底逻辑。
     *
     * @return 分辨率档 → 原价（元/分钟）
     */
    public Map<String, BigDecimal> getPricingTiers() {
        init();
        Map<String, BigDecimal> tiers = new LinkedHashMap<>();
        String raw = currentProperties.getPricingTiers();
        if (StrUtil.isBlank(raw)) {
            return tiers;
        }
        try {
            JSONObject json = JSON.parseObject(raw);
            for (String key : json.keySet()) {
                BigDecimal price = json.getBigDecimal(key);
                if (Objects.nonNull(price)) {
                    tiers.put(key.toUpperCase(), price);
                }
            }
        } catch (Exception e) {
            // 解析失败仅告警，不抛出，避免污染调用方主流程
            log.error("MPS分辨率档单价解析失败, pricingTiers={}", raw, e);
        }
        return tiers;
    }
    private String getCacheValue(String key, String defaultValue) {
        String value = configCache.get(key);
        return StrUtil.isBlank(value) ? defaultValue : value;
    }

    private boolean getCacheBoolean(String key, boolean defaultValue) {
        String value = configCache.get(key);
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    private int getCacheInt(String key, int defaultValue) {
        String value = configCache.get(key);
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("MPS配置项{}的值{}无法转为整数，使用默认值{}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private BigDecimal getCacheDecimal(String key, BigDecimal defaultValue) {
        String value = configCache.get(key);
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            log.warn("MPS配置项{}的值{}无法转为数字，使用默认值{}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private MpsProperties buildProperties() {
        MpsProperties properties = new MpsProperties();
        properties.setEnabled(getCacheBoolean("enabled", false));
        properties.setSecretId(getCacheValue("secretId", ""));
        properties.setSecretKey(getCacheValue("secretKey", ""));
        properties.setRegion(getCacheValue("region", "ap-guangzhou"));
        properties.setOutputBucket(getCacheValue("outputBucket", ""));
        properties.setOutputRegion(getCacheValue("outputRegion", ""));
        properties.setOutputDir(getCacheValue("outputDir", "/compose_result/"));
        properties.setCallbackUrl(getCacheValue("callbackUrl", ""));
        properties.setOutputResolution(getCacheValue("outputResolution", "FHD"));
        properties.setCodec(getCacheValue("codec", "H.264"));
        properties.setPricingTiers(getCacheValue("pricingTiers", ""));
        properties.setCreditRate(getCacheInt("creditRate", 100));
        properties.setProfitMultiplier(getCacheDecimal("profitMultiplier", new BigDecimal("1.1")));
        // 字幕渲染配置：字号 + 每行最大字数（防长台词超出画面，按画幅可调）
        properties.setSubtitleFontSize(getCacheValue("subtitleFontSize", "5%"));
        properties.setSubtitleWrapChars(getCacheInt("subtitleWrapChars", 18));
        return properties;
    }
}
