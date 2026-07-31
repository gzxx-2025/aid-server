package com.aid.media.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 批量进度汇总：用于前端展示整体百分比，同时下发每条任务最新详情（含 ossUrl 等）。
 */
@Data
@Builder
public class MediaBatchProgressResponse {

    /** 业务含义：与请求中 batchId 相同，便于前端核对。 */
    private String batchId;

    /** 业务含义：该批任务总条数（终态+未终态）。 */
    private int totalCount;

    /** 业务含义：已进入终态的条数，即 succeededCount + failedCount（PENDING/PROCESSING 不计入）。 */
    private int completedCount;

    /** 业务含义：status=SUCCEEDED 的任务数。 */
    private int succeededCount;

    /** 业务含义：status=FAILED 的任务数。 */
    private int failedCount;

    /** 业务含义：completedCount * 100 / totalCount 的整数百分比，0～100。 */
    private int progressPercent;

    /** 业务含义：当且仅当每条任务均为 SUCCEEDED 或 FAILED 时为 true。 */
    private boolean allDone;

    /** 业务含义：按任务主键升序排列的明细，元素结构与单任务查询一致。 */
    private List<MediaTaskResponse> tasks;
}
