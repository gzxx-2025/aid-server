package com.aid.compose.domain;

import lombok.Data;

/**
 * 轨道项（映射 MPS ComposeConfig.Tracks[].Items[]）。
 * 视频/配音项引用 fileId；Empty 项仅有 duration；字幕项携带 text 与绝对 start/duration；
 * 背景音项可携带音量与淡入淡出标记。
 *
 * @author 视觉AID
 */
@Data
public class ComposeTrackItem {

    /** 轨道项类型 */
    private ComposeTrackItemType type;

    /** 引用的素材文件ID（VIDEO/AUDIO 时非空，EMPTY/SUBTITLE 时为空） */
    private String fileId;

    /** 轨道时间轴绝对起点（秒）；为空表示顺排无显式钉位 */
    private Double start;

    /** 时长（秒）：Empty/字幕/带时段背景音项必填 */
    private Double duration;

    /** 字幕文本（SUBTITLE 类型使用） */
    private String subtitleText;

    /** 背景音音量（0~1），整片 BGM 压低音量用，可空 */
    private Double volume;

    /** 是否施加淡入淡出（整片 BGM 使用） */
    private boolean fade;

    /**
     * 视频在轨道上的占用时长（秒，仅需变速填满段长时非空）。
     * MPS 语义：SourceMedia 实际时长 ≠ TrackTime.Duration 时自动变速播放，
     * 用于 AUDIO 对齐下配音长于画面时把末条视频拉伸补满段长（替代片尾黑屏）。
     */
    private Double trackDurationSeconds;
}
