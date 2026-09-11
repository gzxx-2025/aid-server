package com.aid.billing.util;

import java.util.Collection;
import java.util.Map;
import java.math.BigDecimal;

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
        if (actualParams == null) return false;
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
                if (!checkRange(actualParams.get(key.substring(0, key.length() - 3)), null, matchValue)) {
                    return false;
                }
                continue;
            }

            // 等值匹配：忽略大小写。清晰度/生成模式等枚举在不同来源大小写不统一
            // （计费推断产出 720P，Vidu 官方档位是 720p），大小写敏感会导致 SKU 永远落兜底。
            Object actualValue = actualParams.get(key);
            if (!matchesValue(matchValue, actualValue)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 等值条件支持单值及枚举集合，集合中任一值命中即可。
     */
    private static boolean matchesValue(Object matchValue, Object actualValue) {
        if (matchValue instanceof Collection<?> candidates) {
            return candidates.stream().anyMatch(candidate -> equalsIgnoreCase(candidate, actualValue));
        }
        return equalsIgnoreCase(matchValue, actualValue);
    }

    private static boolean equalsIgnoreCase(Object expected, Object actual) {
        if (expected == null || actual == null) return false;
        if (expected instanceof Number || actual instanceof Number) {
            try { return new BigDecimal(String.valueOf(expected)).compareTo(new BigDecimal(String.valueOf(actual))) == 0; }
            catch (NumberFormatException ex) { return false; }
        }
        return String.valueOf(expected).equalsIgnoreCase(String.valueOf(actual));
    }

    /**
     * 检查数值是否在[min, max]区间内。
     */
    private static boolean checkRange(Object actual, Object min, Object max) {
        if (actual == null) {
            return false;
        }
        try {
            BigDecimal actualNum = new BigDecimal(String.valueOf(actual));
            if (min != null && actualNum.compareTo(new BigDecimal(String.valueOf(min))) < 0) {
                return false;
            }
            if (max != null && actualNum.compareTo(new BigDecimal(String.valueOf(max))) > 0) {
                return false;
            }
            return true;
        } catch (NumberFormatException ex) {
            // NaN、Infinity 和非数值参数均不命中计费档位，不允许绕过区间。
            return false;
        }
    }
}
