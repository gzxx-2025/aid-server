package com.aid.storyboard.service;

import com.aid.storyboard.dto.StoryboardEditImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardEditImageGenerateVO;
import com.aid.billing.vo.BillingQuoteVO;

/**
 * 分镜编辑图生成服务。
 *
 * @author 视觉AID
 */
public interface IStoryboardEditImageService
{
    /**
     * 提交分镜编辑图生成任务。
     *
     * @param request 入参（含 storyboardId / referenceImage / prompt / modelCode / aspectRatio / size / imageCount）
     * @param userId  当前登录用户 ID
     * @return 生成结果 VO（含 recordIds / imageUrls / status / 可选 taskId）
     */
    StoryboardEditImageGenerateVO generateEditImage(StoryboardEditImageGenerateRequest request, Long userId);

    /** 无任务、无锁、无远程图片探测地报价同一编辑图媒体计划。 */
    BillingQuoteVO quoteEditImage(StoryboardEditImageGenerateRequest request, Long userId);
}
