package com.aid.compose.dto;

import lombok.Data;

import java.util.List;

/**
 * 接口2 合成分组：时间轴上的一「段」。
 * 段内视频顺序播放、配音与视频同起点叠放、字幕覆盖整段、段落 BGM 铺整段；
 * 各段按 groups 数组顺序首尾相接。段时长 = 该段各视频时长之和（videoDurations 求和）。
 *
 * @author 视觉AID
 */
@Data
public class ComposeGroupDto {

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
     * 配音总时长短于段时长的部分自动补静音，不会拉伸或截断视频。
     */
    private List<String> audioUrls;

    /**
     * 该组各配音时长（秒，支持小数），audioUrls 非空时必填，长度与 audioUrls 一致、每项大于 0。
     * 来源：配音资产的 durationMs（毫秒，除以 1000 换算成秒）或前端读取的真实音频时长。
     */
    private List<Double> audioDurations;

    /**
     * 该组字幕文本，可空（不传=该组不烧字幕），显示区间为整段。
     * 来源：前端字幕输入框或分镜台词。后端统一格式化（幂等）：
     * 带 [角色_形象] 等结构标记的台词自动转为「人物：说的话」，纯文本原样烧录。
     */
    private String subtitle;

    /**
     * 该组背景音乐 URL，可空。来源同 globalBgmUrl。
     * 仅在整片 globalBgmUrl 为空时生效，铺设区间为该段；整片 BGM 非空时本字段被忽略。
     */
    private String bgmUrl;
}
