package com.aid.storyboard.service;

import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.storyboard.dto.StoryboardImageUpscaleRequest;

/**
 * 分镜图高清服务。
 *
 * @author 视觉AID
 */
public interface IStoryboardImageUpscaleService
{
    /**
     * 提交分镜图高清任务。
     *
     * @param request 入参（含 genRecordId / modelCode / 可选 resolution）
     * @param userId  当前登录用户 ID
     * @return 任务视图（taskId + PENDING）
     */
    AssetExtractTaskVO upscaleStoryboardImage(StoryboardImageUpscaleRequest request, Long userId);
}
