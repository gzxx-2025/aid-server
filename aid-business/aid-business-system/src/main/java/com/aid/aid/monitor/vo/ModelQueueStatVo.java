package com.aid.aid.monitor.vo;

import lombok.Data;

/**
 * 单个模型的实时排队 / 并发监控行。
 *
 * @author 视觉AID
 */
@Data
public class ModelQueueStatVo
{
    /** 模型主键ID */
    private Long id;

    /** 模型展示/调用编码（model_code，并发名额维度键） */
    private String modelCode;

    /** 前端展示名称 */
    private String modelName;

    /** 真实上游模型名（可空） */
    private String realModelCode;

    /** 模型分类（image/video/audio/text 等） */
    private String modelType;

    /** 生成模式细分 */
    private String generateMode;

    /** 所属服务商ID */
    private Long providerId;

    /** 所属服务商展示名 */
    private String providerName;

    /** 模型状态：0正常 1停用 */
    private String status;

    /** 模型并发上限；null 表示不限制（schedule_strategy_json 未配置或 <=0） */
    private Integer concurrencyLimit;

    /** 是否配置了有限并发上限（false=不限） */
    private boolean limited;

    /** 当前正在执行（占用名额）的任务数 */
    private long running;

    /** 当前排队等待中的任务数（取扫描快照统计值） */
    private long waiting;

    /** 并发使用率百分比（running/limit*100，0~100；不限时为 null） */
    private Integer usagePercent;

    /** 是否已打满并发名额（limited 且 running>=limit） */
    private boolean saturated;

    /** 近窗口期（默认24h）调用总次数，用于看「使用频繁度」；统计失败时为 null */
    private Long recentUsage;
}
