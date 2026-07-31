package com.aid.compose.dto;

import com.aid.compose.domain.TimedSubtitleCue;
import lombok.Data;

import java.util.List;

/** 腾讯云语音识别测试的最终结果。 */
@Data
public class TencentAsrTestResult {

    /** 原始上传文件名。 */
    private String fileName;

    /** 上传文件大小，单位字节。 */
    private Long fileSize;

    /** 腾讯云识别到的媒体时长，单位秒。 */
    private Double durationSeconds;

    /** 从逐段结果整理出的完整文本。 */
    private String text;

    /** 腾讯云返回的原始文本结果。 */
    private String rawText;

    /** 字幕分段数量。 */
    private Integer cueCount;

    /** 带时间戳的最终字幕分段。 */
    private List<TimedSubtitleCue> cues;

    /** 上传、识别和结果整理总耗时，单位毫秒。 */
    private Long elapsedMs;
}
