package com.aid.media.service;

import com.aid.media.dto.ViduCallbackContext;

/**
 * Vidu 媒体任务回调处理服务。
 *
 * @author 视觉AID
 */
public interface ViduCallbackService {

    /**
     * 校验并处理 Vidu 状态回调。
     *
     * @param rawBody 回调原始报文
     * @param context 回调请求头上下文
     */
    void handleViduCallback(String rawBody, ViduCallbackContext context);
}
