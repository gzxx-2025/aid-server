package com.aid.tokendance.provider.common;

import cn.hutool.core.util.StrUtil;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.provider.OpenAiCompatiblePayloadResolver;
import com.aid.media.provider.SubmitTimeoutResolver;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** TokenDance 模型生成请求的独立 HTTP 传输实现。 */
@Component
public class DefaultTokenDanceTransport implements TokenDanceTransport
{
    public static final String APP_URL = "https://aidstudio.com.cn";
    public static final String KEY_NAME = "视觉AID";
    public static final String RECOVERY_ACTION_HEADER = "TokenDance-Recovery-Action";

    private static final int DEFAULT_TIMEOUT_MS = 120_000;
    private static final int MAX_MULTIPART_BODY_BYTES = 20 * 1024 * 1024 + 4096;
    private static final int MAX_BUFFERED_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_LARGE_RESPONSE_BYTES = 96 * 1024 * 1024;
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final int MAX_HEADER_VALUE_LENGTH = 512;
    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Pattern MULTIPART_BOUNDARY = Pattern.compile("^[A-Za-z0-9-]{16,70}$");
    private static final Set<String> PROTECTED_HEADERS = Set.of(
            "authorization", "x-app-url", "x-site-url", "host", "content-length",
            "content-type", "connection", "transfer-encoding");
    private static final Set<String> PROTOCOL_HEADERS = Set.of(
            "x-api-resource-id", "x-control-require-usage-tokens-return",
            "x-dashscope-async", "anthropic-version");

    @Override
    public TokenDanceHttpResponse exchange(String method, AiModelConfigVo config,
            String relativePath, byte[] body, Map<String, String> protocolHeaders) throws IOException
    {
        return execute(method, config, relativePath, body, protocolHeaders, null, null,
                MAX_BUFFERED_RESPONSE_BYTES);
    }

    @Override
    public TokenDanceHttpResponse exchangeBounded(String method, AiModelConfigVo config,
            String relativePath, byte[] body, Map<String, String> protocolHeaders,
            int maxResponseBytes) throws IOException
    {
        if (maxResponseBytes <= 0 || maxResponseBytes > MAX_LARGE_RESPONSE_BYTES)
        {
            throw new IllegalArgumentException("响应上限无效");
        }
        return execute(method, config, relativePath, body, protocolHeaders, null, null,
                maxResponseBytes);
    }

    @Override
    public TokenDanceHttpResponse exchangeMultipart(AiModelConfigVo config,
            String relativePath, byte[] body, String boundary) throws IOException
    {
        if (body == null || body.length == 0 || body.length > MAX_MULTIPART_BODY_BYTES)
        {
            throw new IllegalArgumentException("上传内容大小无效");
        }
        String normalizedBoundary = StrUtil.trimToNull(boundary);
        if (normalizedBoundary == null || !MULTIPART_BOUNDARY.matcher(normalizedBoundary).matches())
        {
            throw new IllegalArgumentException("上传边界无效");
        }
        return execute("POST", config, relativePath, body, Collections.emptyMap(), null,
                "multipart/form-data; boundary=" + normalizedBoundary, MAX_BUFFERED_RESPONSE_BYTES);
    }

    @Override
    public TokenDanceHttpResponse exchangeStream(String method, AiModelConfigVo config,
            String relativePath, byte[] body, Map<String, String> protocolHeaders,
            TokenDanceStreamHandler handler) throws IOException
    {
        if (handler == null)
        {
            throw new IllegalArgumentException("流处理器不能为空");
        }
        return execute(method, config, relativePath, body, protocolHeaders, handler, null,
                MAX_BUFFERED_RESPONSE_BYTES);
    }

    private TokenDanceHttpResponse execute(String method, AiModelConfigVo config,
            String relativePath, byte[] body, Map<String, String> protocolHeaders,
            TokenDanceStreamHandler streamHandler, String requestContentType,
            int maxResponseBytes) throws IOException
    {
        requireConfig(config);
        String normalizedMethod = normalizeMethod(method);
        String endpoint = ProviderEndpointUtils.buildSubmitUrl(config.getBaseUrl(), relativePath);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try
        {
            int timeout = SubmitTimeoutResolver.resolveMs(config, DEFAULT_TIMEOUT_MS);
            connection.setConnectTimeout(Math.min(timeout, 30_000));
            connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(normalizedMethod);
            applyConfiguredHeaders(connection, config.getExtraHeadersJson());
            applyProtocolHeaders(connection, protocolHeaders);
            connection.setRequestProperty("Authorization", "Bearer " + config.getApiKey().trim());
            connection.setRequestProperty("X-App-URL", APP_URL);
            connection.setRequestProperty("Accept", streamHandler == null
                    ? "application/json" : "text/event-stream, application/json");
            if (body != null && body.length > 0)
            {
                if (!"POST".equals(normalizedMethod))
                {
                    throw new IllegalArgumentException("仅POST允许请求体");
                }
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", requestContentType == null
                        ? "application/json; charset=UTF-8" : requestContentType);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream())
                {
                    output.write(body);
                }
            }

            int statusCode = connection.getResponseCode();
            String contentType = connection.getContentType();
            String recoveryAction = safeHeaderValue(connection.getHeaderField(RECOVERY_ACTION_HEADER));
            InputStream rawStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (rawStream == null)
            {
                return new TokenDanceHttpResponse(statusCode, contentType, new byte[0], recoveryAction);
            }
            try (InputStream input = rawStream)
            {
                if (streamHandler != null && statusCode >= 200 && statusCode < 300)
                {
                    streamHandler.accept(input, statusCode, contentType, recoveryAction);
                    return new TokenDanceHttpResponse(statusCode, contentType, new byte[0], recoveryAction);
                }
                return new TokenDanceHttpResponse(statusCode, contentType,
                        readBounded(input, maxResponseBytes), recoveryAction);
            }
        }
        finally
        {
            connection.disconnect();
        }
    }

    private void applyConfiguredHeaders(HttpURLConnection connection, String json)
    {
        Map<String, String> headers = OpenAiCompatiblePayloadResolver.parseExtraHeaders(json);
        if (headers == null)
        {
            return;
        }
        for (Map.Entry<String, String> entry : headers.entrySet())
        {
            String name = normalizeHeaderName(entry.getKey());
            String lower = name.toLowerCase(Locale.ROOT);
            if (PROTECTED_HEADERS.contains(lower) || PROTOCOL_HEADERS.contains(lower))
            {
                continue;
            }
            connection.setRequestProperty(name, requireHeaderValue(entry.getValue()));
        }
    }

    private void applyProtocolHeaders(HttpURLConnection connection, Map<String, String> headers)
    {
        for (Map.Entry<String, String> entry : headers == null
                ? Collections.<String, String>emptyMap().entrySet() : headers.entrySet())
        {
            String name = normalizeHeaderName(entry.getKey());
            if (!PROTOCOL_HEADERS.contains(name.toLowerCase(Locale.ROOT)))
            {
                throw new IllegalArgumentException("协议请求头不支持");
            }
            String value = requireHeaderValue(entry.getValue());
            if ("x-control-require-usage-tokens-return".equals(name.toLowerCase(Locale.ROOT))
                    && !"*".equals(value))
            {
                throw new IllegalArgumentException("用量请求头无效");
            }
            connection.setRequestProperty(name, value);
        }
    }

    private String normalizeHeaderName(String value)
    {
        String name = StrUtil.trimToNull(value);
        if (name == null || !HEADER_NAME.matcher(name).matches())
        {
            throw new IllegalArgumentException("请求头名称无效");
        }
        return name;
    }

    private String requireHeaderValue(String value)
    {
        String normalized = StrUtil.trimToNull(value);
        if (normalized == null || normalized.length() > MAX_HEADER_VALUE_LENGTH
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0)
        {
            throw new IllegalArgumentException("请求头内容无效");
        }
        return normalized;
    }

    private String safeHeaderValue(String value)
    {
        String normalized = StrUtil.trimToNull(value);
        if (normalized == null || normalized.length() > 64
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0)
        {
            return null;
        }
        return normalized;
    }

    private byte[] readBounded(InputStream input, int limit) throws IOException
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, BUFFER_SIZE));
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) >= 0)
        {
            if (read == 0)
            {
                continue;
            }
            if (read > limit - output.size())
            {
                throw new IOException("上游响应过大");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String normalizeMethod(String method)
    {
        String normalized = StrUtil.blankToDefault(method, "GET").trim().toUpperCase(Locale.ROOT);
        if (!"GET".equals(normalized) && !"POST".equals(normalized))
        {
            throw new IllegalArgumentException("请求方法不支持");
        }
        return normalized;
    }

    private void requireConfig(AiModelConfigVo config)
    {
        if (config == null || !TokenDanceProtocols.isTokenDance(config.getProviderCode())
                || StrUtil.isBlank(config.getBaseUrl()) || StrUtil.isBlank(config.getApiKey()))
        {
            throw new IllegalArgumentException("模型配置不完整");
        }
    }
}
