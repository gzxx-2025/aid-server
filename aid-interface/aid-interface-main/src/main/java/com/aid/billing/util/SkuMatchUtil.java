package com.aid.billing.util;

import java.util.Map;

/**
 * SKU匹配工具：根据命中的条件Map和实际参数Map判断是否匹配。
 * 支持等值匹配和范围匹配（Min/Max后缀的key成对组成区间）。
 */
public final class SkuMatchUtil {

    private SkuMatchUtil() {
    }

    /**
     * 判断实际参数是否满足SKU的命中条件。
     * 所有条件必须同时满足（AND逻辑）。
     *
     * @param matchConditions SKU的match条件（如 {resolution:"720P", durationMin:1, durationMax:5}）
     * @param actualParams    实际请求参数（如 {resolution:"720P", duration:5}）
     * @return true=匹配
     */
    public static boolean isMatch(Map<String, Object> matchConditions, Map<String, Object> actualParams) {
        if (matchConditions == null || matchConditions.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> entry : matchConditions.entrySet()) {
            String key = entry.getKey();
            Object matchValue = entry.getValue();

            // 范围匹配：key以Min/Max结尾时，提取baseKey做区间判断
            if (key.endsWith("Min")) {
                String baseKey = key.substring(0, key.length() - 3);
                String maxKey = baseKey + "Max";
                Object maxVal = matchConditions.get(maxKey);
                if (!checkRange(actualParams.get(baseKey), matchValue, maxVal)) {
                    return false;
                }
                continue;
            }
            if (key.endsWith("Max")) {
                // Max已在Min分支处理过，跳过避免重复判断
                String minKey = key.substring(0, key.length() - 3) + "Min";
                if (matchConditions.containsKey(minKey)) {
                    continue;
                }
            }

            // 等值匹配：忽略大小写。清晰度/生成模式等枚举在不同来源大小写不统一
            // （计费推断产出 720P，Vidu 官方档位是 720p），大小写敏感会导致 SKU 永远落兜底。
            Object actualValue = actualParams.get(key);
            if (!String.valueOf(matchValue).equalsIgnoreCase(String.valueOf(actualValue))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查数值是否在[min, max]区间内。
     */
    private static boolean checkRange(Object actual, Object min, Object max) {
        if (actual == null) {
            return false;
        }
        double actualNum = toDouble(actual);
        if (min != null && actualNum < toDouble(min)) {
            return false;
        }
        if (max != null && actualNum > toDouble(max)) {
            return false;
        }
        return true;
    }

    /**
     * 将Object转为double
     */
    private static double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }
}
