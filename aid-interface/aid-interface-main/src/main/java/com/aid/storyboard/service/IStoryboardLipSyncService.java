package com.aid.storyboard.service;

import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.storyboard.dto.LipSyncRequest;
import com.aid.storyboard.dto.StoryboardLipSyncBatchRequest;
import com.aid.storyboard.vo.AudioTaskVO;

/**
 * 分镜对口型服务：台词现场 TTS 配音后与分镜视频一并提交对口型模型（单个 + 批量）。
 *
 * @author 视觉AID
 */
public interface IStoryboardLipSyncService {

    /**
     * 发起单分镜对口型（异步受理）。
     *
     * @param request 对口型请求（分镜ID + 可选兜底音色与 TTS 参数）
     * @param userId  当前用户ID
     * @return 配音任务VO（前端按 id 轮询对口型进度）
     */
    AudioTaskVO lipSync(LipSyncRequest request, Long userId);

    /**
     * 发起批量对口型（父任务受理，SSE 推进度）。
     *
     * @param request 批量对口型请求
     * @param userId  当前用户ID
     * @return 父任务VO（taskId + 状态 + 总数）
     */
    AssetExtractTaskVO batchLipSync(StoryboardLipSyncBatchRequest request, Long userId);
}
