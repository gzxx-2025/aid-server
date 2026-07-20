package com.aid.rps.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 资产提取任务级计费服务：一次冻结、一次结算/退回，支持 SKU 差额结算（多退少补）。
 *
 * @author 视觉AID
 */
public interface IExtractBillingService {

    /**
     * 预冻结：提交任务前一次性冻结预估费用，同时保存计费快照
     *
     * @param taskId              提取任务ID
     * @param userId              用户ID
     * @param frozenAmount        冻结金额
     * @param billingSnapshotJson 计费快照JSON（SKU定价+token估值，可为null）
     */
    void prepareBilling(Long taskId, Long userId, BigDecimal frozenAmount, String billingSnapshotJson);

    /**
     * 结算（全额）：任务成功后将冻结金额全额转入消费
     *
     * @param taskId 提取任务ID
     * @param userId 用户ID
     * @return true=结算成功，false=CAS失败（已被其他线程处理）
     */
    boolean settleBilling(Long taskId, Long userId);

    /**
     * 结算（差额）：任务成功后按 provider 实际 token usage 计算实际费用（多退少补）。
     *
     * @param taskId    提取任务ID
     * @param userId    用户ID
     * @param usageData 实际 token usage（input_tokens, output_tokens）
     * @return true=结算成功，false=CAS失败
     */
    boolean settleBilling(Long taskId, Long userId, Map<String, Object> usageData);

    /**
     * 退回：任务失败后将冻结金额退回余额
     *
     * @param taskId 提取任务ID
     * @param userId 用户ID
     * @return true=退回成功，false=CAS失败
     */
    boolean refundBilling(Long taskId, Long userId);

    /**
     * 续生重置计费周期：把已完整结算（SUCCESS）或已退款（FAILED）的任务。
     *
     * @param taskId              提取任务ID
     * @param userId              用户ID
     * @param frozenAmount        本次续生预冻结金额
     * @param billingSnapshotJson 本次续生计费快照JSON（可为null则退化全额结算）
     */
    void rearmBillingForResume(Long taskId, Long userId, BigDecimal frozenAmount, String billingSnapshotJson);

    /**
     * 续生回滚：rearmBillingForResume 成功后、入队前失败时退回本轮冻结并恢复上一轮已结算周期。
     *
     * @return true=退款已确认且已恢复上一轮周期；false=退款未确认、未恢复（待补偿）
     */
    boolean rollbackResumeBilling(Long taskId, Long userId, String priorBillingStatus,
                                  String priorTraceId, BigDecimal priorFrozenAmount,
                                  String priorBillingSnapshotJson, String priorBillingSnapshotRefJson);

    /**
     * 按主表快照引用读取真实快照JSON；主表仍是完整JSON时原样返回。
     *
     * @param taskId 提取任务ID
     * @param billingSnapshotJson 主表快照字段
     * @return 真实快照JSON
     */
    String resolveBillingSnapshotJson(Long taskId, String billingSnapshotJson);

    /**
     * 按主表快照引用恢复真实快照JSON。
     *
     * @param taskId 提取任务ID
     * @param billingSnapshotJson 真实快照JSON
     * @param billingSnapshotRefJson 主表快照字段
     */
    void restoreBillingSnapshotJson(Long taskId, String billingSnapshotJson, String billingSnapshotRefJson);

    /**
     * 补偿结算：扫描 FROZEN/SETTLING/REFUNDING 且超时的记录，重试结算或退回。
     *
     * @param batchSize 单次扫描数量
     * @return 本次处理的记录数
     */
    int retryStaleFrozenBillings(int batchSize);

    /**
     * 追补扫描：扫描 PARTIAL_SUCCESS 的提取任务，从可用余额追补剩余差额，补齐后推进到 SUCCESS。
     *
     * @param batchSize 单次批量拉取上限
     * @return 本次成功处理的任务数
     */
    int retryPartialExtraCharges(int batchSize);
}
