package com.aid.rps.dto;

import lombok.Data;

/**
 * 查询形态图片列表请求 DTO（以 form_image 为根，输出每张图片归属）。
 *
 * @author 视觉AID
 */
@Data
public class RpsFormImageListRequest
{
    /** 项目ID（可选过滤） */
    private Long projectId;

    /** 剧集ID（可选过滤） */
    private Long episodeId;

    /** 主资产ID（可选过滤） */
    private Long assetId;

    /** 形态ID（可选；非空时仅查该 form 下图片，其它字段忽略） */
    private Long formId;

    /** 资产类型：character / scene / prop（formId 为空时必填） */
    private String assetType;

    /** 是否使用中过滤：null=不限，0=非使用中，1=使用中 */
    private Integer isUse;
}
