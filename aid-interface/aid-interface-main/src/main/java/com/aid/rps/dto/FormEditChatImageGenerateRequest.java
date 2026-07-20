package com.aid.rps.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 形态图片创作请求 DTO（编辑图片 / 对话作图统一入口）。
 *
 * @author 视觉AID
 */
@Data
public class FormEditChatImageGenerateRequest
{
    /** 从表形态ID：必须存在、未删除、归属当前用户 */
    @NotNull(message = "形态不存在")
    private Long formId;

    /** 生成模式：edit=编辑图片（必须参考图）；chat=对话作图（参考图可选） */
    @NotBlank(message = "模式不能空")
    private String genMode;

    /** 参考图 URL 列表：edit 模式必传≥1 张，chat 模式可空；发起前逐张做远程合法性校验 */
    private List<String> referenceImages;

    /** 前端输入的提示词：非空 */
    @NotBlank(message = "提示词为空")
    private String prompt;

    /** 模型编码：必须存在 / 启用 / model_type=image，且在 image_edit 模型池内 */
    @NotBlank(message = "模型不能空")
    private String modelCode;

    /** 图片比例（如 1:1 / 3:4 / 16:9）；必须命中所选模型 aspectRatioOptions */
    @NotBlank(message = "比例不能空")
    private String aspectRatio;

    /** 图片清晰度档（如 2K）；必须命中所选模型 sizeOptions */
    @NotBlank(message = "清晰度为空")
    private String size;

    /** 生成张数：1 ~ 4，且须 ≤ 模型 maxOutputCount */
    @NotNull(message = "张数不能空")
    @Min(value = 1, message = "张数不合法")
    @Max(value = 4, message = "张数不合法")
    private Integer imageCount;

    /** 取干净的参考图 URL 列表副本（去空白、去空串），供 Service 内部统一消费 */
    public List<String> referenceImagesAsList()
    {
        List<String> result = new ArrayList<>();
        if (referenceImages == null)
        {
            return result;
        }
        for (String url : referenceImages)
        {
            if (url != null && !url.isBlank())
            {
                result.add(url.trim());
            }
        }
        return result;
    }
}
