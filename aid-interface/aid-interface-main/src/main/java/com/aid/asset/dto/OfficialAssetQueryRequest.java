package com.aid.asset.dto;

import lombok.Data;

/**
 * 官方素材查询请求DTO
 * 查询 aid_comic_asset 表中的可复用素材
 * 不用于查询角色/场景/道具主资产
 *
 * @author 视觉AID
 */
@Data
public class OfficialAssetQueryRequest {

    /**
     * 素材类型（可选）。
     * 白名单与 /api/user/asset/custom/* 保持一致：
     * reference_character / reference_scene / reference_prop /
     * style / pose / expression / effect / file / mood / camera
     * 未传时仅返回该白名单内类型的素材。
     */
    private String assetType;

    /** 素材名称（模糊查询） */
    private String assetName;
}
