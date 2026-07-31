package com.aid.compose.dto;

import lombok.Data;

/**
 * 分段素材打包下载入参：按项目+剧集把分镜素材（分镜图/视频/配音/字幕）流式打包为 zip 下载。
 *
 * @author 视觉AID
 */
@Data
public class EpisodeSegmentZipDownloadRequest {

    /** 项目ID（aid_comic_project.id），必填。来源：项目列表/详情接口 */
    private Long projectId;

    /** 剧集ID（aid_comic_episode.id），必填；电影（项目级）固定传 0。来源：剧集列表/详情接口 */
    private Long episodeId;

    /** 是否包含分镜图（最终选中的分镜画面），缺省 true */
    private Boolean includeImages;

    /** 是否包含分镜视频（最终选中的分镜原视频，即 final_video_id 指向的记录），缺省 true */
    private Boolean includeVideos;

    /** 是否包含配音音频，缺省 true */
    private Boolean includeAudios;

    /** 是否包含字幕文本（每段一个 txt），缺省 true */
    private Boolean includeSubtitles;
}
