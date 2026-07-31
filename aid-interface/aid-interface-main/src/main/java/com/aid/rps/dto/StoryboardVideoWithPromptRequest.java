package com.aid.rps.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 合并接口请求 DTO：批量生成分镜视频提示词 + 自动出片（任务4，多参方向）。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardVideoWithPromptRequest
{
    /** 项目 ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 剧集 ID（电影项目固定传 0） */
    @NotNull(message = "剧集ID不能为空")
    private Long episodeId;

    /** 目标分镜 ID 列表（可选：不传=本剧集全部） */
    private List<Long> storyboardIds;

    /** 提示词阶段智能体编码（默认视觉导演，多参方向） */
    private String agentCode;

    /** 提示词阶段文本模型编码（可选） */
    private String modelCode;

    /** 是否覆盖已有 video_prompt（默认 false，仅不传 storyboardIds 时生效） */
    private Boolean overwrite;

    /** 出片阶段：视频模型编码（可选，默认 main_storyboard_video 池兜底） */
    private String genModelName;

    /** 出片阶段：宽高比（可选） */
    private String genAspectRatio;

    /** 出片阶段：清晰度档位（可选，如 540p / 720p / 1080p，须命中模型 sizeOptions 白名单） */
    private String genResolution;

    /** 出片阶段：时长秒数（可选） */
    private Integer genDurationSeconds;

    /** 出片阶段：是否生成音频（可选，仅部分模型支持） */
    private Boolean genGenerateAudio;
}
