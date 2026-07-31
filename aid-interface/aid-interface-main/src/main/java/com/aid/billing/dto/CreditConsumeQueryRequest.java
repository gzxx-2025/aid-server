package com.aid.billing.dto;

import java.io.Serial;
import java.io.Serializable;

import lombok.Data;

/**
 * 积分消耗明细查询请求 DTO（C 端，分页）。
 *
 * @author 视觉AID
 */
@Data
public class CreditConsumeQueryRequest implements Serializable
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
