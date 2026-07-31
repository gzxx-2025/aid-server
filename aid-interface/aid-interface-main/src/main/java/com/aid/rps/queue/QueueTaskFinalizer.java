package com.aid.rps.queue;

/**
 * 队列层把任务推进到终态（CANCELLED / FAILED）后的「业务收尾」回调 SPI，由业务层实现补齐锁释放等收尾。
 *
 * @author 视觉AID
 */
public interface QueueTaskFinalizer
{
    /**
     * 队列层已将任务置为终态（CANCELLED / FAILED）后回调，执行业务侧收尾（须幂等且不得抛出）。
     *
     * @param taskId 已落终态的任务ID
     */
    void onQueueTaskTerminated(Long taskId);
}
