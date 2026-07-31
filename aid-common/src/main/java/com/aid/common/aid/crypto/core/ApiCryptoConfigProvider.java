package com.aid.common.aid.crypto.core;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.aid.crypto.config.ApiCryptoConfig;

import cn.hutool.core.util.StrUtil;

/**
 * 接口加密配置提供者。
 *
 * @author 视觉AID
 */
public class ApiCryptoConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(ApiCryptoConfigProvider.class);

    /** aid_config 分类名 */
    public static final String CATEGORY = "api_crypto";

    // ---- config_name 常量 ----
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_GZIP_ENABLED = "gzip_enabled";
    public static final String KEY_GZIP_THRESHOLD = "gzip_threshold";
    public static final String KEY_MAX_PLAIN_SIZE = "max_plain_size";
    public static final String KEY_TIMESTAMP_WINDOW = "timestamp_window_ms";
    public static final String KEY_PUBLIC_KEY = "public_key";
    public static final String KEY_PRIVATE_KEY = "private_key";

    /** 配置缓存 TTL（毫秒），与 publicConfig 30s 口径一致 */
    private static final long CACHE_TTL_MS = 30_000L;

    private final ConfigService configService;

    /** 缓存的配置快照 */
    private final AtomicReference<ApiCryptoConfig> cache = new AtomicReference<>();

    /** 上次加载时间戳（毫秒） */
    private volatile long lastLoadMs = 0L;

    public ApiCryptoConfigProvider(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * 获取当前生效配置（带 30s 缓存）。
     *
     * 读库异常时返回上一份缓存；若从未加载成功，返回一个 {@code enabled=false} 的安全默认值，
     * 保证异常场景下接口退化为明文而非全部 500。
     *
     * @return 配置快照（永不为 null）
     */
    public ApiCryptoConfig getConfig() {
        long now = System.currentTimeMillis();
        ApiCryptoConfig cached = cache.get();
        // 缓存有效直接返回
        if (cached != null && (now - lastLoadMs) < CACHE_TTL_MS) {
            return cached;
        }
        // 加载（多线程下允许偶发重复加载，结果一致，无需加锁）
        try {
            ApiCryptoConfig fresh = loadFromDb();
            cache.set(fresh);
            lastLoadMs = now;
            return fresh;
        } catch (Exception e) {
            // 读库失败：有旧值用旧值，否则用安全默认（关闭加密）
            log.error("加载接口加密配置失败(category={}), 使用兜底配置", CATEGORY, e);
            if (cached != null) {
                return cached;
            }
            ApiCryptoConfig fallback = new ApiCryptoConfig();
            fallback.setEnabled(false);
            return fallback;
        }
    }

    /**
     * 强制清空缓存，下次 {@link #getConfig()} 立即回源（供后台改配置后主动刷新）。
     */
    public void refresh() {
        lastLoadMs = 0L;
        cache.set(null);
    }

    /**
     * 从 aid_config 读取并组装配置快照。
     */
    private ApiCryptoConfig loadFromDb() {
        ApiCryptoConfig cfg = new ApiCryptoConfig();
        Map<String, String> map;
        try {
            // getConfigValues 在 category 不存在时会抛异常；此处捕获，视为“未配置=关闭”
            map = configService.getConfigValues(CATEGORY);
        } catch (Exception e) {
            log.warn("接口加密配置分类不存在或为空(category={}), 默认关闭加密", CATEGORY);
            cfg.setEnabled(false);
            return cfg;
        }
        // 逐项解析，缺失项用默认值兜底
        cfg.setEnabled(parseBool(map.get(KEY_ENABLED), false));
        cfg.setGzipEnabled(parseBool(map.get(KEY_GZIP_ENABLED), true));
        cfg.setGzipThreshold(parseInt(map.get(KEY_GZIP_THRESHOLD), 1024));
        cfg.setMaxPlainSize(parseLong(map.get(KEY_MAX_PLAIN_SIZE), 20L * 1024 * 1024));
        cfg.setTimestampWindowMs(parseLong(map.get(KEY_TIMESTAMP_WINDOW), 5L * 60 * 1000));
        cfg.setPublicKey(StrUtil.trimToNull(map.get(KEY_PUBLIC_KEY)));
        cfg.setPrivateKey(StrUtil.trimToNull(map.get(KEY_PRIVATE_KEY)));
        return cfg;
    }

    private boolean parseBool(String v, boolean def) {
        if (StrUtil.isBlank(v)) {
            return def;
        }
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    private int parseInt(String v, int def) {
        if (StrUtil.isBlank(v)) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("接口加密配置数值解析失败, value={}, 用默认值 {}", v, def);
            return def;
        }
    }

    private long parseLong(String v, long def) {
        if (StrUtil.isBlank(v)) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            log.warn("接口加密配置数值解析失败, value={}, 用默认值 {}", v, def);
            return def;
        }
    }
}
