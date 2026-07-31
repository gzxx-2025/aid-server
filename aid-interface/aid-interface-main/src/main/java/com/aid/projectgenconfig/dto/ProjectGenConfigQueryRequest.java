package com.aid.projectgenconfig.dto;

import jakarta.validation.constraints.NotNull;

import lombok.Data;

/**
 * 查询项目级生成配置请求。
 *
 * @author 视觉AID
 */
@Data
public class ProjectGenConfigQueryRequest
{
    /** 项目ID（必填） */
    @NotNull(message = "项目不能空")
    private Long projectId;

    /**
     * 剧集ID（可选）。
     * 剧集类项目按剧集 {@code creation_mode} 解析分镜脚本/图/视频提示词的默认智能体；
     * 不传或电影类项目则用项目 {@code default_creation_mode}。
     */
    private Long episodeId;
}
