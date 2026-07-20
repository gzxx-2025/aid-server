package com.aid.media.dto;

import lombok.Data;

/**
 * 媒体任务列表查询请求
 */
@Data
public class MediaTaskListRequest {

    // 项目ID（可选，不传查全部）
    private Long projectId;

    // 剧集ID（可选）
    private Long episodeId;

    // 媒体类型：IMAGE / VIDEO / TEXT（可选）
    private String mediaType;

    // 任务状态：PENDING / QUEUED / PROCESSING / SUCCEEDED / FAILED（可选）
    private String status;
}
