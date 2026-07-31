package com.aid.compose.dto;

import lombok.Data;

import java.util.List;

/**
 * 分段素材批量导出清单出参：按分镜顺序排列的分段视频/配音列表 + 就绪统计。
 *
 * @author 视觉AID
 */
@Data
public class EpisodeSegmentVideosResult {

    /** 项目ID */
    private Long projectId;

    /** 剧集ID（电影为 0） */
    private Long episodeId;

    /** 分镜总段数 */
    private Integer totalSegments;

    /** 已设最终视频的段数（videoUrl 非空） */
    private Integer videoReadyCount;

    /** 有配音的段数（audioUrl 非空） */
    private Integer dubbedCount;

    /** 分段列表，按分镜 sort_order 升序（即成片顺序） */
    private List<EpisodeSegmentVideoItem> items;
}
