package com.aid.config.test.tester;

import java.util.Map;
import java.util.Objects;

import cn.hutool.core.util.StrUtil;

/**
 * Tester 临时配置（payload）读取工具。
 *
 * 统一从 {@code Map<String,Object>} 中按 key 取字符串/布尔/整数值，做空值与类型兜底，
 * 避免各 Tester 重复写强转逻辑。
 *
 * @author 视觉AID
 */
final class TesterPayloads {

    private TesterPayloads() {
    }

    /**
     * 取字符串值，缺失或为 null 时返回空串。
     *
     * @param payload 临时配置
     * @param key     键
     * @return 字符串值（trim 后），永不为 null
     */
    static String str(Map<String, Object> payload, String key) {
        if (payload == null) {
            return "";
        }
        Object value = payload.get(key);
        if (Objects.isNull(value)) {
            return "";
        }
        return StrUtil.trimToEmpty(String.valueOf(value));
    }

    /**
     * 取字符串值，缺失时返回默认值。
     *
     * @param payload      临时配置
     * @param key          键
     * @param defaultValue 默认值
     * @return 字符串值
     */
    static String str(Map<String, Object> payload, String key, String defaultValue) {
        String value = str(payload, key);
        return StrUtil.isBlank(value) ? defaultValue : value;
    }

    /**
     * 取布尔值，缺失或非法时返回默认值。
     *
     * @param payload      临时配置
     * @param key          键
     * @param defaultValue 默认值
     * @return 布尔值
     */
    static boolean bool(Map<String, Object> payload, String key, boolean defaultValue) {
        String value = str(payload, key);
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * 取整数值，缺失或非法时返回默认值。
     *
     * @param payload      临时配置
     * @param key          键
     * @param defaultValue 默认值
     * @return 整数值
     */
    static int integer(Map<String, Object> payload, String key, int defaultValue) {
        String value = str(payload, key);
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
