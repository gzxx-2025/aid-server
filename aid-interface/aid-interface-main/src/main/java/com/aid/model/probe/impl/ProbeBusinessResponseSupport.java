package com.aid.model.probe.impl;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.alibaba.fastjson2.JSONObject;

import cn.hutool.core.util.StrUtil;

/**
 * 识别已知只读查询业务响应。
 */
final class ProbeBusinessResponseSupport {

    private static final Set<String> MISSING_CODES = Set.of(
            "task_not_found", "video_not_found");

    private static final Set<String> MISSING_MESSAGES = Set.of(
            "task not found", "video not found", "任务不存在", "视频不存在");

    private static final Set<String> MODEL_MISSING_CODES = Set.of(
            "model_not_found", "modelnotfound", "not_found");

    private ProbeBusinessResponseSupport() {
    }

    /**
     * 判断响应是否为结构化的任务不存在业务结果。
     *
     * @param body 响应体
     * @return 是否为任务不存在
     */
    static boolean isKnownTaskMissing(String body) {
        JSONObject root = ProbeHttpSupport.parseObject(body);
        if (Objects.isNull(root)) {
            return false;
        }
        JSONObject data = readObject(root, "data");
        if (Objects.nonNull(data) && isMissingCode(data.getString("status"))) {
            return true;
        }
        if (isMissingCode(root.getString("status")) || isMissingCode(root.getString("code"))) {
            return true;
        }
        JSONObject error = readObject(root, "error");
        String errorText = root.get("error") instanceof String text ? text : null;
        if (Objects.nonNull(error)
                && (isMissingCode(error.getString("code")) || isMissingCode(error.getString("type")))) {
            return true;
        }
        if (isMissingMessage(root.getString("detail")) || isMissingMessage(root.getString("message"))) {
            return true;
        }
        if ((Objects.nonNull(error) && isMissingMessage(error.getString("message")))
                || isMissingMessage(errorText)) {
            return true;
        }
        Integer klingCode = readIntegerCode(root);
        return Objects.equals(1203, klingCode) && isMissingMessage(root.getString("message"));
    }

    /**
     * 判断响应是否为可灵官方参数校验业务码。
     *
     * @param body 响应体
     * @return 是否为参数校验结果
     */
    static boolean isKlingParameterValidation(String body) {
        JSONObject root = ProbeHttpSupport.parseObject(body);
        if (Objects.isNull(root)) {
            return false;
        }
        Integer code = readIntegerCode(root);
        return Objects.equals(1200, code) || Objects.equals(1201, code);
    }

    /**
     * 判断响应是否明确表示配置的模型不存在。
     *
     * @param body 响应体
     * @return 是否为模型不存在
     */
    static boolean isKnownModelMissing(String body) {
        JSONObject root = ProbeHttpSupport.parseObject(body);
        if (Objects.isNull(root)) {
            return isModelMissingMessage(body);
        }
        JSONObject error = readObject(root, "error");
        String errorText = root.get("error") instanceof String text ? text : null;
        String code = firstNotBlank(root.getString("code"), root.getString("status"));
        String message = firstNotBlank(root.getString("message"), root.getString("detail"), errorText);
        if (Objects.nonNull(error)) {
            code = firstNotBlank(error.getString("code"), error.getString("type"),
                    error.getString("status"), code);
            message = firstNotBlank(error.getString("message"), error.getString("detail"), message);
        }
        String normalizedCode = normalizeCode(code);
        String normalizedMessage = StrUtil.blankToDefault(message, "").toLowerCase(Locale.ROOT);
        if (Objects.equals("model_not_found", normalizedCode)
                || Objects.equals("modelnotfound", normalizedCode)) {
            return true;
        }
        return StrUtil.isNotBlank(normalizedCode) && MODEL_MISSING_CODES.contains(normalizedCode)
                && isModelMissingMessage(normalizedMessage);
    }

    private static Integer readIntegerCode(JSONObject root) {
        Object value = root.get("code");
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (!(value instanceof String text) || StrUtil.isBlank(text)) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isMissingCode(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        return MISSING_CODES.contains(normalizeCode(value));
    }

    private static boolean isMissingMessage(String value) {
        return StrUtil.isNotBlank(value)
                && MISSING_MESSAGES.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isModelMissingMessage(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("model")
                && (normalized.contains("not found")
                || normalized.contains("does not exist")
                || normalized.contains("not exist"));
    }

    private static String normalizeCode(String value) {
        return StrUtil.isBlank(value) ? null
                : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String firstNotBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static JSONObject readObject(JSONObject root, String key) {
        Object value = root.get(key);
        return value instanceof JSONObject object ? object : null;
    }
}
