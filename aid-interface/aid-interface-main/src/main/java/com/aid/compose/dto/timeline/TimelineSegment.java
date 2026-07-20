package com.aid.compose.dto.timeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 时间轴分镜段：时间轴上的一列，与一个分镜（aid_storyboard）对应。
 * 段内三轨同起点对齐：视频连续播放，配音叠放（短则补静音），字幕覆盖整段。
 * 出参恒定：video/voice/subtitle 三个对象永不为 null（空态用内部字段 url/text=null 表达）。
 *
 * @author 视觉AID
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class TimelineSegment {

    /** 所属分镜ID（aid_storyboard.id），自动初始化时回填；手动加段为 null */
    private Long storyboardId;

    /** 段序号（从 1 开始，后端按数组下标重算），仅展示用，实际顺序以数组下标为准 */
    private Integer sortOrder;

    /** 视频轨元素（恒返回对象；url=null = 该分镜还没有视频，导出前必须补齐） */
    private TimelineVideoItem video;

    /** 配音轨元素（恒返回对象；url=null = 该段无配音，导出时补静音） */
    private TimelineVoiceItem voice;

    /** 字幕轨元素（恒返回对象；text=null = 该段不烧字幕） */
    private TimelineSubtitleItem subtitle;
}
