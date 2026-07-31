package com.aid.media.service;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.domain.vo.AiModelConfigVo;

/**
 * 统一任务调度中心：按供应商/模型策略驱动异步任务的轮询节奏。
 * 所有异步任务提交后，由调度中心统一驱动状态推进，前端不直接轮询上游。
 */
public interface TaskDispatchService {

    /**
     * 提交任务时调用：决定调度模式、冻结策略快照、设 nextPollTime。
     *
     * @param task        媒体任务实体（已落库）
     * @param modelConfig 模型配置（含 providerCode）
     */
    void initDispatchSchedule(AidMediaTask task, AiModelConfigVo modelConfig);

    /**
     * COMPOSE 合成任务调度接入：MPS 不在模型目录，无法走 {@link #initDispatchSchedule}（其依赖 modelConfig
     * 解析策略并按媒体类型回退）。本方法为合成任务冻结一份 CALLBACK_FIRST 策略快照（回调优先 + 轮询兜底），
     * 设置 WAIT_CALLBACK 状态与回调截止/首次轮询时间。
     *
     * @param task COMPOSE 任务实体（已落库、已提交上游并回填 providerTaskId）
     */
    void initComposeDispatchSchedule(AidMediaTask task);

    /**
     * 调度中心主循环：扫描到期任务并执行查询。
     *
     * @param batchSize 单次扫描上限
     * @return 本轮实际轮询的任务数
     */
    int dispatchDueTasks(int batchSize);

    /**
     * 回调超时转轮询：扫描 callbackDeadline 已过的 WAIT_CALLBACK 任务。
     *
     * @param batchSize 单次扫描上限
     * @return 本轮转换的任务数
     */
    int transitionExpiredCallbacks(int batchSize);

    /**
     * 存活异常对账：达到无进展/总时长警戒线后强制查询上游。
     * 已有 providerTaskId 的任务只接受厂商文档明确终态，不因本地超时自动失败。
     *
     * @param batchSize 单次扫描上限
     * @return 本轮完成上游复核的任务数
     */
    int closeTimeoutTasks(int batchSize);

    /**
     * 登记一次「上游仍在推进」的观测，前移无进展超时的起算点。
     * 轮询拿到「处理中」、回调带回非终态时调用；上游无响应时不得调用，否则失联任务永远判不了死。
     *
     * @param task 媒体任务实体（至少含 id 与 userId）
     */
    void markUpstreamProgress(AidMediaTask task);

    /**
     * 把回调命中的任务立即转入轮询；回调只作为唤醒信号，不直接确认终态。
     *
     * @param task 媒体任务实体（至少含 id 与 userId）
     */
    void scheduleImmediatePoll(AidMediaTask task);

    /**
     * 关闭「提交阶段中断」的僵尸任务：仅扫描 PENDING 且无 providerTaskId 且创建超过兜底时限的任务，
     * 终结为 FAILED 并退款。用于进程重启/崩溃在同步提交途中遗留的任务（closeTimeoutTasks 因要求
     * providerTaskId 非空、依赖调度快照而扫不到这类任务）。QUEUED 是合法排队，不超时关闭。
     *
     * @param batchSize 单次扫描上限
     * @return 本轮关闭的任务数
     */
    int closeStaleUnsubmittedTasks(int batchSize);
}
