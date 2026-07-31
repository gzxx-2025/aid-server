package com.aid.compose.dto;

import lombok.Data;

/**
 * 成片流式下载入参：episodeEditorId 与 projectId+episodeId 二选一。
 *
 * @author 视觉AID
 */
@Data
public class EpisodeFinalVideoDownloadRequest {

    /**
     * 剧集剪辑记录ID（aid_episode_editor.id）。
     * 来源：导出受理/进度查询接口出参。为空时必须传 projectId + episodeId。
     */
    private Long episodeEditorId;

    /**
     * 项目ID（aid_comic_project.id）。episodeEditorId 为空时必填。
     */
    private Long projectId;

    /**
     * 剧集ID（aid_comic_episode.id，电影固定传 0）。episodeEditorId 为空时必填。
     */
    private Long episodeId;
}
