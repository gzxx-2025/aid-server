package com.aid.media.service;

/** Webhook 接收结果，由 Controller 映射为明确 HTTP 状态。 */
public enum KlingCallbackResult {
    ACCEPTED,
    INVALID_SIGNATURE_OR_PAYLOAD,
    RETRYABLE_INTERNAL
}
