package com.aid.rps.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 批量分镜脚本生成请求。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardScriptBatchRequest
{
    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 剧集ID（电影项目固定传 0；剧集项目传对应集 ID） */
    @NotNull(message = "剧集ID不能为空")
    private Long episodeId;

    /** 可选：要生成分镜脚本的场景ID列表，不传则跑该剧集下所有场景 */
    private List<Long> sceneIds;

    /** 智能体编码。 */
    private String agentCode;

    /** 模型编码。 */
    private String modelCode;

    /** 镜头密度档位：精简 / 标准（默认） / 细拆，实际镜头数由提示词决定 */
    private String mode;

    /** 是否覆盖已有分镜脚本（默认 false，false 时已存在直接拒绝） */
    private Boolean overwrite;
}
