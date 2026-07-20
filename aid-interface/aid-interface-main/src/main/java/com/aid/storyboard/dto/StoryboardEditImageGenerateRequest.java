package com.aid.storyboard.dto;

import java.util.Collections;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分镜编辑图生成请求。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardEditImageGenerateRequest
{
    /** 分镜 ID。 */
    @NotNull(message = "分镜不存在")
    private Long storyboardId;

    /** 参考图 URL。 */
    @NotBlank(message = "参考图不能为空")
    private String referenceImage;

    /** 提示词。 */
    @NotBlank(message = "提示词为空")
    private String prompt;

    /** 模型编码。 */
    @NotBlank(message = "模型不能空")
    private String modelCode;

    /** 图片比例。 */
    @NotBlank(message = "比例不能空")
    private String aspectRatio;

    /** 图片清晰度档。 */
    @NotBlank(message = "清晰度为空")
    private String size;

    /** 生成张数。 */
    @NotNull(message = "张数不能空")
    @Min(value = 1, message = "张数不合法")
    @Max(value = 4, message = "张数不合法")
    private Integer imageCount;

    /**
     * 获取参考图列表。
     *
     * @return 参考图 URL 列表
     */
    public List<String> referenceImagesAsList()
    {
        return referenceImage == null || referenceImage.isBlank()
                ? Collections.emptyList()
                : Collections.singletonList(referenceImage);
    }
}
