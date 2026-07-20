package com.aid.projectgenconfig.dto;

import lombok.Data;

/**
 * 单个场景的生成配置项（保存请求中的子对象）。
 * 部分更新语义：仅本列表中出现的场景会被校验与保存，未出现的场景不动。
 *
 * @author 视觉AID
 */
@Data
public class ProjectGenConfigItem
{
    /**
     * 场景编码（必填）：必须为合法场景编码之一，
     * 如 main_character_extract / main_character_image / main_storyboard_image / main_storyboard_stylist。
     */
    private String sceneCode;

    /** 智能体编码（必填）：其 biz_category_code 必须与 sceneCode 一致。 */
    private String agentCode;

    /** 模型编码（必填）：必须在该场景模型池（func_code=sceneCode）内。 */
    private String modelCode;

    /** 清晰度/分辨率档位（图片类场景必填）：必须命中模型 capability_json.sizeOptions。 */
    private String resolution;

    /** 图片比例（仅分镜生图必填）：必须命中模型 capability_json.aspectRatioOptions。 */
    private String aspectRatio;
}
