package com.aid.media.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 统一任务预计进度。时间字段使用 Unix 毫秒，前端可基于 calculatedAt 本地倒计时，无需增加轮询。
 */
@Data
@Builder
public class TaskEtaVO {
    private String phase;
    private Integer displayProgress;
    private String progressSource;
    private Long remainingSecondsP50;
    private Long remainingSecondsP90;
    private Long estimatedStartAt;
    private Long estimatedFinishAtP50;
    private Long estimatedFinishAtP90;
    private String confidence;
    private Long sampleCount;
    private Long calculatedAt;
    private Integer totalCount;
    private Integer completedCount;
    private Integer runningCount;
    private Integer queuedCount;
    private Boolean delayed;
    private String predictionVersion;
}
