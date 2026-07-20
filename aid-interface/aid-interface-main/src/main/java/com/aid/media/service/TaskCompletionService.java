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
     * 关闭「尚未提交上游」的僵尸任务（PENDING/QUEUED 且无 providerTaskId，多由进程重启遗留）。
     * CAS 将状态从 PENDING/QUEUED 改为 FAILED，并退回冻结金额、释放并发坑位，退款幂等。
     *
     * @param taskId       本地任务ID
     * @param errorMessage 失败原因（落 error_message）
     * @return true = 本线程赢得关闭权（执行了退款），false = 已被其他路径处理
     */
    boolean closeUnsubmittedTask(Long taskId, String errorMessage);
}
