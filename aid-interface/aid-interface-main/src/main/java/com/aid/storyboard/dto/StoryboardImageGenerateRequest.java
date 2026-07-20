package com.aid.storyboard.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 分镜图生成请求。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardImageGenerateRequest
{
    /** 分镜 ID 列表。 */
    @NotEmpty(message = "分镜ID不能为空")
    private List<Long> storyboardIds;

    /** 智能体编码。 */
    private String agentCode;

    /** 图生图提示词。 */
    private String imagePrompt;

    /** 图片模型编码。 */
    private String modelName;

    /** 宽高比。 */
    private String aspectRatio;

    /** 厂商原生尺寸。 */
    private String size;

    /** 出图数量。 */
    private Integer count;

    /** 业务场景标识。 */
    private String scenario;

    /** 负向提示词。 */
    private String negativePrompt;

    /** 用户补充文本。 */
    private String userInputText;
}
