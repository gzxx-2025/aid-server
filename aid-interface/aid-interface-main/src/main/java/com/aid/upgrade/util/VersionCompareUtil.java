package com.aid.upgrade.util;

import cn.hutool.core.util.StrUtil;

/**
 * 轻量语义化版本比较工具
 *
 * @author 视觉AID
 */
public final class VersionCompareUtil {

    private VersionCompareUtil() {
    }

    /**
     * 判断远端版本是否比当前版本新
     *
     * @param remote  远端版本号
     * @param current 当前版本号
     * @return true=远端更新
     */
    public static boolean isNewer(String remote, String current) {
        if (StrUtil.isBlank(remote) || StrUtil.isBlank(current)) {
            return false;
        }
        return compare(normalize(remote), normalize(current)) > 0;
    }

    /**
     * 去掉版本号前缀 v/V 与首尾空白
     */
    private static String normalize(String version) {
        String trimmed = version.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            return trimmed.substring(1);
        }
        return trimmed;
    }

    /**
     * 比较两个版本号：主版本逐段数值比较，带预发布后缀的版本低于同主版本的正式版
     */
    private static int compare(String left, String right) {
        String[] leftParts = left.split("-", 2);
        String[] rightParts = right.split("-", 2);
        int mainCompare = compareDotted(leftParts[0], rightParts[0]);
        if (mainCompare != 0) {
            return mainCompare;
        }
        boolean leftPre = leftParts.length > 1;
        boolean rightPre = rightParts.length > 1;
        if (leftPre && !rightPre) {
            return -1;
        }
        if (!leftPre && rightPre) {
            return 1;
        }
        if (!leftPre) {
            return 0;
        }
        return compareDotted(leftParts[1], rightParts[1]);
    }

    /**
     * 逐段比较点分字符串，纯数字段按数值比较
     */
    private static int compareDotted(String left, String right) {
        String[] leftSegments = left.split("\\.");
        String[] rightSegments = right.split("\\.");
        int max = Math.max(leftSegments.length, rightSegments.length);
        for (int i = 0; i < max; i++) {
            String x = i < leftSegments.length ? leftSegments[i] : "";
            String y = i < rightSegments.length ? rightSegments[i] : "";
            boolean xNumeric = isNumeric(x);
            boolean yNumeric = isNumeric(y);
            int result;
            if (xNumeric && yNumeric) {
                result = Long.compare(Long.parseLong(x), Long.parseLong(y));
            } else if (xNumeric) {
                result = 1;
            } else if (yNumeric) {
                result = -1;
            } else {
                result = x.compareTo(y);
            }
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static boolean isNumeric(String value) {
        if (StrUtil.isBlank(value) || value.length() > 18) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
