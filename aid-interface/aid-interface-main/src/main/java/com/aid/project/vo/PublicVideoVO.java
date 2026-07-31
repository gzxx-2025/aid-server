package com.aid.project.vo;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 公开视频VO
 *
 * @author 视觉AID
 */
@Data
@Builder
public class PublicVideoVO {

    /** 项目ID */
    private Long id;

    /** 项目名称 */
    private String projectName;

    /** 作者昵称 */
    private String authorNickname;

    /** 项目类型：series剧集 / movie电影 */
    private String projectType;

    /** 项目描述 */
    private String projectDesc;

    /** 发布时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date publishTime;

    /** 已发布剧集数（剧集类型返回过审集数；电影为 null） */
    private Integer episodeCount;

    /** 封面图（出参拼域名） */
    @MediaUrl
    private String coverUrl;

    /** 成片视频地址（电影=项目成片；剧集=第一集成片；出参拼域名） */
    @MediaUrl
    private String finalVideoUrl;
}
