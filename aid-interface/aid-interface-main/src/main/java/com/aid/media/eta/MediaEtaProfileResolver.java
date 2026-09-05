package com.aid.media.eta;

import com.aid.aid.domain.media.AidMediaTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** 把媒体请求归一化成稳定、低基数的 ETA 任务画像。 */
@Component
public class MediaEtaProfileResolver {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public Profile resolve(AidMediaTask task) {
        if (task == null) {
            return defaults("UNKNOWN", "unknown");
        }
        String mediaType = mediaType(task.getMediaType());
        String providerKey = normalized(task.getProtocol(), "unknown", 64);
        String modelCode = normalized(task.getModelName(), "unknown", 100);
        String workloadKey = resolveWorkload(mediaType, task.getRequestJson());
        return build(providerKey, modelCode, mediaType, workloadKey);
    }

    /** 批量 ETA 只读取轻量任务列，不加载 request_json；仍可命中模型级聚合回退。 */
    public Profile resolveSummary(AidMediaTask task) {
        if (task == null) {
            return defaults("UNKNOWN", "unknown");
        }
        return build(
            normalized(task.getProtocol(), "unknown", 64),
            normalized(task.getModelName(), "unknown", 100),
            mediaType(task.getMediaType()),
            "default");
    }

    private Profile build(String providerKey, String modelCode, String mediaType, String workloadKey) {
        String rawKey = providerKey + "|" + modelCode + "|" + mediaType + "|" + workloadKey;
        return new Profile(sha256(rawKey), providerKey, modelCode, mediaType, workloadKey);
    }

    public Profile defaults(String mediaType, String modelCode) {
        String media = mediaType(mediaType);
        String model = normalized(modelCode, "unknown", 100);
        return build("unknown", model, media, "default");
    }

    private String resolveWorkload(String mediaType, String requestJson) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(requestJson == null ? "{}" : requestJson);
            JsonNode options = root.path("options");
            if ("VIDEO".equals(mediaType)) {
                int duration = intValue(root, "durationSeconds", intValue(options, "duration", 0));
                String durationBucket = duration <= 0 ? "d-auto"
                    : duration <= 5 ? "d-5" : duration <= 10 ? "d-10"
                    : duration <= 15 ? "d-15" : duration <= 30 ? "d-30" : "d-gt30";
                String resolution = firstText(root.path("resolution"), options.path("resolution"), options.path("size"));
                String mode = hasText(root.path("imageUrl")) ? "i2v" : "t2v";
                if (root.path("audio").asBoolean(false) || root.path("bgm").asBoolean(false)) {
                    mode += "-audio";
                }
                return mode + "|" + durationBucket + "|r-" + dimension(resolution);
            }
            if ("IMAGE".equals(mediaType)) {
                String size = firstText(root.path("size"), options.path("resolution"), options.path("size"));
                int count = intValue(root, "expectedImageCount", 1);
                String countBucket = count <= 1 ? "n-1" : count <= 4 ? "n-2-4" : "n-gt4";
                boolean reference = hasText(root.path("referenceImageUrl"))
                    || (options.path("referenceImages").isArray() && !options.path("referenceImages").isEmpty());
                return (reference ? "i2i" : "t2i") + "|" + countBucket
                    + "|r-" + dimension(size);
            }
            if ("AUDIO".equals(mediaType)) {
                int textLength = root.path("text").asText("").length();
                String lengthBucket = textLength <= 100 ? "c-100" : textLength <= 500 ? "c-500" : "c-gt500";
                return lengthBucket;
            }
        } catch (Exception ignored) {
            // 历史/压缩请求无法解析时使用默认低基数画像。
        }
        return "default";
    }

    private static int intValue(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : fallback;
    }

    private static boolean hasText(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank();
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (hasText(node)) {
                return node.asText();
            }
        }
        return null;
    }

    private static String dimension(String value) {
        String result = normalized(value, "auto", 40);
        return result.matches("[a-z0-9][a-z0-9._:-]{0,39}") ? result : "custom";
    }

    private static String mediaType(String value) {
        return normalized(value, "UNKNOWN", 20).toUpperCase(Locale.ROOT);
    }

    private static String normalized(String value, String fallback, int maxLength) {
        String result = value == null || value.isBlank() ? fallback : value.trim();
        result = result.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._:-]+", "-");
        return result.length() > maxLength ? result.substring(0, maxLength) : result;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Profile(String profileKey, String providerKey, String modelCode,
                          String mediaType, String workloadKey) {
    }
}
