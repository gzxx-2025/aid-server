package com.aid.compose.dto;

import lombok.Data;

import com.aid.compose.domain.TimedSubtitleCue;

import java.util.List;

/**
 * 接口2 合成分组：时间轴上的一「段」。
 * 段内视频顺序播放、配音与视频同起点叠放、字幕在段内一句一屏依次显示、段落 BGM 铺整段；
 * 各段按 groups 数组顺序首尾相接。段时长 = 该段视频总时长与配音总时长的较长者。
 *
 * @author 视觉AID
 */
@Data
public class ComposeGroupDto {

    /**
     * 该组所属分镜ID（aid_storyboard.id），必填。
     * 用于从剪辑工程按稳定标识匹配字幕，禁止使用素材地址或数组下标猜测分镜身份。
     */
    private Long storyboardId;

    /**
     * 该组视频 URL 列表，必填且不为空，按下标顺序连续播放。
     * 来源：分镜视频生成记录（资产中心 storyboard_video 的 videoUrl）、
     * 一键配音合成的成片（合成进度查询出参 videoUrl）等本站素材地址。
     * 支持相对路径或完整 URL；完整 URL 必须是本站域名或后台白名单内的地址。
     */
    private List<String> videoUrls;

    /**
     * 该组各视频时长（秒，支持小数），必填，长度与 videoUrls 一致、下标一一对应，每项大于 0。
     * 来源：素材接口返回的 videoDuration 字段，或前端加载视频后读取的真实时长（video.duration）。
     * 用途：计算段时长（决定字幕显示区间、BGM 铺设长度、配音补位）与预冻结扣费，必须传准。
     */
    private List<Double> videoDurations;

    /**
     * 该组配音 URL 列表，可空（该组无配音时不传或传空数组），按下标顺序连续播放。
     * 来源：配音资产（资产中心 dubbing 的 audioUrl / aid_audio_asset）等本站音频地址。
     * 配音短于画面的部分自动补静音；配音长于画面时段随配音延长，画面按原速循环补齐，配音不会被截断。
     */
    private List<String> audioUrls;

    /**
     * 该组各配音时长（秒，支持小数），audioUrls 非空时必填，长度与 audioUrls 一致、每项大于 0。
     * 来源：配音资产的 durationMs（毫秒，除以 1000 换算成秒）或前端读取的真实音频时长。
     */
    private List<Double> audioDurations;

    /**
     * 该组字幕文本，可空。任一分组或工程有字幕时整批只用前端数据；整批全空时按 storyboardId
     * 查询服务端分镜台词。来源不同但后端统一格式化（幂等）：
     * 带 [角色_形象] 等结构标记的台词自动转为「人物：说的话」，纯文本原样烧录；
     * 随后按单屏字数上限切成多屏，在段内按字数占比依次显示，一句一屏、不整段常驻。
     */
    private String subtitle;

    /**
     * 该组精确时间戳字幕，可空；每项时间相对本组视频起点。
     * 非空时后端按 startSeconds/endSeconds 精确烧录，不再按字数估算时间。
     */
    private List<TimedSubtitleCue> subtitleCues;

    /** subtitleCues 对应的最终人声音源指纹；由时间轴 subtitle.sourceMediaFingerprint 原样传入。 */
    private String subtitleSourceMediaFingerprint;

    /**
     * 该组背景音乐 URL，可空。来源同 globalBgmUrl。
     * 仅在整片 globalBgmUrl 为空时生效，铺设区间为该段；整片 BGM 非空时本字段被忽略。
     */
    private String bgmUrl;
}
