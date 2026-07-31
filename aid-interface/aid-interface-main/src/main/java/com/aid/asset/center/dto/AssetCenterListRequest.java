package com.aid.asset.center.dto;

import lombok.Data;

/**
 * 资产中心-个人资产列表查询请求 DTO。
 * 三个过滤维度均可选；categoryCode 不传时返回该范围内所有分类的资产混合分页。
 * 仅查询当前登录用户自己的数据，不含官方、不越权。列表不返回任何长正文内容。
 *
 * @author 视觉AID
 */
@Data
public class AssetCenterListRequest {

    /** 项目 ID（可选） */
    private Long projectId;

    /** 剧集 ID（可选；电影模式为 0） */
    private Long episodeId;

    /** 分类编码（可选，见 AssetCenterCategoryEnum；不传=全部分类混合） */
    private String categoryCode;

    /** 页码，默认 1 */
    private Integer pageNum;

    /** 每页数量，默认 20，最大 100 */
    private Integer pageSize;
}
