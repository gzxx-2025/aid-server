package com.aid.compose.dto;

import lombok.Data;

/**
 * 接口3 出参：试听音频结果（二选一：临时URL 或 base64）。
 *
 * @author 视觉AID
 */
@Data
public class VoicePreviewResult {

    /** 临时音频 URL（与 audioBase64 二选一） */
    private String audioUrl;

    /** 音频 base64（与 audioUrl 二选一） */
    private String audioBase64;

    /** 音频时长(毫秒)，可空 */
    private Integer durationMs;
}
