package com.aid.common.event;

import java.time.Instant;

/**
 * 项目审核通过事件。
 *
 * @param projectId 项目ID
 * @param ownerUserId 项目所属用户ID
 * @param approvedAt 审核通过时间
 */
public record ProjectAuditPassedEvent(Long projectId, Long ownerUserId, Instant approvedAt)
{
}
