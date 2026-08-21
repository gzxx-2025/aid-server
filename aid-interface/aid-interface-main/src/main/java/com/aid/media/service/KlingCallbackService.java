package com.aid.media.service;

import com.aid.media.dto.KlingCallbackContext;

/** 可灵媒体任务回调处理服务。 */
public interface KlingCallbackService {
    KlingCallbackResult handleKlingCallback(String rawBody, KlingCallbackContext context);
}
