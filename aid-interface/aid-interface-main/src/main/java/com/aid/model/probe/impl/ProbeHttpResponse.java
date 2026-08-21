package com.aid.model.probe.impl;

/**
 * 探测 HTTP 响应快照。
 *
 * @param status      HTTP 状态码
 * @param body        响应体
 * @param contentType 响应内容类型
 */
public record ProbeHttpResponse(int status, String body, String contentType) {
}
