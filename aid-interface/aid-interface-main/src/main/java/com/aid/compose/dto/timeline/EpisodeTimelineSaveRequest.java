package com.aid.compose.dto.timeline;

import lombok.Data;

/**
 * 时间轴保存入参：整份工程覆盖存储（前端每次保存传完整 timeline）。
 *
 * @author 视觉AID
 */
@Data
public class EpisodeTimelineSaveRequest {

    /** 剧集剪辑记录ID（aid_episode_editor.id）。来源：读取接口出参。优先使用 */
    private Long episodeEditorId;

    /** 项目ID（aid_comic_project.id）。episodeEditorId 为空时与 episodeId 一起必填 */
    private Long projectId;

    /** 剧集ID（aid_comic_episode.id，电影传 0）。episodeEditorId 为空时与 projectId 一起必填 */
    private Long episodeId;

    /** 时间轴工程数据（完整结构，必填），后端校验、补默认值后覆盖存入 timeline_json */
    private TimelineData timeline;
}
