package com.aid.rps.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 合并接口请求 DTO：批量生成分镜图提示词 + 自动出图（任务3）。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardImageWithPromptRequest
{
    /** 项目 ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 剧集 ID（电影项目固定传 0） */
    @NotNull(message = "剧集ID不能为空")
    private Long episodeId;

    /** 要生成的分镜 ID 列表（必填，非空） */
    @NotEmpty(message = "分镜不能为空")
    private List<@NotNull(message = "分镜不能为空") Long> storyboardIds;

    /** 提示词阶段智能体编码（默认分镜画师，biz=main_storyboard_stylist） */
    private String agentCode;

    /** 提示词阶段文本模型编码（可选，按 3 级链路解析） */
    private String modelCode;

    /** 是否覆盖已有 image_prompt（默认 false） */
    private Boolean overwrite;

    /** 出图阶段：智能体编码（可选，默认 aid_storyboard_image） */
    private String genAgentCode;

    /** 出图阶段：图片模型编码（可选，默认按智能体/池解析） */
    private String genModelName;

    /** 出图阶段：宽高比（可选，如 16:9） */
    private String genAspectRatio;

    /** 出图阶段：厂商原生 size（可选，与比例二选一） */
    private String genSize;

    /** 出图阶段：业务场景标识（可选） */
    private String genScenario;

    /** 出图阶段：负向提示词（可选） */
    private String genNegativePrompt;
}
