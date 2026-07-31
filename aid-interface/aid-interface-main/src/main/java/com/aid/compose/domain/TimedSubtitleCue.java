package com.aid.compose.domain;

import lombok.Data;

/**
 * 带时间戳的字幕片段，时间均为相对当前分镜视频起点的秒数。
 *
 * @author 视觉AID
 */
@Data
public class TimedSubtitleCue {

    /** 片段开始时间（秒，含）。 */
    private Double startSeconds;

    /** 片段结束时间（秒，不含）。 */
    private Double endSeconds;

    /** 说话人名称；无法从结构化台词识别时为“旁白”。 */
    private String speaker;

    /** 实际识别到的台词正文，不含展示用人名前缀。 */
    private String text;

    /** 字幕来源：ASR / TTS / MANUAL。 */
    private String source;
}
