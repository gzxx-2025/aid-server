package com.aid.audit.vo;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 项目审核详情VO（后台）
 * 项目审核只审核项目封面与基本信息，不涉及成品视频（成片审核在剧集/成片审核处）。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class AuditProjectDetailVO {

    /** 项目ID */
    private Long id;

    /** 所属用户ID */
    private Long userId;

    /** 作者昵称 */
    private String authorNickname;

    /** 项目名称 */
    private String projectName;

    /** 项目描述 */
    private String projectDesc;

    /** 项目类型: series剧集 / movie电影 */
    private String projectType;

    /** 项目类型描述：电影 / 剧集 */
    private String projectTypeDesc;

    /** 项目封面图（完整URL，来自项目表 cover_url，审核对象） */
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

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
