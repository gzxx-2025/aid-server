package com.aid.compose.dto;

import lombok.Data;

import java.util.List;

/**
 * 接口1 入参：分镜一键配音（纯配音合成，不烧字幕、不加背景音乐）。
 * 分镜视频由服务端按分镜取「分镜视频」（final_video_id 指向的配音前原视频），
 * 任一分镜未选定视频在任务生成前整批拒绝。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardComposeRequest {

    /** 分镜ID列表(aid_storyboard.id)，按顺序即合成顺序。必填且不为空 */
    private List<Long> storyboardIds;

    /** 配音参数：与现有 generateAudio 对齐。必填 */
    private VoiceoverParam voiceover;

    /** 输出分辨率档，默认 FHD */
    private String resolution;

    /** 项目ID */
    private Long projectId;

    /** 剧集ID */
    private Long episodeId;
}
