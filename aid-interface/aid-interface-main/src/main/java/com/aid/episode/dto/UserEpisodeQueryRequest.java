package com.aid.episode.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户剧集查询请求DTO
 *
 * @author 视觉AID
 */
@Data
public class UserEpisodeQueryRequest {

    /** 所属项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 单集标题（模糊查询） */
    private String comicTitle;

    /** 状态(0草稿 1制作中 2完成未审核 3审核中 4审核通过 5审核失败) */
    private Integer status;
}
