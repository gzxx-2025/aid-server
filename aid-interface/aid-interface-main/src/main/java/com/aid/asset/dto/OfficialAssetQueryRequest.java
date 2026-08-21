package com.aid.asset.dto;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 官方素材查询请求DTO
 * 查询 aid_comic_asset 表中的可复用素材
 * 不用于查询角色/场景/道具主资产
 *
 * @author 视觉AID
 */
@Data
@Schema(description = "官方素材查询请求")
public class OfficialAssetQueryRequest {

    /**
     * 素材类型（可选）。
     * 个人参考资产白名单类型及官方背景音乐：
     * reference_character / reference_scene / reference_prop /
     * style / pose / expression / effect / file / mood / camera / bgm
     * 未传时仅返回该白名单内类型的素材。
     */
    private String assetType;

    /** 素材名称（模糊查询） */
    private String assetName;

    /** 风格分类代码；all或空表示全部 */
    @Schema(description = "风格分类代码；all或空表示全部，仅用于风格素材", example = "three_d")
    private String categoryCode;
}
