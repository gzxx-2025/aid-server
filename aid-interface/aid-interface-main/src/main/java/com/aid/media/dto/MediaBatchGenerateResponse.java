package com.aid.media.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 批量提交成功后的立即响应：携带批次号与每条任务的当前快照（通常为 PENDING）。
 */
@Data
@Builder
public class MediaBatchGenerateResponse {

    /** 业务含义：本批唯一标识，后续 POST /batch/progress 必传，与 aid_media_task.batch_id 一致。 */
    private String batchId;

    /** 业务含义：本请求实际创建的子任务条数，应等于 items.size()。 */
    private int totalCount;

    /** 业务含义：与 items 顺序对应的 {@link MediaTaskResponse} 列表，供前端展示 taskId 与初始状态。 */
    private List<MediaTaskResponse> tasks;
}
