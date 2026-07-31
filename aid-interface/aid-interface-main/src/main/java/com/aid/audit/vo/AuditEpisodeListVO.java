package com.aid.audit.vo;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 剧集审核列表项VO（后台）
 * 在剧集实体基础上附带所属项目类型（电影/剧集），便于审核员区分。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class AuditEpisodeListVO {

    /** 剧集ID */
    private Long id;

    /** 所属项目ID */
    private Long projectId;

    /** 所属项目类型: series剧集 / movie电影 */
    private String projectType;

    /** 所属项目类型描述：电影 / 剧集 */
    private String projectTypeDesc;

    /** 第几集 */
    private Long episodeNo;

    /** 单集标题 */
    private String comicTitle;

    /** 单集封面图（完整URL） */
    @MediaUrl
    private String comicCoverUrl;

    /** 所属用户ID */
    private Long userId;

    /** 状态: 0草稿 1制作中 2完成未审核 3审核中 4审核通过 5审核失败 */
    private Integer status;

    /** 状态原因 */
    private String statusReason;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
