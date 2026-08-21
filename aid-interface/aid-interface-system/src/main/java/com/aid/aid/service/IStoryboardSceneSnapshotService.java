package com.aid.aid.service;

/**
 * 分镜场景名称快照维护服务。
 *
 * @author 视觉AID
 */
public interface IStoryboardSceneSnapshotService
{
    /**
     * 同步场景改名后的分镜快照。
     *
     * @param projectId 项目ID
     * @param userId 用户ID
     * @param oldName 原场景名称
     * @param newName 新场景名称
     * @return 更新分镜数
     */
    int synchronizeSceneName(Long projectId, Long userId, String oldName, String newName);
}
