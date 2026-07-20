package com.aid.rps.queue;

/**
 * 任务派发执行器 SPI：调度器抢到名额并 CAS 到 PENDING 后，由对应 dispatchMode 的执行器真正派发去执行。
 *
 * @author 视觉AID
 */
public interface TaskDispatchExecutor
{
    /** 本执行器负责的派发模式标识，与 {@link QueuedTaskContext#getDispatchMode()} 对应 */
    String dispatchMode();

    /**
     * 真正派发任务去执行（此时名额已抢到、任务已 CAS 到 PENDING）。
     *
     * @param ctx 排队任务上下文
     * @return true=派发成功；false=派发失败（调度器将回滚名额 + 标记任务失败 + 退款 + SSE 通知）
     * @throws TaskDispatchRetryableException 临时性资源不足（如本地线程池打满）时抛出，调度器撤销放行 + 重排，不判失败
     */
    boolean dispatch(QueuedTaskContext ctx);

    /**
     * 本执行器当前是否已饱和（无法再接收新任务），供排队文案如实展示受限维度；默认 false。
     */
    default boolean saturated()
    {
        return false;
    }
}
