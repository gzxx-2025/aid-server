package com.aid.projectgenconfig.service;

import com.aid.projectgenconfig.enums.ProjectGenConfigScene;

/**
 * 项目级生成配置解析器（共用）。
 *
 * @author 视觉AID
 */
public interface IProjectGenConfigResolver
{
    /**
     * 解析单场景的最终生效配置（不带剧集维度，分镜矩阵场景按项目默认创作模式解析）。
     *
     * @param projectId             项目ID（必填，用于查项目配置 + 取项目模式/创作模式/剧本类型）
     * @param userId                用户ID（必填，配置表按 项目+用户+场景 索引）
     * @param scene                 场景枚举（必填）
     * @param requestedAgentCode    用户传入的智能体编码（可空；非空时优先使用并校验）
     * @param requestedModelCode    用户传入的模型编码（可空；非空时优先使用并校验）
     * @param requestedResolution   用户传入的清晰度（可空；图片场景生效，非空时优先使用并校验）
     * @param requestedAspectRatio  用户传入的比例（可空；图片场景生效，非空时优先使用并校验）
     * @return 解析结果（智能体 + 模型 + 清晰度 + 比例），所有字段对应场景已通过校验
     */
    ResolvedSceneConfig resolve(Long projectId, Long userId, ProjectGenConfigScene scene,
                                String requestedAgentCode, String requestedModelCode,
                                String requestedResolution, String requestedAspectRatio);

    /**
     * 解析单场景的最终生效配置（带剧集维度）。
     *
     * @param projectId             项目ID（必填）
     * @param episodeId             剧集ID（可空；剧集类项目用于取剧集创作模式）
     * @param userId                用户ID（必填）
     * @param scene                 场景枚举（必填）
     * @param requestedAgentCode    用户传入的智能体编码（可空）
     * @param requestedModelCode    用户传入的模型编码（可空）
     * @param requestedResolution   用户传入的清晰度（可空）
     * @param requestedAspectRatio  用户传入的比例（可空）
     * @return 解析结果
     */
    ResolvedSceneConfig resolve(Long projectId, Long episodeId, Long userId, ProjectGenConfigScene scene,
                                String requestedAgentCode, String requestedModelCode,
                                String requestedResolution, String requestedAspectRatio);
}
