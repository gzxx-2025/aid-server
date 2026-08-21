package com.aid.rps.model;

/**
 * 分镜当前场景上下文。
 *
 * @param storyboardId 分镜ID
 * @param sceneAssetId 当前有效场景资产ID
 * @param sceneName 场景展示名称
 * @param sceneDescription 场景描述
 * @param referenceImageName 实际可用场景图片名称
 */
public record StoryboardSceneContext(Long storyboardId, Long sceneAssetId, String sceneName,
                                     String sceneDescription, String referenceImageName)
{
}
