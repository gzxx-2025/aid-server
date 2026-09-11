package com.aid.tokendance.provider.common;

import com.aid.domain.vo.AiModelConfigVo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/** TokenDance 生成网关传输层；Portal API 不能通过本接口调用。 */
public interface TokenDanceTransport
{
    default TokenDanceHttpResponse exchange(String method, AiModelConfigVo config,
            String relativePath, String body) throws IOException
    {
        return exchange(method, config, relativePath,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), Collections.emptyMap());
    }

    default TokenDanceHttpResponse exchange(String method, AiModelConfigVo config,
            String relativePath, byte[] body) throws IOException
    {
        return exchange(method, config, relativePath, body, Collections.emptyMap());
    }

    TokenDanceHttpResponse exchange(String method, AiModelConfigVo config,
            String relativePath, byte[] body, Map<String, String> protocolHeaders) throws IOException;

    default TokenDanceHttpResponse exchangeBounded(String method, AiModelConfigVo config,
            String relativePath, byte[] body, Map<String, String> protocolHeaders,
            int maxResponseBytes) throws IOException
    {
        return exchange(method, config, relativePath, body, protocolHeaders);
    }

    TokenDanceHttpResponse exchangeMultipart(AiModelConfigVo config,
            String relativePath, byte[] body, String boundary) throws IOException;

    TokenDanceHttpResponse exchangeStream(String method, AiModelConfigVo config,
            String relativePath, byte[] body, Map<String, String> protocolHeaders,
            TokenDanceStreamHandler handler) throws IOException;
}
