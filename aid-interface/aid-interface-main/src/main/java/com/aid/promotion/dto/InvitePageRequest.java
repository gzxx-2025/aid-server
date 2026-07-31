package com.aid.promotion.dto;

import java.io.Serial;
import java.io.Serializable;

import lombok.Data;

/**
 * 邀请模块C端分页入参（我邀请的用户 / 返佣明细）
 *
 * @author 视觉AID
 */
@Data
public class InvitePageRequest implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    /** 页码，从 1 开始；不传默认 1 */
    private Integer pageNum = 1;

    /** 每页条数；不传默认 10 */
    private Integer pageSize = 10;

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
