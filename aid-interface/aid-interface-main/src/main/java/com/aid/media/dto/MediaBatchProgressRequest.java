package com.aid.media.dto;

import lombok.Data;

/**
 * 批量进度轮询入参：仅查询当前登录用户名下、指定 batchId 的任务集合。
 */
@Data
public class MediaBatchProgressRequest {

    /** 业务含义：批量提交接口返回的 batchId，必填。 */
    private String batchId;

    /** 业务含义：为 true（或 null 表示默认）时，对仍处于 PROCESSING 且已有上游任务号的记录主动 query 厂商刷新状态。 */
    private Boolean pollRemote;
}
