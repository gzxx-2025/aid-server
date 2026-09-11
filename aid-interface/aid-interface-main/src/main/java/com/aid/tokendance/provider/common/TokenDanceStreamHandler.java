package com.aid.tokendance.provider.common;

import java.io.IOException;
import java.io.InputStream;

/** 在 HTTP 连接有效期内消费流式响应，调用方不得保存 InputStream 引用。 */
@FunctionalInterface
public interface TokenDanceStreamHandler
{
    void accept(InputStream stream, int statusCode, String contentType, String recoveryAction) throws IOException;
}
