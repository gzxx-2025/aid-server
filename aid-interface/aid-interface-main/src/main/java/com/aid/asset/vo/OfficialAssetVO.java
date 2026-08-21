package com.aid.asset.vo;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;
import com.aid.aid.vo.StyleCategoryVO;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 官方资产VO
 *
 * @author 视觉AID
 */
@Data
@Builder
@Schema(description = "官方素材列表项")
public class OfficialAssetVO {

    /** 主键 */
    private Long id;

    /** 资产类型 */
    private String assetType;

    /** 资产名称 */
    private String assetName;

    /** 提示词 */
    private String promptText;

    /** 主图URL（出参拼域名） */
    @MediaUrl
    private String imageUrl;

    /** 风格分类标签 */
    private List<StyleCategoryVO> categories;

    /** 是否为推荐风格 */
    private Boolean isRecommended;

    /** 展示排序号，数值越小越靠前 */
    private Integer sortOrder;
}
