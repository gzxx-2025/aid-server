package com.aid.rps.service;

import java.util.List;

import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.dto.StoryboardScriptBatchRequest;
import com.aid.billing.vo.BillingQuoteVO;

/**
 * 按项目和剧集维度使用当前有效剧本生成分镜脚本。
 *
 * @author 视觉AID
 */
public interface IStoryboardScriptService
{
    /**
     * 批量分镜脚本生成（父任务模式）。
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID（电影项目固定传 0）
     * @param userId    当前用户ID
     * @param requestSceneIds 可选场景ID列表：null/空则跑全部，传了则只跑指定场景（用于失败重试）
     * @param agentCode 智能体编码（默认 aid_storyboard_script_extractor）
     * @param modelCode 可选，用户指定的文本模型；为空则走智能体默认配置
     * @param mode      拆分模式（精简模式/标准模式/细拆模式）
     * @param overwrite 是否覆盖已有分镜脚本（默认 false：已存在则拒绝；true：成功后再替换）
     * @return 单个父任务VO（含 taskId 和 PENDING 状态）
     */
    AssetExtractTaskVO batchGenerateStoryboardScript(Long projectId, Long episodeId, Long userId,
                                                      List<Long> requestSceneIds,
                                                      String agentCode, String modelCode, String mode,
                                                      Boolean overwrite);

    /** 无副作用地复用正式直驱批次计划生成分镜脚本报价。 */
    BillingQuoteVO quoteStoryboardScript(StoryboardScriptBatchRequest request, Long userId);

    /**
     * 按当前有效剧本片段生成并保存分镜脚本。
     *
     * @param taskId 父任务ID
     * @param userId 当前用户ID
     * @return 批次执行结果JSON
     */
    String doStoryboardScriptBatch(Long taskId, Long userId, String dispatchToken);

    /**
     * 分镜脚本任务续生。
     *
     * @param taskId 原父任务 ID
     * @param userId 当前用户 ID
     * @return 续生提交后的任务 VO（taskId + PROCESSING）
     */
    AssetExtractTaskVO resumeStoryboardScript(Long taskId, Long userId);

    /** 分镜脚本失败批次续生报价。 */
    BillingQuoteVO quoteResumeStoryboardScript(Long taskId, Long userId);
}
