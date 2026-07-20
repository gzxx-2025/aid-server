package com.aid.audit.dto;

import lombok.Data;

/**
 * 后台-项目审核列表查询请求DTO
 *
 * @author 视觉AID
 */
@Data
public class AdminProjectAuditQueryRequest {

    /** 项目名称（可选，模糊查询） */
    private String projectName;

    /** 项目类型（可选）: series剧集 / movie电影 */
    private String projectType;

    /** 所属用户ID（可选） */
    private Long userId;

    /** 状态（可选）: 0草稿 1制作中 2完成未提交 3审核中 4审核通过 5审核失败；不传默认查「审核中(3)」 */
    private Integer status;
}
