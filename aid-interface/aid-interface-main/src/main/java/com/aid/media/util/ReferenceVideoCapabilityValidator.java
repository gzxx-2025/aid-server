package com.aid.media.util;

import com.aid.common.exception.ServiceException;
import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorPresentation;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceVideoInput;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/** 仅使用服务端解析结果校验视频素材，不能用 options 中的自报元数据绕过规则。 */
@Slf4j
public final class ReferenceVideoCapabilityValidator {
    private static final BigDecimal MILLIS_PER_SECOND = BigDecimal.valueOf(1000);
    private static final BigDecimal BYTES_PER_MB = BigDecimal.valueOf(1024 * 1024);

    private ReferenceVideoCapabilityValidator() { }

    public static void validate(MediaVideoGenerateRequest request, JsonNode rule) {
        validate(request, rule, true);
    }

    /** 报价只校验已经从记录解析出的可信数据，缺失字段留待提交探测。 */
    public static void validate(MediaVideoGenerateRequest request, JsonNode rule, boolean requireMetadata) {
        List<ReferenceVideoInput> inputs = request.getResolvedReferenceVideos();
        if (rule == null || !rule.isObject() || inputs == null || inputs.isEmpty()) return;
        BigDecimal totalSeconds = BigDecimal.ZERO;
        for (ReferenceVideoInput input : inputs) {
            if (input == null || !input.isTrusted() || input.getDurationMs() == null
                    || input.getDurationMs() <= 0) {
                if (requireMetadata) throw invalid("视频元数据不可用");
                continue;
            }
            BigDecimal seconds = BigDecimal.valueOf(input.getDurationMs()).divide(MILLIS_PER_SECOND);
            range(rule, "referenceVideoMinDurationSeconds", "referenceVideoMaxDurationSeconds",
                    seconds, "参考视频时长超限");
            totalSeconds = totalSeconds.add(seconds);
            // 文件维度在完整探测后校验；只读报价不把缺失的文件属性冒充不合法素材。
            if (!requireMetadata) continue;
            range(rule, null, "referenceVideoMaxSizeMB", input.getFileSizeBytes() == null ? null
                    : BigDecimal.valueOf(input.getFileSizeBytes()).divide(BYTES_PER_MB), "参考视频大小超限");
            range(rule, null, "referenceVideoMaxFileSizeMb", input.getFileSizeBytes() == null ? null
                    : BigDecimal.valueOf(input.getFileSizeBytes()).divide(BYTES_PER_MB), "参考视频大小超限");
            range(rule, "referenceVideoMinDimensionPixels", "referenceVideoMaxDimensionPixels", decimal(input.getWidth()), "参考视频尺寸超限");
            range(rule, "referenceVideoMinDimensionPixels", "referenceVideoMaxDimensionPixels", decimal(input.getHeight()), "参考视频尺寸超限");
            BigDecimal pixels = input.getWidth() != null && input.getHeight() != null
                    && input.getWidth() > 0 && input.getHeight() > 0
                    ? BigDecimal.valueOf(input.getWidth()).multiply(BigDecimal.valueOf(input.getHeight())) : null;
            range(rule, "referenceVideoMinPixels", "referenceVideoMaxPixels", pixels, "参考视频像素超限");
            BigDecimal ratio = input.getWidth() != null && input.getHeight() != null && input.getHeight() > 0
                    ? BigDecimal.valueOf(input.getWidth()).divide(BigDecimal.valueOf(input.getHeight()), 12, java.math.RoundingMode.HALF_UP) : null;
            range(rule, "referenceVideoMinAspectRatio", "referenceVideoMaxAspectRatio", ratio, "参考视频比例超限");
            range(rule, "referenceVideoMinWidth", "referenceVideoMaxWidth", decimal(input.getWidth()), "参考视频宽度超限");
            range(rule, "referenceVideoMinHeight", "referenceVideoMaxHeight", decimal(input.getHeight()), "参考视频高度超限");
            range(rule, "referenceVideoMinFps", "referenceVideoMaxFps", input.getFps(), "参考视频帧率超限");
            JsonNode formats = rule.get("referenceVideoFormats");
            if (formats != null && formats.isArray() && !formats.isEmpty()) {
                String format = normalizeFormat(input.getFormat());
                boolean accepted = false;
                for (JsonNode allowed : formats) {
                    if (allowed.isTextual() && ("*".equals(allowed.asText()) || normalizeFormat(allowed.asText()).equals(format))) accepted = true;
                }
                if (!accepted || format.isEmpty()) throw invalid("参考视频格式不支持");
            }
        }
        range(rule, null, "referenceVideoMaxTotalDurationSeconds", totalSeconds, "参考视频总时长超限");
        BigDecimal combined = totalSeconds;
        if (request.getDurationSeconds() != null && request.getDurationSeconds() > 0) {
            combined = combined.add(BigDecimal.valueOf(request.getDurationSeconds()));
        } else if (request.getDurationSeconds() != null && request.getDurationSeconds() == -1) {
            // -1 是部分官方协议的智能时长枚举，输出长度在上游推理后才能确定；
            // 此处仍已校验输入视频总时长，不能把合法的智能时长误判成“未指定”。
            return;
        } else if (number(rule, "maxInputOutputVideoDurationSeconds") != null && requireMetadata) {
            throw invalid("请指定输出时长");
        }
        range(rule, null, "maxInputOutputVideoDurationSeconds", combined, "输入输出总时长超限");
    }

    private static BigDecimal decimal(Integer value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static void range(JsonNode rule, String minKey, String maxKey, BigDecimal value, String message) {
        BigDecimal min = number(rule, minKey);
        BigDecimal max = number(rule, maxKey);
        if (min == null && max == null) return;
        if (value == null) throw invalid("视频元数据不可用");
        if ((min != null && value.compareTo(min) < 0) || (max != null && value.compareTo(max) > 0)) {
            throw invalid(message);
        }
    }

    private static ServiceException invalid(String message) {
        log.info("参考视频前置校验未通过: {}", message);
        return TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, message);
    }

    private static BigDecimal number(JsonNode rule, String key) {
        JsonNode node = key == null ? null : rule.get(key);
        return node != null && node.isNumber() && node.decimalValue().signum() > 0 ? node.decimalValue() : null;
    }

    private static String normalizeFormat(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT).replaceFirst("^video/", "");
        return "quicktime".equals(normalized) ? "mov" : normalized;
    }
}
