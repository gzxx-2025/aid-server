package com.aid.common.constant;

/**
 * 跨模块 HTTP 调用通用常量（鉴权头、Content-Type、超时等）。
 */
public final class HttpConstants {

    private HttpConstants() {
    }

    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String AUTH_BEARER_PREFIX = "Bearer ";
    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final String HEADER_ACCEPT = "Accept";
    public static final String ACCEPT_TEXT_EVENT_STREAM = "text/event-stream";
    public static final int DEFAULT_TIMEOUT_MS = 120_000;
}
