package com.aid.asset.center.dto;

import lombok.Data;

/**
 * 资产中心-分类树查询请求 DTO。
 * 仅项目层分页；每个项目下挂剧集，每个剧集下挂固定 15 个资产分类。
 *
 * @author 视觉AID
 */
@Data
public class AssetCenterCategoryTreeRequest {

    /** 项目名称模糊关键字（可选） */
    private String keyword;

    /** 页码，默认 1（仅作用于项目层） */
    private Integer pageNum;

    /** 每页数量，默认 10，最大 50（仅作用于项目层） */
    private Integer pageSize;
}
