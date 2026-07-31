package com.aid.compose.service;

import com.aid.compose.dto.VoicePreviewRequest;
import com.aid.compose.dto.VoicePreviewResult;

/**
 * 试听服务（同步、免费、不落库）。
 *
 * @author 视觉AID
 */
public interface VoicePreviewService {

    /**
     * 同步试听：文本字数上限按试听秒数配置折算（配置缺失按 15 字兜底），
     * 不落库、不进 aid_media_task、不产生任何流水。
     *
     * @param request 试听入参
     * @return 试听音频结果（临时 URL 或 base64）
     */
    VoicePreviewResult preview(VoicePreviewRequest request);
}
