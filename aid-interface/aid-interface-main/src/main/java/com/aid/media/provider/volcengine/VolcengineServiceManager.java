package com.aid.media.provider.volcengine;

import cn.hutool.core.util.StrUtil;
import com.aid.media.constants.VolcengineConstants;
import com.volcengine.ark.runtime.service.ArkService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 火山引擎方舟（Ark）Java SDK 的 {@link ArkService} 连接池管理器。
 * 按 apiKey 缓存实例，图片与视频 Provider 共享同一连接。
 */
@Slf4j
@Component
public class VolcengineServiceManager {

    private final ConcurrentHashMap<String, ArkService> serviceCache = new ConcurrentHashMap<>();

    /**
     * 获取或创建 ArkService 实例（默认 SDK 超时）。
     */
    public ArkService getService(String apiKey, String apiHost) {
        return getService(apiKey, apiHost, VolcengineConstants.SDK_TIMEOUT_SECONDS);
    }

    /**
     * 获取或创建 ArkService 实例，按「apiKey + 超时秒数」缓存。
     * ArkService 的超时是构建期设置，无法按调用变更，故把 timeout 纳入缓存键：
     * 同 apiKey 不同模型（不同 submitTimeoutSeconds）各自复用独立实例，互不影响。
     *
     * @param timeoutSeconds 单次调用超时秒数（来自模型 capability_json.submitTimeoutSeconds，缺省回退默认）
     */
    public ArkService getService(String apiKey, String apiHost, int timeoutSeconds) {
        int effectiveTimeout = timeoutSeconds > 0 ? timeoutSeconds : VolcengineConstants.SDK_TIMEOUT_SECONDS;
        // 缓存键纳入 baseUrl：同 apiKey 配置了不同 base_url 的模型不会复用到错误 host 的实例。
        String baseUrl = resolveBaseUrl(apiHost);
        String cacheKey = apiKey + "|" + baseUrl + "|" + effectiveTimeout;
        return serviceCache.computeIfAbsent(cacheKey, key -> {
            log.info("创建 Volcengine ArkService 实例, baseUrl={}, timeoutSeconds={}", baseUrl, effectiveTimeout);
            ConnectionPool connectionPool = new ConnectionPool(
                VolcengineConstants.OKHTTP_POOL_MAX_IDLE,
                VolcengineConstants.OKHTTP_POOL_KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS);
            Dispatcher dispatcher = new Dispatcher();
            return ArkService.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .connectionPool(connectionPool)
                    .dispatcher(dispatcher)
                    .timeout(Duration.ofSeconds(effectiveTimeout))
                    .build();
        });
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭所有 Volcengine ArkService 实例, 数量={}", serviceCache.size());
        serviceCache.values().forEach(service -> {
            try {
                service.shutdownExecutor();
            } catch (Exception e) {
                log.warn("关闭 ArkService 异常", e);
            }
        });
        serviceCache.clear();
    }

    private String resolveBaseUrl(String apiHost) {
        if (StrUtil.isBlank(apiHost)) {
            log.error("volcengine baseUrl 未配置，请在 aid_ai_provider 表配置 base_url");
            throw new IllegalArgumentException("volcengine baseUrl 不能为空，请在 aid_ai_provider 表配置");
        }
        return apiHost.endsWith("/") ? apiHost.substring(0, apiHost.length() - 1) : apiHost;
    }
}
