package com.aid.common.utils.image;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.hutool.core.util.StrUtil;

/**
 * 图片 URL 校验工具（大模型参考图场景）。
 *
 * @author 视觉AID
 */
public final class ImageUrlValidator
{
    private static final Logger log = LoggerFactory.getLogger(ImageUrlValidator.class);

    /** 允许的协议（仅 http / https） */
    private static final String SCHEME_HTTP = "http";
    private static final String SCHEME_HTTPS = "https";

    /** 合法图片 Content-Type 前缀 */
    private static final String IMAGE_PREFIX = "image/";

    /** 默认连接超时（毫秒）：服务端预校验参考图，控制在 3s 内，避免阻塞大模型调用 */
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 3000;
    /** 默认读超时（毫秒）：HEAD 只取响应头，4s 足够；GET 降级同值（不整流下载） */
    private static final int DEFAULT_READ_TIMEOUT_MS = 4000;

    /** 默认 User-Agent：伪装成常见浏览器，避免部分 CDN 对非浏览器 UA 直接 403 */
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (compatible; AID-ImageUrlValidator/1.0)";

    /** HEAD 不支持时常见的响应码：部分 CDN 返 405，少数返 400 / 501 */
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;
    private static final int HTTP_NOT_IMPLEMENTED = 501;

    /** 工具类不允许实例化 */
    private ImageUrlValidator()
    {
    }
    /**
     * 仅校验 URL 格式 / 协议 / host，不发远程请求。
     *
     * @param url 待校验的远程图片 URL
     * @return 格式合法返回 true；否则 false
     */
    public static boolean isValidImageUrl(String url)
    {
        return validateImageUrlFormat(url).isValid();
    }

    /**
     * 强校验：格式合法 + HEAD/GET 能拿到 2xx + Content-Type 是 image/*。
     *
     * @param url 待校验的远程图片 URL
     * @return 通过返回 true；其他情况返回 false
     */
    public static boolean isValidRemoteImageUrl(String url)
    {
        return validateRemoteImageUrl(url).isValid();
    }
    /**
     * 轻校验：只看 URL 字符串本身，不发网络请求。
     * 适合高频预检，或在 DTO 校验阶段使用。
     */
    public static ImageUrlValidationResult validateImageUrlFormat(String url)
    {
        if (StrUtil.isBlank(url))
        {
            return ImageUrlValidationResult.fail(ImageUrlValidationCode.EMPTY_URL);
        }
        URI uri;
        try
        {
            uri = new URI(url.trim());
        }
        catch (URISyntaxException e)
        {
            return ImageUrlValidationResult.fail(ImageUrlValidationCode.INVALID_URL);
        }
        String scheme = uri.getScheme();
        if (StrUtil.isBlank(scheme))
        {
            return ImageUrlValidationResult.fail(ImageUrlValidationCode.UNSUPPORTED_PROTOCOL);
        }
        String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        if (!Objects.equals(SCHEME_HTTP, lowerScheme) && !Objects.equals(SCHEME_HTTPS, lowerScheme))
        {
            return ImageUrlValidationResult.fail(ImageUrlValidationCode.UNSUPPORTED_PROTOCOL);
        }
        if (StrUtil.isBlank(uri.getHost()))
        {
            return ImageUrlValidationResult.fail(ImageUrlValidationCode.MISSING_HOST);
        }
        if (isPrivateOrMetadataHost(uri.getHost()))
        {
            return ImageUrlValidationResult.fail(ImageUrlValidationCode.PRIVATE_ADDRESS);
        }
        return ImageUrlValidationResult.ok(null, null, false);
    }

    /**
     * 判断 host 是否解析到私有/回环/链路本地/云 metadata 地址。
     * 防止攻击者通过 "http://169.254.169.254/..." 等 URL 代替系统发起内网请求。
     */
    private static boolean isPrivateOrMetadataHost(String host)
    {
        try
        {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses)
            {
                if (addr.isAnyLocalAddress()
                        || addr.isLoopbackAddress()
                        || addr.isSiteLocalAddress()
                        || addr.isLinkLocalAddress()
                        || addr.isMulticastAddress())
                {
                    return true;
                }
                // 云厂商 metadata 服务地址：169.254.169.254 已由 linkLocal 命中；
                // 再显式兜底检查字符串，兼容 IPv4 字面量异常解析。
                String ip = addr.getHostAddress();
                if (ip != null && (ip.startsWith("169.254.") || ip.startsWith("127.") || ip.equals("::1")))
                {
                    return true;
                }
            }
            return false;
        }
        catch (UnknownHostException e)
        {
            // host 无法解析也当非法处理，不冒险
            log.info("图片URL host DNS 解析失败: {}", host);
            return true;
        }
    }

    /**
     * 强校验：先做 {@link #validateImageUrlFormat(String)}，再发 HEAD 请求；
     * 如果目标返回 405 / 400 / 501 等"HEAD 不支持"的状态，自动降级到 GET。
     * GET 降级只会建立连接并读取响应头（拿 status + Content-Type），
     * 响应体靠 {@link HttpURLConnection#disconnect()} 在 finally 释放，不做整流下载。
     */
    public static ImageUrlValidationResult validateRemoteImageUrl(String url)
    {
        return validateRemoteImageUrl(url, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    /**
     * 带自定义超时的强校验变体（单位：毫秒）。
     * 超时值必须为正数；&le; 0 视为使用默认值。
     */
    public static ImageUrlValidationResult validateRemoteImageUrl(String url,
                                                                   int connectTimeoutMs,
                                                                   int readTimeoutMs)
    {
        ImageUrlValidationResult formatResult = validateImageUrlFormat(url);
        if (!formatResult.isValid())
        {
            return formatResult;
        }
        int connectTimeout = connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
        int readTimeout = readTimeoutMs > 0 ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS;

        HeadProbe headProbe = probe(url, "HEAD", connectTimeout, readTimeout);
        if (headProbe.ioError != null)
        {
            return classifyIoError(headProbe.ioError);
        }
        int headStatus = headProbe.status;

        if (isSuccessStatus(headStatus))
        {
            return evaluateContentType(headStatus, headProbe.contentType, false);
        }
        // HEAD 不支持时降级 GET。
        if (isHeadUnsupported(headStatus))
        {
            HeadProbe getProbe = probe(url, "GET", connectTimeout, readTimeout);
            if (getProbe.ioError != null)
            {
                // GET 也挂了：优先用 GET 的错误码细分，保底 HEAD_AND_GET_FAILED
                ImageUrlValidationResult ioResult = classifyIoError(getProbe.ioError);
                if (ioResult.getCode() == ImageUrlValidationCode.UNKNOWN
                        || ioResult.getCode() == ImageUrlValidationCode.CONNECT_FAILED)
                {
                    return ImageUrlValidationResult.fail(
                            ImageUrlValidationCode.HEAD_AND_GET_FAILED, null, null, true);
                }
                return ioResult;
            }
            int getStatus = getProbe.status;
            if (isSuccessStatus(getStatus))
            {
                return evaluateContentType(getStatus, getProbe.contentType, true);
            }
            return ImageUrlValidationResult.fail(
                    ImageUrlValidationCode.BAD_STATUS, getStatus, getProbe.contentType, true);
        }
        return ImageUrlValidationResult.fail(
                ImageUrlValidationCode.BAD_STATUS, headStatus, headProbe.contentType, false);
    }
    /**
     * 发一次 HTTP 探测请求，只取 responseCode + Content-Type；不读响应体。
     * IO / 超时异常被保存到 {@link HeadProbe#ioError}，由上层分类。
     * DNS rebinding 防护：每次连接前重新解析 host，若解析到私有/回环地址直接拒绝，
     * 避免"格式校验时解析到公网 IP，实际请求时 DNS 轮询到内网 IP"的绕过。
     */
    private static HeadProbe probe(String url, String method, int connectTimeout, int readTimeout)
    {
        HttpURLConnection conn = null;
        HeadProbe probe = new HeadProbe();
        try
        {
            URL realUrl = new URL(url.trim());
            // 二次 host 私网检查：关闭重定向后再解析一次
            if (isPrivateOrMetadataHost(realUrl.getHost()))
            {
                probe.ioError = new IOException("DNS 解析到私有地址");
                return probe;
            }
            conn = (HttpURLConnection) realUrl.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            // 关闭自动重定向，避免"外网合规 URL 重定向到内网 IP"绕过 SSRF 防护。
            // 若目标返回 3xx，由调用方决定是否信任 Location；这里直接判定为非成功状态。
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", DEFAULT_USER_AGENT);
            conn.setRequestProperty("Accept", "image/*,*/*;q=0.8");
            conn.setRequestProperty("Connection", "close");
            conn.setDoInput(true);
            conn.setDoOutput(false);
            // 仅建立连接并读取响应头，不读 body；GET 降级同样不读 body
            conn.connect();
            probe.status = conn.getResponseCode();
            probe.contentType = conn.getContentType();
        }
        catch (MalformedURLException e)
        {
            // 理论上 format 校验已经挡住，这里再兜一层
            probe.ioError = e;
        }
        catch (IOException e)
        {
            probe.ioError = e;
        }
        catch (RuntimeException e)
        {
            // URLConnection 在个别 JVM 下会抛 IllegalArgumentException / ClassCastException 等
            probe.ioError = e;
        }
        finally
        {
            if (conn != null)
            {
                try
                {
                    conn.disconnect();
                }
                catch (Exception ignore)
                {
                    // 连接释放异常不影响结果
                }
            }
        }
        return probe;
    }

    /** 判定 2xx */
    private static boolean isSuccessStatus(int status)
    {
        return status >= 200 && status < 300;
    }

    /** 典型的"HEAD 不支持"状态码 */
    private static boolean isHeadUnsupported(int status)
    {
        return status == HTTP_METHOD_NOT_ALLOWED
                || status == HTTP_BAD_REQUEST
                || status == HTTP_NOT_IMPLEMENTED;
    }

    /** 响应 Content-Type 评估：空 → EMPTY_CONTENT_TYPE；非 image/* → NOT_IMAGE；否则通过 */
    private static ImageUrlValidationResult evaluateContentType(int status,
                                                                  String rawContentType,
                                                                  boolean fallbackGet)
    {
        if (StrUtil.isBlank(rawContentType))
        {
            return ImageUrlValidationResult.fail(
                    ImageUrlValidationCode.EMPTY_CONTENT_TYPE, status, null, fallbackGet);
        }
        // Content-Type 可能形如 "image/png; charset=utf-8"；只比较主类型前缀
        String lower = rawContentType.toLowerCase(Locale.ROOT).trim();
        if (!lower.startsWith(IMAGE_PREFIX))
        {
            return ImageUrlValidationResult.fail(
                    ImageUrlValidationCode.NOT_IMAGE, status, rawContentType, fallbackGet);
        }
        return ImageUrlValidationResult.ok(status, rawContentType, fallbackGet);
    }

    /** IO / 超时异常归一化成结构化 code */
    private static ImageUrlValidationResult classifyIoError(Throwable e)
    {
        if (e instanceof SocketTimeoutException)
        {
            log.info("图片URL校验超时: {}", e.getMessage());
            return ImageUrlValidationResult.fail(ImageUrlValidationCode.TIMEOUT);
        }
        if (e instanceof MalformedURLException)
        {
            log.info("图片URL校验URL非法: {}", e.getMessage());
            return ImageUrlValidationResult.fail(ImageUrlValidationCode.INVALID_URL);
        }
        if (e instanceof IOException)
        {
            // 连接层失败（含 UnknownHostException / ConnectException / SSL 握手等）
            log.info("图片URL校验连接失败: {}", e.getMessage());
            return ImageUrlValidationResult.fail(ImageUrlValidationCode.CONNECT_FAILED);
        }
        // 其它未分类异常：保留日志，不把英文异常抛到业务层
        log.info("图片URL校验未知异常: type={}, msg={}", e.getClass().getSimpleName(), e.getMessage());
        return ImageUrlValidationResult.fail(ImageUrlValidationCode.UNKNOWN);
    }

    /** 探测结果载体（内部用），success 时拿 status / contentType；失败时拿 ioError */
    private static final class HeadProbe
    {
        int status;
        String contentType;
        Throwable ioError;
    }
    /**
     * 共享的批量校验线程池：限流并行数，避免同步批量调用时 FD/连接被打爆。
     * 使用 daemon 线程，JVM 关闭时自动回收；CallerRunsPolicy 保证队列满时不丢任务。
     */
    private static final java.util.concurrent.ExecutorService BATCH_EXECUTOR =
            new java.util.concurrent.ThreadPoolExecutor(
                    4,
                    16,
                    60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(256),
                    r -> {
                        Thread t = new Thread(r, "image-url-validator-" + System.nanoTime());
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    /**
     * 批量并行校验多个 URL，结果按输入顺序返回。
     *
     * @param urls 待校验 URL 列表（允许 null/空）
     * @return 按入参顺序返回校验结果，每项都非 null
     */
    public static java.util.List<ImageUrlValidationResult> validateRemoteImageUrlsParallel(
            java.util.List<String> urls)
    {
        if (urls == null || urls.isEmpty())
        {
            return java.util.Collections.emptyList();
        }
        // 为每个 URL 提交异步任务，按原顺序收集结果
        java.util.List<java.util.concurrent.CompletableFuture<ImageUrlValidationResult>> futures =
                new java.util.ArrayList<>(urls.size());
        for (String url : urls)
        {
            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> validateRemoteImageUrl(url), BATCH_EXECUTOR));
        }
        java.util.List<ImageUrlValidationResult> results = new java.util.ArrayList<>(urls.size());
        for (java.util.concurrent.CompletableFuture<ImageUrlValidationResult> f : futures)
        {
            try
            {
                results.add(f.get());
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                results.add(ImageUrlValidationResult.fail(ImageUrlValidationCode.UNKNOWN));
            }
            catch (java.util.concurrent.ExecutionException ee)
            {
                log.info("批量图片URL校验异常: {}", ee.getMessage());
                results.add(ImageUrlValidationResult.fail(ImageUrlValidationCode.UNKNOWN));
            }
        }
        return results;
    }
}
