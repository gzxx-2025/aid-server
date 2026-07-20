package com.aid.projectgenconfig.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

/**
 * 保存项目级生成配置请求。
 * 部分更新（upsert）：只校验并保存 {@code configs} 列表中传入的场景；
 * 未传入的场景保持原值不变。分镜场景未传则完全忽略（不校验不修改）。
 *
 * @author 视觉AID
 */
@Data
public class SaveProjectGenConfigRequest
{
    /** 项目ID（必填） */
    @NotNull(message = "项目不能空")
    private Long projectId;

    /**
     * 剧集ID（可选）。
     * 保存校验不区分创作模式（智能体只需为该场景矩阵的合法候选），该字段当前不参与保存校验。
     */
    private Long episodeId;

    /** 本次要保存的场景配置列表（必填，至少一项） */
    @NotEmpty(message = "配置不能空")
    private List<ProjectGenConfigItem> configs;
}
