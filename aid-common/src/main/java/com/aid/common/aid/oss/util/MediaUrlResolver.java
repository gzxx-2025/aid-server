package com.aid.common.aid.oss.util;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.aid.oss.properties.OssProperties;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;

/**
 * 媒体URL读取侧统一拼接器。
 *
 * @author 视觉AID
 */
@Component
@RequiredArgsConstructor
public class MediaUrlResolver
{
    /** 本地资源前缀（与 Constants.RESOURCE_PREFIX 对齐） */
    private static final String LOCAL_PREFIX = "/profile";

    /** 本地上传模式标识 */
    private static final String UPLOAD_MODE_LOCAL = "local";

    /** 腾讯云 COS 上传模式标识 */
    private static final String UPLOAD_MODE_COS = "cos";

    /** OSS 配置管理器 */
    private final OssConfigManager ossConfigManager;

    /**
     * 将 DB 存的相对路径拼成完整 URL 返回给前端。
     *
     * @param path DB 中存储的相对路径或完整 URL
     * @return 完整可访问 URL；无法拼接时返回原值
     */
    public String toFullUrl(String path)
    {
        if (StrUtil.isBlank(path))
        {
            return path;
        }
        String lower = path.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://"))
        {
            return path;
        }
        OssProperties properties = ossConfigManager.getOssProperties();
        if (path.startsWith(LOCAL_PREFIX))
        {
            String localDomain = Objects.isNull(properties) ? null : properties.getLocalDomain();
            if (StrUtil.isBlank(localDomain))
            {
                return path;
            }
            return stripTrailingSlash(localDomain) + path;
        }
        if (path.startsWith("/"))
        {
            String cdnDomain = Objects.isNull(properties) ? null : properties.getEffectiveCdnDomain();
            if (StrUtil.isBlank(cdnDomain))
            {
                return path;
            }
            return stripTrailingSlash(cdnDomain) + path;
        }
        String cdnDomain = Objects.isNull(properties) ? null : properties.getEffectiveCdnDomain();
        if (StrUtil.isBlank(cdnDomain))
        {
            return "/" + path;
        }
        return stripTrailingSlash(cdnDomain) + "/" + path;
    }

    /**
     * 批量拼接
     *
     * @param paths 相对路径列表
     * @return 完整 URL 列表
     */
    public List<String> toFullUrls(List<String> paths)
    {
        if (CollectionUtil.isEmpty(paths))
        {
            return paths;
        }
        return paths.stream().map(this::toFullUrl).collect(Collectors.toList());
    }

    /**
     * 去除域名末尾多余的 `/`，避免拼接后出现 `//`
     *
     * @param domain 域名
     * @return 规范化后的域名
     */
    private String stripTrailingSlash(String domain)
    {
        return domain.endsWith("/") ? domain.substring(0, domain.length() - 1) : domain;
    }

    /**
     * 判断是否为「本站图片资源」（仅本站放行，用于拒绝站外外链 / 盗图 / 绕过 OSS）。
     *
     * @param url 待校验的图片 URL（相对路径或完整 URL）
     * @return 是否本站资源
     */
    public boolean isSiteImageUrl(String url)
    {
        if (StrUtil.isBlank(url))
        {
            return false;
        }
        // 拒绝内嵌空白，防止 "https://x x.png" 之类构造
        for (int i = 0; i < url.length(); i++)
        {
            if (Character.isWhitespace(url.charAt(i)))
            {
                return false;
            }
        }
        String lower = url.toLowerCase();
        // 完整 URL：域名须与当前上传模式配置的本站域名一致，否则按站外外链拒绝
        if (lower.startsWith("http://") || lower.startsWith("https://"))
        {
            return matchesConfiguredDomain(url);
        }
        // 相对路径：必须以单个 / 开头，拒绝协议相对(//、/\)与路径穿越(..)
        return url.startsWith("/")
                && !url.startsWith("//")
                && !url.startsWith("/\\")
                && !url.contains("..");
    }

    /**
     * 完整 URL 的域名是否与当前上传模式配置的本站域名一致：
     * local 模式比 localDomain，其余（含默认 oss）比 cdnDomain。
     *
     * @param url 完整 http(s) URL
     * @return 是否匹配本站域名
     */
    private boolean matchesConfiguredDomain(String url)
    {
        OssProperties properties = ossConfigManager.getOssProperties();
        if (Objects.isNull(properties))
        {
            return false;
        }
        // 本地模式比 localDomain，其余（含 oss / cos）比生效的 CDN 域名
        String siteDomain = UPLOAD_MODE_LOCAL.equalsIgnoreCase(properties.getUploadMode())
                ? properties.getLocalDomain()
                : properties.getEffectiveCdnDomain();
        if (StrUtil.isNotBlank(siteDomain) && url.startsWith(stripTrailingSlash(siteDomain)))
        {
            return true;
        }
        // 本站域名未命中时，再比配置的图片URL域名白名单（逗号分隔的可信外部域名前缀）
        return matchesWhitelist(url, properties.getImageUrlWhitelist());
    }

    /**
     * 完整 URL 是否命中图片域名白名单（逗号分隔，按前缀匹配，大小写不敏感）。
     *
     * @param url       完整 http(s) URL
     * @param whitelist 逗号分隔的白名单域名前缀，可空
     * @return 是否命中白名单
     */
    private boolean matchesWhitelist(String url, String whitelist)
    {
        if (StrUtil.isBlank(whitelist))
        {
            return false;
        }
        String lowerUrl = url.toLowerCase();
        // 逐个比对白名单前缀：去空白、去末尾 /、忽略空项
        for (String entry : whitelist.split(","))
        {
            String prefix = stripTrailingSlash(StrUtil.trim(entry)).toLowerCase();
            if (StrUtil.isNotBlank(prefix) && lowerUrl.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 完整本站 URL → 相对路径（剥掉配置域名后入库）；已是相对路径或非本站则原样返回。
     *
     * @param url 完整 URL 或相对路径
     * @return 相对路径（以 / 开头）或原值
     */
    public String toRelativePath(String url)
    {
        if (StrUtil.isBlank(url))
        {
            return url;
        }
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://"))
        {
            // 已是相对路径，原样返回
            return url;
        }
        OssProperties properties = ossConfigManager.getOssProperties();
        if (Objects.isNull(properties))
        {
            return url;
        }
        // 优先剥生效的 CDN 域名（oss=cdnDomain / cos=cosCdnDomain），再尝试 localDomain，匹配到则截取剩余相对路径
        String cdnDomain = StrUtil.isBlank(properties.getEffectiveCdnDomain()) ? null : stripTrailingSlash(properties.getEffectiveCdnDomain());
        if (StrUtil.isNotBlank(cdnDomain) && url.startsWith(cdnDomain))
        {
            String rest = url.substring(cdnDomain.length());
            return rest.startsWith("/") ? rest : "/" + rest;
        }
        String localDomain = StrUtil.isBlank(properties.getLocalDomain()) ? null : stripTrailingSlash(properties.getLocalDomain());
        if (StrUtil.isNotBlank(localDomain) && url.startsWith(localDomain))
        {
            String rest = url.substring(localDomain.length());
            return rest.startsWith("/") ? rest : "/" + rest;
        }
        // 域名不在配置中（如外链），保留原样
        return url;
    }

    /**
     * 生成「下发给上游厂商」的素材地址（如腾讯云 MPS 合成、图生图/图生视频参考图）。
     *
     * @param fullUrl 已拼好的完整可访问 URL（相对路径 / 空值原样返回）
     * @return 下发上游用的地址
     */
    public String toProviderUrl(String fullUrl)
    {
        if (StrUtil.isBlank(fullUrl))
        {
            return fullUrl;
        }
        // 非完整 URL（相对路径）不在此处理，交由调用方先 toFullUrl
        String lower = fullUrl.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://"))
        {
            return fullUrl;
        }
        OssProperties properties = ossConfigManager.getOssProperties();
        if (Objects.isNull(properties))
        {
            return fullUrl;
        }
        // 仅 COS 模式才做源站改写，其它模式直接用传入地址
        if (!UPLOAD_MODE_COS.equalsIgnoreCase(properties.getUploadMode()))
        {
            return fullUrl;
        }
        String cosEndpoint = properties.getCosCdnDomain();
        String cdnDomain = properties.getCdnDomain();
        // 缺少 endpoint 或 CDN 域名配置时，回退为原 CDN 地址下发
        if (StrUtil.isBlank(cosEndpoint) || StrUtil.isBlank(cdnDomain))
        {
            return fullUrl;
        }
        String urlHost = extractHost(fullUrl);
        String cdnHost = extractHost(cdnDomain);
        // 外链或非本站 CDN 域名（主域名不一致）原样返回
        if (StrUtil.isBlank(urlHost) || StrUtil.isBlank(cdnHost) || !urlHost.equalsIgnoreCase(cdnHost))
        {
            return fullUrl;
        }
        // 命中本站 CDN + COS 模式：用 COS 源站 endpoint + 原路径（含 query）重拼
        String pathAndQuery = extractPathAndQuery(fullUrl);
        String base = cosEndpoint.contains("://") ? cosEndpoint : "https://" + cosEndpoint;
        return stripTrailingSlash(base) + pathAndQuery;
    }

    /**
     * 提取 URL 主机名（host），支持带或不带 scheme 的域名配置；解析失败返回空串。
     *
     * @param url URL 或域名
     * @return 主机名
     */
    private String extractHost(String url)
    {
        try
        {
            String fixed = url.contains("://") ? url : "https://" + url;
            return URI.create(fixed).getHost();
        }
        catch (Exception e)
        {
            return "";
        }
    }

    /**
     * 提取完整 URL 的路径（含 query），保持原始编码；解析失败返回 "/"。
     *
     * @param url 完整 URL
     * @return 以 / 起始的路径（含 ?query）
     */
    private String extractPathAndQuery(String url)
    {
        try
        {
            URI uri = URI.create(url);
            String path = StrUtil.isBlank(uri.getRawPath()) ? "/" : uri.getRawPath();
            String query = uri.getRawQuery();
            return StrUtil.isBlank(query) ? path : path + "?" + query;
        }
        catch (Exception e)
        {
            return "/";
        }
    }
}
