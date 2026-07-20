package com.aid.modelhealth.vo;

import java.io.Serializable;
import java.util.List;

import lombok.Data;

/**
 * 模型运行状态看板（分页返回：providerTimelines 为当前页的模型时间线）。
 *
 * @author 视觉AID
 */
@Data
public class ModelHealthBoardVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 符合条件的模型时间线总数（分页 total） */
    private Integer total;

    /** 页码 */
    private Integer pageNum;

    /** 每页条数 */
    private Integer pageSize;

    /** 统计窗口（固定 24h） */
    private String trendPeriod;

    /** 数据生成时间（yyyy-MM-dd HH:mm:ss） */
    private String lastUpdated;

    /** 整体状态：operational=全部正常, degraded=有降级, error=有异常（按全量模型汇总，不受分页影响） */
    private String overallStatus;

    /** 整体状态横幅文案（与 overallStatus 对应）：所有服务运行正常 / 部分服务降级 / 部分服务异常 */
    private String overallStatusText;

    /** 最新状态为正常的模型数（全量汇总） */
    private Integer operationalCount;

    /** 最新状态为降级的模型数（全量汇总） */
    private Integer degradedCount;

    /** 最新状态为异常的模型数（全量汇总） */
    private Integer errorCount;

    /** 统计窗口内无调用数据的模型数（全量汇总） */
    private Integer noDataCount;

    /** 当前页的模型时间线列表 */
    private List<ModelHealthTimelineVO> providerTimelines;
}
