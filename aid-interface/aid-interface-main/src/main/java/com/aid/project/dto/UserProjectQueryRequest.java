package com.aid.project.dto;

import lombok.Data;

/**
 * 用户项目查询请求DTO
 *
 * @author 视觉AID
 */
@Data
public class UserProjectQueryRequest {

    /** 项目名称（模糊查询） */
    private String projectName;

    /** 类型: series剧集, movie电影 */
    private String projectType;

    /** 状态(0草稿 1制作中 2完成未提交 3审核中 4审核通过 5审核失败) */
    private Integer status;
}
