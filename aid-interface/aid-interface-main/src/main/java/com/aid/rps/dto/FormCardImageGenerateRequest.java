package com.aid.rps.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 批量角色设定卡生成请求 DTO，基于白底图批量生成角色设定卡（仅 character）。
 *
 * @author 视觉AID
 */
@Data
public class FormCardImageGenerateRequest
{
    /** 白底图对应的图片实例ID列表（aid_role_prop_scene_form_image.id），批量生成 */
    @NotEmpty(message = "图片ID不能为空")
    private List<Long> imageIds;

    /** 智能体编码（必填，biz_category_code 须为 main_character_card_image） */
    @jakarta.validation.constraints.NotBlank(message = "智能体编码不能为空")
    private String agentCode;

    /** 可选：用户指定的模型编码，不传按 3 级链路兜底 */
    private String modelCode;

    /** 可选：用户指定的清晰度档位（如 1K / 2K / 4K） */
    private String resolution;

    /** 可选：用户指定的图片比例（设定卡默认 21:9） */
    private String aspectRatio;
}
