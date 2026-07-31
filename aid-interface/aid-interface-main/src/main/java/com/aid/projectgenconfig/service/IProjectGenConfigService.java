package com.aid.projectgenconfig.service;

import java.util.List;

import com.aid.projectgenconfig.dto.SaveProjectGenConfigRequest;
import com.aid.projectgenconfig.vo.ProjectGenConfigVO;

/**
 * 项目级生成配置服务。
 *
 * @author 视觉AID
 */
public interface IProjectGenConfigService
{
    /**
     * 保存项目级生成配置（部分更新）。
     *
     * @param request 保存请求
     * @param userId  当前登录用户ID
     * @return 本次已保存的各场景配置结果
     */
    List<ProjectGenConfigVO> saveConfig(SaveProjectGenConfigRequest request, Long userId);

    /**
     * 查询项目级生成配置（项目已保存值优先，缺失回退智能体矩阵默认）。
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID（剧集类项目用于取剧集创作模式，可空）
     * @param userId    当前登录用户ID
     * @return 各场景配置结果（含取值来源标识与可选智能体池）
     */
    List<ProjectGenConfigVO> listConfig(Long projectId, Long episodeId, Long userId);

    /**
     * 清空某项目（按当前用户）的全部项目级生成配置（软删除 del_flag=1）。
     *
     * @param projectId 项目ID
     * @param userId    当前登录用户ID
     * @return 受影响行数
     */
    int clearProjectConfig(Long projectId, Long userId);
}
