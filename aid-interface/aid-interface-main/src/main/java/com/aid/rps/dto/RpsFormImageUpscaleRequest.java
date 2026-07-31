package com.aid.rps.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 形态图片最高档高清生成请求 DTO，用当前图作参考图生成并覆盖原图。
 *
 * @author 视觉AID
 */
@Data
public class RpsFormImageUpscaleRequest
{
    /** 要高清的 form_image 记录 ID（必填） */
    @NotNull(message = "图片ID不能为空")
    private Long imageId;

    /** 图片模型 code（必填，须在 image_upscale 池内、image 类型、imageRefine=3 高清） */
    @NotBlank(message = "请选择模型")
    private String modelCode;

    /** 清晰度 / 分辨率档位（可选，如 4k / 8k / 2K，不传自动取模型最高档） */
    private String resolution;
}
