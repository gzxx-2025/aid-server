package com.aid.compose.dto.timeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

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
}
