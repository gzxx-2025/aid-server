package com.aid.faq.dto;

import lombok.Data;

/**
 * 常见问题列表查询请求（C 端）
 *
 * @author 视觉AID
 */
@Data
public class FaqListRequest
{
    /** 分页页码，从 1 起，默认 1 */
    private Integer pageNum;

    /** 分页条数，范围 1..50，默认 10 */
    private Integer pageSize;

    /** 分类过滤（可选） */
    private String category;

    /** 按标题模糊搜索（可选） */
    private String keyword;
}
