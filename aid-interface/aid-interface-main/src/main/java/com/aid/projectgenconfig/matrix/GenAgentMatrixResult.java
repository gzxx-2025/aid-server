package com.aid.projectgenconfig.matrix;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * 智能体矩阵解析结果。
 * 给定 (业务场景 × 创作模式 × 剧本类型 × 模型策略) 解析出的默认智能体/模型 + 可选池。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class GenAgentMatrixResult {

    /** 业务场景编码（=biz_category_code=func_code） */
    private String bizCategoryCode;

    /** 默认智能体编码（该组合 is_default=1；无则为 null） */
    private String agentCode;

    /** 默认模型编码（随默认智能体；无则为 null） */
    private String modelCode;

    /** 默认清晰度（仅图片场景；文字场景为 null） */
    private String resolution;

    /** 默认比例（仅图片场景；文字场景为 null） */
    private String aspectRatio;

    /** 可选智能体池（该组合下所有启用的候选 agentCode，去重） */
    private List<String> agentPool;

    /** 该组合是否在矩阵中有配置（无配置=该场景不适用于当前创作模式） */
    private boolean configured;
}
