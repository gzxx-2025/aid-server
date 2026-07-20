package com.aid.media.provider;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ProviderTaskResult {

    // 归一化任务状态（SUCCEEDED/FAILED/PROCESSING）。
    private String status;

    // 结果URL（成功时返回，图片场景为首图）。
    private String resultUrl;

    // 错误信息（失败时返回）。
    private String errorMessage;

    // 厂商原始响应内容。
    private String rawResponse;

    /**
     * 查询成功时全部结果图 URL（图片 provider 填充）。
     * 单图时退化为单元素列表；resultUrl 继续代表首图以兼容旧链路。
     */
    private List<String> resultUrls;

    /**
     * 查询成功时实际产出张数（图片计费结算依据）。
     * 为空时按 resultUrls.size() 兜底。
     */
    private Integer resultCount;

    /**
     * 查询成功时实际输出视频秒数（PER_SECOND 计费结算依据）。
     * 从厂商 usage.video_duration / usage.output_video_duration 解析。
     */
    private Integer videoDurationSeconds;

    /**
     * MPS 实际输出时长（秒），仅 COMPOSE 任务填充。
     * 由 MpsVideoProviderClient 从 DescribeTaskDetail 的输出媒体元信息解析，
     * 供合成分支结算（多退少补）使用，不影响既有字段与调用方。
     */
    private Long outputDurationSeconds;

    /**
     * 上游任务执行进度百分比（0-100），仅在厂商返回时填充（当前 MPS COMPOSE 任务解析）。
     * 处理中状态用于回写业务表进度展示；终态不依赖本字段。
     */
    private Integer progress;
}
