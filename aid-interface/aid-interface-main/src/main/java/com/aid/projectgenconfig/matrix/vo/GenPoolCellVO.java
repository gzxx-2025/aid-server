package com.aid.projectgenconfig.matrix.vo;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * 矩阵中的一个"格子"：某 (步骤 × 业务场景 × 创作模式 × 剧本类型) 组合的配置。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class GenPoolCellVO {

    /**
     * 步骤(script/stylist/video_prompt)。
     */
    private String step;

    /** 业务场景编码 */
    private String bizCategoryCode;

    /** 创作模式 */
    private String creationMode;

    /** 剧本类型 */
    private String scriptType;

    /** 经济默认智能体 */
    private String economyAgent;

    /** 经济默认模型 */
    private String economyModel;

    /** 经济默认清晰度（仅图片场景） */
    private String economyResolution;

    /** 经济默认比例（仅图片场景） */
    private String economyAspectRatio;

    /** 性能默认智能体 */
    private String performanceAgent;

    /** 性能默认模型 */
    private String performanceModel;

    /** 性能默认清晰度（仅图片场景） */
    private String performanceResolution;

    /** 性能默认比例（仅图片场景） */
    private String performanceAspectRatio;

    /** 可选智能体池（去重，含两个默认 + 额外候选） */
    private List<String> poolAgents;
}
