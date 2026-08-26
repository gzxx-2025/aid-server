package com.aid.storyboard.service;

import com.aid.storyboard.dto.StoryboardMultiViewGridImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardMultiViewGridImageGenerateVO;
import com.aid.billing.vo.BillingQuoteVO;

/**
 * 分镜机位生图服务。
 *
 * @author 视觉AID
 */
public interface IStoryboardMultiViewGridImageService
{
    /**
     * 提交分镜机位生图任务。
     *
     * @param request 分镜机位生图请求
     * @param userId  当前登录用户 ID
     * @return 分镜机位生图任务视图
     */
    StoryboardMultiViewGridImageGenerateVO generateMultiViewGridImage(
            StoryboardMultiViewGridImageGenerateRequest request, Long userId);

    /** 无任务、无锁、无远程图片探测地报价同一机位图请求。 */
    BillingQuoteVO quoteMultiViewGridImage(
            StoryboardMultiViewGridImageGenerateRequest request, Long userId);
}
