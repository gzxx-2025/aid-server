package com.aid.rps.queue;

/**
 * 查询不由 aid_extract_task 承载的批量任务活跃状态。
 *
 * @author 视觉AID
 */
public interface BatchTaskExternalActivityProbe
{
    boolean supports(String logicalType);

    boolean hasActiveTask(Long projectId, Long episodeId, String logicalType);
}
