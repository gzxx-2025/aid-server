package com.aid.compose.domain;

/**
 * 轨道项类型（映射 MPS ComposeConfig.Tracks[].Items[].Type）。
 *
 * @author 视觉AID
 */
public enum ComposeTrackItemType {

    /** 视频项 */
    VIDEO,

    /** 音频项（配音 / 背景音乐） */
    AUDIO,

    /** 空占位项（缺配音段补齐用） */
    EMPTY,

    /** 字幕项 */
    SUBTITLE
}
