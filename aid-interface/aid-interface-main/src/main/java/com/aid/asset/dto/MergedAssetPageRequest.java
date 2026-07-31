package com.aid.asset.dto;

import lombok.Data;

/**
 * 合并资产分页查询请求 DTO（个人 + 官方）。
 * 个人资产取自 aid_user_comic_asset（按 userId 隔离），官方资产取自 aid_comic_asset；
 * 排序固定为「个人在前、官方在后」，跨两表统一分页。两表均无项目/剧集字段，
 * 故仅支持按 assetType + keyword 过滤。
 *
 * @author 视觉AID
 */
@Data
public class MergedAssetPageRequest {

    /** 资产类型（可选，须在 C 端白名单内；不传则查白名单全部类型） */
    private String assetType;

    /** 资产名称模糊关键字（可选） */
    private String keyword;

    /** 页码，默认 1 */
    private Integer pageNum;

    /** 每页数量，默认 20，最大 100 */
    private Integer pageSize;
}
