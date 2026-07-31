package com.aid.projectgenconfig.service;

import lombok.Builder;
import lombok.Data;

/**
 * 项目级场景配置解析结果。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class ResolvedSceneConfig
{
    /** 场景编码 */
    private String sceneCode;

    /** 最终生效的智能体编码 */
    private String agentCode;

    /** 最终生效的模型编码 */
    private String modelCode;

    /** 清晰度（图片场景非空，文字场景为 null） */
    private String resolution;

    /** 图片比例（图片场景非空，文字场景为 null） */
    private String aspectRatio;
}
