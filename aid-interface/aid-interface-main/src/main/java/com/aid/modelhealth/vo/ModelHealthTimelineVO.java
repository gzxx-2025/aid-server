package com.aid.modelhealth.vo;

import java.io.Serializable;
import java.util.List;

import lombok.Data;

/**
 * 单个模型的健康时间线（对应状态页一行：模型信息 + 48格时间轴 + 可用率）。
 *
 * @author 视觉AID
 */
@Data
public class ModelHealthTimelineVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 服务商ID */
    private Long providerId;

    /** 服务商编码（aid_ai_provider.provider_code） */
    private String providerCode;

    /** 服务商展示名称（前端分组标题，同参考图 groupName） */
    private String providerName;

    /** 模型编码（aid_ai_model.model_code） */
    private String modelCode;

    /** 模型展示名称 */
    private String modelName;

    /** 模型类型（text/image/video/audio） */
    private String modelType;

    /** 模型是否启用（true=运行中；C端只返回启用的，后台管理含停用） */
    private Boolean enabled;

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

    /** 7天可用率（百分比，两位小数；无调用为 null，仅后台监控返回） */
    private Double availability7dPct;

    /** 15天可用率（百分比，两位小数；无调用为 null，仅后台监控返回） */
    private Double availability15dPct;

    /** 30天可用率（百分比，两位小数；无调用为 null，仅后台监控返回） */
    private Double availability30dPct;

    /** 7天成功任务平均耗时（毫秒），无成功调用为 null（仅后台监控返回） */
    private Long avgLatency7dMs;

    /** 时间轴（固定48格，从24小时前到当前，含无数据格 status=none） */
    private List<ModelHealthBucketVO> items;
}
