package com.aid.audit.dto;

import lombok.Data;

/**
 * 后台-审核记录查询请求DTO
 *
 * @author 视觉AID
 */
@Data
public class AdminAuditRecordQueryRequest {

    /** 审核对象类型（可选）: project项目 / episode剧集 */
    private String targetType;

    /** 审核对象ID（可选） */
    private Long targetId;

    /** 作品所属用户ID（可选） */
    private Long ownerUserId;

    /** 审核动作（可选）: 1提交审核 2审核通过 3审核驳回 */
    private Integer action;
}
