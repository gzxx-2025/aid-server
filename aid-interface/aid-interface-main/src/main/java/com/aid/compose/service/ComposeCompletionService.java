package com.aid.compose.service;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.media.provider.ProviderTaskResult;

/**
 * 合成任务终态收口的计费结算/退款（独立合成分支，绕开 BillingFacadeService）。
 *
 * @author 视觉AID
 */
public interface ComposeCompletionService {

    /**
     * 合成成功收口：按 MPS 实际输出时长结算（多退少补），持久化实际时长与计费状态。
     *
     * @param task   COMPOSE 任务（已 CAS 抢到 SUCCEEDED 终态）
     * @param result Provider 查询结果（含 outputDurationSeconds）
     */
    void onSucceeded(AidMediaTask task, ProviderTaskResult result);

    /**
     * 合成失败收口：全额退款；接口2 任务额外回写 aid_episode_editor 失败终态。
     *
     * @param task   COMPOSE 任务（已 CAS 抢到 FAILED 终态）
     * @param result Provider 查询结果（含错误信息）
     */
    void onFailed(AidMediaTask task, ProviderTaskResult result);

    /**
     * 合成执行中进度回写：把 MPS 真实进度（0-100）写入 aid_episode_editor.export_progress，
     * 供前端导出进度查询接口展示。仅对接口2 任务（callback_category=episode_editor）生效；
     * 进度只增不减、上限收敛到 99（100 由成功收口写入），记录已非「合成中」时跳过。
     * 本方法失败仅打日志，不影响调度主链路。
     *
     * @param task     COMPOSE 任务（处理中）
     * @param progress 上游返回的进度百分比（可空，空则忽略）
     */
    void onProgress(AidMediaTask task, Integer progress);
}
