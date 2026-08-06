package com.aid.compose.enums;

/**
 * 分镜字幕识别检查点状态。
 *
 * @author 视觉AID
 */
public enum SubtitleRecognitionStatus {

    /** 当前分镜正在同步识别。 */
    PROCESSING,

    /** 当前识别来源的精确字幕已完整写入。 */
    COMPLETED,

    /** 当前音源未识别到可用人声，已稳定回落为文本时长排布。 */
    TEXT_FALLBACK,

    /** 当前分镜识别失败，下次导出需要重试。 */
    FAILED
}
