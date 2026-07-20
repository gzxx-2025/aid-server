package com.aid.billing.util;

import java.util.Map;

/**
 * 分辨率解析工具：将图片/视频的尺寸字符串转为统一的分辨率等级。
 */
public final class ResolutionUtil {

    private ResolutionUtil() {
    }

    /** 常见尺寸到分辨率等级的映射（宽高任意一边达到阈值即归入对应等级） */
    private static final int THRESHOLD_2K = 2048;
    private static final int THRESHOLD_1K = 1024;

    /**
     * 从尺寸字符串解析分辨率等级。
     * 格式如 "1024*1024" 或 "1024x1024"。
     *
     * @param size 尺寸字符串
     * @return 分辨率等级：4K / 2K / 1K / SD
     */
    public static String parseResolution(String size) {
        if (size == null || size.isBlank()) {
            return "1K";
        }
        // 统一分隔符
        String normalized = size.toLowerCase().replace('x', '*');
        String[] parts = normalized.split("\\*");
        if (parts.length < 2) {
            return "1K";
        }
        try {
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            int maxDim = Math.max(w, h);
            if (maxDim >= THRESHOLD_2K * 2) {
                return "4K";
            }
            if (maxDim >= THRESHOLD_2K) {
                return "2K";
            }
            if (maxDim >= THRESHOLD_1K) {
                return "1K";
            }
            return "SD";
        } catch (NumberFormatException e) {
            return "1K";
        }
    }

    /**
     * 识别「档位式」尺寸串：业务层习惯把清晰度档位直接放 size 字段（如 "1080p"/"2K"/"4K"）。
     * 仅当 size 不含宽高分隔符且形如 NNNp / NK 时返回规范化档位：
     * p 结尾统一小写 p、K 结尾统一大写 K（与 Vidu 官方枚举 1080p/2K/4K 及图片 SKU match 值对齐）。
     * 非档位形态返回 null，由调用方回退尺寸推断。
     *
     * @param size 尺寸字符串（如 "2K" 或 "1024*1024"）
     * @return 规范化档位（如 "2K"/"1080p"），非档位返回 null
     */
    public static String parseTier(String size) {
        if (size == null || size.isBlank()) {
            return null;
        }
        String value = size.trim();
        // 含宽高分隔符（* / x / :）的是尺寸串，不是档位
        if (value.contains("*") || value.toLowerCase().contains("x") || value.contains(":")) {
            return null;
        }
        // NNNp / NNNNp 形态：720p、1080p 等，统一小写 p
        if (value.matches("(?i)\\d{3,4}p")) {
            return value.substring(0, value.length() - 1) + "p";
        }
        // NK 形态：2K、4K 等，统一大写 K
        if (value.matches("(?i)\\d{1,2}k")) {
            return value.substring(0, value.length() - 1) + "K";
        }
        return null;
    }

    /**
     * 从尺寸字符串提取宽高比。
     *
     * @param size 尺寸字符串（如 "1024*1024"）
     * @return 宽高比字符串（如 "1:1"），无法解析返回 null
     */
    public static String parseRatio(String size) {
        if (size == null || size.isBlank()) {
            return null;
        }
        String normalized = size.toLowerCase().replace('x', '*');
        String[] parts = normalized.split("\\*");
        if (parts.length < 2) {
            return null;
        }
        try {
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            int gcd = gcd(w, h);
            return (w / gcd) + ":" + (h / gcd);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 根据视频的宽高比推断分辨率等级。
     */
    public static String inferVideoResolution(String aspectRatio) {
        // 视频默认返回 720p，后续可通过 options.resolution 传入精确值
        return "720p";
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
