package com.aid.tokendance.provider.common;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** TokenDance HTTP 响应的最小可信快照，不包含请求密钥。 */
public final class TokenDanceHttpResponse
{
    private final int statusCode;
    private final String contentType;
    private final byte[] body;
    private final String recoveryAction;

    public TokenDanceHttpResponse(int statusCode, String contentType, byte[] body, String recoveryAction)
    {
        this.statusCode = statusCode;
        this.contentType = contentType;
        this.body = body == null ? new byte[0] : Arrays.copyOf(body, body.length);
        this.recoveryAction = recoveryAction;
    }

    public int getStatusCode()
    {
        return statusCode;
    }

    public String getContentType()
    {
        return contentType;
    }

    public byte[] getBody()
    {
        return Arrays.copyOf(body, body.length);
    }

    /** 返回只读响应流，供大响应解析时避免额外复制字节数组。 */
    public InputStream openBodyStream()
    {
        return new ByteArrayInputStream(body);
    }

    public String bodyUtf8()
    {
        return new String(body, StandardCharsets.UTF_8);
    }

    public String getRecoveryAction()
    {
        return recoveryAction;
    }

    public boolean isSuccessful()
    {
        return statusCode >= 200 && statusCode < 300;
    }
}
