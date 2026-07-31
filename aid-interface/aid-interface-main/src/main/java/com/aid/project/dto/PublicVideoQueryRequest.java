package com.aid.project.dto;

import lombok.Data;

/**
 * 公开广场列表查询请求DTO
 *
 * @author 视觉AID
 */
@Data
public class PublicVideoQueryRequest {

    /** 项目名称（模糊查询，非必填） */
    private String projectName;

    /** 项目类型筛选（movie电影 / series剧集，非必填，不传返回全部） */
    private String projectType;

    /** 页码（PageHelper 分页） */
    private Integer pageNum;

    /** 每页条数（PageHelper 分页） */
    private Integer pageSize;

    /** 取安全页码（兜底为 1） */
    public int resolvePageNum()
    {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    /** 取安全每页条数（兜底 10，上限 100，防止超大分页拖垮查询） */
    public int resolvePageSize()
    {
        if (pageSize == null || pageSize < 1)
        {
            return 10;
        }
        return Math.min(pageSize, 100);
    }
}
