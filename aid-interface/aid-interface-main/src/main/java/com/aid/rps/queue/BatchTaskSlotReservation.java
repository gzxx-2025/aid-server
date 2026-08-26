package com.aid.rps.queue;

import java.util.List;

/**
 * 批量任务逻辑槽凭证。
 *
 * @author 视觉AID
 */
public record BatchTaskSlotReservation(Long projectId, Long episodeId, List<String> logicalTypes,
                                       String ownerToken)
{
}
