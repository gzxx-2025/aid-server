package com.aid.aid.domain.vo;

import java.io.Serializable;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;

import lombok.Data;

/**
 * 后台发布管理列表行VO（项目 联表 sys_user 作者信息）
 *
 * @author 视觉AID
 */
@Data
public class AidPublishItemVo implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 项目ID */
    private Long id;

    /** 所属用户ID */
    private Long userId;

    /** 作者昵称 */
    private String nickName;

    /** 作者邮箱 */
    private String email;

    /** 作者手机号 */
    private String phonenumber;

    /** 作品名称 */
    private String projectName;

    /** 作品类型: series剧集 / movie电影 */
    private String projectType;

    /** 作品介绍 */
    private String projectDesc;

    /** 封面图（完整URL） */
    @MediaUrl
    private String coverUrl;

    /** 审核状态: 0草稿 1制作中 2完成未提交 3审核中 4审核通过 5审核失败 */
    private Integer status;

    /** 状态原因（下架/回撤原因等） */
    private String statusReason;

    /** 是否公开: 1已发布 0未发布 */
    private String isPublic;

    /** 是否允许查看已发布流程快照 */
    private String allowPreview;

    /** 是否允许复制已发布流程快照 */
    private String allowCopy;

    /** 复制来源项目ID；原创项目为空 */
    private Long sourceProjectId;

    /** 复制来源标签；仅管理员可修改 */
    private String copyLabel;

    /** 最近一次发布时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date publishTime;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
