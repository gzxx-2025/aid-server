package com.aid.media.service;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceAudioInput;
import com.aid.media.dto.ReferenceVideoInput;
import com.aid.media.provider.TextModelCapabilityValidator;
import com.aid.media.provider.ReferenceAudioLimiter;
import com.aid.media.util.ModelCapabilityResolver;
import com.aid.media.util.ModelInputCapabilityValidator;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 共用输入校验层，只在存在相应限制时补齐旧素材的可信元数据。 */
@Service
@RequiredArgsConstructor
public class VerifiedMediaInputService {
    private static final BigDecimal MB = BigDecimal.valueOf(1024L * 1024L);
    private final VerifiedMediaMetadataService metadata;

    public void configured(AiModelConfigVo model, Object request) {
        com.aid.model.definition.ModelMaterialValidator.validate(model, request, metadata);
    }

    public void image(AiModelConfigVo model, MediaImageGenerateRequest request) {
        configured(model, request);
        JsonNode root = ModelCapabilityResolver.parseCapability(model.getCapabilityJson());
        List<JsonNode> rules = rules(root, ModelInputCapabilityValidator.imageScene(model, request));
        Set<String> images = new LinkedHashSet<>();
        collect(images, request.getReferenceImageUrl());
        collectOptions(images, request.getOptions(), "referenceImages", "images", "key_images", "image_settings");
        long bytes = validateImages(images, rules);
        for (JsonNode rule : rules) range(rule, null, "maxInputMediaTotalFileSizeMb", BigDecimal.valueOf(bytes).divide(MB));
    }

    public void video(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        configured(model, request);
        JsonNode root = ModelCapabilityResolver.parseCapability(model.getCapabilityJson());
        List<JsonNode> rules = rules(root, ModelInputCapabilityValidator.videoScene(model, request));
        Set<String> images = new LinkedHashSet<>();
        collect(images, request.getImageUrl());
        collectOptions(images, request.getOptions(), "referenceImages", "images", "key_images", "image_settings", "lastFrameImageUrl");
        long totalBytes = validateImages(images, rules);
        if (request.getReferenceAudios() != null) {
            ReferenceAudioLimiter.limit(request.getReferenceAudios(), model, model.getModelCode());
            if (!request.getReferenceAudios().isEmpty() && !ReferenceAudioLimiter.readCapability(model).isUsable()) {
                throw new ServiceException("模型不支持参考音频");
            }
            BigDecimal totalAudioSeconds = BigDecimal.ZERO;
            Set<String> audioUrls = new LinkedHashSet<>();
            for (ReferenceAudioInput audio : request.getReferenceAudios()) {
                if (audio == null) throw new ServiceException("参考音频无效");
                var actual = metadata.inspect(audio.getSampleUrl(), "audio");
                audio.setDurationMs(actual.durationSeconds().multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.CEILING).intValueExact());
                audio.setFormat(actual.format());
                for (JsonNode rule : rules) validate(actual, rule, "referenceAudio");
                if (audioUrls.add(audio.getSampleUrl())) {
                    totalAudioSeconds = totalAudioSeconds.add(actual.durationSeconds());
                    totalBytes = Math.addExact(totalBytes, actual.sizeBytes());
                }
            }
            for (JsonNode rule : rules) range(rule, null, "referenceAudioMaxTotalDurationSeconds", totalAudioSeconds);
        }
        Set<String> videos = new LinkedHashSet<>();
        collectOptions(videos, request.getOptions(), "referenceVideos", "videos", "featureVideoUrl", "referenceVideoUrl", "baseVideoUrl", "inputVideoUrl", "videoUrl", "video_url");
        if (videos.isEmpty()) {
            for (JsonNode rule : rules) range(rule, null, "maxInputMediaTotalFileSizeMb", BigDecimal.valueOf(totalBytes).divide(MB));
            return;
        }
        if (!"tokendance".equalsIgnoreCase(model.getProviderCode())
                && !com.aid.model.definition.ModelMaterialStatistics.required(model.getResolvedDefinition())
                && rules.stream().noneMatch(rule -> requiresMetadata(rule, "referenceVideo") || requiresMetadata(rule, "maxInputMediaTotal"))) return;
        List<ReferenceVideoInput> resolved = new ArrayList<>();
        Map<String, ReferenceVideoInput> prior = new LinkedHashMap<>();
        if (request.getResolvedReferenceVideos() != null) request.getResolvedReferenceVideos().forEach(value -> prior.put(value.getVideoUrl(), value));
        BigDecimal total = BigDecimal.ZERO;
        List<BigDecimal> durations = new ArrayList<>();
        for (String video : videos) {
            ReferenceVideoInput known = prior.get(video);
            if (known == null || known.getDurationMs() == null || known.getFileSizeBytes() == null || known.getWidth() == null || known.getHeight() == null || known.getFps() == null || known.getFormat() == null) {
                var actual = metadata.inspect(video, "video");
                known = new ReferenceVideoInput(known == null ? null : known.getRecordId(), video,
                        actual.durationSeconds().multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.CEILING).longValueExact(),
                        actual.sizeBytes(), actual.width(), actual.height(), actual.fps(), actual.format());
            }
            resolved.add(known);
            totalBytes = Math.addExact(totalBytes, known.getFileSizeBytes());
            BigDecimal seconds = BigDecimal.valueOf(known.getDurationMs()).divide(BigDecimal.valueOf(1000));
            total = total.add(seconds);
            durations.add(seconds);
        }
        request.setResolvedReferenceVideos(resolved);
        Map<String, Object> options = new LinkedHashMap<>(request.getOptions());
        options.put("referenceVideoDurations", durations);
        options.put("referenceVideoSeconds", total);
        options.put("inputVideoSeconds", total);
        request.setOptions(options);
        for (JsonNode rule : rules) range(rule, null, "maxInputMediaTotalFileSizeMb", BigDecimal.valueOf(totalBytes).divide(MB));
    }

    public void text(AiModelConfigVo model, MediaTextGenerateRequest request) {
        configured(model, request);
        TextModelCapabilityValidator.validateStructure(model, request);
        if (request.getMessages() == null) return;
        JsonNode capability = ModelCapabilityResolver.parseCapability(model.getCapabilityJson());
        BigDecimal totalBytes = BigDecimal.ZERO;
        for (var message : request.getMessages()) {
            if (message == null || message.getParts() == null) continue;
            for (var part : message.getParts()) {
                String type = part.getType().toLowerCase(Locale.ROOT);
                if (!Set.of("image", "audio", "video").contains(type)) continue;
                String title = type.substring(0, 1).toUpperCase(Locale.ROOT) + type.substring(1);
                if (!requiresMetadata(capability, "maxInput" + title) && !requiresMetadata(capability, "input" + title)
                        && !requiresMetadata(capability, "maxInputMediaTotal")) continue;
                var actual = metadata.inspect(part.getUrl(), type);
                totalBytes = totalBytes.add(BigDecimal.valueOf(actual.sizeBytes()));
                part.setSizeBytes(actual.sizeBytes());
                part.setDurationSeconds(actual.durationSeconds() == null ? null : actual.durationSeconds().doubleValue());
                part.setWidth(actual.width() > 0 ? actual.width() : null);
                part.setHeight(actual.height() > 0 ? actual.height() : null);
                part.setFps(actual.fps() == null || actual.fps().signum() <= 0
                        ? null : actual.fps().doubleValue());
                part.setMimeType(type + "/" + actual.format());
                validate(actual, capability, "input" + title);
            }
        }
        if (capability != null) range(capability, null, "maxInputMediaTotalFileSizeMb", totalBytes.divide(MB));
    }

    private long validateImages(Set<String> images, List<JsonNode> rules) {
        if (rules.stream().noneMatch(rule -> requiresMetadata(rule, "referenceImage") || requiresMetadata(rule, "maxInputMediaTotal"))) return 0L;
        long bytes = 0;
        for (String url : images) {
            var actual = metadata.inspect(url, "image");
            bytes = Math.addExact(bytes, actual.sizeBytes());
            for (JsonNode rule : rules) validate(actual, rule, "referenceImage");
        }
        return bytes;
    }

    private boolean requiresMetadata(JsonNode rule, String prefix) {
        if (rule == null || !rule.isObject()) return false;
        var fields = rule.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String key = field.getKey();
            if (key.startsWith(prefix) && (key.contains("Duration") || key.contains("FileSize") || key.contains("SizeMB") || key.contains("Dimension")
                    || key.contains("Width") || key.contains("Height") || key.contains("AspectRatio") || key.contains("Pixels") || key.contains("Fps") || key.endsWith("Formats"))) {
                JsonNode value = field.getValue();
                if (value.isNumber() && value.decimalValue().signum() > 0 || value.isArray() && !value.isEmpty()) return true;
            }
        }
        return false;
    }

    private void validate(VerifiedMediaMetadataService.Metadata value, JsonNode rule, String prefix) {
        range(rule, null, prefix + "MaxFileSizeMb", BigDecimal.valueOf(value.sizeBytes()).divide(MB));
        range(rule, prefix + "MinDurationSeconds", prefix + "MaxDurationSeconds", value.durationSeconds());
        range(rule, prefix + "MinDimensionPixels", prefix + "MaxDimensionPixels", BigDecimal.valueOf(value.width()));
        range(rule, prefix + "MinDimensionPixels", prefix + "MaxDimensionPixels", BigDecimal.valueOf(value.height()));
        range(rule, prefix + "MinWidth", prefix + "MaxWidth", BigDecimal.valueOf(value.width()));
        range(rule, prefix + "MinHeight", prefix + "MaxHeight", BigDecimal.valueOf(value.height()));
        range(rule, prefix + "MinFps", prefix + "MaxFps", value.fps());
        range(rule, prefix + "MinPixels", prefix + "MaxPixels", BigDecimal.valueOf(value.width()).multiply(BigDecimal.valueOf(value.height())));
        BigDecimal ratio = value.height() > 0 ? BigDecimal.valueOf(value.width()).divide(BigDecimal.valueOf(value.height()), 12, RoundingMode.HALF_UP) : null;
        range(rule, prefix + "MinAspectRatio", prefix + "MaxAspectRatio", ratio);
        JsonNode formats = rule.path(prefix + "Formats");
        if (formats.isArray() && !formats.isEmpty()) {
            boolean accepted = false;
            for (JsonNode item : formats) if ("*".equals(item.asText()) || normalize(item.asText()).equals(normalize(value.format()))) accepted = true;
            if (!accepted) throw new ServiceException("素材格式不支持");
        }
    }

    private void range(JsonNode rule, String minKey, String maxKey, BigDecimal value) {
        JsonNode min = minKey == null ? null : rule.get(minKey);
        JsonNode max = maxKey == null ? null : rule.get(maxKey);
        boolean hasMin = min != null && min.isNumber() && min.decimalValue().signum() > 0;
        boolean hasMax = max != null && max.isNumber() && max.decimalValue().signum() > 0;
        if ((hasMin || hasMax) && value == null) throw new ServiceException("素材元数据不可用");
        if (hasMin && value.compareTo(min.decimalValue()) < 0 || hasMax && value.compareTo(max.decimalValue()) > 0) throw new ServiceException("素材参数超限");
    }

    private List<JsonNode> rules(JsonNode root, String scene) {
        if (root == null || !root.isObject()) return List.of();
        JsonNode specific = root.path("sceneRules").path(scene);
        return specific.isObject() ? List.of(root, specific) : List.of(root);
    }

    private String normalize(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceFirst("^(image|audio|video)/", "");
        return switch (normalized) { case "jpg" -> "jpeg"; case "tif" -> "tiff"; case "mpeg" -> "mp3"; case "heic" -> "heif"; default -> normalized; };
    }

    private void collectOptions(Set<String> result, Map<String, Object> options, String... keys) {
        if (options != null) for (String key : keys) collect(result, options.get(key));
    }

    private void collect(Set<String> result, Object value) {
        if (value instanceof String url && !url.isBlank()) result.add(url.trim());
        else if (value instanceof List<?> values) values.forEach(item -> collect(result, item));
        else if (value instanceof Map<?, ?> object) {
            for (String key : List.of("url", "image_url", "imageUrl", "key_image", "keyImage", "video_url", "videoUrl")) {
                if (object.get(key) != null) { collect(result, object.get(key)); return; }
            }
        }
    }
}
