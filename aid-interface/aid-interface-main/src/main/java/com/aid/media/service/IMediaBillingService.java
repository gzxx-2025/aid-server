package com.aid.media.service;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.domain.vo.AiModelConfigVo;

/**
 * 媒体任务三阶段计费服务：预冻结 → 执行 → 结算/退回。
 * 1) prepareBilling：从 balance 冻结预估费用到 frozenBalance，幂等（billingTraceId 防重）
 * 2) settleBilling：任务成功，从 frozenBalance 扣减并累加 totalConsumption
 * 3) refundBilling：任务失败，从 frozenBalance 退回到 balance
 */
public interface IMediaBillingService {

    /**
     * 预冻结：任务提交前冻结预估费用。
     * 成功时 task.billingStatus=FROZEN, task.frozenAmount=冻结金额。
     * 失败时抛 ServiceException（余额不足），billingStatus=FAILED。
     *
     * @param task        媒体任务（需已设置 userId）
     * @param modelConfig 模型配置（含 costCredits 单价）
     */
    void prepareBilling(AidMediaTask task, AiModelConfigVo modelConfig);

    /**
     * 结算：任务成功后从冻结余额扣减，累加累计消费。
     * 仅 billingStatus=FROZEN 时执行，否则跳过。
     *
     * @param task 媒体任务（需已设置 userId、frozenAmount）
     * @return true = 本线程抢到 CAS（或无需 CAS），task 已更新；
     *         false = CAS 失败，task 未被修改，调用方需从 DB 同步 billingStatus
     */
    boolean settleBilling(AidMediaTask task);

    /**
     * 退回：任务失败后将冻结金额退回到可用余额。
     * 仅 billingStatus=FROZEN 时执行，否则跳过。
     *
     * @param task 媒体任务（需已设置 userId、frozenAmount）
     * @return true = 本线程抢到 CAS（或无需 CAS），task 已更新；
     *         false = CAS 失败，task 未被修改，调用方需从 DB 同步 billingStatus
     */
    boolean refundBilling(AidMediaTask task);

    /**
     * 补偿扫描：扫描 billing_status 为 SETTLING/REFUNDING/FROZEN 且超过2分钟的媒体任务，
     * 重试结算或退回。账户操作自带幂等守卫，重复调用安全。
     *
     * @param batchSize 单次批量拉取上限
     * @return 本次成功处理的任务数
     */
    int retryStaleBillings(int batchSize);
}
