package com.aid.media.provider;

import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorPresentation;
import cn.hutool.core.util.StrUtil;
import com.aid.common.utils.image.ImageUrlValidator;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 校验文本模型的思考和多模态输入能力。 */
@Slf4j
public final class TextModelCapabilityValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> PART_TYPES = Set.of("text", "image", "video", "audio", "document");
    private TextModelCapabilityValidator() {
    }

    public static void normalizeAndValidate(AiModelConfigVo model, MediaTextGenerateRequest request) {
        normalizeAndValidate(model, request, true);
    }

    public static void normalizeAndValidate(AiModelConfigVo model, MediaTextGenerateRequest request, boolean verifyMetadata) {
        JsonNode capability = parse(model == null ? null : model.getCapabilityJson());
        validateProtocolFeatures(capability, request);
        normalizeReasoning(capability, request);
        validateParts(capability, request, verifyMetadata);
    }

    public static void validateStructure(AiModelConfigVo model, MediaTextGenerateRequest request) {
        JsonNode capability = parse(model == null ? null : model.getCapabilityJson());
        validateProtocolFeatures(capability, request);
        validateParts(capability, request, false);
    }

    private static void validateProtocolFeatures(JsonNode capability, MediaTextGenerateRequest request) {
        if (request == null) return;
        if (request.getMessages() != null) for (int index = 0; index < request.getMessages().size(); index++) {
            var message = request.getMessages().get(index);
            if (message == null || !Boolean.TRUE.equals(message.getPrefix())) continue;
            if (!bool(capability, "supportsChatPrefix") || !"assistant".equals(message.getRole())
                    || index != request.getMessages().size() - 1 || StrUtil.isNotBlank(request.getPrompt())) {
                throw reject(TaskErrorCode.USER_INPUT_INVALID, "对话前缀不支持");
            }
        }
        if (Boolean.TRUE.equals(request.getStream()) && explicitlyFalse(capability, "supportsStreaming")) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "模型不支持流式输出");
        }
        Map<String, Object> options = request.getOptions();
        if (options == null || options.isEmpty()) return;
        if ((options.containsKey("tools") || options.containsKey("tool_choice"))
                && explicitlyFalse(capability, "supportsToolCalling")) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "模型不支持工具调用");
        }
        if ((options.containsKey("response_format") || options.containsKey("json_schema"))
                && explicitlyFalse(capability, "supportsStructuredOutput")) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "模型不支持结构化输出");
        }
    }

    private static boolean explicitlyFalse(JsonNode capability, String field) {
        return capability != null && capability.has(field) && capability.path(field).isBoolean()
                && !capability.path(field).booleanValue();
    }

    private static void normalizeReasoning(JsonNode capability, MediaTextGenerateRequest request) {
        boolean supportsReasoning = bool(capability, "supportsReasoning");
        boolean supportsDisable = bool(capability, "supportsReasoningDisable", true);
        if (supportsReasoning && !supportsDisable && !Boolean.TRUE.equals(request.getReasoningEnabled())) {
            request.setReasoningEnabled(Boolean.TRUE);
            request.setIncludeReasoning(Boolean.FALSE);
        }
        if (Boolean.TRUE.equals(request.getReasoningEnabled()) && !supportsReasoning) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "模型不支持思考");
        }
        String level = lower(request.getReasoningLevel());
        if (StrUtil.isBlank(level)) {
            level = lower(text(capability, "defaultReasoningLevel"));
            request.setReasoningLevel(StrUtil.blankToDefault(level, null));
        }
        Set<String> allowed = strings(capability == null ? null : capability.get("allowedReasoningLevels"));
        if (StrUtil.isNotBlank(level) && (allowed.isEmpty() || !allowed.contains(level))) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "思考档位不支持");
        }
        if (request.getReasoningBudgetTokens() != null
                && !bool(capability, "supportsReasoningBudget")) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "思考预算不支持");
        }
        int maxBudget = intValue(capability, "maxReasoningBudgetTokens");
        if (request.getReasoningBudgetTokens() != null && maxBudget > 0
                && request.getReasoningBudgetTokens() > maxBudget) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "思考预算超限");
        }
        if (Boolean.TRUE.equals(request.getIncludeReasoning())
                && !bool(capability, "supportsReasoningContent")
                && !bool(capability, "returnsReasoningContent")) {
            request.setIncludeReasoning(Boolean.FALSE);
        }
    }

    private static void validateParts(JsonNode capability, MediaTextGenerateRequest request) {
        validateParts(capability, request, true);
    }

    private static void validateParts(JsonNode capability, MediaTextGenerateRequest request, boolean metadata) {
        if (request.getMessages() == null) {
            return;
        }
        Map<String, Integer> counts = new HashMap<>();
        Map<String, BigDecimal> durations = new HashMap<>();
        BigDecimal totalBytes = BigDecimal.ZERO;
        for (MediaTextGenerateRequest.TextMessageItem message : request.getMessages()) {
            if (message == null || message.getParts() == null) continue;
            for (MediaTextGenerateRequest.TextContentPart part : message.getParts()) {
                String type = lower(part == null ? null : part.getType());
                if (type != null && PART_TYPES.contains(type) && !"text".equals(type)) {
                    counts.merge(type, 1, Integer::sum);
                }
            }
        }
        for (MediaTextGenerateRequest.TextMessageItem message : request.getMessages()) {
            if (message == null || message.getParts() == null) {
                continue;
            }
            for (MediaTextGenerateRequest.TextContentPart part : message.getParts()) {
                String type = lower(part == null ? null : part.getType());
                if (type == null || !PART_TYPES.contains(type)) {
                    throw reject(TaskErrorCode.USER_INPUT_INVALID, "输入类型无效");
                }
                if ("text".equals(type)) {
                    if (StrUtil.isBlank(part.getText())) {
                        throw reject(TaskErrorCode.USER_INPUT_EMPTY, "文本内容为空");
                    }
                    continue;
                }
                validateUrl(part.getUrl());
                if (!supports(capability, type)) {
                    throw reject(TaskErrorCode.USER_INPUT_INVALID, "输入类型不支持");
                }
                validateMediaRole(capability, message.getRole());
                int count = counts.getOrDefault(type, 0);
                if (count > maximumCount(capability, type)) {
                    throw reject(TaskErrorCode.USER_INPUT_INVALID, "输入数量超限");
                }
                if (!metadata) continue;
                validateLimits(capability, type, part, count);
                if (part.getSizeBytes() != null) {
                    totalBytes = totalBytes.add(BigDecimal.valueOf(part.getSizeBytes()));
                }
                JsonNode limitNode = capability.path("maxInput" + title(type) + "TotalDurationSeconds");
                BigDecimal totalLimit = limitNode.isNumber() ? limitNode.decimalValue() : BigDecimal.ZERO;
                if (totalLimit.signum() > 0 && part.getDurationSeconds() == null) {
                    throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体时长缺失");
                }
                if (part.getDurationSeconds() != null) {
                    BigDecimal total = durations.merge(type,
                            BigDecimal.valueOf(part.getDurationSeconds()), BigDecimal::add);
                    if (totalLimit.signum() > 0 && total.compareTo(totalLimit) > 0) {
                        throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体总时长超限");
                    }
                }
            }
        }
        if (metadata) {
            BigDecimal totalLimit = megabytes(capability, "maxInputMediaTotalFileSizeMb");
            int mediaCount = counts.values().stream().mapToInt(Integer::intValue).sum();
            if (totalLimit.signum() > 0 && mediaCount > 0 && totalBytes.signum() == 0) {
                throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体总大小无法核验");
            }
            if (totalLimit.signum() > 0 && totalBytes.compareTo(totalLimit) > 0) {
                throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体总大小超限");
            }
        }
    }

    private static void validateMediaRole(JsonNode capability, String role) {
        Set<String> allowed = strings(capability == null ? null
                : capability.get("inputMediaAllowedMessageRoles"));
        if (!allowed.isEmpty() && (StrUtil.isBlank(role) || !allowed.contains(lower(role)))) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "消息角色不支持媒体");
        }
    }

    private static void validateUrl(String value) {
        if (StrUtil.isBlank(value) || value.regionMatches(true, 0, "data:", 0, 5)) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体地址无效");
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = lower(uri.getScheme());
            if (!Set.of("http", "https", "gs").contains(scheme)) {
                throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体地址无效");
            }
            if (("http".equals(scheme) || "https".equals(scheme))
                    && !ImageUrlValidator.validateImageUrlFormat(value).isValid()) {
                throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体地址无效");
            }
            if ("gs".equals(scheme) && StrUtil.isBlank(uri.getHost())) {
                throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体地址无效");
            }
        } catch (IllegalArgumentException error) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体地址无效");
        }
    }

    private static void validateLimits(JsonNode capability, String type,
                                       MediaTextGenerateRequest.TextContentPart part,
                                       int typeCount) {
        if (part.getSizeBytes() != null && part.getSizeBytes() < 0L
                || part.getDurationSeconds() != null && (!Double.isFinite(part.getDurationSeconds())
                    || part.getDurationSeconds() < 0D)
                || part.getPageCount() != null && part.getPageCount() < 0
                || part.getFps() != null && (!Double.isFinite(part.getFps()) || part.getFps() <= 0D)
                || part.getWidth() != null && part.getWidth() <= 0
                || part.getHeight() != null && part.getHeight() <= 0) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体元数据无效");
        }
        int maxUrlLength = intValue(capability, "inputMediaMaxUrlLength");
        if (maxUrlLength > 0 && part.getUrl() != null && part.getUrl().length() > maxUrlLength) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体地址长度超限");
        }
        BigDecimal maxBytes = megabytes(capability, "maxInput" + title(type) + "FileSizeMb");
        if (maxBytes.signum() > 0 && part.getSizeBytes() == null) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体大小缺失");
        }
        if (maxBytes.signum() > 0 && part.getSizeBytes() != null
                && BigDecimal.valueOf(part.getSizeBytes()).compareTo(maxBytes) > 0) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体文件过大");
        }
        double minDuration = doubleValue(capability, "minInput" + title(type) + "DurationSeconds");
        double maxDuration = doubleValue(capability, "maxInput" + title(type) + "DurationSeconds");
        if ((minDuration > 0D || maxDuration > 0D) && part.getDurationSeconds() == null) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体时长缺失");
        }
        if (minDuration > 0D && part.getDurationSeconds() != null
                && part.getDurationSeconds() < minDuration) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体时长过短");
        }
        if (maxDuration > 0D && part.getDurationSeconds() != null
                && part.getDurationSeconds() > maxDuration) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体时长超限");
        }
        validateVisualLimits(capability, type, part, typeCount);
        int maxPages = intValue(capability, "maxInputDocumentPages");
        if ("document".equals(type) && maxPages > 0 && part.getPageCount() == null) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "文档页数缺失");
        }
        if ("document".equals(type) && maxPages > 0 && part.getPageCount() != null
                && part.getPageCount() > maxPages) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "文档页数超限");
        }
        Set<String> formats = strings(capability == null ? null
                : capability.get("input" + title(type) + "Formats"));
        String format = mediaFormat(part);
        if (!formats.isEmpty()) {
            if (StrUtil.isBlank(format)) {
                throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体格式缺失");
            }
            if (formats.stream().noneMatch(value -> "*".equals(value)
                    || canonicalFormat(value, type).equals(canonicalFormat(format, type)))) {
                throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体格式不支持");
            }
        }
    }

    private static void validateVisualLimits(JsonNode capability, String type,
                                             MediaTextGenerateRequest.TextContentPart part,
                                             int typeCount) {
        if (!"image".equals(type) && !"video".equals(type)) return;
        String prefix = "input" + title(type);
        int minDimension = intValue(capability, prefix + "MinDimensionPixels");
        int maxDimension = intValue(capability, prefix + "MaxDimensionPixels");
        if ("image".equals(type)) {
            int threshold = intValue(capability, "inputImageHighCountThreshold");
            int highCountMax = intValue(capability, "inputImageHighCountMaxDimensionPixels");
            if (threshold > 0 && highCountMax > 0 && typeCount >= threshold) {
                maxDimension = maxDimension > 0 ? Math.min(maxDimension, highCountMax) : highCountMax;
            }
        }
        long minPixels = longValue(capability, prefix + "MinPixels");
        long maxPixels = longValue(capability, prefix + "MaxPixels");
        double minAspect = doubleValue(capability, prefix + "MinAspectRatio");
        double maxAspect = doubleValue(capability, prefix + "MaxAspectRatio");
        boolean dimensionRequired = minDimension > 0 || maxDimension > 0 || minPixels > 0L
                || maxPixels > 0L || minAspect > 0D || maxAspect > 0D;
        if (dimensionRequired && (part.getWidth() == null || part.getHeight() == null)) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "画面尺寸无法核验");
        }
        if (part.getWidth() != null && part.getHeight() != null) {
            int width = part.getWidth();
            int height = part.getHeight();
            int shortest = Math.min(width, height);
            int longest = Math.max(width, height);
            if (minDimension > 0 && shortest < minDimension) {
                throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体画面尺寸过小");
            }
            if (maxDimension > 0 && longest > maxDimension) {
                throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体画面尺寸超限");
            }
            long pixels = (long) width * (long) height;
            if (minPixels > 0L && pixels < minPixels) {
                throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体总像素过低");
            }
            if (maxPixels > 0L && pixels > maxPixels) {
                throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体总像素超限");
            }
            double aspect = (double) width / (double) height;
            if (minAspect > 0D && aspect < minAspect || maxAspect > 0D && aspect > maxAspect) {
                throw reject(TaskErrorCode.USER_INPUT_INVALID, "媒体画面比例不支持");
            }
        }
        if ("video".equals(type)) {
            double minFps = doubleValue(capability, "inputVideoMinFps");
            double maxFps = doubleValue(capability, "inputVideoMaxFps");
            if ((minFps > 0D || maxFps > 0D) && part.getFps() == null) {
                throw reject(TaskErrorCode.USER_INPUT_INVALID, "视频帧率无法核验");
            }
            if (part.getFps() != null && (minFps > 0D && part.getFps() < minFps
                    || maxFps > 0D && part.getFps() > maxFps)) {
                throw reject(TaskErrorCode.USER_INPUT_INVALID, "视频帧率不支持");
            }
        }
    }

    private static BigDecimal megabytes(JsonNode capability, String name) {
        JsonNode node = capability == null ? null : capability.get(name);
        return node != null && node.isNumber()
                ? node.decimalValue().multiply(BigDecimal.valueOf(1024L * 1024L))
                : BigDecimal.ZERO;
    }

    private static String canonicalFormat(String value, String type) {
        String format = value.toLowerCase(Locale.ROOT).trim();
        int separator = format.indexOf('/');
        if (separator >= 0) format = format.substring(separator + 1);
        return switch (format) {
            case "jpg", "pjpeg" -> "jpeg";
            case "tif" -> "tiff";
            case "heic" -> "heif";
            case "mpeg", "mpga" -> "audio".equals(type) ? "mp3" : format;
            case "mp4", "x-m4a" -> "audio".equals(type) ? "m4a" : format;
            case "x-wav", "wave" -> "wav";
            case "quicktime" -> "mov";
            default -> format;
        };
    }

    private static String mediaFormat(MediaTextGenerateRequest.TextContentPart part) {
        String mime = lower(part.getMimeType());
        if (StrUtil.isNotBlank(mime)) {
            int parameter = mime.indexOf(';');
            return parameter < 0 ? mime : mime.substring(0, parameter).trim();
        }
        try {
            String path = URI.create(part.getUrl().trim()).getPath();
            int slash = path == null ? -1 : path.lastIndexOf('/');
            int dot = path == null ? -1 : path.lastIndexOf('.');
            return dot > slash && dot < path.length() - 1 ? lower(path.substring(dot + 1)) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean supports(JsonNode capability, String type) {
        Set<String> modalities = strings(capability == null ? null : capability.get("inputModalities"));
        if (!modalities.isEmpty()) {
            return modalities.stream().anyMatch(value -> type.equalsIgnoreCase(value));
        }
        return bool(capability, "supports" + title(type) + "Input");
    }

    private static int maximumCount(JsonNode capability, String type) {
        JsonNode value = capability == null ? null : capability.get("maxInput" + title(type) + "s");
        if (value == null || value.isNull()) {
            value = capability == null ? null : capability.get("maxInput" + title(type) + "Count");
        }
        // 官方未公开数量上限时不推测一个人为值；仍由供应商自身协议校验兜底。
        if (value == null || value.isNull()) return Integer.MAX_VALUE;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw reject(TaskErrorCode.USER_INPUT_INVALID, "模型能力配置无效");
        }
        return value.intValue() < 0 ? Integer.MAX_VALUE : value.intValue();
    }

    private static JsonNode parse(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            JsonNode value = MAPPER.readTree(json);
            return value != null && value.isObject() ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean bool(JsonNode root, String name) {
        return bool(root, name, false);
    }

    private static boolean bool(JsonNode root, String name, boolean fallback) {
        JsonNode node = root == null ? null : root.get(name);
        return node != null && node.isBoolean() ? node.asBoolean() : fallback;
    }

    private static int intValue(JsonNode root, String name) {
        JsonNode node = root == null ? null : root.get(name);
        return node != null && node.canConvertToInt() ? node.asInt() : 0;
    }

    private static long longValue(JsonNode root, String name) {
        JsonNode node = root == null ? null : root.get(name);
        return node != null && node.canConvertToLong() ? node.asLong() : 0L;
    }

    private static double doubleValue(JsonNode root, String name) {
        JsonNode node = root == null ? null : root.get(name);
        return node != null && node.isNumber() ? node.asDouble() : 0D;
    }

    private static String text(JsonNode root, String name) {
        JsonNode node = root == null ? null : root.get(name);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static Set<String> strings(JsonNode node) {
        Set<String> values = new HashSet<>();
        if (node != null && node.isArray()) {
            node.forEach(value -> {
                if (value.isTextual()) {
                    values.add(lower(value.asText()));
                }
            });
        }
        return values;
    }

    private static String title(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static RuntimeException reject(TaskErrorCode code, String message) {
        log.info("文本模型能力校验拒绝: code={}, reason={}", code, message);
        return TaskErrorPresentation.fromCode(code, message);
    }
}
