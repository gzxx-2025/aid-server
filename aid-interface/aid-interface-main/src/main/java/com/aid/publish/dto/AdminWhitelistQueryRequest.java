package com.aid.publish.dto;

import lombok.Data;

/**
 * 后台-发布白名单列表查询请求DTO
 *
 * @author 视觉AID
 */
@Data
public class AdminWhitelistQueryRequest {

    /** 搜索关键字（昵称/邮箱/手机号，可空） */
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
