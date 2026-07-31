package com.aid.storyboard.service;

import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.storyboard.dto.StoryboardAudioBatchRequest;

/**
 * 分镜批量配音服务：逐分镜配音并合成配音视频（genType=compose）后自动设为使用中，仅做父任务编排。
 *
 * @author 视觉AID
 */
public interface IStoryboardAudioBatchService {

    /**
     * 提交批量配音（受理型异步，产物为逐分镜配音合成视频并自动切换使用中）。
     *
     * @param request 批量配音请求
     * @param userId  用户ID
     * @return 父任务受理结果（taskId + PENDING，前端订阅任务 SSE 获取进度与逐分镜结果）
     */
    AssetExtractTaskVO batchGenerateAudio(StoryboardAudioBatchRequest request, Long userId);
}
