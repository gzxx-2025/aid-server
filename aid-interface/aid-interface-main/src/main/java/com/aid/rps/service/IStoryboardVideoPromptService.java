package com.aid.rps.service;

import java.util.List;
import java.util.Map;

import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.dto.StoryboardVideoWithPromptRequest;

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
     * @param storyboardIds  目标分镜 ID 列表（可选）：不传/为空 → 处理本剧集全部分镜（由 overwrite 区分
     * @param agentCode      智能体编码（默认 aid_visual_director）
     * @param modelCode      用户指定文本模型；为空走智能体默认
     * @param overwrite      仅在不传 storyboardIds（全集）时生效：true=重新生成(全部覆盖)，
     * @return 父任务 VO（含 taskId / status=PENDING / totalShots 计数）
     */
    AssetExtractTaskVO batchGenerateVideoPrompt(Long projectId, Long episodeId, Long userId,
                                                List<Long> storyboardIds,
                                                String agentCode, String modelCode,
                                                Boolean overwrite, Map<String, Object> chainNext);

    /**
     * 批量提交"图生方向（漫剧版）分镜视频提示词生成"父任务。
     *
     * @param agentCode 智能体编码（默认 aid_visual_director_image，biz 必须为 main_storyboard_video_prompt_image）
     */
    AssetExtractTaskVO batchGenerateVideoPromptImage(Long projectId, Long episodeId, Long userId,
                                                     List<Long> storyboardIds,
                                                     String agentCode, String modelCode,
                                                     Boolean overwrite);

    /**
     * 批量提交"宫格方向（auto_grid 专业版）分镜视频提示词生成"父任务。
     *
     * @param agentCode 智能体编码（默认 aid_visual_director_grid，biz 必须为 main_storyboard_video_prompt_grid）
     */
    AssetExtractTaskVO batchGenerateVideoPromptGrid(Long projectId, Long episodeId, Long userId,
                                                    List<Long> storyboardIds,
                                                    String agentCode, String modelCode,
                                                    Boolean overwrite);

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
    String doStoryboardVideoPromptBatch(Long taskId, Long userId);

    /**
     * 续生：仅对 {@code PARTIAL_FAILED} 终态任务可调，重跑未生成 video_prompt 的镜头。
     */
    AssetExtractTaskVO resumeVideoPrompt(Long taskId, Long userId);

    /**
     * 手动落库单条分镜的视频提示词（用户在前端手动填写后保存）。
     *
     * @param storyboardId 分镜 ID
     * @param videoPrompt  用户填写的视频提示词
     * @param userId       当前用户 ID
     */
    void saveManualVideoPrompt(Long storyboardId, String videoPrompt, Long userId);

    /**
     * 校验视频提示词格式是否符合视觉导演规范。
     * 必须命中视觉导演（aid_visual_director）输出契约的三段式标记：
     * {@code # 主题}、{@code # 运镜}、{@code # 风格}，缺一不可。
     *
     * @param videoPrompt 待校验的提示词文本
     * @return true 表示格式合法
     */
    boolean isVideoPromptFormatValid(String videoPrompt);
}
