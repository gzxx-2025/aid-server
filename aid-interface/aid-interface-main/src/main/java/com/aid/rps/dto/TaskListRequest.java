package com.aid.rps.dto;

import lombok.Data;

/**
 * C端任务列表查询请求
 *
 * @author 视觉AID
 */
@Data
public class TaskListRequest
{
    /** 分页页码，从 1 起，默认 1 */
    private Integer pageNum;

    /** 分页条数，范围 1..50，默认 10 */
    private Integer pageSize;

    /** 项目ID（可选，不传则查用户所有任务） */
    private Long projectId;

    /** 任务类型（可选，如 asset_extract / form_generate） */
    private String taskType;

    /** 任务状态（可选，PENDING / PROCESSING / SUCCEEDED / FAILED） */
    private String status;
}
