package com.aid.media.provider;

import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorPresentation;
import cn.hutool.core.util.StrUtil;
import com.aid.common.utils.image.ImageUrlValidator;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 校验文本模型的思考和多模态输入能力。 */
public final class TextModelCapabilityValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> PART_TYPES = Set.of("text", "image", "video", "audio", "document");
    private static final int UNPUBLISHED_COUNT_DEFAULT = 10;

    private TextModelCapabilityValidator() {
    }

    public static void normalizeAndValidate(AiModelConfigVo model, MediaTextGenerateRequest request) {
        JsonNode capability = parse(model == null ? null : model.getCapabilityJson());
        normalizeReasoning(capability, request);
        validateParts(capability, request);
    }

    private static void normalizeReasoning(JsonNode capability, MediaTextGenerateRequest request) {
        boolean supportsReasoning = bool(capability, "supportsReasoning");
        boolean supportsDisable = bool(capability, "supportsReasoningDisable", true);
        if (supportsReasoning && !supportsDisable && !Boolean.TRUE.equals(request.getReasoningEnabled())) {
            request.setReasoningEnabled(Boolean.TRUE);
            request.setIncludeReasoning(Boolean.FALSE);
        }
        if (Boolean.TRUE.equals(request.getReasoningEnabled()) && !supportsReasoning) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "模型不支持思考");
        }
        String level = lower(request.getReasoningLevel());
        if (StrUtil.isBlank(level)) {
            level = lower(text(capability, "defaultReasoningLevel"));
            request.setReasoningLevel(StrUtil.blankToDefault(level, null));
        }
        Set<String> allowed = strings(capability == null ? null : capability.get("allowedReasoningLevels"));
        if (StrUtil.isNotBlank(level) && (allowed.isEmpty() || !allowed.contains(level))) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "思考档位不支持");
        }
        if (request.getReasoningBudgetTokens() != null
                && !bool(capability, "supportsReasoningBudget")) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "思考预算不支持");
        }
        int maxBudget = intValue(capability, "maxReasoningBudgetTokens");
        if (request.getReasoningBudgetTokens() != null && maxBudget > 0
                && request.getReasoningBudgetTokens() > maxBudget) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "思考预算超限");
        }
        if (Boolean.TRUE.equals(request.getIncludeReasoning())
                && !bool(capability, "supportsReasoningContent")
                && !bool(capability, "returnsReasoningContent")) {
            request.setIncludeReasoning(Boolean.FALSE);
        }
    }

    private static void validateParts(JsonNode capability, MediaTextGenerateRequest request) {
        if (request.getMessages() == null) {
            return;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (MediaTextGenerateRequest.TextMessageItem message : request.getMessages()) {
            if (message == null || message.getParts() == null) {
                continue;
            }
            for (MediaTextGenerateRequest.TextContentPart part : message.getParts()) {
                String type = lower(part == null ? null : part.getType());
                if (!PART_TYPES.contains(type)) {
                    throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "输入类型无效");
                }
                if ("text".equals(type)) {
                    if (StrUtil.isBlank(part.getText())) {
                        throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_EMPTY, "文本内容为空");
                    }
                    continue;
                }
                validateUrl(part.getUrl());
                if (!supports(capability, type)) {
                    throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "输入类型不支持");
                }
                int count = counts.merge(type, 1, Integer::sum);
                if (count > maximumCount(capability, type)) {
                    throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "输入数量超限");
                }
                validateLimits(capability, type, part);
            }
        }
    }

    private static void validateUrl(String value) {
        if (StrUtil.isBlank(value) || value.regionMatches(true, 0, "data:", 0, 5)) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "媒体地址无效");
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = lower(uri.getScheme());
            if (!Set.of("http", "https", "gs").contains(scheme)) {
                throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "媒体地址无效");
            }
            if (("http".equals(scheme) || "https".equals(scheme))
                    && !ImageUrlValidator.validateImageUrlFormat(value).isValid()) {
                throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "媒体地址无效");
            }
            if ("gs".equals(scheme) && StrUtil.isBlank(uri.getHost())) {
                throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "媒体地址无效");
            }
        } catch (IllegalArgumentException error) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "媒体地址无效");
        }
    }

    private static void validateLimits(JsonNode capability, String type,
                                       MediaTextGenerateRequest.TextContentPart part) {
        if (part.getSizeBytes() != null && part.getSizeBytes() < 0L
                || part.getDurationSeconds() != null && part.getDurationSeconds() < 0D
                || part.getPageCount() != null && part.getPageCount() < 0
                || part.getFps() != null && part.getFps() <= 0D
                || part.getWidth() != null && part.getWidth() <= 0
                || part.getHeight() != null && part.getHeight() <= 0) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "媒体元数据无效");
        }
        long maxBytes = longValue(capability, "maxInput" + title(type) + "FileSizeMb") * 1024L * 1024L;
        if (maxBytes > 0L && part.getSizeBytes() == null) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "媒体大小缺失");
        }
        if (maxBytes > 0L && part.getSizeBytes() != null && part.getSizeBytes() > maxBytes) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "媒体文件过大");
        }
        double maxDuration = doubleValue(capability, "maxInput" + title(type) + "DurationSeconds");
        if (maxDuration > 0D && part.getDurationSeconds() == null) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "媒体时长缺失");
        }
        if (maxDuration > 0D && part.getDurationSeconds() != null
                && part.getDurationSeconds() > maxDuration) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "媒体时长超限");
        }
        int maxPages = intValue(capability, "maxInputDocumentPages");
        if ("document".equals(type) && maxPages > 0 && part.getPageCount() == null) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "文档页数缺失");
        }
        if ("document".equals(type) && maxPages > 0 && part.getPageCount() != null
                && part.getPageCount() > maxPages) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "文档页数超限");
        }
        Set<String> formats = strings(capability == null ? null
                : capability.get("input" + title(type) + "Formats"));
        String format = mediaFormat(part);
        if (!formats.isEmpty()) {
            if (StrUtil.isBlank(format)) {
                throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "媒体格式缺失");
            }
            if (formats.stream().noneMatch(value -> value.equals(format)
                    || format.endsWith("/" + value) || value.endsWith("/" + format))) {
                throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "媒体格式不支持");
            }
        }
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
        int value = intValue(capability, "maxInput" + title(type) + "s");
        if (value == 0) {
            value = intValue(capability, "maxInput" + title(type) + "Count");
        }
        return value < 0 ? Integer.MAX_VALUE : value == 0 ? UNPUBLISHED_COUNT_DEFAULT : value;
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
}
