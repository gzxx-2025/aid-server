package com.aid.step.service;

import com.aid.step.vo.StepStatusVO;

/**
 * 创作流水线步骤Service接口。
 *
 * @author 视觉AID
 */
public interface ICreationStepService {

    /**
     * 查询当前步骤状态(前端渲染左侧导航栏)。
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID(电影传null或0)
     * @param userId 用户ID
     * @return 步骤状态VO
     */
    StepStatusVO getStepStatus(Long projectId, Long episodeId, Long userId);

    /**
     * 校验是否已解锁到指定步骤(未解锁则抛异常)。
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID(电影传null或0)
     * @param userId 用户ID
     * @param requiredStep 需要的步骤值(1~7)
     */
    void checkStepUnlocked(Long projectId, Long episodeId, Long userId, int requiredStep);

    /**
     * 尝试推进步骤(检查当前步骤的完成条件，满足则自动+1)。
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID(电影传null或0)
     * @param userId 用户ID
     * @param completedStep 刚完成的步骤值(1~7)
     */
    void tryAdvanceStep(Long projectId, Long episodeId, Long userId, int completedStep);

    /**
     * 静默尝试推进步骤：作为其它写操作的附带副作用调用，不抛异常、不影响主流程。
     *
     * @param projectId     项目ID
     * @param episodeId     剧集ID(电影传null或0)
     * @param userId        用户ID
     * @param completedStep 刚完成的步骤值(1~7)
     */
    void tryAdvanceStepQuietly(Long projectId, Long episodeId, Long userId, int completedStep);
}
