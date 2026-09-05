package com.aid.media.eta;

import com.aid.aid.domain.AidConfig;
import com.aid.aid.service.IAidConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** ETA 配置的单次查询、本地缓存与安全默认值。 */
@Component
@RequiredArgsConstructor
public class MediaEtaSettings {

    private static final String CATEGORY = "media_eta";
    private static final long SETTINGS_TTL_MILLIS = 30_000L;

    private final IAidConfigService configService;
    private volatile Snapshot cached = Snapshot.defaults();
    private volatile long expireAt;

    public Snapshot current() {
        long now = System.currentTimeMillis();
        if (now < expireAt) {
            return cached;
        }
        synchronized (this) {
            if (now < expireAt) {
                return cached;
            }
            try {
                AidConfig query = new AidConfig();
                query.setCategory(CATEGORY);
                List<AidConfig> rows = configService.selectAidConfigList(query);
                Map<String, String> values = new HashMap<>();
                if (rows != null) {
                    for (AidConfig row : rows) {
                        values.put(row.getConfigName(), row.getConfigValue());
                    }
                }
                cached = Snapshot.from(values);
            } catch (Exception ignored) {
                // 配置表不可用时沿用上一次快照；初次读取失败使用代码安全默认值。
            }
            expireAt = now + SETTINGS_TTL_MILLIS;
            return cached;
        }
    }

    public record Snapshot(boolean enabled, int windowDays, int retentionDays, int minSamples,
                           int cacheTtlSeconds, long imageP50, long imageP90,
                           long videoP50, long videoP90, long audioP50, long audioP90,
                           long queueP50, long queueP90) {

        static Snapshot defaults() {
            return new Snapshot(true, 7, 30, 20, 60,
                60, 180, 300, 900, 60, 180, 15, 60);
        }

        static Snapshot from(Map<String, String> values) {
            Snapshot d = defaults();
            return new Snapshot(
                boolValue(values, "enabled", d.enabled),
                intValue(values, "window_days", d.windowDays, 1, 90),
                intValue(values, "retention_days", d.retentionDays, 7, 365),
                intValue(values, "min_samples", d.minSamples, 1, 10000),
                intValue(values, "cache_ttl_seconds", d.cacheTtlSeconds, 5, 600),
                intValue(values, "image_p50_seconds", (int) d.imageP50, 1, 86400),
                intValue(values, "image_p90_seconds", (int) d.imageP90, 1, 86400),
                intValue(values, "video_p50_seconds", (int) d.videoP50, 1, 86400),
                intValue(values, "video_p90_seconds", (int) d.videoP90, 1, 86400),
                intValue(values, "audio_p50_seconds", (int) d.audioP50, 1, 86400),
                intValue(values, "audio_p90_seconds", (int) d.audioP90, 1, 86400),
                intValue(values, "queue_p50_seconds", (int) d.queueP50, 1, 86400),
                intValue(values, "queue_p90_seconds", (int) d.queueP90, 1, 86400));
        }

        public long defaultP50(String mediaType) {
            return switch (normalize(mediaType)) {
                case "VIDEO", "COMPOSE" -> videoP50;
                case "AUDIO" -> audioP50;
                default -> imageP50;
            };
        }

        public long defaultP90(String mediaType) {
            return switch (normalize(mediaType)) {
                case "VIDEO", "COMPOSE" -> videoP90;
                case "AUDIO" -> audioP90;
                default -> imageP90;
            };
        }

        private static String normalize(String value) {
            return value == null ? "" : value.toUpperCase(Locale.ROOT);
        }

        private static boolean boolValue(Map<String, String> values, String key, boolean fallback) {
            String value = values.get(key);
            return value == null ? fallback : "true".equalsIgnoreCase(value) || "1".equals(value);
        }

        private static int intValue(Map<String, String> values, String key, int fallback, int min, int max) {
            try {
                int result = Integer.parseInt(values.get(key));
                return Math.max(min, Math.min(max, result));
            } catch (Exception ignored) {
                return fallback;
            }
        }
    }
}
