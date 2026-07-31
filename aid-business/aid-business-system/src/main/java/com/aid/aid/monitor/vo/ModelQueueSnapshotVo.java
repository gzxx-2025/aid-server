package com.aid.aid.monitor.vo;

import java.util.List;

import com.aid.modelhealth.vo.ModelHealthBoardVO;

import lombok.Data;

/**
 * AI 模型排队 / 并发监控总快照。
 * 由 {@code ModelQueueMonitorService} 周期性（短 TTL 缓存）聚合生成，后台监控页轮询读取。
 *
 * @author 视觉AID
 */
@Data
public class ModelQueueSnapshotVo
{
    /** 快照生成时间戳（毫秒），前端用于展示「数据更新于」 */
    private long generatedAt;

    /** 全局并发上限 */
    private int globalLimit;

    /** 全局当前并发占用数 */
    private long globalRunning;

    /** 全局并发使用率百分比（0~100） */
    private int globalUsagePercent;

    /** 当前等待上游名额的请求总数（全部模型合计，精确统计不抽样） */
    private long totalWaiting;

    /**
     * 未归属到任何在册模型（model_name 不在 aid_ai_model，如合成任务）的排队条数。
     * 这些请求计入 {@link #totalWaiting} 但不出现在任何模型 / 服务商行，
     * 故「各服务商排队之和」可能小于总排队，属正常。
     */
    private long unassignedProviderWaiting;

    /** 单用户默认并发上限 */
    private int userDefaultLimit;

    /** 使用频繁度统计窗口（小时） */
    private int usageWindowHours;

    /** 模型监控明细 */
    private List<ModelQueueStatVo> models;

    /** 服务商监控明细 */
    private List<ProviderQueueStatVo> providers;

    /**
     * 模型健康总览（最近24小时48格时间轴 + 可用率 + 每格上游错误摘要，含停用模型不分页）。
     * 与排队监控并入同一快照返回，监控页只轮询一个接口；服务端30秒缓存。
     * 健康数据查询异常时为 null，不影响排队监控主数据。
     */
    private ModelHealthBoardVO health;
}
