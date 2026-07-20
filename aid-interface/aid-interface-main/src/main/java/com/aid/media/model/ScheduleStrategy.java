package com.aid.media.model;

import lombok.Data;

/**
 * 调度策略：控制异步任务的轮询节奏、退避、超时等行为。
 * 存储在 aid_ai_provider（供应商级默认）和 aid_ai_model（模型级覆写），
 * 任务提交时冻结到 aid_media_task.schedule_snapshot_json。
 */
@Data
public class ScheduleStrategy {

    /** 调度模式：DIRECT / CALLBACK_FIRST / POLL_ONLY */
    private String dispatchMode;

    /** 供应商是否支持回调（null=未设置/继承上级, true=显式开启, false=显式关闭） */
    private Boolean supportsCallback;

    /** 首次轮询延迟（秒）：提交后等多久才第一次查 */
    private int firstPollDelaySeconds;

    /** 基础轮询间隔（秒） */
    private int baseIntervalSeconds;

    /** 最大轮询间隔（秒）：退避上限 */
    private int maxIntervalSeconds;

    /** 退避系数：如 1.5 → 5s→7.5s→11.25s */
    private double backoffFactor;

    /** 单任务最大轮询次数 */
    private int maxRetryCount;

    /** 单任务最大存活时间（秒） */
    private int maxLifeSeconds;

    /**
     * 供应商维度上游请求并发上限：该供应商下所有模型的媒体任务（aid_media_task）在途总数不得超过此值。
     * 配置位：aid_ai_provider.schedule_strategy_json.providerConcurrency，&lt;=0 或缺失表示不限；
     * 提交准入与排队拉起时由 MediaConcurrencyLimiter 读实时配置强制执行（5 秒本地缓存），
     * 任务快照（schedule_snapshot_json）中的取值仅为冻结留档，不参与准入判定。
     */
    private int providerConcurrency;

    /**
     * 模型维度上游请求并发上限：所有用户对该模型的媒体任务（aid_media_task）在途总数不得超过此值。
     * 配置位：aid_ai_model.schedule_strategy_json.modelConcurrency，&lt;=0 或缺失表示不限；
     * 提交准入与排队拉起时由 MediaConcurrencyLimiter 读实时配置强制执行（5 秒本地缓存），
     * 任务快照（schedule_snapshot_json）中的取值仅为冻结留档，不参与准入判定。
     */
    private int modelConcurrency;
    /** 图片默认策略 */
    public static ScheduleStrategy defaultImage() {
        ScheduleStrategy s = new ScheduleStrategy();
        s.setDispatchMode("POLL_ONLY");
        s.setFirstPollDelaySeconds(5);
        s.setBaseIntervalSeconds(5);
        s.setMaxIntervalSeconds(30);
        s.setBackoffFactor(1.5);
        s.setMaxRetryCount(120);
        s.setMaxLifeSeconds(600);
        s.setProviderConcurrency(20);
        s.setModelConcurrency(10);
        return s;
    }

    /** 视频默认策略 */
    public static ScheduleStrategy defaultVideo() {
        ScheduleStrategy s = new ScheduleStrategy();
        s.setDispatchMode("POLL_ONLY");
        s.setFirstPollDelaySeconds(30);
        s.setBaseIntervalSeconds(30);
        s.setMaxIntervalSeconds(120);
        s.setBackoffFactor(1.5);
        s.setMaxRetryCount(60);
        s.setMaxLifeSeconds(1800);
        s.setProviderConcurrency(10);
        s.setModelConcurrency(5);
        return s;
    }

    /** 音频（TTS）默认策略：豆包 TTS 一般 10~60s 完成，首次延迟短、间隔适中 */
    public static ScheduleStrategy defaultAudio() {
        ScheduleStrategy s = new ScheduleStrategy();
        s.setDispatchMode("POLL_ONLY");
        s.setFirstPollDelaySeconds(5);
        s.setBaseIntervalSeconds(5);
        s.setMaxIntervalSeconds(30);
        s.setBackoffFactor(1.5);
        s.setMaxRetryCount(60);
        s.setMaxLifeSeconds(600);
        s.setProviderConcurrency(10);
        s.setModelConcurrency(5);
        return s;
    }
}
