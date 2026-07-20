package com.aid.storyboard.service;

import com.aid.storyboard.dto.StoryboardVideoGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoFromImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoEdgeGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoGridGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoGenerateVO;

/**
 * 分镜图生视频服务。
 *
 * @author 视觉AID
 */
public interface IStoryboardVideoGenerationService
{
    /**
     * 发起分镜图生视频（多参方向，异步父任务）。
     *
     * @param request 入参（storyboardId 必填，其余可选）
     * @param userId  当前登录用户 ID
     * @return taskId + PENDING 的父任务视图
     */
    StoryboardVideoGenerateVO generateVideo(StoryboardVideoGenerateRequest request, Long userId);

    /**
     * 发起分镜图生视频（图生方向，异步父任务）。
     *
     * @param request 入参（storyboardId、images 必填，其余可选）
     * @param userId  当前登录用户 ID
     * @return taskId + PENDING 的父任务视图
     */
    StoryboardVideoGenerateVO generateVideoFromImage(StoryboardVideoFromImageGenerateRequest request, Long userId);

    /**
     * 发起分镜宫格生视频（宫格方向，仅 auto_grid 创作模式可用，异步父任务）。
     *
     * @param request 入参（storyboardIds 必填，其余可选）
     * @param userId  当前登录用户 ID
     * @return taskId + PENDING 的父任务视图
     */
    StoryboardVideoGenerateVO generateVideoFromGrid(StoryboardVideoGridGenerateRequest request, Long userId);

    /**
     * 发起分镜首尾帧生视频（首尾帧方向，异步父任务）。
     *
     * @param request 入参（storyboardIds、items 必填，其余可选）
     * @param userId  当前登录用户 ID
     * @return taskId + PENDING 的父任务视图
     */
    StoryboardVideoGenerateVO generateVideoFromEdge(StoryboardVideoEdgeGenerateRequest request, Long userId);

    /**
     * 续生分镜视频出片（断点续生，只补跑未成功镜头，已成功子任务不重复出片与扣费）。
     *
     * @param taskId 父任务 ID（aid_extract_task.id）
     * @param userId 当前登录用户 ID
     * @return taskId + PENDING 的父任务视图
     */
    StoryboardVideoGenerateVO resumeVideo(Long taskId, Long userId);

    /**
     * 媒体子任务终态回调：成功幂等落 {@code aid_gen_record}，失败计数，随后尝试父任务收尾。
     *
     * @param mediaTaskId aid_media_task.id
     * @param success     是否成功（成功且 ossUrl 非空才落库）
     * @param ossUrl      成功时的 OSS 持久化地址
     */
    void onMediaVideoTaskTerminal(Long mediaTaskId, boolean success, String ossUrl);

    /**
     * 扇入收尾（幂等）：父任务全部子任务到齐时置终态、推 SSE、释放锁与并发名额。
     *
     * @param taskId 父任务 ID（aid_extract_task.id）
     */
    void finalizeVideoBatchIfDone(Long taskId);
}
