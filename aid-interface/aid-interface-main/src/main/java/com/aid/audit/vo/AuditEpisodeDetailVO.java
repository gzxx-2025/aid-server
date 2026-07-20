package com.aid.audit.vo;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 剧集审核详情VO（后台，含成品视频在线观看地址）
 *
 * @author 视觉AID
 */
@Data
@Builder
public class AuditEpisodeDetailVO {

    /** 剧集ID */
    private Long id;

    /** 所属项目ID */
    private Long projectId;

    /** 所属项目名称 */
    private String projectName;

    /** 所属项目类型: series剧集 / movie电影 */
    private String projectType;

    /** 所属项目类型描述：电影 / 剧集 */
    private String projectTypeDesc;

    /** 所属项目描述（来自所属项目） */
    private String projectDesc;

    /** 所属用户ID */
    private Long userId;

    /** 作者昵称 */
    private String authorNickname;

    /** 第几集 */
    private Long episodeNo;

    /** 单集标题 */
    private String comicTitle;

    /** 单集描述 */
    private String comicDesc;

    /** 单集封面图（完整URL） */
    @MediaUrl
    private String comicCoverUrl;

    /** 生成模式（economy经济 / performance性能） */
    private String genMode;

    /** 创作模式（i2v图生视频 / multi多参生视频） */
    private String creationMode;

    /** 画面比例（来自所属项目） */
    private String aspectRatio;

    /** 剧本类型（来自所属项目：剧情演绎 / 真人解说） */
    private String scriptType;

    /** 视频风格名称（来自所属项目） */
    private String videoStyleType;

    /** 视频风格值（来自所属项目） */
    private String videoStyleValue;

    /** 状态: 0草稿 1制作中 2完成未审核 3审核中 4审核通过 5审核失败 */
    private Integer status;

    /** 状态原因（驳回原因等） */
    private String statusReason;

    /** 成品视频在线地址（完整URL）：取 episode_id=该剧集ID 的成片，无则为空 */
    @MediaUrl
    private String finalVideoUrl;

    /** 待审核新成片地址（完整URL）：重新导出产生的新片，非空时本次审核对象为该新片（finalVideoUrl 为线上旧片） */
    @MediaUrl
    private String pendingVideoUrl;

    /** 成品视频封面（完整URL） */
    @MediaUrl
    private String finalCoverUrl;

    /** 成片导出状态: 0未导出 1合成中 2导出成功 3导出失败 */
    private Integer exportStatus;

    /** 是否有成品视频可观看 */
    private Boolean hasVideo;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
