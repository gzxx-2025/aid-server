package com.aid.rps.queue;

/**
 * 批量任务「重启自愈」回收策略接口（启动回收专用），按任务类型各实现一个，禁止反向依赖高层 Service。
 *
 * @author 视觉AID
 */
public interface BatchTaskRestartRecovery
{
    /**
     * 是否由本策略负责回收该任务类型。
     *
     * @param taskType {@code aid_extract_task.task_type}
     * @return true=本策略负责
     */
    boolean supports(String taskType);

    /**
     * 回收被服务重启打断的任务，保证有产出的任务回到可续生终态。
     *
     * @param taskId 父任务 ID
     * @return true=已按本类型回收；false=非本类型 / 任务不存在，交回通用回收
     */
    boolean recover(Long taskId);
}
