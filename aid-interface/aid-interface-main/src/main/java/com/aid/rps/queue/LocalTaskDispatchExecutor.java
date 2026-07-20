package com.aid.rps.queue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 本地派发执行器：调度放行后从 {@link LocalJobRegistry} 取出预注册 Runnable 在本服务线程池异步执行。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class LocalTaskDispatchExecutor implements TaskDispatchExecutor
{
    public static final String MODE = "LOCAL";

    @Value("${aid.taskq.local-executor.core-size:6}")
    private int coreSize;
    @Value("${aid.taskq.local-executor.max-size:12}")
    private int maxSize;
    @Value("${aid.taskq.local-executor.queue-capacity:100}")
    private int queueCapacity;

    @Autowired
    private LocalJobRegistry localJobRegistry;

    private volatile ExecutorService executor;

    private ExecutorService getExecutor()
    {
        ExecutorService exec = executor;
        if (exec != null)
        {
            return exec;
        }
        synchronized (this)
        {
            if (executor == null)
            {
                int core = coreSize > 0 ? coreSize : 6;
                int max = Math.max(maxSize, core);
                int cap = queueCapacity > 0 ? queueCapacity : 100;
                // 用 AbortPolicy（拒绝抛异常）而非 CallerRunsPolicy：
                //   dispatch() 由调度器单线程调用，若用 CallerRunsPolicy 队列满时会让调度线程亲自跑这个长任务，
                //   直接冻结整个排队调度。改为拒绝→dispatch 返回 false→任务回滚重排，调度线程永不被业务阻塞。
                executor = new ThreadPoolExecutor(
                        core, max, 60L, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(cap),
                        r -> {
                            Thread t = new Thread(r, "taskq-local-worker");
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
                log.info("本地派发线程池初始化: core={}, max={}, queueCap={}", core, max, cap);
            }
            return executor;
        }
    }

    @PreDestroy
    public void destroy()
    {
        if (executor != null)
        {
            executor.shutdownNow();
        }
    }

    @Override
    public String dispatchMode()
    {
        return MODE;
    }

    /**
     * 本地线程池是否已饱和：活跃线程达最大池且等待队列无剩余容量，供排队文案提示（不参与名额硬判定）。
     */
    @Override
    public boolean saturated()
    {
        ExecutorService exec = executor;
        if (!(exec instanceof ThreadPoolExecutor))
        {
            // 尚未初始化（无任务派发过）→ 视为未饱和
            return false;
        }
        ThreadPoolExecutor tpe = (ThreadPoolExecutor) exec;
        return tpe.getActiveCount() >= tpe.getMaximumPoolSize()
                && tpe.getQueue().remainingCapacity() == 0;
    }

    @Override
    public boolean dispatch(QueuedTaskContext ctx)
    {
        Long taskId = ctx.getTaskId();
        Runnable job = localJobRegistry.take(taskId);
        if (job == null)
        {
            log.warn("排队放行-本地job缺失(可能重启丢失内存态), 派发失败: taskId={}", taskId);
            return false;
        }
        try
        {
            getExecutor().execute(job);
            log.info("排队放行-本地任务已提交线程池: taskId={}", taskId);
            return true;
        }
        catch (java.util.concurrent.RejectedExecutionException rex)
        {
            // 线程池满（全局并发上限 >> 本地池容量 时可能发生）：这是临时性资源不足，任务本身没问题。
            //   把 job 放回注册表，抛 TaskDispatchRetryableException 让调度器走"撤销放行 + 重排"分支，
            //   保持任务可被下一拍重新放行；绝不判 FAILED、绝不退款（返回 false 会被上层误判为失败并触发退款）。
            log.warn("排队放行-本地线程池已满, 任务重新入队等待下一拍(请检查 全局并发上限 是否超过本地池容量): taskId={}", taskId);
            localJobRegistry.register(taskId, job);
            throw new TaskDispatchRetryableException("本地线程池已满");
        }
        catch (Exception e)
        {
            log.error("排队放行-本地任务提交失败: taskId={}", taskId, e);
            return false;
        }
    }
}
