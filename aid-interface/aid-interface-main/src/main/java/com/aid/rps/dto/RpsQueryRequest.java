package com.aid.rps.dto;

import lombok.Data;

/**
 * 查询资产列表请求DTO
 *
 * @author 视觉AID
 */
@Data
public class RpsQueryRequest {

    /** 项目ID（可选过滤） */
    private Long projectId;

    /** 剧集ID（可选过滤） */
    private Long episodeId;

    /** 资产类型（可选过滤） */
    private String assetType;

    /** 是否启用（可选过滤，0未使用 1已使用） */
    private Integer isUse;
}
