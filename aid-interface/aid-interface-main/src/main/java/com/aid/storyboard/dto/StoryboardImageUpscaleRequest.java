package com.aid.storyboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分镜图高清生成请求。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardImageUpscaleRequest
{
    /** 生成记录 ID。 */
    @NotNull(message = "记录不存在")
    private Long genRecordId;

    /** 模型编码。 */
    @NotBlank(message = "模型不能空")
    private String modelCode;

    /** 清晰度档位。 */
    private String resolution;
}
