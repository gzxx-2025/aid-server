package com.aid.rps.service;

import java.util.List;
import java.util.Map;

import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.dto.StoryboardVideoPromptBatchRequest;
import com.aid.rps.dto.StoryboardVideoPromptImageBatchRequest;
import com.aid.rps.dto.StoryboardVideoWithPromptRequest;
import com.aid.billing.vo.BillingQuoteVO;

/**
 * 批量生成"分镜视频提示词"服务（视觉导演多参版）。
 *
 * @author 视觉AID
 */
public interface IStoryboardVideoPromptService
{
    /**
     * 批量提交"分镜视频提示词生成"父任务。
     *
     * @param projectId      项目 ID
     * @param episodeId      剧集 ID（电影 0）
     * @param userId         当前用户 ID
     * @param storyboardIds  目标分镜 ID 列表（可选）：不传/为空时处理本剧集全部分镜
     * @param agentCode      智能体编码（默认 aid_visual_director）
     * @param modelCode      用户指定文本模型；为空走智能体默认
     * @param overwrite      仅在不传 storyboardIds（全集）时生效：true=重新生成(全部覆盖)，
     * @param continuityMode 跨镜连续性模式；为空按 none
     * @param previousStoryboardId 单镜开启连续性时指定的直接上一镜 ID
     * @param chainNext      提示词完成后的可选链式任务参数
     * @return 父任务 VO（含 taskId / status=PENDING / totalShots 计数）
     */
    AssetExtractTaskVO batchGenerateVideoPrompt(Long projectId, Long episodeId, Long userId,
                                                List<Long> storyboardIds,
                                                String agentCode, String modelCode,
                                                Boolean overwrite, String continuityMode,
                                                Long previousStoryboardId,
                                                Map<String, Object> chainNext);

    /**
     * 批量提交"图生方向（漫剧版）分镜视频提示词生成"父任务。
     *
     * @param projectId             项目 ID
     * @param episodeId             剧集 ID
     * @param userId                当前用户 ID
     * @param storyboardIds         目标分镜 ID 列表
     * @param agentCode             智能体编码（默认 aid_visual_director_image）
     * @param modelCode             文本模型编码
     * @param overwrite             全集请求是否覆盖已有提示词
     * @param continuityMode        跨镜连续性模式；为空按 none
     * @param previousStoryboardId  单镜开启连续性时指定的直接上一镜 ID
     * @return 父任务 VO
     */
    AssetExtractTaskVO batchGenerateVideoPromptImage(Long projectId, Long episodeId, Long userId,
                                                     List<Long> storyboardIds,
                                                     String agentCode, String modelCode,
                                                     Boolean overwrite, String continuityMode,
                                                     Long previousStoryboardId);

    /**
     * 批量提交"宫格方向（auto_grid 专业版）分镜视频提示词生成"父任务。
     *
     * @param projectId             项目 ID
     * @param episodeId             剧集 ID
     * @param userId                当前用户 ID
     * @param storyboardIds         目标分镜 ID 列表
     * @param agentCode             智能体编码（默认 aid_visual_director_grid）
     * @param modelCode             文本模型编码
     * @param overwrite             全集请求是否覆盖已有提示词
     * @param continuityMode        跨镜连续性模式；为空按 none
     * @param previousStoryboardId  单镜开启连续性时指定的直接上一镜 ID
     * @return 父任务 VO
     */
    AssetExtractTaskVO batchGenerateVideoPromptGrid(Long projectId, Long episodeId, Long userId,
                                                    List<Long> storyboardIds,
                                                    String agentCode, String modelCode,
                                                    Boolean overwrite, String continuityMode,
                                                    Long previousStoryboardId);

    /** 无副作用地复用正式提交计划，报价多参方向的视频提示词。 */
    BillingQuoteVO quoteVideoPrompt(StoryboardVideoPromptBatchRequest request, Long userId);

    /** 无副作用地复用正式提交计划，报价图生方向的视频提示词。 */
    BillingQuoteVO quoteVideoPromptImage(StoryboardVideoPromptImageBatchRequest request, Long userId);

    /** 无副作用地复用正式提交计划，报价宫格方向的视频提示词。 */
    BillingQuoteVO quoteVideoPromptGrid(StoryboardVideoPromptImageBatchRequest request, Long userId);

    /** 按正式 creationMode 路由，聚合提示词与后续出片两段权威报价。 */
    BillingQuoteVO quoteVideoWithPrompt(StoryboardVideoWithPromptRequest request, Long userId);

    /**
     * 【统一视频合一】批量生成分镜视频提示词 + 自动出片，按创作模式自动路由（一次点击两阶段）。
     *
     * @param request 统一视频合一请求（提示词模型/是否覆盖 + 出片模型·比例·时长·音频）
     * @param userId  当前用户 ID
     * @return 提示词父任务 VO（含 taskId / status=PENDING）
     */
    AssetExtractTaskVO batchGenerateVideoWithPromptAuto(StoryboardVideoWithPromptRequest request, Long userId);

    /**
     * Consumer 调用：实际执行分镜视频提示词批量生成。
     * 整段批量：把【全局风格 + 整段分镜脚本表(18字段)】一次性喂给视觉导演，一次产出 JSON 数组按序回填。
     * 必须独立写库，不依赖外层事务。返回结果 JSON（供 {@code aid_extract_task.result_data} 落盘）。
     * 计费按本次实际 token 结算（首跑与续生统一：均经任务计费快照 + 本次 provider 真实 token 结算，
     * 续生先经 rearmBillingForResume 重置为新一轮计费周期，TOKEN 口径多退少补）。
     * 全部失败抛异常让 Consumer 标 FAILED；部分失败由外层判定 PARTIAL_FAILED。
     */
    String doStoryboardVideoPromptBatch(Long taskId, Long userId, String dispatchToken);

    /**
     * 续生：仅对 {@code PARTIAL_FAILED} 终态任务可调，重跑未生成 video_prompt 的镜头。
     */
    AssetExtractTaskVO resumeVideoPrompt(Long taskId, Long userId);

    /** 分镜视频提示词剩余镜头续生报价。 */
    BillingQuoteVO quoteResumeVideoPrompt(Long taskId, Long userId);

    /**
     * 手动落库单条分镜的视频提示词（用户在前端手动填写后保存）。
     *
     * @param storyboardId 分镜 ID
     * @param videoPrompt  用户填写的视频提示词
     * @param userId       当前用户 ID
     */
    void saveManualVideoPrompt(Long storyboardId, String videoPrompt, Long userId);

}
