package com.aid.quartz.domain;

import lombok.Data;

/**
 * 定时任务推荐频率建议（按数据库承载压力动态计算）
 *
 * @author AID
 */
@Data
public class SysJobFrequencyAdvice
{
    /** 任务ID（数据库中存在时返回，未注册时为 null） */
    private Long jobId;

    /** 任务名称 */
    private String jobName;

    /** 规范化调用目标（bean.method） */
    private String target;

    /** 任务类型（1固定任务 2可选任务） */
    private String jobType;

    /** 当前生效的 cron 表达式 */
    private String currentCron;

    /** 当前任务状态（0正常 1暂停） */
    private String currentStatus;

    /** 基准 cron（代码内置的推荐基准） */
    private String baseCron;

    /** 动态推荐 cron（按当前数据库压力计算） */
    private String recommendedCron;

    /** 动态推荐间隔（秒） */
    private Long recommendedIntervalSeconds;

    /** 当前 cron 的实际间隔（秒，-1 表示无法估算） */
    private Long currentIntervalSeconds;

    /** 频率安全下限（秒，0=不限制，仅固定任务强校验） */
    private Integer minIntervalSeconds;

    /** 频率安全上限（秒，0=不限制，仅固定任务强校验） */
    private Integer maxIntervalSeconds;

    /** 是否按日任务（不参与动态频率计算） */
    private Boolean daily;

    /** 任务说明 */
    private String remark;
}
