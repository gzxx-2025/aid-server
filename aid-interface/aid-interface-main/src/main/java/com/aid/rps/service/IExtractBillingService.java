package com.aid.rps.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 资产提取任务级计费服务：一次冻结、一次结算/退回，支持 SKU 差额结算（多退少补）。
 *
 * @author 视觉AID
 */
public interface IExtractBillingService {

    /** 当前父任务计费周期的文本调用归属；归属随快照持久化，运行期配置变化不影响已创建周期。 */
    record TextCallBillingContext(boolean mediaTaskBilling, String billingTraceId,
                                  String parentTaskType, String priorBillingTraceId,
                                  Long projectId, Long episodeId) { }

    /**
     * 续跑失败时需要恢复的任务字段。该状态与计费回滚上下文一起持久化，
     * 保证进程重启后仍能完成补偿。
     */
    record ResumeTaskState(String status, String errorMessage, String remark,
                           String inputSnapshot, Integer totalCount) { }

    /**
     * 续跑开始时需要写入的新任务字段。是否替换与字段值分开表达，允许显式写入 null。
     */
    record ResumeTaskMutation(boolean replaceRemark, String remark,
                              boolean replaceInputSnapshot, String inputSnapshot,
                              boolean replaceTotalCount, Integer totalCount) { }

    /**
     * 续跑计费周期句柄。字段取自 rearm 实际 CAS 的旧周期，不能由调用方提前缓存。
     */
    record ResumeBillingContext(String priorBillingStatus, String priorTraceId,
                                BigDecimal priorFrozenAmount, String priorBillingSnapshotJson,
                                String priorBillingSnapshotRefJson, String priorBillingSnapshotStage,
                                String resumeTraceId, ResumeTaskState resumeTaskState,
                                String dispatchMode, Long dispatchIntentMillis,
                                String rollbackState) { }

    /** 定时恢复所需的紧凑续跑上下文。 */
    record ResumeBillingRecovery(Long taskId, Long userId, ResumeBillingContext context) { }

    /**
     * 预冻结：提交任务前一次性冻结预估费用，同时保存计费快照
     *
     * @param taskId              提取任务ID
     * @param userId              用户ID
     * @param frozenAmount        冻结金额
     * @param billingSnapshotJson 计费快照JSON（SKU定价+token估值，可为null）
     */
    void prepareBilling(Long taskId, Long userId, BigDecimal frozenAmount, String billingSnapshotJson);

    /** 读取当前周期持久化的计费归属，供每次 Provider 文本调用决定是否由媒体子任务计费。 */
    TextCallBillingContext resolveTextCallBillingContext(Long taskId);

    /**
     * 在创建逐调用媒体子任务的同一事务中锁定父任务并校验执行周期。
     * 该门禁与续生回滚串行化，保证回滚确认“无 child”后不会再迟到插入本周期 child。
     */
    void assertRollingTextCallExecution(Long taskId, Long userId, String expectedTraceId);

    /**
     * 在文本调用的业务结果提交事务内锁定父任务并校验执行周期。
     * MEDIA_TASK 周期还会严格校验 owner/FROZEN；旧 PARENT_TASK 周期保留兼容，
     * 但同样不允许跨 trace 或失去执行权后写入业务结果。
     */
    void assertTextTaskBusinessCommit(Long taskId, Long userId, String expectedTraceId);

    /**
     * 聚合当前计费轮次的文本媒体任务用量。
     * 返回根级 token 合计、按模型拆分的调用统计，以及逐媒体子任务的紧凑 usage，
     * MQ 与本地执行共用同一协议。
     *
     * @param taskId 提取任务ID
     * @return 当前计费轮次的用量数据
     */
    Map<String, Object> aggregateTokenUsage(Long taskId);

    /**
     * 查询提取任务当前最大的媒体子任务ID，续跑时作为新计费轮次的用量水位线。
     *
     * @param taskId 提取任务ID
     * @return 最大媒体子任务ID；不存在时返回0
     */
    long findLatestExtractMediaTaskId(Long taskId);

    /**
     * 查询任务类型对应的最新文本媒体子任务ID，用作新计费轮次水位线。
     *
     * @param taskId 父任务ID
     * @return 最大媒体子任务ID；不存在时返回0
     */
    long findLatestBillingMediaTaskId(Long taskId);

    /**
     * 结算（差额）：任务成功后按 provider 实际 token usage 计算实际费用（多退少补）。
     *
     * @param taskId    提取任务ID
     * @param userId    用户ID
     * @param usageData 当前轮次聚合协议：包含 aggregation_complete、model_usages、call_usages、调用数和 token
     * @return true=结算成功，false=CAS失败或当前轮次用量尚未收敛
     */
    /** 按指定派发周期差额结算；trace 不匹配时不执行任何资金动作。 */
    boolean settleBilling(Long taskId, Long userId, Map<String, Object> usageData,
                          String expectedTraceId);

    /**
     * 执行失败后的计费收口：有可计费用量则结算，权威确认无调用时退款，
     * 子任务尚未收敛时保留冻结等待补偿。
     *
     * @return true=本轮已收口，false=周期变化或用量尚未收敛
     */
    boolean settleOrRefundAfterExecutionFailure(Long taskId, Long userId, String expectedTraceId);

    /** 按指定派发周期退款；trace 不匹配时不执行任何资金动作。 */
    boolean refundBilling(Long taskId, Long userId, String expectedTraceId);

    /**
     * 续生重置计费周期：把已完整结算（SUCCESS）、已退款（FAILED），
     * 或无冻结金额的派发周期重置为新一轮冻结。
     *
     * @param taskId              提取任务ID
     * @param userId              用户ID
     * @param frozenAmount        本次续生预冻结金额
     * @param billingSnapshotJson 本次续生计费快照JSON（可为null则退化全额结算）
     * @param expectedTerminalStatus 调用方已校验的原终态，事务内会再次校验
     * @param taskMutation         本轮需要原子写入的新任务字段
     * @param dispatchMode         本轮固定派发模式（MQ/LOCAL）
     * @return 本次新计费周期及其实际旧周期句柄
     */
    ResumeBillingContext rearmBillingForResume(Long taskId, Long userId, BigDecimal frozenAmount,
                                               String billingSnapshotJson,
                                               String expectedTerminalStatus,
                                               ResumeTaskMutation taskMutation,
                                               String dispatchMode);

    /**
     * 入队失败后，原子恢复业务任务字段并把续跑回滚上下文标记为待补偿。
     * 调用方必须在任务派发锁内调用，返回 false 时不得退款或释放执行名额。
     */
    boolean requestResumeBillingRollback(Long taskId, Long userId, ResumeBillingContext context);

    /**
     * 冻结完成后、真正调用立即入队前持久化派发意图，消除进程崩溃时是否已入队的歧义。
     */
    ResumeBillingContext markResumeBillingDispatchIntent(Long taskId, ResumeBillingContext context);

    /** 真正提交到 MQ/LOCAL 执行器后确认派发，保留周期上下文供终态清理与异常对账。 */
    void confirmResumeBillingSubmission(Long taskId, ResumeBillingContext context);

    /** 按派发令牌确认，供统一队列在执行器明确接受任务后调用。 */
    void confirmResumeBillingSubmission(Long taskId, String dispatchToken);

    /**
     * 续生回滚：rearmBillingForResume 成功后、入队前失败时退回本轮冻结并恢复上一轮已结算周期。
     *
     * @return true=退款已确认且已恢复上一轮周期；false=退款未确认、未恢复（待补偿）
     */
    boolean rollbackResumeBilling(Long taskId, Long userId, ResumeBillingContext context);

    /**
     * 查询超过安全窗口仍未收敛的续跑上下文，供统一队列在同一 task 派发锁内恢复。
     */
    List<ResumeBillingRecovery> listStaleResumeBillingRecoveries(int batchSize);

    /**
     * 在 task 派发锁内恢复 PREPARED/FUNDS_FROZEN/ROLLBACK_REQUIRED；
     * INTENT/CONFIRMED 返回 true 并留给队列恢复，false 表示任务没有活动续跑上下文。
     */
    boolean recoverResumeBillingIfNeeded(Long taskId, Long userId);

    /** 当前 trace 是否存在活动续跑上下文，用于拒绝上一轮无 token 的延迟消息。 */
    boolean hasActiveResumeBilling(Long taskId, String billingTraceId);

    /** 已确认入队但队列明确派发失败时，恢复原任务并退回本轮冻结。调用方须持有派发锁。 */
    boolean rollbackResumeAfterQueueFailure(Long taskId, Long userId);

    /**
     * 在派发锁内确认当前执行周期已无收据/租约后，回滚已领取但失活的续跑周期。
     */
    boolean rollbackDeadResumeExecution(Long taskId, Long userId, String dispatchToken);

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
