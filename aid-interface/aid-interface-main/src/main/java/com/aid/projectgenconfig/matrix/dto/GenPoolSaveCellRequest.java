package com.aid.projectgenconfig.matrix.dto;

import java.util.List;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 保存矩阵格子请求：覆盖式保存某 (步骤 × 业务场景 × 创作模式 × 剧本类型) 组合。
 *
 * @author 视觉AID
 */
@Data
public class GenPoolSaveCellRequest {

    /**
     * 步骤(script/stylist/video_prompt)。
     */
    @NotBlank(message = "步骤不能空")
    private String step;

    /** 业务场景编码 */
    @NotBlank(message = "业务场景不能空")
    private String bizCategoryCode;

    /** 创作模式 */
    @NotBlank(message = "创作模式不能空")
    private String creationMode;

    /** 剧本类型 */
    @NotBlank(message = "剧本类型不能空")
    private String scriptType;

    /** 经济默认智能体（可空=该组合经济不配默认） */
    private String economyAgent;

    /** 经济默认模型 */
    private String economyModel;

    /** 经济默认清晰度（仅图片场景） */
    private String economyResolution;

    /** 经济默认比例（仅图片场景） */
    private String economyAspectRatio;

    /** 性能默认智能体（可空） */
    private String performanceAgent;

    /** 性能默认模型 */
    private String performanceModel;

    /** 性能默认清晰度（仅图片场景） */
    private String performanceResolution;

    /** 性能默认比例（仅图片场景） */
    private String performanceAspectRatio;

    /** 额外候选智能体（除两个默认外，纳入可选池的其它智能体） */
    private List<String> extraPoolAgents;
}
