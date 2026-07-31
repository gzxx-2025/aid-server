package com.aid.asset.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Data;

/**
 * C端 - 创建用户自定义参考资产请求DTO
 *
 * @author 视觉AID
 */
@Data
public class UserComicAssetCreateRequest {

    /** 资产类型（C端白名单：reference_character/reference_scene/reference_prop/style/pose/expression/effect/file/mood/camera） */
    private String assetType;

    /** 资产名称，最大100字 */
    private String assetName;

    /** 特征描述/生成约束 */
    private String personalityDesc;

    /** 提示词内容 */
    private String promptText;

    /** 主图URL：入参自动剥离域名入库 */
    @MediaUrl
    private String imageUrl;

    /** 备注 */
    private String remark;
}
