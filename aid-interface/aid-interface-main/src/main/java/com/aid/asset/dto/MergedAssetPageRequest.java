package com.aid.asset.dto;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 合并资产分页查询请求 DTO（个人 + 官方）。
 * 个人资产取自 aid_user_comic_asset（按 userId 隔离），官方资产取自 aid_comic_asset；
 * 排序固定为「官方推荐、个人、官方非推荐」，跨两表统一分页。两表均无项目/剧集字段，
 * 支持按 assetType、keyword 与官方风格 categoryCode 过滤；具体分类会排除无官方分类的个人素材。
 *
 * @author 视觉AID
 */
@Data
@Schema(description = "个人与官方素材合并分页请求")
public class MergedAssetPageRequest {

    /** 资产类型（可选，须在 C 端白名单内；不传则查白名单全部类型） */
    private String assetType;

    /** 资产名称模糊关键字（可选） */
    private String keyword;

    /** 风格分类代码；all或空表示全部 */
    @Schema(description = "风格分类代码；all或空表示全部，仅用于风格素材", example = "comic_drama")
    private String categoryCode;

    /** 页码，默认 1 */
    private Integer pageNum;

    /** 每页数量，默认 20，最大 100 */
    private Integer pageSize;
}
