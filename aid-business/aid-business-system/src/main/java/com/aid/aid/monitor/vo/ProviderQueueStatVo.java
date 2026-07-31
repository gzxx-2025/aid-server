package com.aid.aid.monitor.vo;

import lombok.Data;

/**
 * 单个服务商维度的实时排队 / 并发监控行。
 *
 * @author 视觉AID
 */
@Data
public class ProviderQueueStatVo
{
    /** 服务商ID */
    private Long providerId;

    /** 服务商展示名 */
    private String providerName;

    /** 服务商状态：0正常 1停用 */
    private String status;

    /** 服务商并发上限；null 表示不限制 */
    private Integer concurrencyLimit;

    /** 是否配置了有限并发上限 */
    private boolean limited;

    /** 当前正在执行（占用名额）的任务数 */
    private long running;

    /** 当前排队等待中的任务数 */
    private long waiting;

    /** 并发使用率百分比；不限时为 null */
    private Integer usagePercent;

    /** 是否已打满并发名额 */
    private boolean saturated;

    /** 该服务商下模型数量 */
    private int modelCount;
}
