package com.aid.agent.dto;

import java.util.List;

import lombok.Data;

/**
 * C 端：按业务分类查询启用智能体列表请求 DTO
 *
 * @author 视觉AID
 */
@Data
public class AgentListRequest
{
    /**
     * 业务分类编码列表（可选）。
     *
     *   - 为空 / null → 返回全部启用智能体，按各自的 bizCategoryCode 分组（bizCategoryCode 为空的归入 key 为 null 的分组）
     *   - 非空 → 仅返回这些分类下的启用智能体，输出时严格按入参顺序输出每一组；
     *       某个分类无匹配数据时仍输出该分组，agents 为空数组（占位）
     *
     */
    private List<String> bizCategoryCodes;

    /**
     * 项目ID（可选）。
     * 传了则按该项目的创作模式（电影=项目 {@code default_creation_mode}；剧集见 {@code episodeId}）
     * 对「受矩阵管理的场景」（分镜脚本/分镜图/视频提示词 + 各资产场景）用可选池裁剪候选智能体，
     * 避免列出当前创作模式不该出现的智能体（如 i2v 下的「分镜脚本提取-专业版」）。
     * 不传则返回全部启用智能体（不按创作模式过滤）。
     */
    private Long projectId;

    /**
     * 剧集ID（可选）。
     * 仅在传了 {@code projectId} 且为剧集类项目时生效：按该剧集 {@code creation_mode} 过滤；
     * 不传或电影类项目则用项目 {@code default_creation_mode}。
     */
    private Long episodeId;
}
