package com.aid.rps.service;

import java.util.List;

import com.aid.rps.dto.AssetExtractTaskVO;

/**
 * 批量分镜脚本生成 Service 接口
 * 按项目+剧集维度，遍历该剧集下所有已提取的场景（aid_role_prop_scene type=scene），
 * 逐场景调用分镜脚本 LLM 智能体，将输出写入 aid_storyboard 表。
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

    /**
     * 批量分镜脚本生成核心逻辑（由 Consumer 调用）
     * 逐场景处理：拼装 LLM 入参 → callLlmRaw → 解析 18 字段 JSON 数组 → 逐镜头写入 aid_storyboard。
     * 单场景失败不影响整批继续执行。返回结果 JSON。
     */
    String doStoryboardScriptBatch(Long taskId, Long userId);

    /**
     * 分镜脚本任务续生。
     *
     * @param taskId 原父任务 ID
     * @param userId 当前用户 ID
     * @return 续生提交后的任务 VO（taskId + PROCESSING）
     */
    AssetExtractTaskVO resumeStoryboardScript(Long taskId, Long userId);
}
