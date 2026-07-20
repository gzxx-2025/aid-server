package com.aid.modelhealth.vo;

import java.io.Serializable;
import java.util.List;

import lombok.Data;

/**
 * 单个模型的健康状态摘要（不含模型/服务商基础信息）：
 * 最新状态 + 24小时可用率/计数/平均耗时 + 48格时间轴。
 * 由看板查询内部组装后映射到 {@link ModelHealthTimelineVO}。
 *
 * @author 视觉AID
 */
@Data
public class ModelHealthSummaryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 最新状态：operational/degraded/error（取最近一个有数据的时间格；窗口内无调用=operational） */
    private String latestStatus;

    /** 24小时可用率（百分比，两位小数；无调用时为 null） */
    private Double availabilityPct;

    /** 24小时总调用次数（成功+失败） */
    private Integer totalChecks;

    /** 24小时成功次数 */
    private Integer successCount;

    /** 24小时失败次数（仅上游返回错误） */
    private Integer failCount;

    /** 24小时成功任务平均耗时（毫秒），无成功调用为 null */
    private Long avgLatencyMs;

    /** 最新延迟（毫秒）：最近一个有成功调用的时间格平均耗时，无数据为 null */
    private Long latestLatencyMs;

    /** 时间轴（固定48格，30分钟/格，从24小时前到当前，含无数据格 status=none） */
    private List<ModelHealthBucketVO> items;
}
