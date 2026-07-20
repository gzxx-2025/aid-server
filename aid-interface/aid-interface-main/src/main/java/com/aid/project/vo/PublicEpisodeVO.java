package com.aid.project.vo;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 公开剧集VO（广场详情页剧集列表切换播放用）
 *
 * @author 视觉AID
 */
@Data
@Builder
public class PublicEpisodeVO {

    /** 剧集ID */
    private Long episodeId;

    /** 第几集 */
    private Long episodeNo;

    /** 单集标题 */
    private String title;

    /** 单集封面图（出参拼域名） */
    @MediaUrl
    private String coverUrl;

    /** 单集成片视频地址（出参拼域名） */
    @MediaUrl
    private String videoUrl;
}
