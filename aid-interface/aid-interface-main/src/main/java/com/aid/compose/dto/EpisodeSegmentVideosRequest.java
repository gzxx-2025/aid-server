package com.aid.compose.dto;

import lombok.Data;

/**
 * 分段素材批量导出清单入参：按项目+剧集列出每个分镜的最终视频与配音。
 *
 * @author 视觉AID
 */
@Data
public class EpisodeSegmentVideosRequest {

    /** 项目ID（aid_comic_project.id），必填。来源：项目列表/详情接口 */
    private Long projectId;

    /** 剧集ID（aid_comic_episode.id），必填；电影（项目级）固定传 0。来源：剧集列表/详情接口 */
    private Long episodeId;
}
