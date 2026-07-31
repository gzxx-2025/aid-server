package com.aid.compose.dto.timeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import com.aid.compose.domain.TimedSubtitleCue;
import com.aid.compose.enums.SubtitleRecognitionStatus;

import java.util.List;

/**
 * 时间轴字幕轨元素：该段字幕文本与样式。
 * 自动初始化时取分镜台词（aid_storyboard.dialogue_text）经统一格式化后的
 * 「人物：说的话」文本；用户在剪辑器修改字幕后由保存接口同步覆盖本结构。
 * 出参恒定：对象永不为 null，无字幕时 text=null、样式字段仍给默认值。
 *
 * @author 视觉AID
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class TimelineSubtitleItem {

    /** 字幕文本（已格式化为「人物：说的话」，多段换行分隔）；为空 = 该段不烧字幕 */
    private String text;

    /** 字体大小（px，12~120，默认 40） */
    private Integer fontSize;

    /** 字体颜色（预留，#RRGGBB，默认 #FFFFFF 白色） */
    private String fontColor;

    /** 字体名称（预留，默认 null = 系统默认黑体） */
    private String fontFamily;

    /** 显示位置（预留，bottom/center/top，默认 bottom 底部居中） */
    private String position;

    /** 是否显示（默认 true；false = 该段字幕暂时隐藏但保留文本） */
    private Boolean show;

    /** 精确时间戳字幕；非空时导出优先于 text 的字数比例排布。 */
    private List<TimedSubtitleCue> cues;

    /** 对齐时的最终人声音源指纹；独立配音优先，否则为视频原声。 */
    private String sourceMediaFingerprint;

    /** 生成当前人物映射时使用的台词指纹；台词变化时仅重算人物，不重复请求 ASR。 */
    private String sourceDialogueFingerprint;

    /** 自动字幕分镜级检查点：PROCESSING / COMPLETED / FAILED；旧工程可为空。 */
    private SubtitleRecognitionStatus recognitionStatus;

    /** 完成或尝试当前检查点的识别厂商编码。 */
    private String recognitionProvider;

    /** 检查点最近更新时间，格式 yyyy-MM-dd HH:mm:ss。 */
    private String recognitionUpdatedAt;

    /** 当前分镜失败的用户安全短文案；非失败状态为空。 */
    private String recognitionError;
}
