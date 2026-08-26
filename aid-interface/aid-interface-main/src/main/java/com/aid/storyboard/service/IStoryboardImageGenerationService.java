package com.aid.storyboard.service;

import com.aid.storyboard.dto.StoryboardImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardImageGenerateVO;
import com.aid.billing.vo.BillingQuoteVO;
import com.aid.rps.queue.BatchTaskSlotReservation;

/**
 * 分镜图生成服务。
 *
 * @author 视觉AID
 */
public interface IStoryboardImageGenerationService
{
    /**
     * 发起分镜图生成（异步父任务，支持单/多镜头批量）。
     *
     * @param request 入参（storyboardIds 必填，其余可选）
     * @param userId  当前登录用户 ID
     * @return taskId + PENDING 的父任务视图
     */
    StoryboardImageGenerateVO generateImage(StoryboardImageGenerateRequest request, Long userId);

    /**
     * 内部批量编排入口。即使拆批后仅剩一个分镜，也保持批量任务自动设置主图的语义。
     *
     * @param request   入参
     * @param userId    当前登录用户 ID
     * @param batchMode 是否来自批量编排
     * @return taskId + PENDING 的父任务视图
     */
    StoryboardImageGenerateVO generateImage(StoryboardImageGenerateRequest request, Long userId, boolean batchMode);

    /** 组合链内部续用提示词父任务持有的逻辑槽。 */
    StoryboardImageGenerateVO generateImage(StoryboardImageGenerateRequest request, Long userId,
                                            boolean batchMode, BatchTaskSlotReservation slotReservation);

    /** 按分镜业务入参映射到统一图片计费链的只读报价。 */
    BillingQuoteVO quoteImage(StoryboardImageGenerateRequest request, Long userId);

    /** 合并流程中提示词尚未生成时，仅基于已知模型/规格/数量的图片报价。 */
    BillingQuoteVO quotePlannedImage(StoryboardImageGenerateRequest request, Long userId);

    /**
     * 续生分镜图出图（断点续生，只补跑未成功镜头，已成功子任务不重复出图与扣费）。
     *
     * @param taskId 父任务 ID（aid_extract_task.id）
     * @param userId 当前登录用户 ID
     * @return taskId + PENDING 的父任务视图
     */
    StoryboardImageGenerateVO resumeImage(Long taskId, Long userId);

    /** 分镜图片缺失槽位续生报价。 */
    BillingQuoteVO quoteResumeImage(Long taskId, Long userId);

    /**
     * 媒体子任务终态回调：成功幂等落 {@code aid_gen_record}，失败计数，随后尝试父任务收尾。
     *
     * @param mediaTaskId aid_media_task.id
     * @param success     是否成功（成功且 ossUrl 非空才落库）
     * @param ossUrl      成功时的 OSS 持久化地址
     */
    void onMediaImageTaskTerminal(Long mediaTaskId, boolean success, String ossUrl);

    /**
     * 扇入收尾（幂等）：父任务全部子任务到齐时置终态、推 SSE、释放锁与并发名额。
     *
     * @param taskId 父任务 ID（aid_extract_task.id）
     */
    void finalizeImageBatchIfDone(Long taskId);
}
