package com.aid.common.utils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

import cn.hutool.core.util.StrUtil;

/**
 * API基础网关地址校验工具。
 *
 * @author 视觉AID
 */
public final class GatewayUrlUtils
{
    private static final String HTTP_SCHEME = "http";
    private static final String HTTPS_SCHEME = "https";
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    private GatewayUrlUtils()
    {
    }

    /**
     * 判断地址是否只包含协议、主机和可选端口。
     *
     * @param baseUrl API基础网关地址
     * @return 是否为基础网关地址
     */
    public static boolean isBaseGatewayUrl(String baseUrl)
    {
        if (StrUtil.isBlank(baseUrl))
        {
            return false;
        }
        try
        {
            URI uri = new URI(baseUrl.trim());
            String scheme = StrUtil.nullToEmpty(uri.getScheme()).toLowerCase(Locale.ROOT);
            boolean supportedScheme = Objects.equals(HTTP_SCHEME, scheme) || Objects.equals(HTTPS_SCHEME, scheme);
            String rawPath = uri.getRawPath();
            int port = uri.getPort();
            boolean basePath = StrUtil.isBlank(rawPath) || Objects.equals("/", rawPath);
            boolean validPort = port < 0 || (port >= MIN_PORT && port <= MAX_PORT);
            return supportedScheme
                    && StrUtil.isNotBlank(uri.getHost())
                    && Objects.isNull(uri.getRawUserInfo())
                    && validPort
                    && basePath
                    && Objects.isNull(uri.getRawQuery())
                    && Objects.isNull(uri.getRawFragment());
        }
        catch (URISyntaxException ex)
        {
            return false;
        }
    }

    /**
     * 规范化基础网关地址。
     *
     * @param baseUrl API基础网关地址
     * @return 去除首尾空格和结尾斜杠的地址
     */
    public static String normalizeBaseGatewayUrl(String baseUrl)
    {
        String normalized = StrUtil.trimToEmpty(baseUrl);
        if (normalized.endsWith("/"))
        {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
