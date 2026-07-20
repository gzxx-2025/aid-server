package com.aid.project.service;

/**
 * 项目级联删除服务。
 *
 * @author 视觉AID
 */
public interface IProjectCascadeDeleteService
{
    /**
     * 级联硬删除整个项目子树（含 OSS 清理）。调用方需先完成项目归属校验。
     *
     * @param projectId 项目ID
     * @param userId    操作用户ID
     */
    void deleteProjectCascade(Long projectId, Long userId);
}
