package com.aid.media.model;

import lombok.Data;

/**
 * 调度策略：控制异步任务的轮询节奏、退避、超时等行为。
 * 存储在 aid_ai_provider（供应商级默认）和 aid_ai_model（模型级覆写），
 * 任务提交时冻结到 aid_media_task.schedule_snapshot_json。
 *
 * 存活判定采用两条互相独立的时钟，不可用单一标量表达：
 * {@link #maxLifeSeconds} 是生命周期对账阈值，达到后必须携上游任务 ID 查询官方状态，不能本地直接判失败；
 * {@link #progressTimeoutSeconds} 是无进展超时，从「最近一次观测到上游仍在推进」起算，
 * 用于触发无进展对账。二者的判定收口在 {@link com.aid.media.util.TaskLivenessDecider}。
 *
 * 不含并发上限：同一 JSON 里的 maxConcurrency 由 {@link com.aid.media.service.MediaConcurrencyLimiter}
 * 读实时配置强制执行，不随任务快照冻结（冻结值会让后台调整并发无法对在途任务生效）。
 */
@Data
public class ScheduleStrategy {

    /**
     * 最大存活未配置（≤0）时的兜底值（秒）。
     * 存量快照与运营漏配都会让该字段反序列化成 0，若直接按 0 判定则任务一进调度就被判超时并退款，
     * 故必须有正数兜底；取值与 {@link #defaultVideo()} 的天花板一致，宁可多等一轮也不误杀。
     */
    private static final int FALLBACK_MAX_LIFE_SECONDS = 3600;

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

    /** 轮询退避增长的计数上限；达到后保持最大间隔继续轮询，不参与任务终态判定 */
    private int maxRetryCount;

    /** 生命周期对账阈值（秒）：达到后强制查上游，但不作为本地判失败依据 */
    private int maxLifeSeconds;

    /**
     * 无进展超时（秒）：距最近一次观测到上游仍在推进超过该时长即判上游已死。
     * ≤0 表示未配置，此时回落到 {@link #maxLifeSeconds}，行为与拆分前的单时钟完全一致。
     */
    private int progressTimeoutSeconds;

    /**
     * 每秒出片时长换算的存活预算（秒/秒），仅对视频生效。
     *
     * {@link #maxLifeSeconds} 是与作业规模无关的常量，用同一个数同时覆盖「5 秒 480p」与「15 秒 1080p」，
     * 必然要么对短单太松、要么对长单太紧——后者就是「上游还在出片、这边已经判超时退款」的成因。
     * 提交时按「实际下发时长 × 分辨率系数」算出预算，与常量取大后冻结进任务快照，
     * 使天花板随作业规模自适应，而不是靠人工把常量往大调。
     * ≤0 表示不启用，完全沿用常量口径。
     */
    private Double lifeSecondsPerVideoSecond;

    /** 生效的最大存活时间（秒）：未配置时用兜底值，绝不返回 ≤0 */
    public int effectiveMaxLifeSeconds() {
        return maxLifeSeconds > 0 ? maxLifeSeconds : FALLBACK_MAX_LIFE_SECONDS;
    }

    /** 生效的无进展超时（秒）：未配置时回落到最大存活，即退化为单时钟 */
    public int effectiveProgressTimeoutSeconds() {
        return progressTimeoutSeconds > 0 ? progressTimeoutSeconds : effectiveMaxLifeSeconds();
    }

    /** 图片默认策略 */
    public static ScheduleStrategy defaultImage() {
        ScheduleStrategy s = new ScheduleStrategy();
        s.setDispatchMode("POLL_ONLY");
        s.setFirstPollDelaySeconds(5);
        s.setBaseIntervalSeconds(5);
        s.setMaxIntervalSeconds(30);
        s.setBackoffFactor(1.5);
        s.setMaxRetryCount(120);
        s.setMaxLifeSeconds(1800);
        s.setProgressTimeoutSeconds(300);
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
        s.setMaxLifeSeconds(3600);
        s.setProgressTimeoutSeconds(600);
        // 每秒成片给 120 秒预算：常规短片仍落在 3600 常量内，长片/高清自动抬高天花板
        s.setLifeSecondsPerVideoSecond(120d);
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
        s.setMaxLifeSeconds(1800);
        s.setProgressTimeoutSeconds(300);
        return s;
    }
}
