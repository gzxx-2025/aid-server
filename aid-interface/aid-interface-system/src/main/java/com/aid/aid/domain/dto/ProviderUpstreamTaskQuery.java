package com.aid.aid.domain.dto;

import lombok.Data;

/** 后台查询供应商上游任务的统一入参。 */
@Data
public class ProviderUpstreamTaskQuery {
    private Long startTime;
    private Long endTime;
    private String cursor;
    private Integer limit;
    private String status;
    private String productType;
    /** MiniMax V2 模型过滤，如 MiniMax-H3。 */
    private String model;
    /** MiniMax V2 任务类型；当前平台仅接入 generation。 */
    private String taskType;
    /** 精确搜索类型：task_ids / external_task_ids；与 searchValue 成对出现。 */
    private String searchType;
    /** 逗号分隔的任务 ID，最多 50 个。 */
    private String searchValue;
}
