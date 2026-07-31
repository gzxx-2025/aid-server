package com.aid.rps.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 批量分镜视频提示词生成请求。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardVideoPromptBatchRequest
{
    /** 项目 ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 剧集 ID（电影项目固定传 0；剧集项目传对应集 ID） */
    @NotNull(message = "剧集ID不能为空")
    private Long episodeId;

    /** 要生成的分镜 ID 列表（可选，不传=全部分镜由 overwrite 区分重生/续生；传了默认覆盖） */
    private List<@NotNull(message = "分镜ID非法") Long> storyboardIds;

    /** 智能体编码（默认 aid_visual_director，biz 须为 main_storyboard_video_prompt） */
    private String agentCode;

    /** 文本模型编码。 */
    private String modelCode;

    /** 是否覆盖已有视频提示词（仅不传 storyboardIds 时生效） */
    private Boolean overwrite;
}
