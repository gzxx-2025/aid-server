package com.aid.compose.dto.timeline;

import lombok.Data;

/**
 * 时间轴读取入参：episodeEditorId 与 projectId+episodeId 二选一。
 * 首次进入剪辑器只传 projectId+episodeId，后端自动建档并按分镜自动初始化时间轴。
 *
 * @author 视觉AID
 */
@Data
public class EpisodeTimelineGetRequest {

    /** 剧集剪辑记录ID（aid_episode_editor.id）。来源：本接口/导出接口出参。优先使用 */
    private Long episodeEditorId;

    /** 项目ID（aid_comic_project.id）。episodeEditorId 为空时与 episodeId 一起必填 */
    private Long projectId;

    /** 剧集ID（aid_comic_episode.id，电影传 0）。episodeEditorId 为空时与 projectId 一起必填 */
    private Long episodeId;

    /** 是否强制按最新分镜数据重建时间轴（默认 false=已有工程原样返回）。true 时丢弃已存工程重新初始化 */
    private Boolean rebuild;
}
