package com.aid.media.service;

import com.aid.media.provider.ProviderTaskResult;

/**
 * 统一终态处理服务：回调与轮询都走同一入口，幂等收口。
 * 确保同一个任务无论通过回调还是轮询到达终态，都只会被处理一次。
 */
public interface TaskCompletionService {

    /**
     * 统一终态入口：回调或轮询都调用此方法，幂等处理。
     *
     * @param taskId     本地任务ID
     * @param taskResult provider 归一化查询结果
     * @return true = 本线程赢得终态处理权（执行了结算/退款），false = 已被其他路径处理
     */
    boolean completeTask(Long taskId, ProviderTaskResult taskResult);

    /**
     * 关闭「占槽后卡在提交阶段」的僵尸任务（PENDING 且无 providerTaskId，多由进程重启遗留）。
     * QUEUED 是合法排队，不允许通过本方法关闭。
     *
     * @param taskId       本地任务ID
     * @param errorMessage 失败原因（落 error_message）
     * @return true = 本线程赢得关闭权（执行了退款），false = 已被其他路径处理
     */
    boolean closeUnsubmittedTask(Long taskId, String errorMessage);

    /**
     * 用户停止父任务时关闭仍处于 QUEUED、尚未提交供应商的媒体子任务并退回冻结积分。
     * 已进入 PENDING/PROCESSING 的任务不由本方法处理，避免把已经发生的供应商调用误报为退款。
     *
     * @param taskId       本地媒体任务ID
     * @param userId       任务所属用户ID
     * @param errorMessage 取消原因
     * @return true = 本线程完成关闭和计费收口，false = 任务已推进或不属于该用户
     */
    boolean cancelQueuedTask(Long taskId, Long userId, String errorMessage);

    /**
     * 合成提交结果无法确认或应用重启时，把已占槽但未产生上游任务号的任务延迟放回队列，
     * 不退款、不判失败；云端依靠幂等键恢复同一任务，本地重新执行。
     */
    boolean requeueUnsubmittedTask(Long taskId);
}
