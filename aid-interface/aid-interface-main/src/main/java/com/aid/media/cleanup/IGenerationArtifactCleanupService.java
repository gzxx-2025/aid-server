package com.aid.media.cleanup;

import java.util.Collection;

/**
 * 生成产物级联清理服务：硬删除生成产物并清理其关联的 OSS 文件。
 *
 * @author 视觉AID
 */
public interface IGenerationArtifactCleanupService
{
    /**
     * 按分镜ID集合级联清理生成产物（OSS + 物理删库）。
     *
     * @param storyboardIds 分镜ID集合
     */
    void cleanupByStoryboardIds(Collection<Long> storyboardIds);

    /**
     * 按项目ID + 剧集ID级联清理生成产物（OSS + 物理删库）。电影模式 episodeId 固定为 0。
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID（电影为0）
     */
    void cleanupByEpisode(Long projectId, Long episodeId);

    /**
     * 按项目ID级联清理生成产物（OSS + 物理删库），覆盖该项目下所有剧集。
     *
     * @param projectId 项目ID
     */
    void cleanupByProject(Long projectId);
}
