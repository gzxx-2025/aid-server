package com.aid.rps.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;


/**
 * AI资产提取请求DTO，公用并行提取接口入参。
 *
 * @author 视觉AID
 */
@Data
public class AssetExtractRequest
{
    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 剧集ID（电影传0或null；剧集增量提取必填=当前集，全量初始化可不传） */
    private Long episodeId;

    /** 提取类型列表: "character", "scene", "prop"（必填，剧集模式允许三类同提） */
    @NotEmpty(message = "提取类型不能为空")
    private List<String> extractTypes;

    /**
     * 提取范围（仅剧集生效，可空）：
     * EPISODE_INCREMENTAL=只分析当前集（剧集默认，episodeId 必填）；
     * PROJECT_FULL=全项目剧本扫描（仅支持角色，用于首次初始化/主动全量分析）。
     * 电影模式忽略本字段（恒为整部电影剧本）。
     */
    private String extractScope;

    /** 智能体编码映射：key=extractType，value=agentCode（必填） */
    @NotEmpty(message = "智能体编码不能为空")
    private Map<String, String> agentCodes;

    /** 模型编码映射（可选）：key=extractType，value=modelCode */
    private Map<String, String> modelCodes;

    /** 是否覆盖已有自动提取资产（默认 false，true 时先软删自动提取资产再重跑） */
    private Boolean overwrite;
}
