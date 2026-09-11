package com.aid.common.event;

/**
 * 项目级联删除事件。
 *
 * @param projectId 项目ID
 * @param ownerUserId 项目所属用户ID
 * @param sourceSnapshotId 复制来源快照ID
 */
public record ProjectCascadeDeletingEvent(Long projectId, Long ownerUserId, Long sourceSnapshotId)
{
}
