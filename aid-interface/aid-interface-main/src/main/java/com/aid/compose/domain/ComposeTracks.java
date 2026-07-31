package com.aid.compose.domain;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 四轨道组装结果（核心合成方法产出，映射 MPS FileInfos + ComposeConfig.Tracks）。
 * 视频轨连续无洞；配音轨缺配音补等长 Empty；字幕轨绝对钉位；背景音轨按 global/分组动态生成
 * （都没有则 bgmItems 为空，不建该轨）。
 *
 * @author 视觉AID
 */
@Data
public class ComposeTracks {

    /** 全部素材文件（MPS FileInfos） */
    private List<ComposeFileInfo> fileInfos = new ArrayList<>();

    /** 视频轨项 */
    private List<ComposeTrackItem> videoItems = new ArrayList<>();

    /** 配音轨项（含缺配音的 Empty 占位） */
    private List<ComposeTrackItem> audioItems = new ArrayList<>();

    /** 字幕轨项（仅有字幕的组生成） */
    private List<ComposeTrackItem> subtitleItems = new ArrayList<>();

    /** 背景音轨项（global 单条铺满 / 分组按时段；全无则空） */
    private List<ComposeTrackItem> bgmItems = new ArrayList<>();

    /** 整片总时长（秒） */
    private double totalDuration;
}
