package com.aid.rps.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 形态图批量生成请求DTO（基础设定图链路，纯文字生图，不接受用户传参考图）。
 *
 * @author 视觉AID
 */
@Data
public class FormImageGenerateRequest
{
    /** 从表形态ID列表（至少 1 个，支持多个批量提交，自动去重） */
    @NotEmpty(message = "形态ID不能为空")
    private List<Long> formIds;

    /** 智能体编码（必填，biz_category_code 须与 form 资产类型对应的 main_*_image 一致） */
    @jakarta.validation.constraints.NotBlank(message = "智能体编码不能为空")
    private String agentCode;

    /** 可选：用户指定的模型编码，不传按 3 级链路兜底，最终须为图片模型 */
    private String modelCode;

    /** 可选：用户指定的清晰度档位（如 1K / 2K / 4K），须命中模型 sizeOptions */
    private String resolution;

    /** 可选：用户指定的图片比例（如 1:1 / 16:9 / 9:16），须命中模型 aspectRatioOptions */
    private String aspectRatio;
}
