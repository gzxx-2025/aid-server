package com.aid.rps.service;

import com.aid.billing.vo.BillingQuoteVO;

/**
 * 统一「继续生成（续生）」分发服务。
 *
 * @author 视觉AID
 */
public interface ITaskResumeService
{
    /**
     * 按任务类型分发续生。
     *
     * @param taskId 父任务 ID（aid_extract_task.id）
     * @param userId 当前登录用户 ID
     * @return 对应类型续生实现返回的视图对象（AssetExtractTaskVO / StoryboardVideoGenerateVO /
     *         StoryboardImageGenerateVO 等，按类型而定），统一装进 AjaxResult 的 data 返回
     */
    Object resume(Long taskId, Long userId);

    /** 按正式续生目标生成无副作用权威报价。 */
    BillingQuoteVO quoteResume(Long taskId, Long userId);
}
