package com.aid.rps.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 多机位形态生图请求DTO，基于机位提示词只改机位、不改主体设计。
 *
 * @author 视觉AID
 */
@Data
public class FormMultiViewImageGenerateRequest
{
    /** 从表形态ID：必须存在、未删除、归属当前用户 */
    @NotNull(message = "形态不存在")
    private Long formId;

    /** 参考图URL：作为多机位生图的参考图输入，必须为 http/https 公网链接 */
    @NotBlank(message = "参考图缺失")
    private String imageUrl;

    /** 机位提示词：如"水平视角 / 俯视 / 近景"等描述，落入模板 {angle_prompt} */
    @NotBlank(message = "机位缺失")
    private String anglePrompt;

    /** 模型编码：必须在 image_multi_view 可选模型池内，且存在、状态正常、未删除 */
    @NotBlank(message = "模型不能空")
    private String modelCode;

    /** 可选图片比例（如 1:1 / 9:16 / 16:9），落入模板 {aspect_ratio}，不传默认 1:1 */
    private String aspectRatio;
}
