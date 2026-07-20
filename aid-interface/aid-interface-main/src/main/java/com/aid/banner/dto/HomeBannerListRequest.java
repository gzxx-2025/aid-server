package com.aid.banner.dto;

import lombok.Data;

/**
 * 首页 Banner 列表查询请求（C 端）
 *
 * @author 视觉AID
 */
@Data
public class HomeBannerListRequest
{
    /** 分页页码，从 1 起，默认 1 */
    private Integer pageNum;

    /** 分页条数，范围 1..50，默认 10 */
    private Integer pageSize;
}
