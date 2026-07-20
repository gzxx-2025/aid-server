package com.aid.rps.service;

import java.util.List;
import java.util.Map;

import com.aid.rps.dto.AssetExtractTaskVO;

/**
 * 批量生成分镜图脚本（图生图 prompt）服务。
 * 复用现有 {@code aid_extract_task} 任务体系（task_type=storyboard_image_prompt_batch），
 * 由"分镜画师"智能体（biz_category_code=main_storyboard_stylist）逐镜生成图生图 prompt，
 * 写回 {@code aid_storyboard.image_prompt} / {@code image_prompt_raw}。
 *
 * @author 视觉AID
 */
public interface IStoryboardImagePromptService
{
    /**
     * 批量提交"分镜图脚本生成"父任务。
     *
     * @param projectId      项目 ID
     * @param episodeId      剧集 ID（电影 0）
     * @param userId         当前用户 ID
     * @param storyboardIds  目标分镜 ID 列表；为空表示跑该 episode 下全部
     * @param agentCode      智能体编码（默认 aid_storyboard_script_stylist）
     * @param modelCode      用户指定文本模型；为空走智能体默认
     * @param overwrite      是否覆盖已有 image_prompt（默认 false）
     * @param chainNext      链式触发下一步规格（合并接口用）：非空时写入 input_snapshot.chainNext，
     * @return 父任务 VO（含 taskId / status=PENDING / totalShots 计数）
     */
    AssetExtractTaskVO batchGenerateImagePrompt(Long projectId, Long episodeId, Long userId,
                                                List<Long> storyboardIds,
                                                String agentCode, String modelCode,
                                                Boolean overwrite, Map<String, Object> chainNext);

    /**
     * Consumer 调用：实际执行分镜图脚本批量生成。
     * 必须独立写库，不依赖外层事务。返回结果 JSON（供 {@code aid_extract_task.result_data} 落盘）。
     * 若全部失败则向上抛异常让 Consumer 标记 FAILED；若部分失败则正常返回，业务侧自行
     * 把父任务推进到 PARTIAL_FAILED。
     */
    String doStoryboardImagePromptBatch(Long taskId, Long userId);

    /**
     * 续生：仅对 {@code PARTIAL_FAILED} 终态任务可调，重跑未生成 image_prompt 的镜头。
     */
    AssetExtractTaskVO resumeImagePrompt(Long taskId, Long userId);
}
