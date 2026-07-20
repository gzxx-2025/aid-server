package com.aid.notice.dto;

import lombok.Data;

/**
 * 公告列表查询请求（C 端）
 *
 * @author 视觉AID
 */
@Data
public class NoticeListRequest
{
    /** 分页页码，从 1 起，默认 1 */
    private Integer pageNum;

    /** 分页条数，范围 1..50，默认 10 */
    private Integer pageSize;

    /** 公告类型过滤（可选：system系统公告 activity活动公告 update更新公告） */
    private String noticeType;

    /** 按标题模糊搜索（可选） */
    private String keyword;
}
