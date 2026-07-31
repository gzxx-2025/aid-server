package com.aid.rps.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 批量分镜图脚本生成请求。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardImagePromptBatchRequest
{
    /** 项目 ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 剧集 ID（电影项目固定传 0；剧集项目传对应集 ID） */
    @NotNull(message = "剧集ID不能为空")
    private Long episodeId;

    /** 要生成图脚本的分镜 ID 列表（必填，非空，须归属当前用户且未删除） */
    @NotEmpty(message = "分镜不能为空")
    private List<@NotNull(message = "分镜不能为空") Long> storyboardIds;

    /** 智能体编码（默认 aid_storyboard_script_stylist，biz 须为 main_storyboard_stylist） */
    private String agentCode;

    /** 文本模型编码。 */
    private String modelCode;

    /** 是否覆盖已有图脚本（默认 false，false 时跳过已生成的分镜不重复扣费） */
    private Boolean overwrite;
}
