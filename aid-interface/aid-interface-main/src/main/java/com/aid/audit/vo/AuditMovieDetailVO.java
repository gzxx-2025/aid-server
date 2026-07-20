package com.aid.audit.vo;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 电影审核详情VO（后台）
 * 电影审核同时审核「封面」与「成品视频」：封面取项目表 cover_url，
 * 成品视频取 aid_episode_editor（project_id + episode_id=0）的 final_video_url。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class AuditMovieDetailVO {

    /** 项目ID */
    private Long id;

    /** 所属用户ID */
    private Long userId;

    /** 作者昵称 */
    private String authorNickname;

    /** 电影名称 */
    private String projectName;

    /** 电影描述 */
    private String projectDesc;

    /** 项目类型: 固定 movie */
    private String projectType;

    /** 项目类型描述：电影 */
    private String projectTypeDesc;

    /** 电影封面图（完整URL，来自项目表 cover_url，审核对象之一） */
    @MediaUrl
    private String coverUrl;

    /** 画面比例 */
    private String aspectRatio;

    /** 剧本类型（剧情演绎 / 真人解说） */
    private String scriptType;

    /** 视频风格名称 */
    private String videoStyleType;

    /** 视频风格值 */
    private String videoStyleValue;

    /** 默认生成模式（economy经济 / performance性能） */
    private String defaultGenMode;

    /** 默认创作模式（i2v图生视频 / multi多参生视频） */
    private String defaultCreationMode;

    /** 状态: 0草稿 1制作中 2完成未提交 3审核中 4审核通过 5审核失败 */
    private Integer status;

    /** 状态原因（驳回原因等） */
    private String statusReason;

    /** 是否公开（0否 1是） */
    private String isPublic;

    /** 最近一次公开发布时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date publishTime;

    /** 成品视频在线地址（完整URL）：取 episode_id=0 的成片，无则为空 */
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
