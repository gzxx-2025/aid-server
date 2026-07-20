package com.aid.modelhealth.vo;

import java.io.Serializable;

import lombok.Data;

/**
 * 模型健康时间轴单格（30分钟一格，24小时=48格）。
 *
 * @author 视觉AID
 */
@Data
public class ModelHealthBucketVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 桶起始时间（yyyy-MM-dd HH:mm:ss） */
    private String bucketTime;

    /** 状态：operational=正常, degraded=部分失败, error=全部失败, none=该时段无调用 */
    private String status;

    /** 本桶成功次数 */
    private Integer successCount;

    /** 本桶失败次数（仅上游返回错误） */
    private Integer failCount;

    /** 本桶成功任务平均耗时（毫秒），无成功调用为 null */
    private Long avgLatencyMs;

    /** 本桶最近一次上游错误摘要（仅后台管理视图返回，C端恒为 null） */
    private String errorMessage;
}
