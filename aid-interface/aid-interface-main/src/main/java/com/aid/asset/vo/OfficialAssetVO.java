package com.aid.asset.vo;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 官方资产VO
 *
 * @author 视觉AID
 */
@Data
@Builder
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
}
