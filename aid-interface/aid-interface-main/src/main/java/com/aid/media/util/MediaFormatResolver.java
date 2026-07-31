package com.aid.media.util;

import java.net.URI;
import java.util.Locale;

import cn.hutool.core.util.StrUtil;

/**
 * 媒体地址格式（扩展名）解析工具。
 * 参考音频的格式白名单校验（上传登记时）与格式下发（出片解析时）必须取同一口径，
 * 否则会出现「上传放行、出片被剔除」的错位，故统一收口在此。
 *
 * @author 视觉AID
 */
public final class MediaFormatResolver
{
    private MediaFormatResolver()
    {
    }

    /**
     * 从媒体地址取小写扩展名。
     * 只看路径部分，避免 query 中的点号被误当作扩展名；点号还必须落在最后一级路径内，
     * 否则 {@code /v1.0/audio} 这类目录名带点的地址会把 {@code 0/audio} 当成扩展名。
     *
     * @param url 完整 URL 或相对路径
     * @return 小写扩展名；无扩展名或地址非法返回 null
     */
    public static String resolveFormat(String url)
    {
        if (StrUtil.isBlank(url))
        {
            return null;
        }
        try
        {
            String path = StrUtil.nullToEmpty(URI.create(url).getPath());
            int dot = path.lastIndexOf('.');
            // 点号须在最后一个路径分隔符之后，且不能是结尾字符（末尾点号视为无扩展名）
            if (dot <= path.lastIndexOf('/') || dot == path.length() - 1)
            {
                return null;
            }
            return path.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        catch (IllegalArgumentException ex)
        {
            return null;
        }
    }
}
