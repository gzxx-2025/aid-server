package com.aid.compose.domain;

import lombok.Data;

import java.util.List;

/**
 * 合成分组（核心合成方法无状态入参的最小单元）。
 * 一组对应时间轴上一段：视频必有（视频轨连续无洞的来源），配音/字幕/分组 BGM 可空。
 *
 * @author 视觉AID
 */
@Data
public class ComposeGroup {

    /** 该组视频 URL 列表，必填不为空（视频轨连续无洞的来源） */
    private List<String> videoUrls;

    /**
     * 该组视频时长列表（秒），下标与 videoUrls 对齐，用于段时长计算与轨道钉位。
     * 由编排层从业务表/前端时间轴提供；缺省项按 0 计。
     */
    private List<Double> videoDurations;

    /** 该组配音 URL 列表，可空（空则该组配音轨补 Empty） */
    private List<String> audioUrls;

    /**
     * 该组配音时长列表（秒），下标与 audioUrls 对齐，用于配音轨补齐 Empty 计算。
     * 由编排层从 aid_audio_record.durationMs 等提供；缺省项按 0 计。
     */
    private List<Double> audioDurations;

    /** 该组字幕文本，可空（空则该组不烧字幕） */
    private String subtitle;

    /** 该组背景音乐 URL，可空（globalBgmUrl 为空时按组铺） */
    private String bgmUrl;
}
