package com.aid.rps.queue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * 本地派发任务执行体注册表（叶子组件，用于打破 TaskQueueService ⇄ LocalTaskDispatchExecutor 循环依赖）。
 *
 * @author 视觉AID
 */
@Component
public class LocalJobRegistry
{
    /** taskId → 带派发周期的执行体（内存态，重启即丢失）。 */
    private final Map<Long, RegisteredJob> localJobs = new ConcurrentHashMap<>();

    private record RegisteredJob(String dispatchToken, Runnable runnable) {}

    /**
     * 注册本地任务执行体（入队前调用）。
     */
    public void register(Long taskId, String dispatchToken, Runnable job)
    {
        if (taskId != null && dispatchToken != null && !dispatchToken.isBlank() && job != null)
        {
            localJobs.put(taskId, new RegisteredJob(dispatchToken, job));
        }
    }

    /**
     * 取出并移除本地任务执行体（放行执行时调用）。
     *
     * @return 执行体；不存在（如重启后内存态丢失）返回 null
     */
    public Runnable take(Long taskId, String dispatchToken)
    {
        if (taskId == null || dispatchToken == null || dispatchToken.isBlank())
        {
            return null;
        }
        RegisteredJob registered = localJobs.get(taskId);
        if (registered == null || !Objects.equals(dispatchToken, registered.dispatchToken()))
        {
            return null;
        }
        return localJobs.remove(taskId, registered) ? registered.runnable() : null;
    }

    /**
     * 移除本地任务执行体（取消 / 释放名额时清理，避免内存泄漏）。
     */
    public void remove(Long taskId, String dispatchToken)
    {
        if (taskId != null && dispatchToken != null && !dispatchToken.isBlank())
        {
            RegisteredJob registered = localJobs.get(taskId);
            if (registered != null && Objects.equals(dispatchToken, registered.dispatchToken()))
            {
                localJobs.remove(taskId, registered);
            }
        }
    }

    /**
     * 是否存在指定任务的本地执行体。
     */
    public boolean contains(Long taskId, String dispatchToken)
    {
        if (taskId == null || dispatchToken == null || dispatchToken.isBlank())
        {
            return false;
        }
        RegisteredJob registered = localJobs.get(taskId);
        return registered != null && Objects.equals(dispatchToken, registered.dispatchToken());
    }
}
