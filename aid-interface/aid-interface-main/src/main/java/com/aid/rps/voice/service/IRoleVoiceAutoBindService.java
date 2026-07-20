package com.aid.rps.voice.service;

/**
 * 角色音色「自动匹配绑定」Service。
 *
 * @author 视觉AID
 */
public interface IRoleVoiceAutoBindService
{
    /**
     * 为某剧集下所有「自动提取角色」自动匹配并绑定音色（覆盖式）。
     * 提取成功后调用；内部对单个角色的失败做隔离，不整体抛出，避免影响提取主流程。
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID（电影模式为 0）
     * @param userId    用户ID
     */
    void autoBindForEpisode(Long projectId, Long episodeId, Long userId);

    /**
     * 单个角色重新匹配并绑定音色（用户改性别/年龄后即时重绑）。
     *
     * @param assetId 角色ID（必须是自动提取角色，否则跳过）
     * @param userId  用户ID
     * @return true=音色发生了变化（绑定被更新/新建）；false=无变化或跳过
     */
    boolean rematchForCharacter(Long assetId, Long userId);
}
