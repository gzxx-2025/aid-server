package com.aid.project.util;

import java.util.Locale;

import cn.hutool.core.util.StrUtil;

/**
 * 公开项目展示媒体比例解析器。
 *
 * @author 视觉AID
 */
public final class PublicMediaMetadataResolver
{
    private static final String FALLBACK_ASPECT_RATIO = "16:9";
    private static final String RATIO_SOURCE_DECLARED = "declared";
    private static final String RATIO_SOURCE_FALLBACK = "fallback";
    private static final double RATIO_TOLERANCE = 0.02D;

    private PublicMediaMetadataResolver()
    {
    }

    /**
     * 将项目声明画幅转换为前端可直接占位的媒体元数据。
     *
     * @param declaredRatio 项目声明画幅
     * @return 展示媒体元数据
     */
    public static Metadata resolve(String declaredRatio)
    {
        if (StrUtil.isBlank(declaredRatio))
        {
            return new Metadata(null, null, FALLBACK_ASPECT_RATIO, RATIO_SOURCE_FALLBACK);
        }
        double ratio = parseRatio(declaredRatio);
        if (!Double.isFinite(ratio) || ratio <= 0D)
        {
            return new Metadata(null, null, FALLBACK_ASPECT_RATIO, RATIO_SOURCE_FALLBACK);
        }
        String normalizedRatio = normalizeRatio(ratio);
        if ("16:9".equals(normalizedRatio)) return declared(1920, 1080, normalizedRatio);
        if ("9:16".equals(normalizedRatio)) return declared(1080, 1920, normalizedRatio);
        if ("1:1".equals(normalizedRatio)) return declared(1080, 1080, normalizedRatio);
        if ("4:3".equals(normalizedRatio)) return declared(1440, 1080, normalizedRatio);
        if ("3:4".equals(normalizedRatio)) return declared(1080, 1440, normalizedRatio);
        if ("21:9".equals(normalizedRatio)) return declared(2520, 1080, normalizedRatio);
        return new Metadata(null, null, FALLBACK_ASPECT_RATIO, RATIO_SOURCE_FALLBACK);
    }

    private static double parseRatio(String raw)
    {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        String[] parts = value.split("[:/x×-]", -1);
        if (parts.length != 2)
        {
            return Double.NaN;
        }
        try
        {
            double width = Double.parseDouble(parts[0].trim());
            double height = Double.parseDouble(parts[1].trim());
            return height <= 0D ? Double.NaN : width / height;
        }
        catch (NumberFormatException e)
        {
            return Double.NaN;
        }
    }

    private static String normalizeRatio(double ratio)
    {
        if (near(ratio, 16D / 9D)) return "16:9";
        if (near(ratio, 9D / 16D)) return "9:16";
        if (near(ratio, 1D)) return "1:1";
        if (near(ratio, 4D / 3D)) return "4:3";
        if (near(ratio, 3D / 4D)) return "3:4";
        if (near(ratio, 21D / 9D)) return "21:9";
        return "other";
    }

    private static Metadata declared(int width, int height, String aspectRatio)
    {
        return new Metadata(width, height, aspectRatio, RATIO_SOURCE_DECLARED);
    }

    private static boolean near(double actual, double expected)
    {
        return Math.abs(actual - expected) < RATIO_TOLERANCE;
    }

    /**
     * 公开展示媒体元数据。
     */
    public record Metadata(Integer mediaWidth, Integer mediaHeight, String aspectRatio, String ratioSource)
    {
    }
}
