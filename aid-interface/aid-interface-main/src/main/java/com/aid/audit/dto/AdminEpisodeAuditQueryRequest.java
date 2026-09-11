package com.aid.audit.dto;

import lombok.Data;

/**
 * 后台-剧集审核列表查询请求DTO
 *
 * @author 视觉AID
 */
@Data
public class AdminEpisodeAuditQueryRequest {

    /** 所属项目ID（可选） */
    private Long projectId;

    /** 单集标题（可选，模糊查询） */
    private String comicTitle;

    /** 所属用户ID（可选） */
    private Long userId;

    /** 状态（可选）: 0草稿 1制作中 2完成未审核 3审核中 4审核通过 5审核失败；不传默认查「审核中(3)」 */
    private Integer status;
}
