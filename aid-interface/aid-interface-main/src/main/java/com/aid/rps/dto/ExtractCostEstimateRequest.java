package com.aid.rps.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 资产提取费用预估请求DTO
 *
 * @author 视觉AID
 */
@Data
public class ExtractCostEstimateRequest
{
    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 剧集ID（电影传0或null；剧集增量预估必填=当前集，全量初始化可不传） */
    private Long episodeId;

    /** 提取类型列表: "character", "scene", "prop" */
    private List<String> extractTypes;

    /**
     * 提取范围（仅剧集生效，可空）：EPISODE_INCREMENTAL=当前集增量（默认）；
     * PROJECT_FULL=全项目扫描（仅支持角色）。与提取接口口径一致。
     */
    private String extractScope;
}
