package com.aid.publish.dto;

import lombok.Data;

/**
 * 后台-发布管理列表查询请求DTO
 *
 * @author 视觉AID
 */
@Data
public class AdminPublishListRequest {

    /** 发布状态：approved=审核通过未发布 published=已发布，不传查两类合集 */
    private String publishState;

    /** 作品名称（模糊搜索，可空） */
    private String projectName;

    /** 作品类型：series剧集 / movie电影（可空） */
    private String projectType;

    /** 作者关键字（昵称/邮箱/手机号模糊搜索，可空） */
    private String keyword;

    /** 页码（PageHelper 分页） */
    private Integer pageNum;

    /** 每页条数（PageHelper 分页） */
    private Integer pageSize;

    /** 取安全页码（兜底为 1） */
    public int resolvePageNum()
    {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    /** 取安全每页条数（兜底为 10，上限 100 防止拉爆） */
    public int resolvePageSize()
    {
        if (pageSize == null || pageSize < 1)
        {
            return 10;
        }
        return Math.min(pageSize, 100);
    }
}
