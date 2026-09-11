package com.aid.tokendance.security;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.DnsResolver;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

/**
 * TokenDance 公网音频产物的无凭证受控下载器。
 *
 * @author 视觉AID
 */
public final class TokenDancePublicMediaDownloader {

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_AUDIO_BYTES = 96 * 1024 * 1024;

    private TokenDancePublicMediaDownloader() {
    }

    /**
     * 下载 TokenDance 返回的公网音频产物。
     *
     * @param originUrl 上游产物地址
     * @param connectionTimeoutMs 连接超时
     * @param readTimeoutMs 读取超时
     * @return 完整且未超过上限的音频字节
     */
    public static byte[] download(String originUrl, int connectionTimeoutMs, int readTimeoutMs) {
        ValidatedTarget current = requirePublicHttpTarget(originUrl);
        Set<String> visited = new HashSet<>();
        int redirectCount = 0;
        while (true) {
            String requestUrl = current.uri().toASCIIString();
            if (!visited.add(requestUrl)) {
                throw new ServiceException("产物重定向循环");
            }
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectTimeout(connectionTimeoutMs)
                    .setSocketTimeout(readTimeoutMs)
                    .setConnectionRequestTimeout(connectionTimeoutMs)
                    .setRedirectsEnabled(false)
                    .build();
            HttpGet request = new HttpGet(current.uri());
            request.setConfig(requestConfig);
            request.setHeader("Accept-Encoding", "identity");
            DnsResolver pinnedResolver = pinnedResolver(current);
            // 每一跳单独建连接池，并把原域名固定到本轮已经核验的公网地址；TLS 仍使用原域名完成 SNI 与证书校验。
            try (CloseableHttpClient client = HttpClients.custom()
                    .setDnsResolver(pinnedResolver)
                    .disableRedirectHandling()
                    .disableAutomaticRetries()
                    .disableContentCompression()
                    .build();
                 CloseableHttpResponse response = client.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                if (isRedirectStatus(status)) {
                    if (redirectCount >= MAX_REDIRECTS) {
                        throw new ServiceException("产物重定向过多");
                    }
                    Header location = response.getFirstHeader("Location");
                    if (location == null || StrUtil.isBlank(location.getValue())) {
                        throw new ServiceException("产物重定向无地址");
                    }
                    current = resolvePublicRedirect(current, location.getValue());
                    redirectCount++;
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new ServiceException("产物下载失败");
                }
                HttpEntity entity = response.getEntity();
                if (entity == null) {
                    throw new ServiceException("产物下载为空");
                }
                rejectOversizedContentLength(entity.getContentLength());
                try (InputStream stream = entity.getContent()) {
                    return readBounded(stream, MAX_AUDIO_BYTES);
                } catch (IOException ex) {
                    throw new ServiceException("产物下载失败");
                }
            } catch (IOException ex) {
                throw new ServiceException("产物下载失败");
            }
        }
    }

    static ValidatedTarget resolvePublicRedirect(ValidatedTarget current, String location) {
        try {
            return requirePublicHttpTarget(current.uri().resolve(location.trim()).toString());
        } catch (IllegalArgumentException ex) {
            throw new ServiceException("产物重定向无效");
        }
    }

    static ValidatedTarget requirePublicHttpTarget(String value) {
        if (StrUtil.isBlank(value)) {
            throw new ServiceException("产物地址无效");
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || StrUtil.isBlank(uri.getHost()) || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getPort() == 0) {
                throw new ServiceException("产物地址无效");
            }
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            if (addresses.length == 0) {
                throw new ServiceException("产物地址不可信");
            }
            for (InetAddress address : addresses) {
                if (!isPublicAddress(address)) {
                    throw new ServiceException("产物地址不可信");
                }
            }
            return new ValidatedTarget(uri, addresses.clone());
        } catch (UnknownHostException ex) {
            throw new ServiceException("产物地址不可用");
        } catch (IllegalArgumentException ex) {
            throw new ServiceException("产物地址无效");
        }
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            return first != 0
                    && first != 10
                    && first != 127
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && second == 0 && third == 0)
                    && !(first == 192 && second == 0 && third == 2)
                    && !(first == 192 && second == 88 && third == 99)
                    && !(first == 192 && second == 168)
                    && !(first == 198 && (second == 18 || second == 19))
                    && !(first == 198 && second == 51 && third == 100)
                    && !(first == 203 && second == 0 && third == 113)
                    && first < 224;
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            // 当前公网单播 IPv6 位于 2000::/3；文档地址 2001:db8::/32 仍需显式排除。
            boolean globalUnicast = (bytes[0] & 0xe0) == 0x20;
            boolean documentation = (bytes[0] & 0xff) == 0x20
                    && (bytes[1] & 0xff) == 0x01
                    && (bytes[2] & 0xff) == 0x0d
                    && (bytes[3] & 0xff) == 0xb8;
            return globalUnicast && !documentation;
        }
        return false;
    }

    static byte[] readBounded(InputStream stream, int maxBytes) throws IOException {
        if (stream == null || maxBytes <= 0) {
            throw new ServiceException("产物下载失败");
        }
        byte[] bytes = stream.readNBytes(maxBytes + 1);
        if (bytes.length == 0) {
            throw new ServiceException("产物下载为空");
        }
        if (bytes.length > maxBytes) {
            throw new ServiceException("音频产物过大");
        }
        return bytes;
    }

    private static void rejectOversizedContentLength(long value) {
        if (value > MAX_AUDIO_BYTES) {
            throw new ServiceException("音频产物过大");
        }
    }

    private static boolean isRedirectStatus(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static DnsResolver pinnedResolver(ValidatedTarget target) {
        String expectedHost = normalizeHost(target.uri().getHost());
        InetAddress[] pinned = target.addresses().clone();
        return requestedHost -> {
            if (!expectedHost.equalsIgnoreCase(normalizeHost(requestedHost))) {
                throw new UnknownHostException("unexpected host");
            }
            return pinned.clone();
        };
    }

    private static String normalizeHost(String host) {
        String value = StrUtil.trimToEmpty(host);
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }

    static record ValidatedTarget(URI uri, InetAddress[] addresses) {
        ValidatedTarget {
            addresses = addresses.clone();
        }

        @Override
        public InetAddress[] addresses() {
            return addresses.clone();
        }
    }
}
