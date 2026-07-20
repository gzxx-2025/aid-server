package com.aid.aid.domain;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.annotation.Excel;
import com.aid.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 作品审核记录对象 aid_comic_audit_record
 * 记录项目/剧集的每一次审核动作（提交审核、审核通过、审核驳回）
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_comic_audit_record")
public class AidComicAuditRecord extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 审核对象类型(project项目 episode剧集) */
    @Excel(name = "审核对象类型")
    private String targetType;

    /** 审核对象ID(项目ID或剧集ID) */
    @Excel(name = "审核对象ID")
    private Long targetId;

    /** 作品所属用户ID */
    @Excel(name = "作品所属用户ID")
    private Long ownerUserId;

    /** 审核动作(1提交审核 2审核通过 3审核驳回) */
    @Excel(name = "审核动作(1提交审核 2审核通过 3审核驳回)")
    private Integer action;

    /** 变更前状态 */
    @Excel(name = "变更前状态")
    private Integer beforeStatus;

    /** 变更后状态 */
    @Excel(name = "变更后状态")
    private Integer afterStatus;

    /** 审核意见/驳回原因 */
    @Excel(name = "审核意见/驳回原因")
    private String auditReason;

    /** 操作人(C端为用户标识, 后台为管理员账号) */
    @Excel(name = "操作人")
    private String operator;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;
}
