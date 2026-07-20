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
     * 超时任务关闭：扫描超过最大存活时间的任务。
     *
     * @param batchSize 单次扫描上限
     * @return 本轮关闭的任务数
     */
    int closeTimeoutTasks(int batchSize);

    /**
     * 关闭「未提交上游」的僵尸任务：扫描 PENDING/QUEUED 且无 providerTaskId 且创建超过兜底时限的任务，
     * 终结为 FAILED 并退款。用于进程重启/崩溃在同步提交途中遗留的任务（closeTimeoutTasks 因要求
     * providerTaskId 非空、依赖调度快照而扫不到这类任务）。
     *
     * @param batchSize 单次扫描上限
     * @return 本轮关闭的任务数
     */
    int closeStaleUnsubmittedTasks(int batchSize);
}
