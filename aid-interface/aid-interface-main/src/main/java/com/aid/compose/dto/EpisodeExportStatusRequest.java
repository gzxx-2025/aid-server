package com.aid.compose.dto;

import lombok.Data;

/**
 * 导出进度查询入参（纯轮询）。
 * episodeEditorId 与 projectId+episodeId 二选一，仅可查本人记录。
 *
 * @author 视觉AID
 */
@Data
public class EpisodeExportStatusRequest {

    /** 剧集剪辑记录ID（aid_episode_editor.id）。来源：导出受理出参 episodeEditorId。优先使用 */
    private Long episodeEditorId;

    /** 项目ID（aid_comic_project.id）。episodeEditorId 为空时与 episodeId 一起必填 */
    private Long projectId;

    /** 剧集ID（aid_comic_episode.id，电影传 0）。episodeEditorId 为空时与 projectId 一起必填 */
    private Long episodeId;
}
