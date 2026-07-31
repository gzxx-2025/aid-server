package com.aid.media.dto;

import com.aid.compose.domain.TimedSubtitleCue;
import lombok.Data;

import java.util.List;

/**
 * 语音识别标准结果：Provider 统一归一化后交给字幕时间轴消费。
 *
 * @author 视觉AID
 */
@Data
public class SpeechRecognitionResult {

    /** 完整识别文本。 */
    private String text;

    /** 厂商识别出的语言。 */
    private String language;

    /** 实际输入时长（秒）。 */
    private Double durationSeconds;

    /** 按时间顺序排列的识别片段。 */
    private List<TimedSubtitleCue> cues;

}
