package com.aid.rps.queue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 本地派发任务执行体注册表（叶子组件，用于打破 TaskQueueService ⇄ LocalTaskDispatchExecutor 循环依赖）。
 *
 * @author 视觉AID
 */
@Component
public class LocalJobRegistry
{
    /** taskId → 执行 Runnable（内存态，重启即丢失，符合"本地任务重启即判失败"预期） */
    private final Map<Long, Runnable> localJobs = new ConcurrentHashMap<>();

    /**
     * 注册本地任务执行体（入队前调用）。
     */
    public void register(Long taskId, Runnable job)
    {
        if (taskId != null && job != null)
        {
            localJobs.put(taskId, job);
        }
    }

    /**
     * 取出并移除本地任务执行体（放行执行时调用）。
     *
     * @return 执行体；不存在（如重启后内存态丢失）返回 null
     */
    public Runnable take(Long taskId)
    {
        if (taskId == null)
        {
            return null;
        }
        return localJobs.remove(taskId);
    }

    /**
     * 移除本地任务执行体（取消 / 释放名额时清理，避免内存泄漏）。
     */
    public void remove(Long taskId)
    {
        if (taskId != null)
        {
            localJobs.remove(taskId);
        }
    }

    /**
     * 是否存在指定任务的本地执行体。
     */
    public boolean contains(Long taskId)
    {
        return taskId != null && localJobs.containsKey(taskId);
    }
}
