package com.aid.compose.config;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
 * 媒体处理配置管理器，统一承载腾讯云 MPS、阿里云 IMS 与本地 FFmpeg。
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

    public static final String MODE_TENCENT_MPS = "tencent-mps";
    public static final String MODE_ALIYUN_IMS = "aliyun-ims";
    public static final String MODE_LOCAL_FFMPEG = "local-ffmpeg";
    private static final long CACHE_TTL_MILLIS = 5_000L;

    /** 配置分类 */
    private static final String CATEGORY = "mps";

    /** 通用配置服务 */
    private final ConfigService configService;

    /** 内存缓存的所有配置 */
    @Getter
    private volatile Map<String, String> configCache = Map.of();

    /** 当前生效的 MPS 配置 */
    @Getter
    private volatile MpsProperties currentProperties;

    /** 初始化标识 */
    private volatile boolean initialized = false;
    private volatile long lastRefreshAt = 0L;

    /**
     * 初始化配置（首次使用时调用）
     */
    public void init() {
        if (!initialized || System.currentTimeMillis() - lastRefreshAt >= CACHE_TTL_MILLIS) {
            refresh();
        }
    }

    /**
     * 刷新配置（从数据库重新加载）
     */
    public synchronized void refresh() {
        log.info("刷新MPS配置...");
        Map<String, String> allConfig = null;
        try {
            // getConfigValues 在 category 不存在时可能抛异常，此处兜底为「未配置」
            allConfig = configService.getConfigValues(CATEGORY);
        } catch (Exception e) {
            log.warn("MPS配置分类不存在或为空(category={}), 默认未启用", CATEGORY);
        }
        Map<String, String> nextConfig = new HashMap<>();
        if (!CollectionUtil.isEmpty(allConfig)) {
            nextConfig.putAll(allConfig);
        }
        Map<String, String> immutableConfig = Collections.unmodifiableMap(nextConfig);
        MpsProperties nextProperties = buildProperties(immutableConfig);
        configCache = immutableConfig;
        currentProperties = nextProperties;
        initialized = true;
        lastRefreshAt = System.currentTimeMillis();
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
     * 是否已正确配置：按当前处理引擎校验必要字段。
     *
     * @return true=已配置可用
     */
    public boolean isConfigured() {
        init();
        MpsProperties properties = currentProperties;
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            return false;
        }
        return switch (properties.getProcessMode()) {
            case MODE_TENCENT_MPS -> StrUtil.isAllNotBlank(
                    properties.getSecretId(), properties.getSecretKey(), properties.getRegion());
            case MODE_ALIYUN_IMS -> StrUtil.isAllNotBlank(properties.getAliyunAccessKeyId(),
                    properties.getAliyunAccessKeySecret(), properties.getAliyunRegion());
            case MODE_LOCAL_FFMPEG -> StrUtil.isAllNotBlank(
                    properties.getFfmpegPath(), properties.getFfprobePath());
            default -> false;
        };
    }

    /**
     * 解析分辨率档单价表（元/分钟）：key=分辨率档(大写)，value=原价。
     * pricingTiers 为空或解析失败时返回空表，由调用方决定兜底逻辑。
     *
     * @return 分辨率档 → 原价（元/分钟）
     */
    public synchronized Map<String, BigDecimal> getPricingTiers() {
        init();
        MpsProperties properties = currentProperties;
        Map<String, String> cache = configCache;
        Map<String, BigDecimal> tiers = new LinkedHashMap<>();
        if (MODE_LOCAL_FFMPEG.equals(properties.getProcessMode())) {
            for (String tier : new String[]{"SD", "HD", "FHD", "2K", "4K"}) {
                tiers.put(tier, properties.getLocalUnitPrice());
            }
            return tiers;
        }
        String prefix = MODE_ALIYUN_IMS.equals(properties.getProcessMode())
                ? "aliyunPrice" : "tencentPrice";
        putConfiguredTier(tiers, cache, "SD", prefix + "Sd");
        putConfiguredTier(tiers, cache, "HD", prefix + "Hd");
        putConfiguredTier(tiers, cache, "FHD", prefix + "Fhd");
        putConfiguredTier(tiers, cache, "2K", prefix + "2k");
        putConfiguredTier(tiers, cache, "4K", prefix + "4k");
        if (!tiers.isEmpty()) {
            return tiers;
        }
        String raw = properties.getPricingTiers();
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

    /** 当前处理引擎的最大并发数；最小为 1，超过上限的任务保持 QUEUED。 */
    public int getActiveMaxConcurrency() {
        return getMaxConcurrency(getMpsProperties().getProcessMode());
    }

    /** 按任务已冻结的处理协议读取并发上限。 */
    public int getMaxConcurrency(String processMode) {
        MpsProperties properties = getMpsProperties();
        int value = switch (processMode) {
            case MODE_ALIYUN_IMS -> properties.getAliyunMaxConcurrency();
            case MODE_LOCAL_FFMPEG -> properties.getFfmpegMaxConcurrency();
            default -> properties.getTencentMaxConcurrency();
        };
        return Math.max(1, value);
    }

    private void putConfiguredTier(Map<String, BigDecimal> tiers, Map<String, String> cache,
                                   String tier, String configName) {
        String raw = cache.get(configName);
        if (StrUtil.isBlank(raw)) {
            return;
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            if (value.signum() >= 0) {
                tiers.put(tier, value);
            }
        } catch (NumberFormatException e) {
            log.warn("媒体处理单价配置非法, configName={}", configName);
        }
    }
    private String getCacheValue(Map<String, String> cache, String key, String defaultValue) {
        String value = cache.get(key);
        return StrUtil.isBlank(value) ? defaultValue : value;
    }

    private boolean getCacheBoolean(Map<String, String> cache, String key, boolean defaultValue) {
        String value = cache.get(key);
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    private int getCacheInt(Map<String, String> cache, String key, int defaultValue) {
        String value = cache.get(key);
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

    private BigDecimal getCacheDecimal(Map<String, String> cache, String key, BigDecimal defaultValue) {
        String value = cache.get(key);
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

    private MpsProperties buildProperties(Map<String, String> cache) {
        MpsProperties properties = new MpsProperties();
        String processMode = getCacheValue(cache, "processMode", MODE_TENCENT_MPS).toLowerCase();
        if (!Set.of(MODE_TENCENT_MPS, MODE_ALIYUN_IMS, MODE_LOCAL_FFMPEG).contains(processMode)) {
            processMode = MODE_TENCENT_MPS;
        }
        properties.setProcessMode(processMode);
        properties.setEnabled(getCacheBoolean(cache, "enabled", false));
        properties.setSecretId(getCacheValue(cache, "tencentSecretId", getCacheValue(cache, "secretId", "")));
        properties.setSecretKey(getCacheValue(cache, "tencentSecretKey", getCacheValue(cache, "secretKey", "")));
        properties.setRegion(getCacheValue(cache, "tencentRegion", getCacheValue(cache, "region", "ap-guangzhou")));
        properties.setTencentCallbackUrl(getCacheValue(cache, "tencentCallbackUrl", getCacheValue(cache, "callbackUrl", "")));
        properties.setTencentMaxConcurrency(getCacheInt(cache, "tencentMaxConcurrency", 5));
        properties.setAliyunAccessKeyId(getCacheValue(cache, "aliyunAccessKeyId", ""));
        properties.setAliyunAccessKeySecret(getCacheValue(cache, "aliyunAccessKeySecret", ""));
        properties.setAliyunRegion(getCacheValue(cache, "aliyunRegion", "cn-shanghai"));
        properties.setAliyunCallbackUrl(getCacheValue(cache, "aliyunCallbackUrl", ""));
        properties.setAliyunMaxConcurrency(getCacheInt(cache, "aliyunMaxConcurrency", 5));
        properties.setFfmpegPath(resolveRuntimePath(cache, "ffmpegPath", "AID_FFMPEG_PATH",
                "ffmpeg", MpsProperties.DEFAULT_FFMPEG_PATH));
        properties.setFfprobePath(resolveRuntimePath(cache, "ffprobePath", "AID_FFPROBE_PATH",
                "ffprobe", MpsProperties.DEFAULT_FFPROBE_PATH));
        properties.setFfmpegTempDir(getCacheValue(cache, "ffmpegTempDir", ""));
        properties.setFfmpegTimeoutSeconds(getCacheInt(cache, "ffmpegTimeoutSeconds", 3600));
        properties.setFfmpegMaxConcurrency(getCacheInt(cache, "ffmpegMaxConcurrency", 2));
        properties.setFfmpegThreads(getCacheInt(cache, "ffmpegThreads", 0));
        properties.setFfmpegFontFile(getCacheValue(cache, "ffmpegFontFile", MpsProperties.DEFAULT_CJK_FONT_PATH));
        properties.setOutputBucket(getCacheValue(cache, "outputBucket", ""));
        properties.setOutputRegion(getCacheValue(cache, "outputRegion", ""));
        properties.setOutputDir(getCacheValue(cache, "outputDir", "/compose_result/"));
        properties.setCallbackUrl(properties.getTencentCallbackUrl());
        properties.setOutputResolution(getCacheValue(cache, "outputResolution", "FHD"));
        properties.setCodec(getCacheValue(cache, "codec", "H.264"));
        properties.setPricingTiers(getCacheValue(cache, "pricingTiers", ""));
        properties.setCreditRate(getCacheInt(cache, "creditRate", 100));
        properties.setProfitMultiplier(getCacheDecimal(cache, "profitMultiplier", new BigDecimal("1.1")));
        properties.setLocalUnitPrice(getCacheDecimal(cache, "localUnitPrice", BigDecimal.ZERO));
        // 字幕渲染配置：字号 + 单屏正文优先上限，最终分屏统一收敛为 7～12 字。
        properties.setSubtitleFontSize(getCacheValue(cache, "subtitleFontSize", "5%"));
        properties.setSubtitleMaxChars(getCacheInt(cache, "subtitleMaxChars", 10));
        return properties;
    }

    private String resolveRuntimePath(Map<String, String> cache, String configName,
                                      String environmentName, String legacyCommand, String managedDefault) {
        String configured = cache.get(configName);
        if (StrUtil.isNotBlank(configured) && !legacyCommand.equals(configured.trim())) {
            return configured.trim();
        }
        String managedPath = System.getenv(environmentName);
        return StrUtil.isNotBlank(managedPath) ? managedPath.trim() : managedDefault;
    }
}
