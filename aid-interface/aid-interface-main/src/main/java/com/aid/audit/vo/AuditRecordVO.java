package com.aid.audit.vo;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

/**
 * 审核记录展示VO（后台）
 *
 * @author 视觉AID
 */
@Data
@Builder
public class AuditRecordVO {

    /** 记录ID */
    private Long id;

    /** 审核对象类型: project项目 / episode剧集 */
    private String targetType;

    /** 审核对象类型描述（项目/剧集） */
    private String targetTypeDesc;

    /** 业务类型: movie电影 / series剧集（项目记录取项目类型；剧集记录恒为剧集） */
    private String bizType;

    /** 业务类型描述：电影 / 剧集 */
    private String bizTypeDesc;

    /** 审核对象ID */
    private Long targetId;

    /** 作品所属用户ID */
    private Long ownerUserId;

    /** 审核动作: 1提交审核 2审核通过 3审核驳回 */
    private Integer action;

    /** 审核动作描述（提交审核/审核通过/审核驳回） */
    private String actionDesc;

    /** 变更前状态 */
    private Integer beforeStatus;

    /** 变更后状态 */
    private Integer afterStatus;

    /** 审核意见/驳回原因 */
    private String auditReason;

    /** 操作人（C端为用户ID，后台为管理员账号） */
    private String operator;

    /** 操作时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
