package com.aid.upgrade.gateway;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.aid.common.aid.core.service.ConfigService;
import com.aid.upgrade.constant.UpgradeConfigKeys;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 官方统一网关配置提供者，带短时缓存并支持改库后热生效
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfficialGatewayConfigProvider {

    /** 配置缓存TTL（毫秒），与 aid_config 其他动态配置 30s 口径一致 */
    private static final long CACHE_TTL_MS = 30_000L;

    private final ConfigService configService;

    /** 缓存的配置快照 */
    private final AtomicReference<OfficialGatewayConfig> cache = new AtomicReference<>();

    /** 上次加载时间戳（毫秒） */
    private volatile long lastLoadMs = 0L;

    /**
     * 获取当前生效的官方网关配置（永不为null，异常时退化为关闭）
     *
     * @return 官方网关配置快照
     */
    public OfficialGatewayConfig getConfig() {
        long now = System.currentTimeMillis();
        OfficialGatewayConfig cached = cache.get();
        if (cached != null && (now - lastLoadMs) < CACHE_TTL_MS) {
            return cached;
        }
        try {
            OfficialGatewayConfig fresh = loadFromDb();
            cache.set(fresh);
            lastLoadMs = now;
            return fresh;
        } catch (Exception e) {
            // 读库失败：有旧值用旧值，否则退化为关闭，保证生成链路不因配置读取中断
            log.error("加载官方网关配置失败(category={})", UpgradeConfigKeys.CATEGORY_OFFICIAL_GATEWAY, e);
            if (cached != null) {
                return cached;
            }
            OfficialGatewayConfig fallback = new OfficialGatewayConfig();
            fallback.setEnabled(false);
            return fallback;
        }
    }

    /**
     * 强制清空缓存，配置保存后立即生效
     */
    public void refresh() {
        lastLoadMs = 0L;
        cache.set(null);
    }

    /**
     * 从 aid_config 读取官方网关配置快照
     */
    private OfficialGatewayConfig loadFromDb() {
        OfficialGatewayConfig config = new OfficialGatewayConfig();
        Map<String, String> map;
        try {
            // 分类不存在时 getConfigValues 会抛异常，视为未配置=关闭
            map = configService.getConfigValues(UpgradeConfigKeys.CATEGORY_OFFICIAL_GATEWAY);
        } catch (Exception e) {
            config.setEnabled(false);
            return config;
        }
        String enabled = map.get(UpgradeConfigKeys.KEY_GATEWAY_ENABLED);
        config.setEnabled(StrUtil.isNotBlank(enabled)
                && ("true".equalsIgnoreCase(enabled.trim()) || "1".equals(enabled.trim())));
        config.setBaseUrl(StrUtil.trimToNull(map.get(UpgradeConfigKeys.KEY_GATEWAY_BASE_URL)));
        config.setApiKey(StrUtil.trimToNull(map.get(UpgradeConfigKeys.KEY_GATEWAY_API_KEY)));
        config.setExcludedModelIds(parseIds(map.get(UpgradeConfigKeys.KEY_GATEWAY_EXCLUDED_MODEL_IDS)));
        config.setExcludedProviderIds(parseIds(map.get(UpgradeConfigKeys.KEY_GATEWAY_EXCLUDED_PROVIDER_IDS)));
        return config;
    }

    /**
     * 解析逗号分隔的例外ID串（模型/厂商通用），非法片段自动跳过
     */
    private Set<Long> parseIds(String raw) {
        if (StrUtil.isBlank(raw)) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .filter(item -> item.chars().allMatch(Character::isDigit))
                .map(Long::parseLong)
                .filter(id -> id > 0)
                .collect(Collectors.toUnmodifiableSet());
    }
}
