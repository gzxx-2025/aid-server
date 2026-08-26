package com.aid.storyboard.service;

import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.storyboard.dto.LipSyncRequest;
import com.aid.storyboard.dto.StoryboardLipSyncBatchRequest;
import com.aid.billing.vo.BillingQuoteVO;

/**
 * 分镜对口型服务：台词现场 TTS 配音后与分镜视频一并提交对口型模型（单个 + 批量）。
 *
 * @author 视觉AID
 */
public interface IStoryboardLipSyncService {

    /**
     * 发起单分镜对口型（父任务受理，SSE 推进度）。
     *
     * @param request 对口型请求（分镜ID + 可选兜底音色与 TTS 参数）
     * @param userId  当前用户ID
     * @return 父任务VO（taskId + 状态 + 总数）
     */
    AssetExtractTaskVO lipSync(LipSyncRequest request, Long userId);

    /**
     * 发起批量对口型（父任务受理，SSE 推进度）。
     *
     * @param request 批量对口型请求
     * @param userId  当前用户ID
     * @return 父任务VO（taskId + 状态 + 总数）
     */
    AssetExtractTaskVO batchLipSync(StoryboardLipSyncBatchRequest request, Long userId);

    /** 单分镜 TTS + 对口型视频复合只读报价。 */
    BillingQuoteVO quoteLipSync(LipSyncRequest request, Long userId);

    /** 批量 TTS + 对口型视频复合只读报价。 */
    BillingQuoteVO quoteBatchLipSync(StoryboardLipSyncBatchRequest request, Long userId);

    /** 媒体子任务状态/OSS 结果变化后的内部编排推进入口。 */
    void onChildMediaTaskChanged(Long mediaTaskId);

    /** 服务重启或父任务租约失活后的内部持久化对账入口。 */
    void reconcileParentTask(Long parentTaskId);
}
