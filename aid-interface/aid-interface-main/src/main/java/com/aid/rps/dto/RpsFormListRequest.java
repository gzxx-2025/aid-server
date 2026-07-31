package com.aid.rps.dto;

import lombok.Data;

/**
 * 查询形态列表请求 DTO（以 form 为根，附带其下 form_image）。
 *
 * @author 视觉AID
 */
@Data
public class RpsFormListRequest
{
    /** 项目ID（可选过滤） */
    private Long projectId;

    /** 剧集ID（可选过滤；为空表示不限制剧集） */
    private Long episodeId;

    /** 资产类型：character / scene / prop（可选过滤） */
    private String assetType;

    /** 主资产ID（可选过滤；传则只查该主资产下的 form 列表） */
    private Long assetId;
}
