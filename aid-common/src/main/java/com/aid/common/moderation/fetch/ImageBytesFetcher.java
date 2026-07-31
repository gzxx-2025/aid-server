package com.aid.common.moderation.fetch;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.common.config.AidAppConfig;
import com.aid.common.constant.Constants;
import com.aid.common.moderation.config.ImageModerationConfigManager;
import com.aid.common.moderation.exception.ImageModerationException;
import com.aid.common.moderation.properties.ImageModerationProperties;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 图片字节获取器
 * - 按 uploadMode 选择最优下载路径：内网优先，省外网流量；失败自动降级公网
 * - COS 模式且 prioritizeFileUrl=true 时直接交给 IMS 拉取（返回 useUrl=true）
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageBytesFetcher
{
    /**
     * 图片字节大小上限：10MB（IMS Base64 限制）
     */
    private static final int MAX_BYTES = 10 * 1024 * 1024;

    /**
     * 连接超时（毫秒）
     */
    private static final int CONNECT_TIMEOUT_MS = 3000;

    /**
     * 读取超时（毫秒）
     */
    private static final int READ_TIMEOUT_MS = 5000;

    /**
     * OSS 配置管理器
     */
    private final OssConfigManager ossConfigManager;

    /**
     * 图片审查配置管理器
     */
    private final ImageModerationConfigManager imageModerationConfigManager;

    /**
     * 字节获取结果
     */
    @Data
    public static class FetchOutcome
    {
        /**
         * 是否使用 URL 模式（交给 IMS 自行拉取，不下载字节）
         */
        private boolean useUrl;

        /**
         * URL 模式下的图片 URL
         */
        private String url;

        /**
         * 字节模式下的图片字节
         */
        private byte[] bytes;

        /**
         * 构造 URL 模式结果
         *
         * @param url 图片 URL
         * @return 结果
         */
        public static FetchOutcome ofUrl(String url)
        {
            FetchOutcome outcome = new FetchOutcome();
            outcome.setUseUrl(true);
            outcome.setUrl(url);
            return outcome;
        }

        /**
         * 构造字节模式结果
         *
         * @param bytes 图片字节
         * @return 结果
         */
        public static FetchOutcome ofBytes(byte[] bytes)
        {
            FetchOutcome outcome = new FetchOutcome();
            outcome.setUseUrl(false);
            outcome.setBytes(bytes);
            return outcome;
        }
    }

    /**
     * 解析图片 URL 为审查所需的来源（URL 或字节）
     *
     * @param fileUrl 图片 URL 或相对路径
     * @return 获取结果
     */
    public FetchOutcome resolve(String fileUrl)
    {
        if (StrUtil.isBlank(fileUrl))
        {
            log.error("图片字节获取失败：fileUrl 为空");
            throw new ImageModerationException("图片地址为空");
        }

        OssProperties oss = ossConfigManager.getOssProperties();
        String mode = Objects.isNull(oss) ? "oss" : oss.getUploadMode();

        // COS 模式且优先 FileUrl：直接交给 IMS 拉取
        if ("cos".equalsIgnoreCase(mode) && isPrioritizeFileUrl())
        {
            return FetchOutcome.ofUrl(fileUrl);
        }

        // COS 模式：走内网域名下载
        if ("cos".equalsIgnoreCase(mode))
        {
            byte[] bytes = cosInternalGet(fileUrl, oss);
            return FetchOutcome.ofBytes(bytes);
        }

        // OSS 模式：走内网域名下载
        if ("oss".equalsIgnoreCase(mode))
        {
            byte[] bytes = ossInternalGet(fileUrl, oss);
            return FetchOutcome.ofBytes(bytes);
        }

        // 本地模式：读本地磁盘
        if ("local".equalsIgnoreCase(mode))
        {
            byte[] bytes = readLocalFile(fileUrl, oss);
            return FetchOutcome.ofBytes(bytes);
        }

        // 兜底：公网下载
        return FetchOutcome.ofBytes(publicGet(fileUrl));
    }

    /**
     * 是否优先使用 FileUrl
     *
     * @return true=优先 FileUrl
     */
    private boolean isPrioritizeFileUrl()
    {
        ImageModerationProperties props = imageModerationConfigManager.getProperties();
        return Objects.nonNull(props) && props.isPrioritizeFileUrl();
    }

    /**
     * COS 内网下载：将 host 换成 cos-internal.{region}.myqcloud.com，失败降级公网
     *
     * @param fileUrl 公网/CDN URL
     * @param oss     OSS 配置
     * @return 图片字节
     */
    private byte[] cosInternalGet(String fileUrl, OssProperties oss)
    {
        String region = Objects.isNull(oss) ? null : oss.getCosRegion();
        if (StrUtil.isNotBlank(region))
        {
            String internalHost = "cos-internal." + region + ".myqcloud.com";
            String internalUrl = replaceHost(fileUrl, internalHost);
            if (StrUtil.isNotBlank(internalUrl))
            {
                try
                {
                    return httpGet(internalUrl);
                }
                catch (Exception e)
                {
                    // 内网下载失败降级公网
                    log.warn("COS 内网下载失败，降级公网, url={}, error={}", internalUrl, e.getMessage());
                }
            }
        }
        return publicGet(fileUrl);
    }

    /**
     * OSS 内网下载：将 host 中的 endpoint 换成内网域名（追加 -internal），失败降级公网
     *
     * @param fileUrl 公网/CDN URL
     * @param oss     OSS 配置
     * @return 图片字节
     */
    private byte[] ossInternalGet(String fileUrl, OssProperties oss)
    {
        String host = extractHost(fileUrl);
        // 仅对阿里云 OSS 域名做内网改写，CDN/自定义域名直接走公网
        if (StrUtil.isNotBlank(host) && host.contains(".aliyuncs.com") && !host.contains("-internal."))
        {
            // 形如 oss-cn-shanghai.aliyuncs.com -> oss-cn-shanghai-internal.aliyuncs.com
            String internalHost = host.replace(".aliyuncs.com", "-internal.aliyuncs.com");
            String internalUrl = replaceHost(fileUrl, internalHost);
            if (StrUtil.isNotBlank(internalUrl))
            {
                try
                {
                    return httpGet(internalUrl);
                }
                catch (Exception e)
                {
                    // 内网下载失败降级公网
                    log.warn("OSS 内网下载失败，降级公网, url={}, error={}", internalUrl, e.getMessage());
                }
            }
        }
        return publicGet(fileUrl);
    }

    /**
     * 读取本地磁盘文件：参考 OssTemplate 的相对路径→物理路径换算（只读不删），失败降级公网
     *
     * @param fileUrl 本地 URL 或相对路径
     * @param oss     OSS 配置
     * @return 图片字节
     */
    private byte[] readLocalFile(String fileUrl, OssProperties oss)
    {
        try
        {
            String normalized = normalizeLocalFileUrl(fileUrl, oss);
            // 必须是 /profile 开头的相对路径，且不含 ..
            if (StrUtil.isNotBlank(normalized)
                    && normalized.startsWith(Constants.RESOURCE_PREFIX)
                    && !normalized.contains(".."))
            {
                Path baseDir = Paths.get(AidAppConfig.getProfile()).toAbsolutePath().normalize();
                String localPath = AidAppConfig.getProfile() + normalized.replace(Constants.RESOURCE_PREFIX, "");
                Path filePath = Paths.get(localPath).toAbsolutePath().normalize();
                // 防路径穿越：物理路径必须在 profile 之内
                if (filePath.startsWith(baseDir) && Files.exists(filePath))
                {
                    byte[] bytes = Files.readAllBytes(filePath);
                    checkSize(bytes);
                    return bytes;
                }
                log.warn("本地图片不存在或路径非法，降级公网, fileUrl={}", fileUrl);
            }
        }
        catch (ImageModerationException e)
        {
            // 大小超限直接抛出
            throw e;
        }
        catch (Exception e)
        {
            log.warn("本地图片读取失败，降级公网, fileUrl={}, error={}", fileUrl, e.getMessage());
        }
        return publicGet(fileUrl);
    }

    /**
     * 公网下载
     *
     * @param fileUrl 图片 URL
     * @return 图片字节
     */
    private byte[] publicGet(String fileUrl)
    {
        try
        {
            return httpGet(fileUrl);
        }
        catch (Exception e)
        {
            // 公网下载失败无法继续审查
            log.error("公网下载图片失败, url={}, error={}", fileUrl, e.getMessage(), e);
            throw new ImageModerationException("图片下载失败", e);
        }
    }

    /**
     * 执行 HTTP GET 并返回字节，附带大小校验
     *
     * @param url 请求地址
     * @return 字节内容
     */
    private byte[] httpGet(String url)
    {
        try (HttpResponse response = HttpRequest.get(url)
                .setConnectionTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS)
                .execute())
        {
            if (!response.isOk())
            {
                throw new ImageModerationException("图片下载状态异常:" + response.getStatus());
            }
            byte[] bytes = response.bodyBytes();
            checkSize(bytes);
            return bytes;
        }
    }

    /**
     * 字节大小校验：超过 10MB 抛出异常
     *
     * @param bytes 字节内容
     */
    private void checkSize(byte[] bytes)
    {
        if (Objects.isNull(bytes) || bytes.length == 0)
        {
            log.error("下载到的图片字节为空");
            throw new ImageModerationException("图片内容为空");
        }
        if (bytes.length > MAX_BYTES)
        {
            log.error("图片字节超过上限, size={}B", bytes.length);
            throw new ImageModerationException("图片过大");
        }
    }

    /**
     * 替换 URL 的 host 为指定内网 host，保留 scheme/path/query
     *
     * @param fileUrl  原始 URL
     * @param newHost  新 host
     * @return 替换后的 URL，解析失败返回 null
     */
    private String replaceHost(String fileUrl, String newHost)
    {
        try
        {
            URI uri = URI.create(fileUrl);
            String scheme = StrUtil.isBlank(uri.getScheme()) ? "https" : uri.getScheme();
            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://").append(newHost);
            if (StrUtil.isNotBlank(uri.getRawPath()))
            {
                sb.append(uri.getRawPath());
            }
            if (StrUtil.isNotBlank(uri.getRawQuery()))
            {
                sb.append("?").append(uri.getRawQuery());
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            // 解析失败由调用方降级公网
            log.warn("内网域名拼装失败, fileUrl={}, error={}", fileUrl, e.getMessage());
            return null;
        }
    }

    /**
     * 提取 URL 的 host
     *
     * @param fileUrl URL
     * @return host，解析失败返回 null
     */
    private String extractHost(String fileUrl)
    {
        try
        {
            return URI.create(fileUrl).getHost();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * 标准化本地文件 URL：去掉 localDomain 前缀，得到 /profile 开头的相对路径
     * 参考 OssTemplate 的同名逻辑（只读用途）。
     *
     * @param fileUrl 本地 URL 或相对路径
     * @param oss     OSS 配置
     * @return 相对路径
     */
    private String normalizeLocalFileUrl(String fileUrl, OssProperties oss)
    {
        if (StrUtil.isBlank(fileUrl))
        {
            return fileUrl;
        }
        // 本身就是相对路径
        if (fileUrl.startsWith(Constants.RESOURCE_PREFIX))
        {
            return fileUrl;
        }
        String localDomain = Objects.isNull(oss) ? null : oss.getLocalDomain();
        if (StrUtil.isBlank(localDomain))
        {
            return fileUrl;
        }
        String domain = localDomain.endsWith("/") ? localDomain.substring(0, localDomain.length() - 1) : localDomain;
        // 必须以 domain 开头，且紧跟 '/'，避免前缀误匹配
        if (fileUrl.length() > domain.length()
                && fileUrl.startsWith(domain)
                && fileUrl.charAt(domain.length()) == '/')
        {
            return fileUrl.substring(domain.length());
        }
        return fileUrl;
    }
}
